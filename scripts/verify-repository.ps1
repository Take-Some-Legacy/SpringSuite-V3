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
    "gradle.properties",
    "gradle\runtime-module-signing.gradle.kts",
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

$requiredRootFiles | ForEach-Object { Assert-File $_ }
Assert-Directory "components"
Assert-Directory "native\go"

$settingsPath = Join-Path $repositoryRoot "settings.gradle.kts"
$settingsContent = if (Test-Path -LiteralPath $settingsPath -PathType Leaf) {
    Get-Content -LiteralPath $settingsPath -Raw
} else {
    ""
}
$declaredModules = @(
    [regex]::Matches($settingsContent, '"(suite-[a-z0-9-]+)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique
)
if ($declaredModules.Count -eq 0) {
    $errors.Add("settings.gradle.kts declares no SpringSuite modules")
}

$componentRoot = Join-Path $repositoryRoot "components"
$componentModuleDirectories = @()
if (Test-Path -LiteralPath $componentRoot -PathType Container) {
    $componentModuleDirectories = @(
        Get-ChildItem -LiteralPath $componentRoot -Directory -Recurse -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -like "suite-*" -and
                (Test-Path -LiteralPath (Join-Path $_.FullName "build.gradle.kts") -PathType Leaf)
            }
    )
}

foreach ($module in $declaredModules) {
    $matches = @($componentModuleDirectories | Where-Object { $_.Name -eq $module })
    if ($matches.Count -eq 0) {
        $errors.Add("declared module has no component directory: $module")
        continue
    }
    if ($matches.Count -gt 1) {
        $errors.Add("declared module is duplicated in components tree: $module")
    }
}

foreach ($directory in $componentModuleDirectories) {
    if ($declaredModules -notcontains $directory.Name) {
        $errors.Add("component module is not declared in settings.gradle.kts: $(Get-RepositoryRelativePath -Path $directory.FullName)")
    }
}

$forbiddenPatterns = @(
    "*.bak-*",
    "*-debug.exe"
)

$gitCommand = Get-Command "git.exe" -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    $warnings.Add("git.exe is unavailable; tracked generated artifacts were not checked")
} else {
    $trackedFiles = @(& $gitCommand.Source -C $repositoryRoot ls-files)
    foreach ($pattern in $forbiddenPatterns) {
        $trackedFiles |
            Where-Object { $_ -like $pattern } |
            ForEach-Object { $errors.Add("generated artifact is tracked by git: $_") }
    }

    $trackedFiles |
        Where-Object { $_ -like "*.springsuite-repository.json" } |
        ForEach-Object { $errors.Add("machine-generated descriptor is tracked by git: $_") }
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
    modules = $declaredModules.Count
    errors = $errors
    warnings = $warnings
}

$result | ConvertTo-Json -Depth 5

if ($errors.Count -gt 0) {
    exit 1
}
