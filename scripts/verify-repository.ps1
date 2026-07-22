[CmdletBinding()]
param(
    [switch]$VerifyDeployImage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory ".."))
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Get-RepositoryRelativePath {
    param([string]$Path)

    $basePath = $repositoryRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    $baseUri = [Uri]$basePath
    $pathUri = [Uri][System.IO.Path]::GetFullPath($Path)
    $relativeUri = $baseUri.MakeRelativeUri($pathUri)
    return [Uri]::UnescapeDataString($relativeUri.ToString()).Replace('/', '\')
}


function Assert-File {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $errors.Add("missing file: $RelativePath")
    }
}

function Assert-Directory {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        $errors.Add("missing directory: $RelativePath")
    }
}

$requiredRootFiles = @(
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradlew",
    "gradlew.bat",
    "README.md",
    ".gitignore",
    ".gitattributes",
    "scripts\clean.ps1",
    "scripts\deploy.ps1",
    "scripts\apply-deploy.ps1",
    "scripts\spring-suite-single-instance-check.ps1"
)

$requiredModules = @(
    "suite-core",
    "suite-ai-api",
    "suite-platform",
    "suite-desktop-api",
    "suite-desktop-config",
    "suite-observability",
    "suite-form-intelligence",
    "suite-browser-dom",
    "suite-logging",
    "suite-database",
    "suite-config",
    "suite-module",
    "suite-cloudflared",
    "suite-cloudflared-module",
    "suite-command",
    "suite-toolbelt",
    "suite-workspace",
    "suite-ai",
    "suite-openai",
    "suite-desktop-helper",
    "suite-agent",
    "suite-app",
    "suite-diagnostics-module",
    "suite-dashboard-module",
    "suite-fn-module"
)

$requiredRootFiles | ForEach-Object { Assert-File $_ }
$requiredModules | ForEach-Object {
    Assert-Directory $_
    Assert-File (Join-Path $_ "build.gradle.kts")
}

$forbiddenPatterns = @(
    "*.bak-*",
    "*-debug.exe"
)

foreach ($pattern in $forbiddenPatterns) {
    Get-ChildItem -LiteralPath $repositoryRoot -Recurse -Force -File -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "[\\/]build[\\/]" -and $_.FullName -notmatch "[\\/]logs[\\/]" } |
        ForEach-Object {
            $relative = Get-RepositoryRelativePath -Path $_.FullName
            $errors.Add("generated artifact in repository tree: $relative")
        }
}

$gitCommand = Get-Command "git.exe" -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    $warnings.Add("git.exe is unavailable; tracked generated descriptors were not checked")
} else {
    $trackedDescriptors = @(
        & $gitCommand.Source -C $repositoryRoot ls-files |
            Where-Object { $_ -like "*.springsuite-repository.json" }
    )
    foreach ($descriptor in $trackedDescriptors) {
        $errors.Add("machine-generated descriptor is tracked by git: $descriptor")
    }
}

if ($VerifyDeployImage) {
    $deployRoot = Join-Path $repositoryRoot "build\deploy"
    $requiredDeployFiles = @(
        "spring-suite.jar",
        "run.bat",
        "run-console.bat",
        "run-elevated.bat",
        "scripts\deploy.ps1",
        "scripts\apply-deploy.ps1",
        "scripts\clean.ps1",
        "config\suite-cloudflared.yml",
        "suiteBinaries\suite-cloudflared-wrapper.exe"
    )

    foreach ($relative in $requiredDeployFiles) {
        $path = Join-Path $deployRoot $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $errors.Add("deploy image missing: $relative")
        }
    }
}

$result = [ordered]@{
    ok = $errors.Count -eq 0
    repository = $repositoryRoot
    modules = $requiredModules.Count
    errors = $errors
    warnings = $warnings
}

$result | ConvertTo-Json -Depth 5

if ($errors.Count -gt 0) {
    exit 1
}
