[CmdletBinding(DefaultParameterSetName = "Apply")]
param(
    [Parameter(Mandatory = $true)]
    [string]$Target,

    [Parameter(Mandatory = $true, ParameterSetName = "Apply")]
    [string]$Payload,

    [Parameter(ParameterSetName = "Apply")]
    [string]$DeploymentId = "",

    [Parameter(ParameterSetName = "Apply")]
    [switch]$ValidateOnly,

    [Parameter(Mandatory = $true, ParameterSetName = "Rollback")]
    [string]$RollbackBackup,

    [string]$ResultPath = "",

    [int]$BackupRetention = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$targetRoot = [System.IO.Path]::GetFullPath($Target).TrimEnd('\', '/')
$stateRoot = Join-Path $targetRoot ".springsuite\deploy"
$lockPath = Join-Path $stateRoot "transaction.lock"
$transactionLog = Join-Path $stateRoot "transactions.log"
$lockStream = $null

function Write-TransactionLog {
    param([string]$Message)

    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    $line = "[{0}] {1}" -f (Get-Date -Format "o"), $Message
    Add-Content -LiteralPath $transactionLog -Value $line -Encoding UTF8
    Write-Host "[deploy-transaction] $Message"
}

function Write-JsonAtomic {
    param(
        [string]$Path,
        [object]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $directory = Split-Path -Parent $fullPath
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $temporary = "$fullPath.tmp-$([Guid]::NewGuid().ToString('N'))"
    $replaceBackup = "$fullPath.replace-backup-$([Guid]::NewGuid().ToString('N'))"
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
    try {
        if ([System.IO.File]::Exists($fullPath)) {
            [System.IO.File]::Replace($temporary, $fullPath, $replaceBackup, $true)
        } else {
            [System.IO.File]::Move($temporary, $fullPath)
        }
    } finally {
        if ([System.IO.File]::Exists($temporary)) {
            [System.IO.File]::Delete($temporary)
        }
        if ([System.IO.File]::Exists($replaceBackup)) {
            [System.IO.File]::Delete($replaceBackup)
        }
    }
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Ensure-ParentDirectory {
    param([string]$Path)
    $parent = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($Path))
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    }
}

function Copy-FileExact {
    param(
        [string]$Source,
        [string]$Destination,
        [switch]$Overwrite
    )
    Ensure-ParentDirectory -Path $Destination
    [System.IO.File]::Copy($Source, $Destination, [bool]$Overwrite)
}

function Move-FileExact {
    param(
        [string]$Source,
        [string]$Destination
    )
    Ensure-ParentDirectory -Path $Destination
    if ([System.IO.File]::Exists($Destination)) {
        throw "Move destination already exists: $Destination"
    }
    [System.IO.File]::Move($Source, $Destination)
}

function Remove-FileExact {
    param([string]$Path)
    if ([System.IO.File]::Exists($Path)) {
        [System.IO.File]::Delete($Path)
    }
}

function Assert-RuntimeTargetSafe {
    if (Test-Path -LiteralPath (Join-Path $targetRoot '.git')) {
        throw "Refusing to update a Git repository or worktree: $targetRoot"
    }
    $markers = @('build.gradle.kts', 'settings.gradle.kts', 'gradlew.bat', '.springsuite-repository.json')
    $count = 0
    foreach ($marker in $markers) {
        if (Test-Path -LiteralPath (Join-Path $targetRoot $marker)) {
            $count++
        }
    }
    if ($count -ge 2) {
        throw "Refusing to update a directory that looks like source code: $targetRoot"
    }
}

function Resolve-SafeRelativePath {
    param(
        [string]$Root,
        [string]$RelativePath
    )

    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.Contains(':')) {
        throw "Deployment manifest contains an unsafe path: $RelativePath"
    }

    $normalized = $RelativePath.Replace('\', '/')
    $segments = $normalized.Split(@('/'), [System.StringSplitOptions]::RemoveEmptyEntries)
    if ($segments.Count -eq 0 -or $segments -contains '.' -or $segments -contains '..') {
        throw "Deployment manifest contains an unsafe path: $RelativePath"
    }
    $first = $segments[0].ToLowerInvariant()
    $protectedRoots = @('.git', '.springsuite', 'data', 'logs', 'authority', 'tmp', 'recovery', 'web', 'src', 'gosrc', 'gradle')
    if ($protectedRoots -contains $first -or $first -like 'suite-*') {
        throw "Deployment manifest attempts to modify a protected/source root: $RelativePath"
    }
    $protectedFiles = @('.springsuite-repository.json', 'build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat')
    if ($segments.Count -eq 1 -and $protectedFiles -contains $segments[0].ToLowerInvariant()) {
        throw "Deployment manifest attempts to modify a protected/source file: $RelativePath"
    }

    $normalizedRelative = [string]::Join([System.IO.Path]::DirectorySeparatorChar, $segments)
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $Root $normalizedRelative))
    $rootPrefix = $Root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Deployment manifest path escapes its root: $RelativePath"
    }
    return $resolved
}

