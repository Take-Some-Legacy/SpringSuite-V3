[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Root = "",
    [ValidateSet("Preflight", "Portable", "Service")]
    [string]$Mode = "Preflight",
    [switch]$InstallToast,
    [switch]$NoTray,
    [switch]$Start,
    [switch]$Force,
    [string]$ServiceName = "NorthStarSuiteV3",
    [string]$ServiceDisplayName = "NorthStar Suite V3 Core",
    [string]$LegacyServiceName = "SpringSuiteRuntimeController",
    [string]$MachineDataRoot = "C:\ProgramData\NorthStarSuite",
    [string]$TunnelId = "626b902a-712c-4932-b7a4-f6daf7512696",
    [string]$TunnelHostname = "testspring.kaylas-systems.ru",
    [string]$ServiceHostSource = ""
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
$serviceHostSourcePath = if ([string]::IsNullOrWhiteSpace($ServiceHostSource)) {
    $controller
} else {
    [System.IO.Path]::GetFullPath($ServiceHostSource)
}
$bootstrap = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-bootstrap.exe"
$preloader = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-preloader.exe"
$toast = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-toast.exe"
$tray = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-tray.exe"
$toastHost = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-toast-host.exe"
$replacer = Join-Path $runtimeRoot "suiteBinaries\suite-runtime-replacer.exe"
$jar = Join-Path $runtimeRoot "spring-suite.jar"

$cloudflaredConfigPath = Join-Path $runtimeRoot "config\suite-cloudflared.yml"

function Resolve-CloudflaredExecutable {
    param([string]$RuntimeRoot)

    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:SPRING_SUITE_CLOUDFLARED_EXECUTABLE)) {
        $candidates.Add($env:SPRING_SUITE_CLOUDFLARED_EXECUTABLE)
    }
    $candidates.Add((Join-Path $RuntimeRoot "suiteBinaries\cloudflared.exe"))

    $command = Get-Command cloudflared.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command -and -not [string]::IsNullOrWhiteSpace($command.Source)) {
        $candidates.Add($command.Source)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\cloudflared.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ChocolateyInstall)) {
        $candidates.Add((Join-Path $env:ChocolateyInstall "bin\cloudflared.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:SCOOP)) {
        $candidates.Add((Join-Path $env:SCOOP "shims\cloudflared.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $candidates.Add((Join-Path $env:USERPROFILE ".cloudflared\cloudflared.exe"))
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and [System.IO.File]::Exists($candidate)) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    return $null
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Stop-And-RemoveServiceIfPresent {
    param([string]$Name)

    if ([string]::IsNullOrWhiteSpace($Name)) { return }
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $service) { return }
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $Name -Force -ErrorAction Stop
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(60))
    }
    & sc.exe delete $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to delete Windows service $Name (exit $LASTEXITCODE)." }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ($null -ne (Get-Service -Name $Name -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if ($null -ne (Get-Service -Name $Name -ErrorAction SilentlyContinue)) {
        throw "Windows service $Name is still pending deletion."
    }
}

function Grant-ServiceRuntimeAccess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Paths
    )
    $principal = "NT SERVICE\$Name"
    foreach ($path in @($Paths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $path)) { continue }
        & icacls.exe $path /grant "${principal}:(OI)(CI)(F)" /T /C /Q | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to grant $principal access to $path (exit $LASTEXITCODE)." }
    }
}

function Initialize-MachineCloudflaredConfig {
    param(
        [Parameter(Mandatory = $true)][string]$DataRoot,
        [Parameter(Mandatory = $true)][string]$TunnelId,
        [Parameter(Mandatory = $true)][string]$Hostname,
        [Parameter(Mandatory = $true)][int]$RuntimePort
    )

    $machineRoot = Join-Path $DataRoot ".cloudflared"
    New-Item -ItemType Directory -Path $machineRoot -Force | Out-Null
    $userRoot = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) ".cloudflared"
    foreach ($name in @("$TunnelId.json", "cert.pem")) {
        $source = Join-Path $userRoot $name
        $destination = Join-Path $machineRoot $name
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
                throw "Cloudflared machine credential is missing and no user source exists: $name"
            }
            Copy-Item -LiteralPath $source -Destination $destination -Force
        }
    }
    $configFile = Join-Path $machineRoot "config.yml"
    $credentialFile = Join-Path $machineRoot "$TunnelId.json"
    $yaml = @"
