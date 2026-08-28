[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateSet("Build", "Generated", "Runtime", "Deep")]
    [string]$Mode = "Generated",

    [ValidateRange(1, 3650)]
    [int]$RetentionDays = 14,

    [switch]$Force,

    [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory ".."))
$removedBytes = [int64]0
$removedPaths = [System.Collections.Generic.List[string]]::new()

function Get-RepositoryRelativePath {
    param([string]$Path)

    $basePath = $repositoryRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $baseUri = [Uri]$basePath
    $pathUri = [Uri][System.IO.Path]::GetFullPath($Path)
    $relativeUri = $baseUri.MakeRelativeUri($pathUri)
    return [Uri]::UnescapeDataString($relativeUri.ToString()).Replace('/', '\')
}

function Get-PathSize {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [int64]0
    }

    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer) {
        return [int64]$item.Length
    }

    $sum = [int64]0
    Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue | ForEach-Object {
        $sum += [int64]$_.Length
    }
    return $sum
}

function Remove-GeneratedPath {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $size = Get-PathSize -Path $Path
    $relative = Get-RepositoryRelativePath -Path $Path

    if ($PSCmdlet.ShouldProcess($relative, "Remove generated/runtime artifact")) {
        Remove-Item -LiteralPath $Path -Recurse -Force
        $script:removedBytes += $size
        $script:removedPaths.Add($relative)
        if (-not $Quiet) {
            Write-Host "[clean] removed $relative"
        }
    }
}

function Remove-MatchingFiles {
    param(
        [string]$Root,
        [string[]]$Patterns
    )

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return
    }

    foreach ($pattern in $Patterns) {
        Get-ChildItem -LiteralPath $Root -Recurse -Force -File -Filter $pattern -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-GeneratedPath -Path $_.FullName
        }
    }
}

function Remove-AgedChildren {
    param(
        [string]$Root,
        [datetime]$CutoffUtc,
        [string[]]$KeepNames = @(),
        [switch]$RecurseFiles
    )

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return
    }

    $keep = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $KeepNames) { [void]$keep.Add($name) }

    if ($RecurseFiles) {
        Get-ChildItem -LiteralPath $Root -Recurse -Force -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -lt $CutoffUtc -and -not $keep.Contains($_.Name) } |
            ForEach-Object { Remove-GeneratedPath -Path $_.FullName }
        return
    }

    Get-ChildItem -LiteralPath $Root -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -lt $CutoffUtc -and -not $keep.Contains($_.Name) } |
        Sort-Object { $_.FullName.Length } -Descending |
        ForEach-Object { Remove-GeneratedPath -Path $_.FullName }
}