function Read-And-ValidatePayload {
    param([string]$PayloadRoot)

    $resolvedPayload = [System.IO.Path]::GetFullPath($PayloadRoot).TrimEnd('\', '/')
    if (-not (Test-Path -LiteralPath $resolvedPayload -PathType Container)) {
        throw "Deployment payload does not exist: $resolvedPayload"
    }

    $manifestPath = Join-Path $resolvedPayload "deploy-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Deployment manifest is missing: $manifestPath"
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([string]$manifest.schema -ne "spring-suite.deploy-manifest.v1") {
        throw "Unsupported deployment manifest schema: $($manifest.schema)"
    }

    $entries = @()
    $seenPaths = @{}
    foreach ($file in @($manifest.files)) {
        $relativePath = ([string]$file.path).Replace('\', '/')
        $key = $relativePath.ToLowerInvariant()
        if ($seenPaths.ContainsKey($key)) {
            throw "Deployment manifest contains a duplicate path: $relativePath"
        }
        $seenPaths[$key] = $true
        $sourcePath = Resolve-SafeRelativePath -Root $resolvedPayload -RelativePath $relativePath
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Deployment payload file is missing: $relativePath"
        }

        $actualSize = (Get-Item -LiteralPath $sourcePath).Length
        if ($actualSize -ne [long]$file.size) {
            throw "Deployment payload size mismatch for ${relativePath}: expected $($file.size), got $actualSize"
        }

        $actualHash = Get-Sha256 -Path $sourcePath
        $expectedHash = ([string]$file.sha256).ToLowerInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "Deployment payload hash mismatch for $relativePath"
        }

        $entries += [pscustomobject]@{
            relativePath = $relativePath
            sourcePath = $sourcePath
            size = $actualSize
            sha256 = $actualHash
        }
    }

    $payloadPrefix = $resolvedPayload + [System.IO.Path]::DirectorySeparatorChar
    foreach ($actualFile in @(Get-ChildItem -LiteralPath $resolvedPayload -Recurse -Force -File)) {
        $relative = $actualFile.FullName.Substring($payloadPrefix.Length).Replace('\', '/')
        if ($relative -eq 'deploy-manifest.json') {
            continue
        }
        if (-not $seenPaths.ContainsKey($relative.ToLowerInvariant())) {
            throw "Deployment payload contains an unmanifested file: $relative"
        }
    }

    foreach ($scriptEntry in @($entries | Where-Object { $_.relativePath.EndsWith('.ps1', [System.StringComparison]::OrdinalIgnoreCase) })) {
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($scriptEntry.sourcePath, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors.Count -gt 0) {
            $messages = ($errors | ForEach-Object { $_.Message }) -join '; '
            throw "PowerShell syntax verification failed for $($scriptEntry.relativePath): $messages"
        }
    }

    $jarEntry = $entries | Where-Object { $_.relativePath -eq "spring-suite.jar" } | Select-Object -First 1
    if ($null -eq $jarEntry) {
        throw "Deployment payload does not contain spring-suite.jar"
    }
    if ($jarEntry.size -lt 1000000) {
        throw "spring-suite.jar is unexpectedly small: $($jarEntry.size) bytes"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jarEntry.sourcePath)
        $bootLoader = $archive.Entries | Where-Object { $_.FullName -eq "org/springframework/boot/loader/launch/JarLauncher.class" } | Select-Object -First 1
        $applicationClass = $archive.Entries | Where-Object { $_.FullName -eq "BOOT-INF/classes/com/takesome/springsuite/app/SpringSuiteApplication.class" } | Select-Object -First 1
        if ($null -eq $bootLoader -or $null -eq $applicationClass) {
            throw "spring-suite.jar is not a valid SpringSuite executable JAR"
        }
    } finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }

    $preservedRoots = @()
    if ($manifest.PSObject.Properties.Name -contains 'preservedRoots') {
        $preservedRoots = @($manifest.preservedRoots)
    }

    return [pscustomobject]@{
        root = $resolvedPayload
        manifestPath = $manifestPath
        manifest = $manifest
        entries = $entries
        jarSha256 = $jarEntry.sha256
        preservedRoots = $preservedRoots
    }
}