# managed-by: SpringSuite service installer
tunnel: $TunnelId
credentials-file: '$credentialFile'

ingress:
  - hostname: $Hostname
    service: http://localhost:$RuntimePort
  - service: http_status:404
"@
    [System.IO.File]::WriteAllText($configFile, $yaml.Trim() + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{
        Root = $machineRoot
        Config = $configFile
        Credentials = $credentialFile
        OriginCert = (Join-Path $machineRoot "cert.pem")
    }
}

function Set-CloudflaredServicePaths {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][object]$MachineConfig,
        [Parameter(Mandatory = $true)][string]$TunnelId,
        [Parameter(Mandatory = $true)][string]$Hostname
    )
    $content = [System.IO.File]::ReadAllText($Path)
    $values = [ordered]@{
        "executable" = $Executable
        "tunnel-name" = $TunnelId
        "hostname" = $Hostname
        "origin-cert-path" = [string]($MachineConfig.OriginCert)
        "user-profile" = (Join-Path (Split-Path -Parent ([string]($MachineConfig.Root))) "profile")
        "config-path" = [string]($MachineConfig.Config)
        "credentials-file" = [string]($MachineConfig.Credentials)
    }
    foreach ($entry in $values.GetEnumerator()) {
        $escaped = "'" + ([string]$entry.Value).Replace("'", "''") + "'"
        $pattern = '(?m)^(\s*' + [regex]::Escape([string]$entry.Key) + '\s*:)\s*.*$'
        if ($content -notmatch $pattern) { throw "suite.cloudflared.$($entry.Key) is missing from $Path" }
        $content = [regex]::Replace($content, $pattern, { param($match) $match.Groups[1].Value + " " + $escaped })
    }
    [System.IO.File]::WriteAllText($Path, $content, [System.Text.UTF8Encoding]::new($false))
}

function Set-CloudflaredRuntimeConfig {
    param(
        [string]$Path,
        [string]$Executable,
        [string]$UserProfile
    )

    if (-not [System.IO.File]::Exists($Path)) {
        throw "Cloudflared configuration is missing: $Path"
    }
    $content = [System.IO.File]::ReadAllText($Path)
    $quoteYaml = {
        param([string]$Value)
        return "'" + $Value.Replace("'", "''") + "'"
    }
    $executableScalar = & $quoteYaml $Executable
    $profileScalar = & $quoteYaml $UserProfile

    if ($content -match '(?m)^\s*executable\s*:') {
        $content = [regex]::Replace(
            $content,
            '(?m)^(\s*executable\s*:)\s*.*$',
            { param($match) $match.Groups[1].Value + " " + $executableScalar }
        )
    } else {
        throw "suite.cloudflared.executable is missing from $Path"
    }

    if ($content -match '(?m)^\s*user-profile\s*:') {
        $content = [regex]::Replace(
            $content,
            '(?m)^(\s*user-profile\s*:)\s*.*$',
            { param($match) $match.Groups[1].Value + " " + $profileScalar }
        )
    } else {
        $content = [regex]::Replace(
            $content,
            '(?m)^(\s*origin-cert-path\s*:.*)$',
            { param($match) $match.Groups[1].Value + [Environment]::NewLine + "    user-profile: " + $profileScalar }
        )
    }

    [System.IO.File]::WriteAllText($Path, $content, [System.Text.UTF8Encoding]::new($false))
}

function Get-ObjectPropertyValue {
    param(
        [AllowNull()]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string[]]$Names,

        [AllowNull()]
        [object]$DefaultValue = $null
    )

    if ($null -eq $InputObject) {
        return $DefaultValue
    }

    foreach ($name in $Names) {
        $property = $InputObject.PSObject.Properties[$name]

        if ($null -ne $property -and $null -ne $property.Value) {
            return $property.Value
        }
    }

    return $DefaultValue
}

