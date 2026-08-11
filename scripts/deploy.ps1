[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Target = "",

    [int]$Port = 8090,

    [int]$HealthTimeoutSeconds = 120,

    [int]$StabilizationSeconds = 20,

    [int]$ShutdownTimeoutSeconds = 30,

    [int]$PortReleaseTimeoutSeconds = 30,

    [switch]$SkipTests,

    [switch]$SkipBuild,

    [switch]$NoStart,

    [switch]$ReplaceConfig,

    [switch]$PreserveConfig,

    [switch]$ForceStop,

    [switch]$KeepBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory "..")).TrimEnd('\', '/')
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$deployImage = Join-Path $repositoryRoot "build\deploy"
$applyScript = Join-Path $scriptDirectory "apply-deploy.ps1"
$supervisorScript = Join-Path $scriptDirectory "spring-suite-supervisor.ps1"
$toastScript = Join-Path $scriptDirectory "suite-toast.ps1"
$startedBootstrapSupervisor = $null

function Resolve-DeploymentTarget {
    param([string]$ConfiguredTarget)

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredTarget)) {
        return [System.IO.Path]::GetFullPath($ConfiguredTarget).TrimEnd('\', '/')
    }

    if (-not [string]::IsNullOrWhiteSpace($env:SPRING_SUITE_RUNTIME)) {
        return [System.IO.Path]::GetFullPath($env:SPRING_SUITE_RUNTIME).TrimEnd('\', '/')
    }

    $documents = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
    return [System.IO.Path]::GetFullPath((Join-Path $documents "Take Some\NorthStar-Suite-V3-Runtime")).TrimEnd('\', '/')
}

function Test-PathInside {
    param(
        [string]$Child,
        [string]$Parent
    )

    $childPath = [System.IO.Path]::GetFullPath($Child).TrimEnd('\', '/')
    $parentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if ($childPath.Equals($parentPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    return $childPath.StartsWith(
        $parentPath + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Assert-SafeDeploymentTopology {
    param([string]$TargetRoot)

    if ($TargetRoot.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Deployment target must not be the source repository: $TargetRoot"
    }
    if ((Test-PathInside -Child $TargetRoot -Parent $repositoryRoot) -or
        (Test-PathInside -Child $repositoryRoot -Parent $TargetRoot)) {
        throw "Source and runtime directories must not overlap. Source=$repositoryRoot Target=$TargetRoot"
    }
    if (Test-Path -LiteralPath (Join-Path $TargetRoot '.git')) {
        throw "Refusing to deploy over a Git repository or worktree: $TargetRoot"
    }

    $sourceMarkers = @('build.gradle.kts', 'settings.gradle.kts', 'gradlew.bat', '.springsuite-repository.json')
    $markerCount = 0
    foreach ($marker in $sourceMarkers) {
        if (Test-Path -LiteralPath (Join-Path $TargetRoot $marker)) {
            $markerCount++
        }
    }
    if ($markerCount -ge 2) {
        throw "Refusing to deploy over a directory that looks like source code: $TargetRoot"
    }

    if (Test-Path -LiteralPath $TargetRoot -PathType Container) {
        $entries = @(Get-ChildItem -LiteralPath $TargetRoot -Force -ErrorAction SilentlyContinue)
        $hasRuntimeJar = Test-Path -LiteralPath (Join-Path $TargetRoot 'spring-suite.jar') -PathType Leaf
        if ($entries.Count -gt 0 -and -not $hasRuntimeJar) {
            $allowedBootstrapEntries = @('.springsuite', 'config', 'data', 'logs', 'authority')
            $unknown = @($entries | Where-Object { $allowedBootstrapEntries -notcontains $_.Name })
            if ($unknown.Count -gt 0) {
                throw "Non-empty target is not a recognized SpringSuite runtime: $TargetRoot"
            }
        }
    }
}

function Assert-SourceRepositoryHealthy {
    foreach ($required in @($applyScript, $supervisorScript, $toastScript)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "Required deployment component is missing: $required"
        }
    }
    if (-not $SkipBuild) {
        foreach ($required in @('build.gradle.kts', 'settings.gradle.kts', 'gradlew.bat')) {
            if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot $required) -PathType Leaf)) {
                throw "Source repository is incomplete; missing $required"
            }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'components\application\suite-app\src') -PathType Container)) {
            throw "Source repository is incomplete; components/application/suite-app/src is missing"
        }
    }
}

function Invoke-GradleBuild {
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper is missing: $gradleWrapper"
    }

    $arguments = @("clean", "verifyModuleBoundaries")
    if (-not $SkipTests) {
        $arguments += "test"
    }
    $arguments += @("assembleDeploy", "verifyDeployLayout", "--no-daemon")

    Write-Host "[deploy] Gradle $($arguments -join ' ')"
    & $gradleWrapper @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

function Send-DeployToast {
    param(
        [string]$EventId,
        [string]$Title,
        [string]$Message,
        [ValidateSet("Info", "Success", "Warning", "Error")]
        [string]$Level = "Info"
    )

    if (-not (Test-Path -LiteralPath $toastScript -PathType Leaf)) {
        return
    }
    try {
        & $toastScript -Title $Title -Message $Message -Level $Level -Root $targetRoot -EventId $EventId | Out-Null
    } catch {
        Write-Warning "Toast notification failed: $($_.Exception.Message)"
    }
}

function Write-JsonAtomic {
    param(
        [string]$Path,
        [object]$Value
    )

    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $temporary = "$Path.tmp-$([Guid]::NewGuid().ToString('N'))"
    $replaceBackup = "$Path.replace-backup-$([Guid]::NewGuid().ToString('N'))"
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
    try {
        if ([System.IO.File]::Exists($Path)) {
            [System.IO.File]::Replace($temporary, $Path, $replaceBackup, $true)
        } else {
            [System.IO.File]::Move($temporary, $Path)
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

function Copy-FileExact {
    param(
        [string]$Source,
        [string]$Destination,
        [switch]$Overwrite
    )
    $parent = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($Destination))
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    }
    [System.IO.File]::Copy($Source, $Destination, [bool]$Overwrite)
}

function Copy-DirectoryExact {
    param(
        [string]$Source,
        [string]$Destination
    )
    $sourceRoot = [System.IO.Path]::GetFullPath($Source).TrimEnd('\', '/')
    $destinationRoot = [System.IO.Path]::GetFullPath($Destination).TrimEnd('\', '/')
    [System.IO.Directory]::CreateDirectory($destinationRoot) | Out-Null
    foreach ($directory in [System.IO.Directory]::GetDirectories($sourceRoot, '*', [System.IO.SearchOption]::AllDirectories)) {
        $relative = $directory.Substring($sourceRoot.Length).TrimStart('\', '/')
        [System.IO.Directory]::CreateDirectory((Join-Path $destinationRoot $relative)) | Out-Null
    }
    foreach ($file in [System.IO.Directory]::GetFiles($sourceRoot, '*', [System.IO.SearchOption]::AllDirectories)) {
        $relative = $file.Substring($sourceRoot.Length).TrimStart('\', '/')
        Copy-FileExact -Source $file -Destination (Join-Path $destinationRoot $relative) -Overwrite
    }
}

function Remove-FileExact {
    param([string]$Path)
    if ([System.IO.File]::Exists($Path)) {
        [System.IO.File]::Delete($Path)
    }
}

function Get-RuntimeStatus {
    try {
        $response = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/api/system/status" -TimeoutSec 2
        $components = $response.data.components
        $launchRoot = [string]$components.launchDirectory
        if ([string]::IsNullOrWhiteSpace($launchRoot)) {
            $launchRoot = [string]$components.projectRoot
        }
        $supervisorPid = 0
        $deploymentId = ""
        if ($components.PSObject.Properties.Name -contains "supervisorPid") {
            $supervisorPid = [int]$components.supervisorPid
        }
        if ($components.PSObject.Properties.Name -contains "deploymentId") {
            $deploymentId = [string]$components.deploymentId
        }
        return [pscustomobject]@{
            pid = [int]$components.pid
            launchRoot = $launchRoot
            status = [string]$response.data.status
            supervisorPid = $supervisorPid
            deploymentId = $deploymentId
        }
    } catch {
        return $null
    }
}

function Get-SupervisorState {
    if (-not (Test-Path -LiteralPath $supervisorStateFile -PathType Leaf)) {
        return $null
    }
    try {
        $state = Get-Content -LiteralPath $supervisorStateFile -Raw | ConvertFrom-Json
        $stateStatus = [string]$state.status
        $terminalStates = @("stopped-after-deploy", "stopped", "failed")
        $process = Get-Process -Id ([int]$state.supervisorPid) -ErrorAction SilentlyContinue
        if ($null -eq $process -and -not ($terminalStates -contains $stateStatus)) {
            return $null
        }
        $stateRootPath = [System.IO.Path]::GetFullPath([string]$state.root).TrimEnd('\', '/')
        if ($stateRootPath -ne $targetRoot) {
            return $null
        }
        return $state
    } catch {
        return $null
    }
}

function Get-BridgeToken {
    if (-not [string]::IsNullOrWhiteSpace($env:NORTHSTAR_BRIDGE_ACCESS_TOKEN)) {
        return $env:NORTHSTAR_BRIDGE_ACCESS_TOKEN.Trim()
    }

    $candidatePaths = @(
        (Join-Path $env:LOCALAPPDATA "NoesisSuite\authority\bridge_access_token.txt"),
        (Join-Path $targetRoot "authority\bridge_access_token.txt")
    )
    foreach ($candidate in $candidatePaths) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            $token = (Get-Content -LiteralPath $candidate -Raw).Trim()
            if (-not [string]::IsNullOrWhiteSpace($token)) {
                return $token
            }
        }
    }
    return ""
}

function Request-GracefulTransition {
    param(
        [string]$Token,
        [string]$CommandLine
    )

    if ([string]::IsNullOrWhiteSpace($Token)) {
        return $false
    }

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = "deploy-$([Guid]::NewGuid().ToString('N'))"
        method = "tools/call"
        params = [ordered]@{
            name = "command.execute"
            arguments = [ordered]@{
                line = $CommandLine
            }
        }
    }
    try {
        $response = Invoke-RestMethod `
            -Method Post `
            -Uri "http://127.0.0.1:$Port/mcp" `
            -Headers @{ "X-NorthStar-Bridge-Token" = $Token } `
            -ContentType "application/json" `
            -Body ($request | ConvertTo-Json -Depth 8) `
            -TimeoutSec 10

        if ($null -ne $response -and ($response.PSObject.Properties.Name -contains "error") -and $null -ne $response.error) {
            Write-Warning "SpringSuite rejected lifecycle transition: $($response.error.message)"
            return $false
        }
        return $true
    } catch {
        Write-Warning "Could not request graceful lifecycle transition: $($_.Exception.Message)"
        return $false
    }
}

function Copy-DeployImageToStage {
    param(
        [string]$ImageRoot,
        [string]$StageRoot
    )

    New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null
    $preservedRoots = @("data", "logs", ".springsuite", "authority", "tmp", "recovery", "WEB", ".git")
    if ($PreserveConfig -and -not $ReplaceConfig -and (Test-Path -LiteralPath (Join-Path $targetRoot "config") -PathType Container)) {
        $preservedRoots += "config"
    }

    Get-ChildItem -LiteralPath $ImageRoot -Force | ForEach-Object {
        if ($preservedRoots -contains $_.Name) {
            Write-Host "[deploy] preserving runtime directory: $($_.Name)"
            return
        }
        $destination = Join-Path $StageRoot $_.Name
        if ($_.PSIsContainer) {
            Copy-DirectoryExact -Source $_.FullName -Destination $destination
        } else {
            Copy-FileExact -Source $_.FullName -Destination $destination -Overwrite
        }
    }
    return $preservedRoots
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-SafeDeployPath {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or [System.IO.Path]::IsPathRooted($Path) -or $Path.Contains(':')) {
        throw "Unsafe deploy path: $Path"
    }
    $normalized = $Path.Replace('\', '/')
    $segments = $normalized.Split(@('/'), [System.StringSplitOptions]::RemoveEmptyEntries)
    if ($segments.Count -eq 0 -or $segments -contains '.' -or $segments -contains '..') {
        throw "Unsafe deploy path: $Path"
    }
    $first = $segments[0].ToLowerInvariant()
    $protectedRoots = @('.git', '.springsuite', 'data', 'logs', 'authority', 'tmp', 'recovery', 'web', 'src', 'gosrc', 'gradle')
    if ($protectedRoots -contains $first -or $first -like 'suite-*') {
        throw "Deploy image attempts to modify a protected/source root: $Path"
    }
    $protectedFiles = @('.springsuite-repository.json', 'build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat')
    if ($segments.Count -eq 1 -and $protectedFiles -contains $segments[0].ToLowerInvariant()) {
        throw "Deploy image attempts to modify a protected/source file: $Path"
    }
}

function Write-EffectiveDeployManifest {
    param(
        [string]$ImageRoot,
        [string]$StageRoot,
        [string[]]$PreservedRoots
    )

    $sourceManifestPath = Join-Path $ImageRoot 'deploy-manifest.json'
    $stageManifestPath = Join-Path $StageRoot 'deploy-manifest.json'
    $sourceManifest = Get-Content -LiteralPath $sourceManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$sourceManifest.schema -ne 'spring-suite.deploy-manifest.v1') {
        throw "Unsupported deploy manifest schema: $($sourceManifest.schema)"
    }

    $published = @{}
    foreach ($entry in @($sourceManifest.files)) {
        $relative = ([string]$entry.path).Replace('\', '/')
        Assert-SafeDeployPath -Path $relative
        $key = $relative.ToLowerInvariant()
        if ($published.ContainsKey($key)) {
            throw "Duplicate path in deploy manifest: $relative"
        }
        $published[$key] = $entry
    }

    $stagePrefix = [System.IO.Path]::GetFullPath($StageRoot).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $effectiveFiles = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $StageRoot -Recurse -Force -File | Sort-Object FullName)) {
        $relative = $file.FullName.Substring($stagePrefix.Length).Replace('\', '/')
        if ($relative -eq 'deploy-manifest.json') {
            continue
        }
        Assert-SafeDeployPath -Path $relative
        $key = $relative.ToLowerInvariant()
        if (-not $published.ContainsKey($key)) {
            throw "Staged payload contains an unmanifested file: $relative"
        }
        $expected = $published[$key]
        $actualHash = Get-Sha256 -Path $file.FullName
        if ([int64]$expected.size -ne [int64]$file.Length -or
            ([string]$expected.sha256).ToLowerInvariant() -ne $actualHash) {
            throw "Staged payload differs from the published manifest: $relative"
        }
        $effectiveFiles += [ordered]@{
            path = $relative
            size = [int64]$file.Length
            sha256 = $actualHash
        }
    }

    $effective = [ordered]@{
        schema = 'spring-suite.deploy-manifest.v1'
        version = [string]$sourceManifest.version
        builtAt = [string]$sourceManifest.builtAt
        stagedAt = (Get-Date).ToString('o')
        parentManifestSha256 = Get-Sha256 -Path $sourceManifestPath
        preservedRoots = @($PreservedRoots)
        fileCount = $effectiveFiles.Count
        files = $effectiveFiles
    }
    Write-JsonAtomic -Path $stageManifestPath -Value $effective
    Write-Host "[deploy] effective staged manifest: $($effectiveFiles.Count) files"
}

function Validate-DeployImage {
    param([string]$ImageRoot)

    $validationResult = Join-Path $supervisorRoot ("validation-" + $deploymentId + ".json")
    & $applyScript -Target $targetRoot -Payload $ImageRoot -DeploymentId $deploymentId -ValidateOnly -ResultPath $validationResult
    if ($LASTEXITCODE -ne 0) {
        throw "Deploy image validation failed. Result: $validationResult"
    }
    $result = Get-Content -LiteralPath $validationResult -Raw | ConvertFrom-Json
    Remove-FileExact -Path $validationResult
    if (-not [bool]$result.ok) {
        throw "Deploy image validation failed: $($result.error)"
    }
    Write-Host "[deploy] validated $($result.fileCount) files; JAR SHA-256 $($result.jarSha256)"
    return $result
}

function Quote-NativeArgument {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value -or $Value.Length -eq 0) {
        return '""'
    }
    if ($Value.Contains('"')) {
        throw "Process argument contains an unsupported quote character: $Value"
    }
    if ($Value -notmatch '\s') {
        return $Value
    }
    return '"' + $Value + '"'
}

function Resolve-WindowsPowerShell {
    $candidate = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        return $candidate
    }
    return "powershell.exe"
}

function Start-BootstrapSupervisor {
    param(
        [int]$OldProcessId,
        [bool]$ApplyWithoutStart
    )

    $bootstrapRoot = Join-Path $supervisorRoot "bootstrap"
    New-Item -ItemType Directory -Path $bootstrapRoot -Force | Out-Null
    foreach ($name in @("spring-suite-supervisor.ps1", "apply-deploy.ps1", "suite-toast.ps1")) {
        Copy-FileExact -Source (Join-Path $scriptDirectory $name) -Destination (Join-Path $bootstrapRoot $name) -Overwrite
    }

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", (Join-Path $bootstrapRoot "spring-suite-supervisor.ps1"),
        "-Root", $targetRoot,
        "-Port", [string]$Port,
        "-StartupTimeoutSeconds", [string]$HealthTimeoutSeconds,
        "-StabilizationSeconds", [string]$StabilizationSeconds,
        "-ShutdownTimeoutSeconds", [string]$ShutdownTimeoutSeconds,
        "-PortReleaseTimeoutSeconds", [string]$PortReleaseTimeoutSeconds
    )
    if ($OldProcessId -gt 0) {
        $arguments += @("-TakeoverPid", [string]$OldProcessId)
    }
    if ($ApplyWithoutStart) {
        $arguments += "-StopAfterApply"
    }

    $argumentLine = ($arguments | ForEach-Object { Quote-NativeArgument -Value ([string]$_) }) -join " "
    $process = Start-Process `
        -FilePath (Resolve-WindowsPowerShell) `
        -ArgumentList $argumentLine `
        -WorkingDirectory $targetRoot `
        -WindowStyle Hidden `
        -PassThru
    Write-Host "[deploy] bootstrap supervisor PID $($process.Id)"
    return $process
}

function Wait-ForBootstrapTakeover {
    param(
        [System.Diagnostics.Process]$SupervisorProcess,
        [int]$OldProcessId
    )

    if ($null -eq $SupervisorProcess -or $OldProcessId -le 0) {
        return
    }

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if ($SupervisorProcess.HasExited) {
            throw "Bootstrap supervisor PID $($SupervisorProcess.Id) exited before taking ownership of runtime PID $OldProcessId."
        }
        $state = Get-SupervisorState
        if ($null -ne $state -and
            [int]$state.supervisorPid -eq $SupervisorProcess.Id -and
            [string]$state.status -eq "waiting-for-takeover" -and
            [int]$state.childPid -eq $OldProcessId) {
            Write-Host "[deploy] bootstrap supervisor is tracking runtime PID $OldProcessId"
            return
        }
        Start-Sleep -Milliseconds 250
        try { $SupervisorProcess.Refresh() } catch {}
    }
    throw "Bootstrap supervisor did not confirm process-tree ownership for runtime PID $OldProcessId within 20 seconds."
}

function Wait-ForDeploymentResult {
    param([bool]$ExpectStarted)

    $deadline = (Get-Date).AddSeconds(180)
    $resultPath = Join-Path $supervisorRoot ("deployment-result-" + $deploymentId + ".json")

    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
            $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
            if (-not [bool]$result.ok) {
                throw "Deployment transaction failed: $($result.error)"
            }
        }

        $state = Get-SupervisorState
        if ($null -ne $state) {
            $stateStatus = [string]$state.status
            $stateDeployment = [string]$state.deploymentId
            if ($stateStatus -eq "failed" -or $stateStatus -eq "crash-loop") {
                throw "Supervisor failed during deployment: $($state.error)"
            }
            if ($stateStatus -eq "blocked-port") {
                throw "Deployment is blocked before commit because port $Port has an unexpected owner. Files remain unchanged until the port is released. Supervisor error: $($state.error)"
            }
            if ($stateStatus -eq "recovery-wait") {
                throw "Deployment entered self-recovery wait. SpringSuite supervisor is still alive. Read incident current after recovery or inspect $targetRoot\.springsuite\incidents\current.json."
            }
            if ($stateStatus -eq "ready-after-recovery") {
                throw "Deployment did not complete normally; SpringSuite restored a known-good runtime. Read: incident current."
            }
            if ($stateStatus -eq "ready-after-rollback" -and $stateDeployment.EndsWith($deploymentId, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Deployment $deploymentId failed its health check and was rolled back successfully. Read: incident current."
            }
            if ($stateDeployment -eq $deploymentId) {
                if ($ExpectStarted -and $stateStatus -eq "ready") {
                    Write-Host "[deploy] SpringSuite is READY, PID $($state.childPid)"
                    return
                }
                if (-not $ExpectStarted -and $stateStatus -eq "stopped-after-deploy") {
                    Write-Host "[deploy] deployment applied; runtime remains stopped"
                    return
                }
            }
        }
        Start-Sleep -Milliseconds 750
    }

    throw "Deployment $deploymentId did not complete within 180 seconds. Supervisor state: $supervisorStateFile"
}

$targetRoot = Resolve-DeploymentTarget -ConfiguredTarget $Target
Assert-SafeDeploymentTopology -TargetRoot $targetRoot
Assert-SourceRepositoryHealthy

if (-not $SkipBuild) {
    Invoke-GradleBuild
}
if (-not (Test-Path -LiteralPath (Join-Path $deployImage "spring-suite.jar") -PathType Leaf)) {
    throw "Deploy image is missing. Run without -SkipBuild or execute gradlew.bat assembleDeploy"
}

$deploymentId = "deploy-" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
$supervisorRoot = Join-Path $targetRoot ".springsuite\supervisor"
$supervisorStateFile = Join-Path $supervisorRoot "state.json"
$pendingFile = Join-Path $supervisorRoot "pending-deployment.json"
$stageRoot = Join-Path $targetRoot (".springsuite\deploy\staging\" + $deploymentId)
$payloadRoot = Join-Path $stageRoot "payload"

if (-not $PSCmdlet.ShouldProcess($targetRoot, "Deploy SpringSuite transaction $deploymentId")) {
    return
}

try {
    New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $supervisorRoot -Force | Out-Null

    if (Test-Path -LiteralPath $pendingFile -PathType Leaf) {
        $existing = Get-Content -LiteralPath $pendingFile -Raw | ConvertFrom-Json
        throw "Another deployment is already pending: $($existing.deploymentId)"
    }

    Validate-DeployImage -ImageRoot $deployImage | Out-Null
    $preservedRoots = @(Copy-DeployImageToStage -ImageRoot $deployImage -StageRoot $payloadRoot)
    Write-EffectiveDeployManifest -ImageRoot $deployImage -StageRoot $payloadRoot -PreservedRoots $preservedRoots
    Validate-DeployImage -ImageRoot $payloadRoot | Out-Null

    $pending = [ordered]@{
        schema = "spring-suite.pending-deployment.v2"
        deploymentId = $deploymentId
        payload = $payloadRoot
        target = $targetRoot
        port = $Port
        createdAt = (Get-Date).ToString("o")
        requestedByPid = $PID
        replaceConfig = [bool]$ReplaceConfig
        startAfterDeploy = -not $NoStart
        healthTimeoutSeconds = $HealthTimeoutSeconds
        stabilizationSeconds = $StabilizationSeconds
        shutdownTimeoutSeconds = $ShutdownTimeoutSeconds
        portReleaseTimeoutSeconds = $PortReleaseTimeoutSeconds
    }
    Write-JsonAtomic -Path $pendingFile -Value $pending
    Write-Host "[deploy] staged transaction $deploymentId"
    Send-DeployToast -EventId "deploy-staged" -Title "SpringSuite deployment prepared" -Message "Deployment $deploymentId passed validation and is ready to install." -Level "Info"

    $runtimeStatus = Get-RuntimeStatus
    if ($null -ne $runtimeStatus -and -not [string]::IsNullOrWhiteSpace($runtimeStatus.launchRoot)) {
        $activeRoot = [System.IO.Path]::GetFullPath([string]$runtimeStatus.launchRoot).TrimEnd('\', '/')
        if ($activeRoot -ne $targetRoot) {
            Write-Host "[deploy] port $Port belongs to another runtime: $activeRoot"
            $runtimeStatus = $null
        }
    }

    $supervisorState = Get-SupervisorState
    $existingSupervisorAlive = $false
    if ($null -ne $supervisorState) {
        $existingSupervisorAlive = $null -ne (Get-Process -Id ([int]$supervisorState.supervisorPid) -ErrorAction SilentlyContinue)
    }
    if ($NoStart -and $null -ne $runtimeStatus -and -not $ForceStop) {
        throw "-NoStart cannot replace a running JAR without stopping it. Re-run with -ForceStop or omit -NoStart."
    }

    if (-not $existingSupervisorAlive) {
        $oldPid = if ($null -eq $runtimeStatus) { 0 } else { [int]$runtimeStatus.pid }
        $startedBootstrapSupervisor = Start-BootstrapSupervisor -OldProcessId $oldPid -ApplyWithoutStart ([bool]$NoStart)
        if ($oldPid -gt 0) {
            Wait-ForBootstrapTakeover -SupervisorProcess $startedBootstrapSupervisor -OldProcessId $oldPid
        } else {
            Start-Sleep -Milliseconds 500
        }
    }

    if ($null -ne $runtimeStatus) {
        $hasOwningSupervisor = $existingSupervisorAlive
        $transitionCommand = if ($hasOwningSupervisor) { "restart --force --delay 1" } else { "exit" }
        $transitionLabel = if ($hasOwningSupervisor) { "supervised restart" } else { "legacy shutdown" }
        Send-DeployToast -EventId "deploy-transition-requested" -Title "SpringSuite lifecycle transition" -Message "Runtime PID $($runtimeStatus.pid) will perform a $transitionLabel before the verified transaction is committed." -Level "Info"
        $transitionRequested = Request-GracefulTransition -Token (Get-BridgeToken) -CommandLine $transitionCommand
        if (-not $transitionRequested) {
            if ($ForceStop) {
                Send-DeployToast -EventId "deploy-force-stop" -Title "SpringSuite force stop" -Message "Graceful $transitionLabel was unavailable. PID $($runtimeStatus.pid) will be stopped to continue the verified deployment." -Level "Warning"
                if ($env:OS -eq "Windows_NT") {
                    & taskkill.exe /PID ([int]$runtimeStatus.pid) /T /F 2>$null | Out-Null
                } else {
                    Stop-Process -Id ([int]$runtimeStatus.pid) -Force
                }
            } else {
                if ($null -ne $startedBootstrapSupervisor) {
                    Stop-Process -Id $startedBootstrapSupervisor.Id -Force -ErrorAction SilentlyContinue
                }
                Remove-FileExact -Path $pendingFile
                throw "Runtime is active and graceful $transitionLabel could not be requested. Configure NORTHSTAR_BRIDGE_ACCESS_TOKEN or rerun with -ForceStop."
            }
        }
    }

    Wait-ForDeploymentResult -ExpectStarted (-not $NoStart)

    if (-not $KeepBuild) {
        & (Join-Path $scriptDirectory "clean.ps1") -Mode Build -Quiet
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Deployment succeeded, but build cleanup returned exit code $LASTEXITCODE"
        }
    }

    Write-Host "[deploy] deployment completed"
    Write-Host "[deploy] id: $deploymentId"
    Write-Host "[deploy] target: $targetRoot"
    Write-Host "[deploy] supervisor: $supervisorStateFile"
} catch {
    $message = $_.Exception.Message
    Send-DeployToast -EventId "deploy-failed" -Title "SpringSuite deployment failed" -Message $message -Level "Error"
    Write-Host ("[deploy] stack: " + $_.ScriptStackTrace)
    Write-Host ("[deploy] exception: " + $_.Exception.ToString())
    Write-Error $_
    exit 1
}