function Assert-SufficientDiskSpace {
    param([object]$PayloadInfo)

    $payloadBytes = [int64]0
    foreach ($entry in @($PayloadInfo.entries)) {
        $payloadBytes += [int64]$entry.size
    }
    $required = [Math]::Max([int64](256MB), [int64]($payloadBytes * 3))
    $drive = [System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($targetRoot))
    if ($drive.AvailableFreeSpace -lt $required) {
        throw "Insufficient disk space for transactional update. Required=$required Available=$($drive.AvailableFreeSpace)"
    }
}

function Restore-Transaction {
    param(
        [string]$BackupRoot,
        [object[]]$InstalledEntries
    )

    foreach ($entry in @($InstalledEntries | Sort-Object order -Descending)) {
        $destination = Resolve-SafeRelativePath -Root $targetRoot -RelativePath ([string]$entry.relativePath)
        if ([System.IO.File]::Exists($destination)) {
            Remove-FileExact -Path $destination
        }

        if ([bool]$entry.hadOriginal) {
            $backupPath = Resolve-SafeRelativePath -Root $BackupRoot -RelativePath ([string]$entry.relativePath)
            if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) {
                throw "Rollback backup is missing: $($entry.relativePath)"
            }
            Ensure-ParentDirectory -Path $destination
            Copy-FileExact -Source $backupPath -Destination $destination -Overwrite
            $expectedOriginalHash = ""
            if ($entry.PSObject.Properties.Name -contains 'originalSha256') {
                $expectedOriginalHash = [string]$entry.originalSha256
            }
            if (-not [string]::IsNullOrWhiteSpace($expectedOriginalHash) -and
                (Get-Sha256 -Path $destination) -ne $expectedOriginalHash) {
                throw "Rollback hash verification failed: $($entry.relativePath)"
            }
        }
    }
}