function ConvertTo-SafeInt {
    param(
        [AllowNull()]
        [object]$Value,

        [int]$DefaultValue
    )

    if ($null -eq $Value) {
        return $DefaultValue
    }

    $parsed = 0

    if ([int]::TryParse([string]$Value, [ref]$parsed)) {
        return $parsed
    }

    return $DefaultValue
}

function ConvertTo-SafeBool {
    param(
        [AllowNull()]
        [object]$Value,

        [bool]$DefaultValue = $false
    )

    if ($null -eq $Value) {
        return $DefaultValue
    }

    if ($Value -is [bool]) {
        return [bool]$Value
    }

    $parsed = $false

    if ([bool]::TryParse([string]$Value, [ref]$parsed)) {
        return $parsed
    }

    switch -Regex ([string]$Value) {
        '^(1|yes|y|on)$'  { return $true }
        '^(0|no|n|off)$' { return $false }
        default           { return $DefaultValue }
    }
}

function Get-RuntimePortFromConfig {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [int]$DefaultValue = 8080
    )

    if (-not [System.IO.File]::Exists($Path)) {
        return $DefaultValue
    }

    try {
        $configObject = [System.IO.File]::ReadAllText($Path) |
            ConvertFrom-Json

        $directValue = Get-ObjectPropertyValue `
            -InputObject $configObject `
            -Names @(
                "runtimePort",
                "runtime_port",
                "port"
            ) `
            -DefaultValue $null

        if ($null -ne $directValue) {
            return ConvertTo-SafeInt `
                -Value $directValue `
                -DefaultValue $DefaultValue
        }

        foreach ($containerName in @(
            "runtime",
            "server",
            "controller",
            "http"
        )) {
            $container = Get-ObjectPropertyValue `
                -InputObject $configObject `
                -Names @($containerName) `
                -DefaultValue $null

            if ($null -eq $container) {
                continue
            }

            $nestedValue = Get-ObjectPropertyValue `
                -InputObject $container `
                -Names @(
                    "runtimePort",
                    "runtime_port",
                    "port"
                ) `
                -DefaultValue $null

            if ($null -ne $nestedValue) {
                return ConvertTo-SafeInt `
                    -Value $nestedValue `
                    -DefaultValue $DefaultValue
            }
        }
    }
    catch {
        Write-Verbose (
            "Unable to resolve runtime port from {0}: {1}" -f
            $Path,
            $_.Exception.Message
        )
    }

    return $DefaultValue
}
function Get-ListeningPortOwners {
    param([Parameter(Mandatory = $true)][int]$Port)

    $owners = @()
    $getNetTcpConnection = Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue
    if ($null -ne $getNetTcpConnection) {
        try {
            $owners = @(
                Get-NetTCPConnection -LocalPort $Port -ErrorAction Stop |
                    Where-Object { $_.State -eq 'Listen' } |
                    Select-Object -ExpandProperty OwningProcess -Unique
            )
        } catch {
            Write-Verbose "Get-NetTCPConnection fallback failed for port $Port`: $($_.Exception.Message)"
        }
    }

    if ($owners.Count -eq 0) {
        $netstat = Get-Command netstat.exe -ErrorAction SilentlyContinue
        if ($null -ne $netstat) {
            try {
                $pattern = '^\s*TCP\s+\S*:' + [regex]::Escape([string]$Port) + '\s+\S+\s+\S+\s+(\d+)\s*$'
                $owners = @(
                    & $netstat.Source -ano -p tcp 2>$null |
                        ForEach-Object {
                            $match = [regex]::Match([string]$_, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
                            if ($match.Success) {
                                [int]$match.Groups[1].Value
                            }
                        }
                )
            } catch {
                Write-Verbose "netstat fallback failed for port $Port`: $($_.Exception.Message)"
            }
        }
    }

    return @(
        $owners |
            Where-Object { $null -ne $_ -and [int]$_ -gt 0 } |
            ForEach-Object { [int]$_ } |
            Sort-Object -Unique
    )
}

