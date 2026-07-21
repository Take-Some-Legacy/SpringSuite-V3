param(
    [Parameter(Mandatory = $true)]
    [string]$Root,
    [int]$Port = 8090
)

$ErrorActionPreference = 'SilentlyContinue'

try {
    $health = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$Port/actuator/health" `
        -TimeoutSec 3
    if ($health.status -eq 'UP') {
        Write-Host "[SpringSuite] already running and healthy on port $Port."
        exit 10
    }
} catch {}

$listener = Get-NetTCPConnection `
    -LocalPort $Port `
    -State Listen `
    -ErrorAction SilentlyContinue |
    Select-Object -First 1

if ($null -ne $listener) {
    $owner = Get-CimInstance Win32_Process `
        -Filter "ProcessId = $($listener.OwningProcess)" `
        -ErrorAction SilentlyContinue
    $name = if ($null -eq $owner) { 'unknown' } else { $owner.Name }
    Write-Error "[SpringSuite] port $Port is occupied by PID $($listener.OwningProcess) ($name). Refusing a second launch."
    exit 11
}

exit 0