function Invoke-Rollback {
    param([string]$BackupRoot)

    $resolvedBackup = [System.IO.Path]::GetFullPath($BackupRoot).TrimEnd('\', '/')
    $journalPath = Join-Path $resolvedBackup "transaction.json"
    if (-not (Test-Path -LiteralPath $journalPath -PathType Leaf)) {
        throw "Rollback transaction journal is missing: $journalPath"
    }

    $journal = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
    Write-TransactionLog "rolling back deployment $($journal.deploymentId) from $resolvedBackup"
    Restore-Transaction -BackupRoot $resolvedBackup -InstalledEntries @($journal.files)

    $result = [ordered]@{
        ok = $true
        action = "rollback"
        deploymentId = [string]$journal.deploymentId
        backup = $resolvedBackup
        restoredFiles = @($journal.files).Count
        completedAt = (Get-Date).ToString("o")
    }
    Write-JsonAtomic -Path $ResultPath -Value $result
    Write-TransactionLog "rollback completed for deployment $($journal.deploymentId)"
    return $result
}

function Remove-OldBackups {
    param([string]$CurrentBackup)

    $backupsRoot = Join-Path $stateRoot "backups"
    try {
        if (-not [System.IO.Directory]::Exists($backupsRoot)) {
            return
        }

        $keep = [Math]::Max(1, $BackupRetention)
        $currentFullPath = [System.IO.Path]::GetFullPath($CurrentBackup).TrimEnd('\', '/')
        $backups = @([System.IO.Directory]::GetDirectories($backupsRoot) | ForEach-Object {
            [pscustomobject]@{
                path = [System.IO.Path]::GetFullPath($_).TrimEnd('\', '/')
                lastWriteTimeUtc = [System.IO.Directory]::GetLastWriteTimeUtc($_)
            }
        } | Sort-Object lastWriteTimeUtc -Descending)

        foreach ($backup in @($backups | Select-Object -Skip $keep)) {
            if (-not $backup.path.Equals($currentFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
                try {
                    [System.IO.Directory]::Delete($backup.path, $true)
                    Write-TransactionLog "removed expired backup $($backup.path)"
                } catch {
                    Write-TransactionLog "warning: could not remove expired backup $($backup.path): $($_.Exception.Message)"
                }
            }
        }
    } catch {
        Write-TransactionLog "warning: backup retention cleanup skipped: $($_.Exception.Message)"
    }
}

try {
    Assert-RuntimeTargetSafe
    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    $lockStream = [System.IO.File]::Open(
        $lockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )

    if ($PSCmdlet.ParameterSetName -eq "Rollback") {
        $rollbackResult = Invoke-Rollback -BackupRoot $RollbackBackup
        $rollbackResult | ConvertTo-Json -Depth 8
        exit 0
    }

    $payloadInfo = Read-And-ValidatePayload -PayloadRoot $Payload
    if ([string]::IsNullOrWhiteSpace($DeploymentId)) {
        $DeploymentId = "deploy-" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    }

    if (-not $ValidateOnly) {
        Assert-SufficientDiskSpace -PayloadInfo $payloadInfo
    }

    if ($ValidateOnly) {
        $result = [ordered]@{
            ok = $true
            action = "validate"
            deploymentId = $DeploymentId
            payload = $payloadInfo.root
            fileCount = $payloadInfo.entries.Count
            jarSha256 = $payloadInfo.jarSha256
            completedAt = (Get-Date).ToString("o")
        }
        Write-JsonAtomic -Path $ResultPath -Value $result
        $result | ConvertTo-Json -Depth 8
        exit 0
    }

    New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
    $backupRoot = Join-Path $stateRoot ("backups\" + $DeploymentId)
    if (Test-Path -LiteralPath $backupRoot) {
        throw "Deployment backup already exists: $backupRoot"
    }
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

    $orderedEntries = @($payloadInfo.entries | Sort-Object @{ Expression = { if ($_.relativePath -eq "spring-suite.jar") { 1 } else { 0 } } }, relativePath)
    $installed = @()
    $order = 0

    Write-TransactionLog "applying deployment $DeploymentId with $($orderedEntries.Count) files"
    try {
        foreach ($entry in $orderedEntries) {
            $relativePath = [string]$entry.relativePath
            $destination = Resolve-SafeRelativePath -Root $targetRoot -RelativePath $relativePath
            $destinationDirectory = Split-Path -Parent $destination
            [System.IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null

            $temporary = Join-Path $destinationDirectory ("." + [System.IO.Path]::GetFileName($destination) + ".new-" + $DeploymentId)
            if ([System.IO.File]::Exists($temporary)) {
                Remove-FileExact -Path $temporary
            }
            Copy-FileExact -Source $entry.sourcePath -Destination $temporary -Overwrite
            if ((Get-Sha256 -Path $temporary) -ne $entry.sha256) {
                throw "Temporary installation hash mismatch: $relativePath"
            }

            $hadOriginal = Test-Path -LiteralPath $destination -PathType Leaf
            $originalSha256 = ""
            if ($hadOriginal) {
                $originalSha256 = Get-Sha256 -Path $destination
                $backupPath = Resolve-SafeRelativePath -Root $backupRoot -RelativePath $relativePath
                Ensure-ParentDirectory -Path $backupPath
                Move-FileExact -Source $destination -Destination $backupPath
            }

            $order++
            $installed += [pscustomobject]@{
                order = $order
                relativePath = $relativePath
                hadOriginal = $hadOriginal
                originalSha256 = $originalSha256
                installedSha256 = $entry.sha256
                removedOnly = $false
            }
            if ($hadOriginal -and (Get-Sha256 -Path $backupPath) -ne $originalSha256) {
                throw "Backup hash verification failed: $relativePath"
            }
            Move-FileExact -Source $temporary -Destination $destination
            if ((Get-Sha256 -Path $destination) -ne $entry.sha256) {
                throw "Installed hash verification failed: $relativePath"
            }
            Write-TransactionLog "installed $relativePath"
        }

        $newPathSet = @{}
        foreach ($entry in @($payloadInfo.entries)) {
            $newPathSet[([string]$entry.relativePath).ToLowerInvariant()] = $true
        }
        $preserved = @($payloadInfo.preservedRoots | ForEach-Object { ([string]$_).Trim('\', '/').ToLowerInvariant() })
        $currentManifestPath = Join-Path $targetRoot 'deploy-manifest.json'
        if (Test-Path -LiteralPath $currentManifestPath -PathType Leaf) {
            $currentManifest = Get-Content -LiteralPath $currentManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($oldEntry in @($currentManifest.files)) {
                $oldRelative = ([string]$oldEntry.path).Replace('\', '/')
                $oldKey = $oldRelative.ToLowerInvariant()
                $oldRoot = ($oldKey.Split('/')[0])
                if ($newPathSet.ContainsKey($oldKey) -or $preserved -contains $oldRoot) {
                    continue
                }
                $oldDestination = Resolve-SafeRelativePath -Root $targetRoot -RelativePath $oldRelative
                if (-not (Test-Path -LiteralPath $oldDestination -PathType Leaf)) {
                    continue
                }
                $oldHash = Get-Sha256 -Path $oldDestination
                $oldBackup = Resolve-SafeRelativePath -Root $backupRoot -RelativePath $oldRelative
                Ensure-ParentDirectory -Path $oldBackup
                Move-FileExact -Source $oldDestination -Destination $oldBackup
                $order++
                $installed += [pscustomobject]@{
                    order = $order
                    relativePath = $oldRelative
                    hadOriginal = $true
                    originalSha256 = $oldHash
                    installedSha256 = ""
                    removedOnly = $true
                }
                if ((Get-Sha256 -Path $oldBackup) -ne $oldHash) {
                    throw "Obsolete-file backup verification failed: $oldRelative"
                }
                Write-TransactionLog "removed obsolete $oldRelative"
            }
        }

        $manifestRelative = 'deploy-manifest.json'
        $manifestDestination = Join-Path $targetRoot $manifestRelative
        $manifestTemporary = Join-Path $targetRoot ('.deploy-manifest.json.new-' + $DeploymentId)
        Copy-FileExact -Source $payloadInfo.manifestPath -Destination $manifestTemporary -Overwrite
        $manifestHash = Get-Sha256 -Path $manifestTemporary
        $manifestHadOriginal = Test-Path -LiteralPath $manifestDestination -PathType Leaf
        $manifestOriginalHash = ""
        if ($manifestHadOriginal) {
            $manifestOriginalHash = Get-Sha256 -Path $manifestDestination
            $manifestBackup = Resolve-SafeRelativePath -Root $backupRoot -RelativePath $manifestRelative
            Move-FileExact -Source $manifestDestination -Destination $manifestBackup
        }
        $order++
        $installed += [pscustomobject]@{
            order = $order
            relativePath = $manifestRelative
            hadOriginal = $manifestHadOriginal
            originalSha256 = $manifestOriginalHash
            installedSha256 = $manifestHash
            removedOnly = $false
        }
        if ($manifestHadOriginal -and (Get-Sha256 -Path $manifestBackup) -ne $manifestOriginalHash) {
            throw "Deploy manifest backup verification failed"
        }
        Move-FileExact -Source $manifestTemporary -Destination $manifestDestination
        if ((Get-Sha256 -Path $manifestDestination) -ne $manifestHash) {
            throw "Installed deploy manifest verification failed"
        }

        $journal = [ordered]@{
            schema = "spring-suite.deploy-transaction.v2"
            deploymentId = $DeploymentId
            payload = $payloadInfo.root
            target = $targetRoot
            jarSha256 = $payloadInfo.jarSha256
            appliedAt = (Get-Date).ToString("o")
            files = $installed
        }
        Write-JsonAtomic -Path (Join-Path $backupRoot "transaction.json") -Value $journal

        $result = [ordered]@{
            ok = $true
            action = "apply"
            deploymentId = $DeploymentId
            target = $targetRoot
            backup = $backupRoot
            fileCount = $installed.Count
            jarSha256 = $payloadInfo.jarSha256
            completedAt = (Get-Date).ToString("o")
        }
        Write-JsonAtomic -Path $ResultPath -Value $result
        Remove-OldBackups -CurrentBackup $backupRoot
        Write-TransactionLog "deployment $DeploymentId applied atomically"
        $result | ConvertTo-Json -Depth 8
        exit 0
    } catch {
        Write-TransactionLog "deployment $DeploymentId failed; restoring previous files: $($_.Exception.Message)"
        Restore-Transaction -BackupRoot $backupRoot -InstalledEntries $installed
        throw
    }
} catch {
    $errorResult = [ordered]@{
        ok = $false
        action = if ($PSCmdlet.ParameterSetName -eq "Rollback") { "rollback" } else { if ($ValidateOnly) { "validate" } else { "apply" } }
        deploymentId = $DeploymentId
        target = $targetRoot
        error = $_.Exception.Message
        exception = $_.Exception.ToString()
        stackTrace = $_.ScriptStackTrace
        completedAt = (Get-Date).ToString("o")
    }
    Write-JsonAtomic -Path $ResultPath -Value $errorResult
    Write-TransactionLog "ERROR: $($_.Exception.Message)"
    Write-Error $_
    exit 1
} finally {
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
}
