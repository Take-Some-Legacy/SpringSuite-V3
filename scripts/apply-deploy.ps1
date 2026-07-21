[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Target,

    [Parameter(Mandatory = $true)]
    [string]$Payload,

    [int]$OldProcessId = 0,

    [switch]$NoStart,

    [int]$Port = 8090,

    [string]$LogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$targetRoot = [System.IO.Path]::GetFullPath($Target)
$payloadRoot = [System.IO.Path]::GetFullPath($Payload)

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path $targetRoot ".springsuite\deploy\apply-deploy.log"
}

$logDirectory = Split-Path -Parent $LogPath
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function Write-DeployLog {
    param([string]$Message)

    $line = "[{0}] {1}" -f (Get-Date -Format "o"), $Message
    $line | Tee-Object -FilePath $LogPath -Append
}

function Copy-Payload {
    param(
        [string]$SourceRoot,
        [string]$DestinationRoot,
        [string]$BackupRoot
    )

    $sourcePrefix = $SourceRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar

    Get-ChildItem -LiteralPath $SourceRoot -Recurse -Force -File | ForEach-Object {
        $relativePath = $_.FullName.Substring($sourcePrefix.Length)
        $destinationPath = Join-Path $DestinationRoot $relativePath
        $destinationDirectory = Split-Path -Parent $destinationPath

        if (Test-Path -LiteralPath $destinationPath -PathType Leaf) {
            $backupPath = Join-Path $BackupRoot $relativePath
            $backupDirectory = Split-Path -Parent $backupPath
            New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
            Copy-Item -LiteralPath $destinationPath -Destination $backupPath -Force
        }

        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destinationPath -Force
        Write-DeployLog "installed $relativePath"
    }
}

try {
    if (-not (Test-Path -LiteralPath $payloadRoot -PathType Container)) {
        throw "Deployment payload does not exist: $payloadRoot"
    }

    New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null

    if ($OldProcessId -gt 0) {
        Write-DeployLog "waiting for SpringSuite PID $OldProcessId"
        Wait-Process -Id $OldProcessId -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 750
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $backupRoot = Join-Path $targetRoot ".springsuite\deploy-backups\$timestamp"
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

    Write-DeployLog "applying payload from $payloadRoot"
    Copy-Payload -SourceRoot $payloadRoot -DestinationRoot $targetRoot -BackupRoot $backupRoot

    if (-not $NoStart) {
        $launcher = Join-Path $targetRoot "run.bat"
        if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
            throw "Runtime launcher is missing after deployment: $launcher"
        }

        Write-DeployLog "starting SpringSuite through run.bat"
        Start-Process -FilePath $launcher -WorkingDirectory $targetRoot

        $healthUri = "http://127.0.0.1:$Port/actuator/health"
        $deadline = (Get-Date).AddSeconds(90)
        $ready = $false

        while ((Get-Date) -lt $deadline) {
            try {
                $health = Invoke-RestMethod -Method Get -Uri $healthUri -TimeoutSec 2
                if ($health.status -eq "UP") {
                    $ready = $true
                    break
                }
            } catch {
                Start-Sleep -Seconds 1
            }
        }

        if (-not $ready) {
            throw "SpringSuite did not become healthy within 90 seconds: $healthUri"
        }

        Write-DeployLog "SpringSuite health is UP"
    } else {
        Write-DeployLog "deployment applied without starting SpringSuite"
    }

    $payloadParent = Split-Path -Parent $payloadRoot
    if (Test-Path -LiteralPath $payloadRoot) {
        Remove-Item -LiteralPath $payloadRoot -Recurse -Force
        Write-DeployLog "removed staged payload"
    }

    $result = [ordered]@{
        ok = $true
        target = $targetRoot
        backup = $backupRoot
        started = -not $NoStart
        port = $Port
        completedAt = (Get-Date).ToString("o")
    }

    $result | ConvertTo-Json -Depth 4
    exit 0
} catch {
    Write-DeployLog "ERROR: $($_.Exception.Message)"
    Write-DeployLog $_.ScriptStackTrace
    exit 1
}
