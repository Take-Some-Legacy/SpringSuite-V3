[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Root = "",
    [ValidateSet("Preflight", "Portable", "Service")]
    [string]$Mode = "Preflight",
    [switch]$InstallToast,
    [switch]$NoTray,
    [switch]$Start,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($InstallToast -and $NoTray) {
    throw "-InstallToast and -NoTray are mutually exclusive."
}

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
$toastHost = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-toast-host.exe"
$replacer = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-replacer.exe"
$jar = Join-Path $runtimeRoot "spring-suite.jar"

$required = @($configPath, $controller, $bootstrap, $toast, $tray, $toastHost, $replacer, $jar)
$missing = @($required | Where-Object { -not [System.IO.File]::Exists($_) })
if ($missing.Count -gt 0) {
    throw "Runtime controller integration is incomplete. Missing: $($missing -join ', ')"
}

$currentSessionId = [System.Diagnostics.Process]::GetCurrentProcess().SessionId
$trayRequested = $InstallToast -or (($Mode -in @("Portable", "Service")) -and -not $NoTray)

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
    sessionId = $currentSessionId
    trayRequested = $trayRequested
}
$preflight | ConvertTo-Json -Depth 5

# The user-session broker must be installed before the elevated/service phase.
# Session 0 owns the runtime, but Windows notification-area UI must be created
# by the interactive user whose HKCU hive and desktop are active.
if ($trayRequested) {
    if ($currentSessionId -eq 0) {
        throw "SpringSuite tray installation requires an interactive user session. Run the installer from the signed-in desktop before entering the service phase."
    }
    if ($PSCmdlet.ShouldProcess("Current interactive user", "Install and start SpringSuite runtime tray")) {
        & $tray install --config $configPath --start=true
        if ($LASTEXITCODE -ne 0) {
            throw "Runtime tray install failed: $LASTEXITCODE"
        }

        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        $trayProcess = $null
        do {
            $trayProcess = Get-Process -Name "suite-runtime-tray" -ErrorAction SilentlyContinue |
                Where-Object { $_.SessionId -eq $currentSessionId } |
                Select-Object -First 1
            if ($null -eq $trayProcess) {
                Start-Sleep -Milliseconds 250
            }
        } while ($null -eq $trayProcess -and [DateTime]::UtcNow -lt $deadline)

        if ($null -eq $trayProcess) {
            throw "Runtime tray was registered but did not start in interactive session $currentSessionId."
        }
    }
}

if ($Mode -eq "Preflight") {
    exit 0
}

if ($owners.Count -gt 0 -and -not $Force) {
    throw "Runtime port $($doctor.runtimePort) is already owned by PID(s): $($owners -join ', '). Refusing cutover without -Force."
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
