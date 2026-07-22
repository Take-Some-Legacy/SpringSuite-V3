[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Root = "",
    [ValidateSet("Preflight", "Portable", "Service")]
    [string]$Mode = "Preflight",
    [switch]$InstallToast,
    [switch]$Start,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runtimeRoot = if ([string]::IsNullOrWhiteSpace($Root)) {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
} else {
    [System.IO.Path]::GetFullPath($Root)
}
$configPath = Join-Path $runtimeRoot "config\runtime-controller.json"
$controller = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-controller.exe"
$bootstrap = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-bootstrap.exe"
$toast = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-toast.exe"
$tray = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-tray.exe"
$host = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-toast-host.exe"
$replacer = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-replacer.exe"
$jar = Join-Path $runtimeRoot "spring-suite.jar"

$required = @($configPath, $controller, $bootstrap, $toast, $tray, $host, $replacer, $jar)
$missing = @($required | Where-Object { -not [System.IO.File]::Exists($_) })
if ($missing.Count -gt 0) {
    throw "Runtime controller integration is incomplete. Missing: $($missing -join ', ')"
}

$doctorRaw = & $controller doctor --config $configPath
if ($LASTEXITCODE -ne 0) {
    throw "Runtime controller doctor failed with exit code $LASTEXITCODE"
}
$doctor = $doctorRaw | ConvertFrom-Json
$owners = @($doctor.portOwners)
$preflight = [ordered]@{
    root = $runtimeRoot
    config = $configPath
    java = $doctor.javaResolved
    jarExists = [bool]$doctor.jarExists
    replacerExists = [bool]$doctor.replacerExists
    bootstrapExists = [bool]$doctor.bootstrapExists
    runtimePort = [int]$doctor.runtimePort
    portOwners = $owners
    controlTokenPresent = [bool]$doctor.controlTokenPresent
    mode = $Mode
}
$preflight | ConvertTo-Json -Depth 5

if ($Mode -eq "Preflight") {
    exit 0
}

if ($owners.Count -gt 0 -and -not $Force) {
    throw "Runtime port $($doctor.runtimePort) is already owned by PID(s): $($owners -join ', '). Refusing cutover without -Force."
}

if ($InstallToast) {
    if ($PSCmdlet.ShouldProcess("Current user", "Install SpringSuite WinToast broker autostart")) {
        & $toast install --config $configPath
        if ($LASTEXITCODE -ne 0) { throw "Toast broker install failed: $LASTEXITCODE" }
    }
}

switch ($Mode) {
    "Portable" {
        if ($Start -and $PSCmdlet.ShouldProcess($runtimeRoot, "Start portable runtime controller")) {
            & $bootstrap start --config $configPath
            if ($LASTEXITCODE -ne 0) { throw "Portable controller start failed: $LASTEXITCODE" }
        }
    }
    "Service" {
        if ($PSCmdlet.ShouldProcess("SpringSuiteRuntimeController", "Install Windows service")) {
            & $controller service-install --config $configPath
            if ($LASTEXITCODE -ne 0) { throw "Service installation failed: $LASTEXITCODE" }
        }
        if ($Start -and $PSCmdlet.ShouldProcess("SpringSuiteRuntimeController", "Start Windows service")) {
            & $controller service-start --config $configPath
            if ($LASTEXITCODE -ne 0) { throw "Service start failed: $LASTEXITCODE" }
        }
    }
}