function Invoke-RuntimeCleanup {
    $manifestPath = Join-Path $repositoryRoot "deploy-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Runtime cleanup requires deploy-manifest.json at $repositoryRoot"
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$manifest.schema -ne "spring-suite.deploy-manifest.v1") {
        throw "Unsupported deploy manifest schema: $($manifest.schema)"
    }

    $managed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        [void]$managed.Add(([string]$entry.path).Replace('/', '\'))
    }

    # Remove only explicitly known legacy top-level artifacts that are not part of the deployed manifest.
    foreach ($relative in @(
        ".github",
        "service",
        "Apply-Cloudflared-AutoDiscovery.ps1"
    )) {
        $normalized = $relative.Replace('/', '\')
        $isManaged = $managed.Contains($normalized) -or @($managed | Where-Object { $_.StartsWith($normalized + '\', [System.StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
        if (-not $isManaged) {
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot $relative)
        }
    }

    # Persistent roots remain intact; only known historical subtrees/files are retention-pruned.
    $cutoffUtc = [DateTime]::UtcNow.AddDays(-$RetentionDays)
    Remove-AgedChildren -Root (Join-Path $repositoryRoot ".springsuite\deploy-staging") -CutoffUtc $cutoffUtc
    Remove-AgedChildren -Root (Join-Path $repositoryRoot ".springsuite\deploy-backups") -CutoffUtc $cutoffUtc

    foreach ($legacy in @(
        ".springsuite\update-cloudflared-autostart",
        ".springsuite\updater-tests"
    )) {
        $full = Join-Path $repositoryRoot $legacy
        if (Test-Path -LiteralPath $full) {
            $item = Get-Item -LiteralPath $full -Force
            if ($item.LastWriteTimeUtc -lt $cutoffUtc) {
                Remove-GeneratedPath -Path $full
            }
        }
    }

    Remove-AgedChildren -Root (Join-Path $repositoryRoot ".springsuite\controller") -CutoffUtc $cutoffUtc -KeepNames @(
        "state.json",
        "startup.jsonl",
        "notifications.jsonl",
        "notifications",
        "pending"
    )

    Remove-AgedChildren -Root (Join-Path $repositoryRoot "logs\archive") -CutoffUtc $cutoffUtc -RecurseFiles
    Remove-AgedChildren -Root (Join-Path $repositoryRoot "logs\crash") -CutoffUtc $cutoffUtc -RecurseFiles
    Remove-AgedChildren -Root (Join-Path $repositoryRoot "logs\service") -CutoffUtc $cutoffUtc -RecurseFiles
    Remove-AgedChildren -Root (Join-Path $repositoryRoot "logs") -CutoffUtc $cutoffUtc -KeepNames @(
        "spring-suite.log",
        "gc.log",
        "jar-deploy-latest.json",
        "archive",
        "crash",
        "service"
    )
}

Push-Location $repositoryRoot
try {
    if ($Mode -eq "Runtime") {
        Invoke-RuntimeCleanup
    } else {
        Get-ChildItem -LiteralPath $repositoryRoot -Recurse -Force -Directory -Filter "build" |
            Sort-Object { $_.FullName.Length } -Descending |
            ForEach-Object { Remove-GeneratedPath -Path $_.FullName }

        Remove-GeneratedPath -Path (Join-Path $repositoryRoot "out")

        if ($Mode -in @("Generated", "Deep")) {
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot ".gradle")
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot ".idea")
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot "deploy-manifest.json")
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot "DEPLOY_MANIFEST.json")
            Remove-GeneratedPath -Path (Join-Path $repositoryRoot "MIGRATION-REPORT.json")

            Get-ChildItem -LiteralPath $repositoryRoot -Recurse -Force -File -Filter "*.bak-*" -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.FullName -notmatch "[\\/]\.git[\\/]" -and
                    $_.FullName -notmatch "[\\/]logs[\\/]" -and
                    $_.FullName -notmatch "[\\/]data[\\/]" -and
                    $_.FullName -notmatch "[\\/]\.springsuite[\\/]"
                } |
                ForEach-Object { Remove-GeneratedPath -Path $_.FullName }

            Remove-MatchingFiles -Root (Join-Path $repositoryRoot "suiteBinaries") -Patterns @(
                "*.bak-*",
                "*-debug.exe"
            )

            Remove-MatchingFiles -Root (Join-Path $repositoryRoot "native\go") -Patterns @(
                "*.exe",
                "*.test"
            )
        }

        if ($Mode -eq "Deep") {
            if (-not $Force) {
                throw "Deep cleanup removes local runtime state. Rerun with -Mode Deep -Force to confirm."
            }

            foreach ($relativePath in @(".springsuite", "config", "data", "logs", "modules")) {
                Remove-GeneratedPath -Path (Join-Path $repositoryRoot $relativePath)
            }
        }
    }
} finally {
    Pop-Location
}

$result = [ordered]@{
    mode = $Mode
    retentionDays = if ($Mode -eq "Runtime") { $RetentionDays } else { $null }
    removedCount = $removedPaths.Count
    removedBytes = $removedBytes
    removedMiB = [Math]::Round($removedBytes / 1MB, 2)
    removed = $removedPaths
}

if (-not $Quiet) {
    Write-Host "[clean] removed $($removedPaths.Count) paths, $($result.removedMiB) MiB"
}

$result | ConvertTo-Json -Depth 4