$required = @($configPath, $controller, $bootstrap, $preloader, $toast, $tray, $toastHost, $replacer, $jar)
$cloudflaredExecutable = Resolve-CloudflaredExecutable -RuntimeRoot $runtimeRoot
$cloudflaredUserProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
if (-not [string]::IsNullOrWhiteSpace($cloudflaredExecutable)) {
    $env:SPRING_SUITE_CLOUDFLARED_EXECUTABLE = $cloudflaredExecutable
}
if (-not [string]::IsNullOrWhiteSpace($cloudflaredUserProfile)) {
    $env:SPRING_SUITE_CLOUDFLARED_USER_PROFILE = $cloudflaredUserProfile
}
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
$doctorText = [string]::Join(
    [Environment]::NewLine,
    @($doctorRaw)
)

if ([string]::IsNullOrWhiteSpace($doctorText)) {
    throw "Runtime controller doctor returned an empty response."
}

try {
    $doctor = $doctorText | ConvertFrom-Json
}
catch {
    throw (
        "Runtime controller doctor returned invalid JSON: {0}. Raw output: {1}" -f
        $_.Exception.Message,
        $doctorText
    )
}

$runtimePortDefault = Get-RuntimePortFromConfig `
    -Path $configPath `
    -DefaultValue 8080

$runtimePortValue = Get-ObjectPropertyValue `
    -InputObject $doctor `
    -Names @(
        "runtimePort",
        "runtime_port",
        "port"
    ) `
    -DefaultValue $runtimePortDefault

$runtimePort = ConvertTo-SafeInt `
    -Value $runtimePortValue `
    -DefaultValue $runtimePortDefault

$javaResolved = [string](
    Get-ObjectPropertyValue `
        -InputObject $doctor `
        -Names @(
            "javaResolved",
            "java_resolved",
            "java",
            "javaExecutable",
            "java_executable"
        ) `
        -DefaultValue ""
)

