[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateSet("Build", "Generated", "Deep")]
    [string]$Mode = "Generated",

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

    if ($PSCmdlet.ShouldProcess($relative, "Remove generated repository artifact")) {
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

Push-Location $repositoryRoot
try {
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
} finally {
    Pop-Location
}

$result = [ordered]@{
    mode = $Mode
    removedCount = $removedPaths.Count
    removedBytes = $removedBytes
    removedMiB = [Math]::Round($removedBytes / 1MB, 2)
    removed = $removedPaths
}

if (-not $Quiet) {
    Write-Host "[clean] removed $($removedPaths.Count) paths, $($result.removedMiB) MiB"
}

$result | ConvertTo-Json -Depth 4