$jarExists = ConvertTo-SafeBool `
    -Value (
        Get-ObjectPropertyValue `
            -InputObject $doctor `
            -Names @(
                "jarExists",
                "jar_exists"
            ) `
            -DefaultValue ([System.IO.File]::Exists($jar))
    ) `
    -DefaultValue ([System.IO.File]::Exists($jar))

$replacerExists = ConvertTo-SafeBool `
    -Value (
        Get-ObjectPropertyValue `
            -InputObject $doctor `
            -Names @(
                "replacerExists",
                "replacer_exists"
            ) `
            -DefaultValue ([System.IO.File]::Exists($replacer))
    ) `
    -DefaultValue ([System.IO.File]::Exists($replacer))

$bootstrapExists = ConvertTo-SafeBool `
    -Value (
        Get-ObjectPropertyValue `
            -InputObject $doctor `
            -Names @(
                "bootstrapExists",
                "bootstrap_exists"
            ) `
            -DefaultValue ([System.IO.File]::Exists($bootstrap))
    ) `
    -DefaultValue ([System.IO.File]::Exists($bootstrap))

$controlTokenPresent = ConvertTo-SafeBool `
    -Value (
        Get-ObjectPropertyValue `
            -InputObject $doctor `
            -Names @(
                "controlTokenPresent",
                "control_token_present"
            ) `
            -DefaultValue $false
    ) `
    -DefaultValue $false

$portOwnersValue = Get-ObjectPropertyValue `
    -InputObject $doctor `
    -Names @(
        "portOwners",
        "port_owners"
    ) `
    -DefaultValue $null

if ($null -ne $portOwnersValue) {
    $owners = @(
        $portOwnersValue |
            Where-Object {
                $null -ne $_
            } |
            ForEach-Object {
                ConvertTo-SafeInt -Value $_ -DefaultValue 0
            } |
            Where-Object {
                $_ -gt 0
            } |
            Sort-Object -Unique
    )
}
else {
    $owners = @(
        Get-ListeningPortOwners -Port $runtimePort
    )
}

$preflight = [ordered]@{
    root                       = $runtimeRoot
    config                     = $configPath
    java                       = $javaResolved
    jarExists                  = $jarExists
    replacerExists             = $replacerExists
    bootstrapExists            = $bootstrapExists
    runtimePort                = $runtimePort
    portOwners                 = $owners
    controlTokenPresent        = $controlTokenPresent
    mode                       = $Mode
    sessionId                  = $currentSessionId
    trayRequested              = $trayRequested
    cloudflaredExecutable      = $cloudflaredExecutable
    cloudflaredUserProfile     = $cloudflaredUserProfile
    doctorSchema               = @(
        $doctor.PSObject.Properties.Name
    )
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
    throw "Runtime port $runtimePort is already owned by PID(s): $($owners -join ', '). Refusing cutover without -Force."
}

if ($Mode -ne "Preflight") {
    $cloudflaredConfig = if ([System.IO.File]::Exists($cloudflaredConfigPath)) {
        [System.IO.File]::ReadAllText($cloudflaredConfigPath)
    } else {
        ""
    }
    $cloudflaredEnabled = $cloudflaredConfig -match '(?m)^\s*enabled\s*:\s*true\s*$'
    if ($cloudflaredEnabled -and [string]::IsNullOrWhiteSpace($cloudflaredExecutable)) {
        throw "Cloudflared is enabled, but cloudflared.exe was not found. Install it or set SPRING_SUITE_CLOUDFLARED_EXECUTABLE."
    }
    if (-not [string]::IsNullOrWhiteSpace($cloudflaredExecutable) -and $PSCmdlet.ShouldProcess($cloudflaredConfigPath, "Persist cloudflared executable and user profile for detached/service startup")) {
        Set-CloudflaredRuntimeConfig -Path $cloudflaredConfigPath -Executable $cloudflaredExecutable -UserProfile $cloudflaredUserProfile
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
        if (-not (Test-IsAdministrator)) {
            throw "Service mode requires an elevated PowerShell session."
        }
        if (-not [string]::IsNullOrWhiteSpace($Root)) {
            $requestedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\\')
            if ([System.IO.Path]::GetFullPath($runtimeRoot).TrimEnd('\\') -ne $requestedRoot) {
                throw "Resolved runtime root does not match the requested production root."
            }
        }
        $runtimeConfig = [System.IO.File]::ReadAllText($configPath) | ConvertFrom-Json
        $runtimePortForService = [int]$runtimeConfig.runtime_port
        $machineCloudflared = Initialize-MachineCloudflaredConfig `
            -DataRoot $MachineDataRoot `
            -TunnelId $TunnelId `
            -Hostname $TunnelHostname `
            -RuntimePort $runtimePortForService
        Set-CloudflaredServicePaths `
            -Path $cloudflaredConfigPath `
            -Executable $cloudflaredExecutable `
            -MachineConfig $machineCloudflared `
            -TunnelId $TunnelId `
            -Hostname $TunnelHostname

        $repositoryPaths = @()
        if ($null -ne $runtimeConfig.maintenance -and $null -ne $runtimeConfig.maintenance.source_repositories) {
            $repositoryPaths = @($runtimeConfig.maintenance.source_repositories | ForEach-Object { [string]$_.path })
        }

        $serviceHostRoot = Join-Path $MachineDataRoot "service"
        $machineRuntimeRoot = Join-Path $MachineDataRoot "runtime"
        $machineProfileRoot = Join-Path $MachineDataRoot "profile"
        $serviceHost = Join-Path $serviceHostRoot "NorthStarServiceHost.exe"
        $serviceConfig = Join-Path $serviceHostRoot "runtime-controller.json"
        foreach ($directory in @($serviceHostRoot, $machineRuntimeRoot, $machineProfileRoot, [string]($machineCloudflared.Root))) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        if (-not (Test-Path -LiteralPath $serviceHostSourcePath -PathType Leaf)) {
            throw "External service host source is missing: $serviceHostSourcePath"
        }

        # The external host survives target replacement. Its config always points
        # to the absolute production runtime root rather than to ProgramData.
        $runtimeConfig.runtime_root = $runtimeRoot
        if ($null -eq $runtimeConfig.cloudflared) {
            throw "runtime-controller.json is missing the controller-owned cloudflared block."
        }
        $runtimeConfig.cloudflared.enabled = $true
        $runtimeConfig.cloudflared.required = $true
        $runtimeConfig.cloudflared.wrapper_path = (Join-Path $runtimeRoot "suiteBinaries\suite-cloudflared-wrapper.exe")
        $runtimeConfig.cloudflared.executable = $cloudflaredExecutable
        $runtimeConfig.cloudflared.mode = "run"
        $runtimeConfig.cloudflared.target_url = "http://127.0.0.1:$runtimePortForService"
        $runtimeConfig.cloudflared.tunnel = $TunnelId
        $runtimeConfig.cloudflared.config_path = [string]($machineCloudflared.Config)
        $runtimeConfig.cloudflared.credentials_file = [string]($machineCloudflared.Credentials)
        $runtimeConfig.cloudflared.runtime_dir = (Join-Path $runtimeRoot ".springsuite\cloudflared")
        if ($null -eq $runtimeConfig.application_args) {
            $runtimeConfig | Add-Member -MemberType NoteProperty -Name application_args -Value @()
        }
        if (-not (@($runtimeConfig.application_args) -contains "--suite.cloudflared.auto-start=false")) {
            $runtimeConfig.application_args = @($runtimeConfig.application_args) + "--suite.cloudflared.auto-start=false"
        }
        if ($null -eq $runtimeConfig.environment) {
            $runtimeConfig | Add-Member -MemberType NoteProperty -Name environment -Value ([pscustomobject]@{})
        }
        $runtimeConfig.environment | Add-Member -MemberType NoteProperty -Name SPRING_SUITE_SUPERVISED -Value "true" -Force
        $runtimeConfig.environment | Add-Member -MemberType NoteProperty -Name SPRINGSUITE_SERVICE_MODE -Value "true" -Force
        $runtimeConfig.environment | Add-Member -MemberType NoteProperty -Name USERPROFILE -Value $machineProfileRoot -Force
        $runtimeConfig.environment | Add-Member -MemberType NoteProperty -Name HOME -Value $machineProfileRoot -Force
        $runtimeConfig.environment | Add-Member -MemberType NoteProperty -Name TUNNEL_ORIGIN_CERT -Value ([string]($machineCloudflared.OriginCert)) -Force
        $serviceConfigJson = $runtimeConfig | ConvertTo-Json -Depth 30

        if (-not [string]::IsNullOrWhiteSpace($LegacyServiceName) -and $LegacyServiceName -ne $ServiceName) {
            if ($PSCmdlet.ShouldProcess($LegacyServiceName, "Remove legacy competing Windows service")) {
                Stop-And-RemoveServiceIfPresent -Name $LegacyServiceName
            }
        }
        if ($PSCmdlet.ShouldProcess($ServiceName, "Reinstall external NorthStar service host")) {
            Stop-And-RemoveServiceIfPresent -Name $ServiceName
            if (Test-Path -LiteralPath $serviceHost -PathType Leaf) {
                Copy-Item -LiteralPath $serviceHost -Destination ($serviceHost + ".previous") -Force
            }
            Copy-Item -LiteralPath $serviceHostSourcePath -Destination $serviceHost -Force
            [System.IO.File]::WriteAllText($serviceConfig, $serviceConfigJson + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
            & $serviceHost service-install --config $serviceConfig --service-name $ServiceName --service-display $ServiceDisplayName
            if ($LASTEXITCODE -ne 0) { throw "Service installation failed: $LASTEXITCODE" }
            # Never recurse over the whole MachineDataRoot. Windows components may
            # create protected caches under a service profile; traversing those paths
            # made installation fail after the service itself had already been created.
            Grant-ServiceRuntimeAccess `
                -Name $ServiceName `
                -Paths @(
                    $runtimeRoot,
                    $serviceHostRoot,
                    $machineRuntimeRoot,
                    $machineProfileRoot,
                    [string]($machineCloudflared.Root)
                )
        }
        if ($Start -and $PSCmdlet.ShouldProcess($ServiceName, "Start external NorthStar service host")) {
            & $serviceHost service-start --config $serviceConfig --service-name $ServiceName
            if ($LASTEXITCODE -ne 0) { throw "Service start failed: $LASTEXITCODE" }
        }
    }
}
