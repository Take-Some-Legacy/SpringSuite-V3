[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [int]$Port = 8090,

    [int]$TakeoverPid = 0,

    [int]$StartupTimeoutSeconds = 90,

    [int]$StabilizationSeconds = 20,

    [int]$ShutdownTimeoutSeconds = 30,

    [int]$PortReleaseTimeoutSeconds = 30,

    [switch]$Console,

    [switch]$Elevated,

    [switch]$StopAfterApply,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ApplicationArgs = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$stateRoot = Join-Path $rootPath ".springsuite\supervisor"
$stateFile = Join-Path $stateRoot "state.json"
$pendingFile = Join-Path $stateRoot "pending-deployment.json"
$lockPath = Join-Path $stateRoot "supervisor.lock"
$supervisorLog = Join-Path $stateRoot "supervisor.log"
$recoveryRoot = Join-Path $rootPath ".springsuite\recovery"
$knownGoodRoot = Join-Path $recoveryRoot "known-good"
$knownGoodPointer = Join-Path $recoveryRoot "current.json"
$incidentsRoot = Join-Path $rootPath ".springsuite\incidents"
$currentIncident = Join-Path $incidentsRoot "current.json"
$applyScript = Join-Path $scriptDirectory "apply-deploy.ps1"
$toastScript = Join-Path $scriptDirectory "suite-toast.ps1"
$restartExitCode = 42
$lockStream = $null
$child = $null
$currentDeploymentId = ""
$lastBackup = ""
$crashTimes = New-Object System.Collections.Generic.List[datetime]
$activeStartupTimeoutSeconds = $StartupTimeoutSeconds
$activeStabilizationSeconds = $StabilizationSeconds
$activeShutdownTimeoutSeconds = $ShutdownTimeoutSeconds
$activePortReleaseTimeoutSeconds = $PortReleaseTimeoutSeconds
$trackedRuntimeProcesses = @{}
$runtimeJobHandle = [IntPtr]::Zero
$blockedPortIncidentKey = ""

function Disable-HandleInheritance {
    param([System.IO.FileStream]$Stream)

    if ($env:OS -ne "Windows_NT" -or $null -eq $Stream) {
        return
    }
    if ($null -eq ("SpringSuite.NativeHandleMethods" -as [type])) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace SpringSuite {
    public static class NativeHandleMethods {
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
    }
}
"@
    }
    $handleFlagInherit = [uint32]1
    $handle = $Stream.SafeFileHandle.DangerousGetHandle()
    if (-not [SpringSuite.NativeHandleMethods]::SetHandleInformation($handle, $handleFlagInherit, [uint32]0)) {
        $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "Could not mark supervisor lock as non-inheritable. Win32 error: $errorCode"
    }
}

function Initialize-RuntimeJobSupport {
    if ($env:OS -ne "Windows_NT" -or $null -ne ("SpringSuite.RuntimeJob" -as [type])) {
        return
    }

    Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace SpringSuite {
    public static class RuntimeJob {
        private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
        private const int JobObjectExtendedLimitInformation = 9;

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize;
            public UIntPtr MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IO_COUNTERS {
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit;
            public UIntPtr JobMemoryLimit;
            public UIntPtr PeakProcessMemoryUsed;
            public UIntPtr PeakJobMemoryUsed;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateJobObject(IntPtr securityAttributes, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetInformationJobObject(
            IntPtr job,
            int informationClass,
            ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION information,
            uint informationLength);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool CloseHandle(IntPtr handle);

        public static IntPtr CreateKillOnCloseJob() {
            IntPtr job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateJobObject failed");
            }

            JOBOBJECT_EXTENDED_LIMIT_INFORMATION information = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            information.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            uint length = (uint)Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, ref information, length)) {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(job);
                throw new Win32Exception(error, "SetInformationJobObject failed");
            }
            return job;
        }

        public static void Assign(IntPtr job, Process process) {
            if (job == IntPtr.Zero) {
                throw new ArgumentException("Job handle is zero", "job");
            }
            if (process == null) {
                throw new ArgumentNullException("process");
            }
            if (!AssignProcessToJobObject(job, process.Handle)) {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "AssignProcessToJobObject failed");
            }
        }
    }
}
"@
}

function Close-RuntimeJob {
    if ($env:OS -ne "Windows_NT" -or $script:runtimeJobHandle -eq [IntPtr]::Zero) {
        return
    }
    $handle = $script:runtimeJobHandle
    $script:runtimeJobHandle = [IntPtr]::Zero
    if (-not [SpringSuite.RuntimeJob]::CloseHandle($handle)) {
        $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        Write-SupervisorLog "closing runtime Job Object failed with Win32 error $errorCode"
    } else {
        Write-SupervisorLog "runtime Job Object closed; kill-on-close applied"
    }
}

function New-RuntimeJobForProcess {
    param([System.Diagnostics.Process]$Process)

    if ($env:OS -ne "Windows_NT") {
        return
    }
    Initialize-RuntimeJobSupport
    Close-RuntimeJob
    $handle = [SpringSuite.RuntimeJob]::CreateKillOnCloseJob()
    try {
        [SpringSuite.RuntimeJob]::Assign($handle, $Process)
        $script:runtimeJobHandle = $handle
        Write-SupervisorLog "assigned JVM PID $($Process.Id) to kill-on-close Job Object"
    } catch {
        [SpringSuite.RuntimeJob]::CloseHandle($handle) | Out-Null
        throw "Could not place JVM PID $($Process.Id) in the runtime Job Object: $($_.Exception.Message)"
    }
}

function Get-ProcessInventory {
    if ($env:OS -ne "Windows_NT") {
        return @()
    }
    try {
        return @(Get-CimInstance Win32_Process -OperationTimeoutSec 5 -ErrorAction Stop | ForEach-Object {
            $createdTicks = [long]0
            try {
                if ($null -ne $_.CreationDate) {
                    $createdTicks = ([datetime]$_.CreationDate).ToUniversalTime().Ticks
                }
            } catch {}
            [pscustomobject]@{
                pid = [int]$_.ProcessId
                parentPid = [int]$_.ParentProcessId
                name = [string]$_.Name
                commandLine = [string]$_.CommandLine
                createdTicks = $createdTicks
            }
        })
    } catch {
        Write-SupervisorLog "process inventory failed: $($_.Exception.Message)"
        return @()
    }
}

function Reset-TrackedRuntimeProcesses {
    $script:trackedRuntimeProcesses = @{}
}

function Register-TrackedProcess {
    param(
        [int]$ProcessId,
        [long]$CreatedTicks = 0
    )
    if ($ProcessId -le 0 -or $ProcessId -eq $PID) {
        return
    }
    if (-not $script:trackedRuntimeProcesses.ContainsKey($ProcessId)) {
        $script:trackedRuntimeProcesses[$ProcessId] = $CreatedTicks
        Write-SupervisorLog "tracking runtime process PID $ProcessId"
    } elseif ([long]$script:trackedRuntimeProcesses[$ProcessId] -eq 0 -and $CreatedTicks -ne 0) {
        $script:trackedRuntimeProcesses[$ProcessId] = $CreatedTicks
    }
}

function Test-TrackedProcessRecord {
    param([object]$Record)
    if ($null -eq $Record -or -not $script:trackedRuntimeProcesses.ContainsKey([int]$Record.pid)) {
        return $false
    }
    $expected = [long]$script:trackedRuntimeProcesses[[int]$Record.pid]
    return $expected -eq 0 -or [long]$Record.createdTicks -eq 0 -or $expected -eq [long]$Record.createdTicks
}

function Update-TrackedRuntimeTree {
    param([int]$RootPid)

    if ($env:OS -ne "Windows_NT") {
        if ($RootPid -gt 0) {
            Register-TrackedProcess -ProcessId $RootPid
        }
        return
    }

    $inventory = @(Get-ProcessInventory)
    if ($inventory.Count -eq 0) {
        return
    }
    $byPid = @{}
    $children = @{}
    foreach ($record in $inventory) {
        $byPid[[int]$record.pid] = $record
        $parent = [int]$record.parentPid
        if (-not $children.ContainsKey($parent)) {
            $children[$parent] = New-Object System.Collections.ArrayList
        }
        [void]$children[$parent].Add($record)
    }

    $queue = New-Object System.Collections.Queue
    $seen = @{}
    if ($RootPid -gt 0 -and $byPid.ContainsKey($RootPid)) {
        $rootRecord = $byPid[$RootPid]
        Register-TrackedProcess -ProcessId $RootPid -CreatedTicks ([long]$rootRecord.createdTicks)
        $queue.Enqueue($RootPid)
    }
    foreach ($trackedPid in @($script:trackedRuntimeProcesses.Keys)) {
        $trackedId = [int]$trackedPid
        if ($byPid.ContainsKey($trackedId) -and (Test-TrackedProcessRecord -Record $byPid[$trackedId])) {
            $queue.Enqueue($trackedId)
        }
    }

    while ($queue.Count -gt 0) {
        $parentPid = [int]$queue.Dequeue()
        if ($seen.ContainsKey($parentPid)) {
            continue
        }
        $seen[$parentPid] = $true
        if (-not $children.ContainsKey($parentPid)) {
            continue
        }
        foreach ($childRecord in @($children[$parentPid])) {
            Register-TrackedProcess -ProcessId ([int]$childRecord.pid) -CreatedTicks ([long]$childRecord.createdTicks)
            $queue.Enqueue([int]$childRecord.pid)
        }
    }
}

function Get-LiveTrackedProcessIds {
    $live = New-Object System.Collections.Generic.List[int]
    if ($env:OS -ne "Windows_NT") {
        foreach ($trackedPid in @($script:trackedRuntimeProcesses.Keys)) {
            if ($null -ne (Get-Process -Id ([int]$trackedPid) -ErrorAction SilentlyContinue)) {
                $live.Add([int]$trackedPid)
            }
        }
        return @($live)
    }

    $inventory = @(Get-ProcessInventory)
    $byPid = @{}
    foreach ($record in $inventory) {
        $byPid[[int]$record.pid] = $record
    }
    foreach ($trackedPid in @($script:trackedRuntimeProcesses.Keys)) {
        $trackedId = [int]$trackedPid
        if ($byPid.ContainsKey($trackedId) -and (Test-TrackedProcessRecord -Record $byPid[$trackedId])) {
            $live.Add($trackedId)
        }
    }
    return @($live)
}

function Stop-TrackedRuntimeProcesses {
    param([int]$RootPid = 0)

    if ($RootPid -gt 0) {
        Update-TrackedRuntimeTree -RootPid $RootPid
    }
    Close-RuntimeJob

    $deadline = (Get-Date).AddSeconds([Math]::Max(5, $activeShutdownTimeoutSeconds))
    while ((Get-Date) -lt $deadline) {
        $live = @(Get-LiveTrackedProcessIds)
        if ($live.Count -eq 0) {
            return
        }
        foreach ($processId in $live) {
            if ($processId -eq $PID) {
                continue
            }
            try {
                if ($env:OS -eq "Windows_NT") {
                    & taskkill.exe /PID $processId /T /F 2>$null | Out-Null
                } else {
                    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
                }
                Write-SupervisorLog "forced tracked runtime process PID $processId to stop"
            } catch {
                Write-SupervisorLog "could not stop tracked PID ${processId}: $($_.Exception.Message)"
            }
        }
        Start-Sleep -Milliseconds 300
    }

    $remaining = @(Get-LiveTrackedProcessIds)
    if ($remaining.Count -gt 0) {
        throw "Tracked runtime processes did not terminate: $($remaining -join ', ')"
    }
}

function Invoke-BoundedNetstat {
    if ($env:OS -ne "Windows_NT") {
        return ""
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Join-Path $env:SystemRoot 'System32\netstat.exe')
    $startInfo.Arguments = '-ano -p tcp'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "netstat could not be started"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(5000)) {
        try { $process.Kill() } catch {}
        throw "netstat timed out"
    }
    $stdoutTask.Wait(1000) | Out-Null
    $stderrTask.Wait(1000) | Out-Null
    return [string]$stdoutTask.Result
}

function Get-PortListenerProcessIds {
    $owners = New-Object System.Collections.Generic.HashSet[int]
    if ($env:OS -ne "Windows_NT") {
        return @()
    }
    try {
        $netstat = Invoke-BoundedNetstat
        foreach ($line in ($netstat -split "`r?`n")) {
            if ($line -notmatch '^\s*TCP\s+(\S+)\s+(\S+)\s+LISTENING\s+(\d+)\s*$') {
                continue
            }
            $localEndpoint = [string]$matches[1]
            $listenerPid = [int]$matches[3]
            $listenerPort = 0
            if ($localEndpoint -match '\]:(\d+)$' -or $localEndpoint -match ':(\d+)$') {
                $listenerPort = [int]$matches[1]
            }
            if ($listenerPort -eq $Port) {
                [void]$owners.Add($listenerPid)
            }
        }
    } catch {
        throw "Could not determine listener ownership for port ${Port}: $($_.Exception.Message)"
    }
    return @($owners)
}

function Get-PortOwnerDescription {
    param([int[]]$OwnerPids)

    $inventory = @(Get-ProcessInventory)
    $byPid = @{}
    foreach ($record in $inventory) {
        $byPid[[int]$record.pid] = $record
    }
    $parts = @()
    foreach ($ownerPid in @($OwnerPids)) {
        if ($byPid.ContainsKey([int]$ownerPid)) {
            $record = $byPid[[int]$ownerPid]
            $command = [string]$record.commandLine
            if ($command.Length -gt 300) {
                $command = $command.Substring(0, 300) + '...'
            }
            $parts += "PID $ownerPid ($($record.name)): $command"
        } else {
            $parts += "PID $ownerPid (details unavailable)"
        }
    }
    return ($parts -join '; ')
}

function Test-PortOwnerIsTracked {
    param([int]$OwnerPid)

    if (-not $script:trackedRuntimeProcesses.ContainsKey($OwnerPid)) {
        return $false
    }
    $inventory = @(Get-ProcessInventory | Where-Object { [int]$_.pid -eq $OwnerPid })
    return $inventory.Count -eq 1 -and (Test-TrackedProcessRecord -Record $inventory[0])
}

function Wait-ForPortFreeStable {
    param(
        [string]$Phase,
        [switch]$AllowTrackedReap
    )

    $stableSamples = 0
    $deadline = (Get-Date).AddSeconds([Math]::Max(5, $activePortReleaseTimeoutSeconds))
    while ($true) {
        $owners = @(Get-PortListenerProcessIds)
        if ($owners.Count -eq 0) {
            $stableSamples++
            if ($stableSamples -ge 3) {
                $script:blockedPortIncidentKey = ""
                Write-SupervisorLog "port $Port is free for three consecutive checks during $Phase"
                return
            }
            Start-Sleep -Milliseconds 500
            continue
        }

        $stableSamples = 0
        $trackedOwners = @($owners | Where-Object { Test-PortOwnerIsTracked -OwnerPid ([int]$_) })
        $unknownOwners = @($owners | Where-Object { $trackedOwners -notcontains [int]$_ })
        if ($unknownOwners.Count -eq 0 -and $AllowTrackedReap) {
            Write-SupervisorLog "port $Port is still held by tracked PID(s) $($trackedOwners -join ', ') during $Phase; reaping tracked runtime tree"
            Stop-TrackedRuntimeProcesses
            $deadline = (Get-Date).AddSeconds([Math]::Max(5, $PortReleaseTimeoutSeconds))
            Start-Sleep -Milliseconds 500
            continue
        }

        $description = Get-PortOwnerDescription -OwnerPids $unknownOwners
        $incidentKey = "$Phase|$($unknownOwners -join ',')"
        if ($script:blockedPortIncidentKey -ne $incidentKey) {
            $script:blockedPortIncidentKey = $incidentKey
            $message = "Port $Port is owned by an unexpected process during ${Phase}: $description"
            Write-SupervisorLog "BLOCKED_PORT: $message"
            Set-SupervisorState -Status "blocked-port" -DeploymentId $currentDeploymentId -ErrorMessage $message
            Write-IncidentReport -Phase $Phase -Severity "critical" -Message $message -DeploymentId $currentDeploymentId -RecoveryAction "wait-for-operator-or-port-release" | Out-Null
            Send-Toast -EventId "blocked-port" -Title "SpringSuite update blocked" -Message "Port $Port is owned by an unexpected process. No files will be changed until it is released." -Level "Error"
        }

        if ((Get-Date) -ge $deadline) {
            Write-SupervisorLog "port $Port remains blocked during $Phase; continuing fail-closed wait without modifying files"
            $deadline = (Get-Date).AddSeconds([Math]::Max(5, $activePortReleaseTimeoutSeconds))
        }
        Start-Sleep -Seconds 2
    }
}

function Complete-RuntimeTermination {
    param([int]$RootPid = 0)

    Stop-TrackedRuntimeProcesses -RootPid $RootPid
    Wait-ForPortFreeStable -Phase "runtime-termination" -AllowTrackedReap
    Reset-TrackedRuntimeProcesses
}

function Wait-ForRuntimeExit {
    param([System.Diagnostics.Process]$Process)

    while (-not $Process.HasExited) {
        Update-TrackedRuntimeTree -RootPid $Process.Id
        Start-Sleep -Milliseconds 500
        try { $Process.Refresh() } catch {}
    }
    Update-TrackedRuntimeTree -RootPid $Process.Id
}

function Write-SupervisorLog {
    param([string]$Message)

    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    $line = "[{0}] {1}" -f (Get-Date -Format "o"), $Message
    Add-Content -LiteralPath $supervisorLog -Value $line -Encoding UTF8
    if ($Console) {
        Write-Host "[supervisor] $Message"
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
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
    $replaceBackup = "$Path.replace-backup-$([Guid]::NewGuid().ToString('N'))"
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

function Set-SupervisorState {
    param(
        [string]$Status,
        [int]$ChildPid = 0,
        [string]$DeploymentId = "",
        [string]$ErrorMessage = ""
    )

    $state = [ordered]@{
        schema = "spring-suite.supervisor-state.v2"
        supervisorPid = $PID
        childPid = $ChildPid
        status = $Status
        deploymentId = $DeploymentId
        root = $rootPath
        port = $Port
        console = [bool]$Console
        elevated = [bool]$Elevated
        script = [System.IO.Path]::GetFullPath($MyInvocation.ScriptName)
        error = $ErrorMessage
        updatedAt = (Get-Date).ToString("o")
    }
    Write-JsonAtomic -Path $stateFile -Value $state
}

function Send-Toast {
    param(
        [string]$EventId,
        [string]$Title,
        [string]$Message,
        [ValidateSet("Info", "Success", "Warning", "Error", "Recovery")]
        [string]$Level = "Info"
    )

    if (-not (Test-Path -LiteralPath $toastScript -PathType Leaf)) {
        Write-SupervisorLog "toast script missing: $toastScript"
        return
    }

    try {
        $toastArguments = @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-WindowStyle', 'Hidden',
            '-File', $toastScript,
            '-Title', $Title,
            '-Message', $Message,
            '-Level', $Level,
            '-Root', $rootPath,
            '-EventId', $EventId
        )
        $argumentLine = ($toastArguments | ForEach-Object {
            '"' + ([string]$_).Replace('"', "'") + '"'
        }) -join ' '
        Start-Process -FilePath 'powershell.exe' -ArgumentList $argumentLine -WindowStyle Hidden | Out-Null
    } catch {
        Write-SupervisorLog "toast dispatch failed for ${EventId}: $($_.Exception.Message)"
    }
}

function Get-SafeFileName {
    param([string]$Value)
    $normalized = if ([string]::IsNullOrWhiteSpace($Value)) { "runtime" } else { $Value.Trim() }
    return ($normalized -replace '[^A-Za-z0-9_.-]', '_')
}

function Get-FileTail {
    param(
        [string]$Path,
        [int]$Lines = 0,
        [int]$MaxBytes = 0
    )
    if (-not [System.IO.File]::Exists($Path)) {
        return @()
    }

    $stream = $null
    try {
        $stream = New-Object System.IO.FileStream(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite
        )
        $requestedLength = if ($MaxBytes -le 0) { $stream.Length } else { [long][Math]::Max(1024, $MaxBytes) }
        $readLength = [int][Math]::Min([int]::MaxValue, [Math]::Min($requestedLength, $stream.Length))
        if ($readLength -le 0) {
            return @()
        }
        [void]$stream.Seek(-$readLength, [System.IO.SeekOrigin]::End)
        $buffer = New-Object byte[] $readLength
        $offset = 0
        while ($offset -lt $readLength) {
            $count = $stream.Read($buffer, $offset, $readLength - $offset)
            if ($count -le 0) { break }
            $offset += $count
        }
        $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $offset)
        $allLines = @($text -split "`r?`n")
        if ($Lines -le 0 -or $allLines.Count -le $Lines) {
            return $allLines
        }
        return @($allLines | Select-Object -Last $Lines)
    } catch {
        return @("Could not read log tail: " + $_.Exception.Message)
    } finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Write-IncidentReport {
    param(
        [string]$Phase,
        [string]$Severity,
        [string]$Message,
        [int]$ExitCode = 0,
        [string]$DeploymentId = "",
        [string]$RecoveryAction = "",
        [string]$StackTrace = ""
    )

    [System.IO.Directory]::CreateDirectory($incidentsRoot) | Out-Null
    $incidentId = (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $incidentPath = Join-Path $incidentsRoot ($incidentId + ".json")
    $runtimeLog = Join-Path $rootPath "logs\\spring-suite.log"
    $crashFiles = @()
    $crashRoot = Join-Path $rootPath "logs\crash"
    if ([System.IO.Directory]::Exists($crashRoot)) {
        try {
            $crashFiles = @([System.IO.Directory]::GetFiles($crashRoot) | ForEach-Object {
                [pscustomobject]@{
                    path = $_
                    time = [System.IO.File]::GetLastWriteTimeUtc($_)
                }
            } | Sort-Object time -Descending | Select-Object -First 5 | ForEach-Object { $_.path })
        } catch {
            $crashFiles = @()
        }
    }

    $report = [ordered]@{
        schema = "spring-suite.incident.v1"
        incidentId = $incidentId
        occurredAt = (Get-Date).ToString("o")
        severity = $Severity
        phase = $Phase
        message = $Message
        stackTrace = $StackTrace
        exitCode = $ExitCode
        deploymentId = $DeploymentId
        supervisorPid = $PID
        childPid = if ($null -eq $child) { 0 } else { $child.Id }
        root = $rootPath
        port = $Port
        recoveryAction = $RecoveryAction
        supervisorLog = $supervisorLog
        runtimeLog = $runtimeLog
        runtimeLogTail = Get-FileTail -Path $runtimeLog -Lines 80
        supervisorLogTail = Get-FileTail -Path $supervisorLog -Lines 80
        crashFiles = $crashFiles
        ai = [ordered]@{
            command = "incident current"
            instruction = "Read this incident through NorthStar MCP, inspect the referenced logs and source repository, identify the root cause, prepare a minimal fix, run regression tests, and deploy through the supervised transaction pipeline."
            status = "ready-for-analysis"
        }
    }
    Write-JsonAtomic -Path $incidentPath -Value $report
    Write-JsonAtomic -Path $currentIncident -Value $report
    Write-SupervisorLog "incident $incidentId prepared for AI analysis: $Phase / $Severity"
    return $report
}

function Save-KnownGoodSnapshot {
    param([string]$DeploymentId)

    $jar = Join-Path $rootPath "spring-suite.jar"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        return $null
    }
    $safeId = Get-SafeFileName -Value $DeploymentId
    $snapshotRoot = Join-Path $knownGoodRoot $safeId
    New-Item -ItemType Directory -Path $snapshotRoot -Force | Out-Null
    $snapshotJar = Join-Path $snapshotRoot "spring-suite.jar"
    Copy-Item -LiteralPath $jar -Destination $snapshotJar -Force
    $hash = (Get-FileHash -LiteralPath $snapshotJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = [ordered]@{
        schema = "spring-suite.known-good.v1"
        deploymentId = $DeploymentId
        jar = $snapshotJar
        sha256 = $hash
        verifiedAt = (Get-Date).ToString("o")
        supervisorPid = $PID
    }
    Write-JsonAtomic -Path (Join-Path $snapshotRoot "known-good.json") -Value $metadata
    Write-JsonAtomic -Path $knownGoodPointer -Value $metadata
    Write-SupervisorLog "known-good snapshot saved for $DeploymentId ($hash)"
    return $metadata
}

function Get-KnownGoodCandidates {
    $candidates = @()
    if (Test-Path -LiteralPath $knownGoodPointer -PathType Leaf) {
        try {
            $candidates += Get-Content -LiteralPath $knownGoodPointer -Raw | ConvertFrom-Json
        } catch {}
    }
    if (Test-Path -LiteralPath $knownGoodRoot -PathType Container) {
        foreach ($metadataFile in @(Get-ChildItem -LiteralPath $knownGoodRoot -Filter "known-good.json" -Recurse -File | Sort-Object LastWriteTimeUtc -Descending)) {
            try {
                $candidate = Get-Content -LiteralPath $metadataFile.FullName -Raw | ConvertFrom-Json
                if (-not ($candidates | Where-Object { [string]$_.sha256 -eq [string]$candidate.sha256 })) {
                    $candidates += $candidate
                }
            } catch {}
        }
    }
    return @($candidates)
}

function Restore-KnownGoodJar {
    param([object]$Candidate)

    if ($null -eq $Candidate -or [string]::IsNullOrWhiteSpace([string]$Candidate.jar)) {
        return $false
    }
    $source = [string]$Candidate.jar
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        return $false
    }
    $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not [string]::IsNullOrWhiteSpace([string]$Candidate.sha256) -and $actualHash -ne ([string]$Candidate.sha256).ToLowerInvariant()) {
        Write-SupervisorLog "known-good candidate hash mismatch: $source"
        return $false
    }

    $destination = Join-Path $rootPath "spring-suite.jar"
    $temporary = Join-Path $rootPath (".spring-suite.jar.recovery-" + [Guid]::NewGuid().ToString("N"))
    Copy-Item -LiteralPath $source -Destination $temporary -Force
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $actualHash) {
        if ([System.IO.File]::Exists($temporary)) {
            [System.IO.File]::Delete($temporary)
        }
        return $false
    }
    if ([System.IO.File]::Exists($destination)) {
        [System.IO.File]::Delete($destination)
    }
    [System.IO.File]::Move($temporary, $destination)
    $script:currentDeploymentId = "recovered-" + [string]$Candidate.deploymentId
    Write-SupervisorLog "restored known-good JAR $($Candidate.deploymentId) ($actualHash)"
    return $true
}

function Try-SelfRecovery {
    param(
        [string]$Reason,
        [int]$ExitCode = 0,
        [string]$FailedDeploymentId = ""
    )

    $incident = $null
    if (Test-Path -LiteralPath $currentIncident -PathType Leaf) {
        try {
            $candidateIncident = Get-Content -LiteralPath $currentIncident -Raw | ConvertFrom-Json
            if ([string]$candidateIncident.message -eq $Reason -and [string]$candidateIncident.deploymentId -eq $FailedDeploymentId) {
                $incident = $candidateIncident
            }
        } catch {}
    }
    if ($null -eq $incident) {
        $incident = Write-IncidentReport `
            -Phase "self-recovery" `
            -Severity "error" `
            -Message $Reason `
            -ExitCode $ExitCode `
            -DeploymentId $FailedDeploymentId `
            -RecoveryAction "restore-known-good-and-restart"
    }
    Send-Toast -EventId "self-recovery-started" -Title "SpringSuite self-recovery" -Message "Restoring a verified known-good executable. Incident $($incident.incidentId) is ready for AI analysis." -Level "Recovery"

    foreach ($candidate in @(Get-KnownGoodCandidates)) {
        if (Restore-KnownGoodJar -Candidate $candidate) {
            Set-SupervisorState -Status "recovering-known-good" -DeploymentId $currentDeploymentId -ErrorMessage $Reason
            return $true
        }
    }
    Write-SupervisorLog "no valid known-good JAR was available"
    return $false
}

function Resolve-JavaExecutable {
    $name = if ($Console) { "java.exe" } else { "javaw.exe" }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME ("bin\" + $name)
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    if (-not $Console) {
        $fallback = Get-Command "java.exe" -ErrorAction SilentlyContinue
        if ($null -ne $fallback) {
            return $fallback.Source
        }
    }

    throw "Java executable was not found. Configure JAVA_HOME or add Java 17+ to PATH."
}

function Quote-ProcessArgument {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value -or $Value.Length -eq 0) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + $Value.Replace('\', '\').Replace('"', '\"') + '"'
}

function Get-DeploymentIdFromRuntime {
    if ([string]::IsNullOrWhiteSpace($currentDeploymentId)) {
        return "runtime"
    }
    return $currentDeploymentId
}

function Start-Runtime {
    $jar = Join-Path $rootPath "spring-suite.jar"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Executable JAR is missing: $jar"
    }

    $java = Resolve-JavaExecutable
    $arguments = @(
        "-XX:ErrorFile=$rootPath\logs\crash\hs_err_pid%p.log",
        "-XX:+ShowCodeDetailsInExceptionMessages",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dsuite.supervisor.pid=$PID",
        "-Dsuite.deployment.id=$(Get-DeploymentIdFromRuntime)",
        "-jar",
        $jar,
        "--suite-working-directory=$rootPath"
    )
    if ($Elevated) {
        $arguments += "--elevated"
    }
    if ($null -ne $ApplicationArgs -and $ApplicationArgs.Count -gt 0) {
        $arguments += $ApplicationArgs
    }

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java
    $startInfo.Arguments = (($arguments | ForEach-Object { Quote-ProcessArgument -Value ([string]$_) }) -join " ")
    $startInfo.WorkingDirectory = $rootPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = -not $Console
    $startInfo.EnvironmentVariables["SPRING_SUITE_SUPERVISED"] = "1"
    $startInfo.EnvironmentVariables["SPRING_SUITE_SUPERVISOR_PID"] = [string]$PID
    $startInfo.EnvironmentVariables["SPRING_SUITE_DEPLOYMENT_ID"] = (Get-DeploymentIdFromRuntime)

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $process.EnableRaisingEvents = $true
    Wait-ForPortFreeStable -Phase "before-runtime-start" -AllowTrackedReap
    if (-not $process.Start()) {
        throw "Java process could not be started."
    }

    try {
        Reset-TrackedRuntimeProcesses
        $startedTicks = [long]0
        try { $startedTicks = $process.StartTime.ToUniversalTime().Ticks } catch {}
        Register-TrackedProcess -ProcessId $process.Id -CreatedTicks $startedTicks
        New-RuntimeJobForProcess -Process $process
        Update-TrackedRuntimeTree -RootPid $process.Id
    } catch {
        try { & taskkill.exe /PID $process.Id /T /F 2>$null | Out-Null } catch {}
        throw
    }

    Write-SupervisorLog "started JVM PID $($process.Id) from $jar"
    Set-SupervisorState -Status "starting" -ChildPid $process.Id -DeploymentId $currentDeploymentId
    return $process
}

function Invoke-BoundedJsonGet {
    param(
        [string]$Uri,
        [int]$TimeoutMilliseconds = 1500
    )

    $request = [System.Net.HttpWebRequest]::Create($Uri)
    $request.Method = 'GET'
    $request.Timeout = [Math]::Max(250, $TimeoutMilliseconds)
    $request.ReadWriteTimeout = [Math]::Max(250, $TimeoutMilliseconds)
    $request.KeepAlive = $false
    $request.Proxy = $null
    $response = $null
    $reader = $null
    try {
        $response = $request.GetResponse()
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        return ($reader.ReadToEnd() | ConvertFrom-Json)
    } finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Abort()
    }
}

function Get-RuntimeStatus {
    try {
        $health = Invoke-BoundedJsonGet -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutMilliseconds 1500
        if ([string]$health.status -ne 'UP') {
            return $null
        }
        $response = Invoke-BoundedJsonGet -Uri "http://127.0.0.1:$Port/api/system/status" -TimeoutMilliseconds 1500
        $components = $response.data.components
        $launchRoot = [string]$components.launchDirectory
        if ([string]::IsNullOrWhiteSpace($launchRoot)) {
            $launchRoot = [string]$components.projectRoot
        }
        return [pscustomobject]@{
            status = [string]$response.data.status
            pid = [int]$components.pid
            launchRoot = $launchRoot
            deploymentId = [string]$components.deploymentId
            supervisorPid = [int]$components.supervisorPid
            health = [string]$health.status
        }
    } catch {
        return $null
    }
}

function Wait-ForHealthyRuntime {
    param([System.Diagnostics.Process]$Process)

    $deadline = (Get-Date).AddSeconds([Math]::Max(10, $activeStartupTimeoutSeconds + $activeStabilizationSeconds))
    $requiredSamples = [Math]::Max(3, [int][Math]::Ceiling($activeStabilizationSeconds / 0.75))
    $consecutiveSamples = 0
    while ((Get-Date) -lt $deadline) {
        Update-TrackedRuntimeTree -RootPid $Process.Id
        if ($Process.HasExited) {
            return $false
        }

        $status = Get-RuntimeStatus
        $portOwners = @(Get-PortListenerProcessIds)
        $expectedDeploymentId = Get-DeploymentIdFromRuntime
        if ($null -ne $status -and $status.pid -eq $Process.Id) {
            $activeRoot = [System.IO.Path]::GetFullPath([string]$status.launchRoot).TrimEnd('\', '/')
            if ($activeRoot -eq $rootPath -and
                $status.health -eq 'UP' -and
                ($status.status -eq "READY" -or $status.status -eq "UP") -and
                $status.deploymentId -eq $expectedDeploymentId -and
                $status.supervisorPid -eq $PID -and
                $portOwners.Count -eq 1 -and
                [int]$portOwners[0] -eq $Process.Id) {
                $consecutiveSamples++
                if ($consecutiveSamples -ge $requiredSamples) {
                    return $true
                }
            } else {
                $consecutiveSamples = 0
            }
        } else {
            $consecutiveSamples = 0
        }
        Start-Sleep -Milliseconds 750
    }
    return $false
}

function Stop-ChildProcess {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process) {
        return
    }
    try {
        Update-TrackedRuntimeTree -RootPid $Process.Id
        Complete-RuntimeTermination -RootPid $Process.Id
        try { $Process.WaitForExit(1000) | Out-Null } catch {}
    } catch {
        Write-SupervisorLog "could not stop child process tree PID $($Process.Id): $($_.Exception.Message)"
        throw
    }
}

function Read-PendingDeployment {
    if (-not (Test-Path -LiteralPath $pendingFile -PathType Leaf)) {
        return $null
    }
    return Get-Content -LiteralPath $pendingFile -Raw | ConvertFrom-Json
}

function Invoke-DeploymentApply {
    param([object]$Pending)

    if (-not (Test-Path -LiteralPath $applyScript -PathType Leaf)) {
        throw "Deployment transaction engine is missing: $applyScript"
    }

    $deploymentId = [string]$Pending.deploymentId
    $payload = [string]$Pending.payload
    if ($Pending.PSObject.Properties.Name -contains 'healthTimeoutSeconds') {
        $script:activeStartupTimeoutSeconds = [Math]::Max(10, [int]$Pending.healthTimeoutSeconds)
    }
    if ($Pending.PSObject.Properties.Name -contains 'stabilizationSeconds') {
        $script:activeStabilizationSeconds = [Math]::Max(3, [int]$Pending.stabilizationSeconds)
    }
    if ($Pending.PSObject.Properties.Name -contains 'shutdownTimeoutSeconds') {
        $script:activeShutdownTimeoutSeconds = [Math]::Max(5, [int]$Pending.shutdownTimeoutSeconds)
    }
    if ($Pending.PSObject.Properties.Name -contains 'portReleaseTimeoutSeconds') {
        $script:activePortReleaseTimeoutSeconds = [Math]::Max(5, [int]$Pending.portReleaseTimeoutSeconds)
    }
    $resultPath = Join-Path $stateRoot ("deployment-result-" + $deploymentId + ".json")

    Send-Toast -EventId "deploy-installing" -Title "SpringSuite update" -Message "Installing deployment $deploymentId. The service will return automatically." -Level "Info"
    Set-SupervisorState -Status "deploying" -DeploymentId $deploymentId
    Write-SupervisorLog "applying pending deployment $deploymentId from $payload"

    & $applyScript -Target $rootPath -Payload $payload -DeploymentId $deploymentId -ResultPath $resultPath | Out-Null
    $exitCode = $LASTEXITCODE
    Write-SupervisorLog "deployment transaction process exited with code $exitCode for $deploymentId"
    if ($exitCode -ne 0) {
        $errorText = if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
            [string](Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json).error
        } else {
            "transaction engine exited with code $exitCode"
        }
        throw "Deployment $deploymentId failed: $errorText"
    }

    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    Write-SupervisorLog "deployment result loaded for $deploymentId; ok=$([bool]$result.ok)"
    if (-not [bool]$result.ok) {
        throw "Deployment $deploymentId failed: $($result.error)"
    }

    if ([System.IO.File]::Exists($pendingFile)) {
        Write-SupervisorLog "deleting consumed pending deployment file for $deploymentId"
        [System.IO.File]::Delete($pendingFile)
        Write-SupervisorLog "pending deployment file deleted for $deploymentId"
    }
    $script:currentDeploymentId = $deploymentId
    $script:lastBackup = [string]$result.backup
    Write-SupervisorLog "deployment $deploymentId installed; backup=$($script:lastBackup); awaiting health verification"
    return $result
}

function Invoke-DeploymentRollback {
    param(
        [string]$Backup,
        [string]$FailedDeploymentId
    )

    if ([string]::IsNullOrWhiteSpace($Backup)) {
        throw "No deployment backup is available for rollback."
    }

    $resultPath = Join-Path $stateRoot ("rollback-result-" + $FailedDeploymentId + ".json")
    Send-Toast -EventId "deploy-rollback" -Title "SpringSuite rollback" -Message "The new runtime did not become healthy. Restoring the previous JAR." -Level "Warning"
    Set-SupervisorState -Status "rolling-back" -DeploymentId $FailedDeploymentId
    Write-SupervisorLog "rolling back failed deployment $FailedDeploymentId from $Backup"

    & $applyScript -Target $rootPath -RollbackBackup $Backup -ResultPath $resultPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Rollback transaction failed. Result: $resultPath"
    }

    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if (-not [bool]$result.ok) {
        throw "Rollback transaction failed: $($result.error)"
    }

    $script:currentDeploymentId = "rollback-" + $FailedDeploymentId
    $script:lastBackup = ""
    Write-SupervisorLog "rollback completed for $FailedDeploymentId"
}

function Wait-ForTakeoverProcess {
    if ($TakeoverPid -le 0) {
        return
    }

    $existing = Get-Process -Id $TakeoverPid -ErrorAction SilentlyContinue
    if ($null -eq $existing) {
        return
    }

    Reset-TrackedRuntimeProcesses
    $createdTicks = [long]0
    try { $createdTicks = $existing.StartTime.ToUniversalTime().Ticks } catch {}
    Register-TrackedProcess -ProcessId $TakeoverPid -CreatedTicks $createdTicks
    Write-SupervisorLog "waiting for legacy/runtime PID $TakeoverPid before taking ownership"
    Set-SupervisorState -Status "waiting-for-takeover" -ChildPid $TakeoverPid

    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Update-TrackedRuntimeTree -RootPid $TakeoverPid
        $existing = Get-Process -Id $TakeoverPid -ErrorAction SilentlyContinue
        if ($null -eq $existing -or $existing.HasExited) {
            Complete-RuntimeTermination -RootPid $TakeoverPid
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for PID $TakeoverPid to stop."
}

function Record-CrashAndCanRestart {
    $now = Get-Date
    for ($index = $crashTimes.Count - 1; $index -ge 0; $index--) {
        if ($crashTimes[$index] -lt $now.AddMinutes(-5)) {
            $crashTimes.RemoveAt($index)
        }
    }
    $crashTimes.Add($now)
    return $crashTimes.Count -le 3
}

try {
    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $rootPath "logs\crash") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $rootPath "data") -Force | Out-Null

    try {
        $lockStream = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        Disable-HandleInheritance -Stream $lockStream
    } catch [System.IO.IOException] {
        Write-SupervisorLog "another supervisor already owns $rootPath"
        exit 20
    }

    Write-SupervisorLog "supervisor PID $PID acquired runtime ownership"
    Set-SupervisorState -Status "initializing"
    Wait-ForTakeoverProcess
    Wait-ForPortFreeStable -Phase "supervisor-initialization" -AllowTrackedReap

    while ($true) {
        $pending = Read-PendingDeployment
        $appliedThisCycle = $false
        $appliedDeploymentId = ""
        if ($null -ne $pending) {
            Wait-ForPortFreeStable -Phase "before-deployment-apply" -AllowTrackedReap
            $applied = Invoke-DeploymentApply -Pending $pending
            $appliedThisCycle = $true
            $appliedDeploymentId = [string]$applied.deploymentId
            if ($StopAfterApply) {
                Send-Toast -EventId "deploy-applied-stopped" -Title "SpringSuite updated" -Message "Deployment $appliedDeploymentId was installed. The service remains stopped." -Level "Success"
                Set-SupervisorState -Status "stopped-after-deploy" -DeploymentId $appliedDeploymentId
                break
            }
        }

        $child = Start-Runtime
        if (-not (Wait-ForHealthyRuntime -Process $child)) {
            Stop-ChildProcess -Process $child
            if ($appliedThisCycle -and -not [string]::IsNullOrWhiteSpace($lastBackup)) {
                try {
                    $failureMessage = "Deployment $appliedDeploymentId did not become healthy within $activeStartupTimeoutSeconds seconds and did not pass the $activeStabilizationSeconds-second stability window."
                    Write-IncidentReport `
                        -Phase "deployment-health-check" `
                        -Severity "error" `
                        -Message $failureMessage `
                        -DeploymentId $appliedDeploymentId `
                        -RecoveryAction "transaction-rollback" | Out-Null
                    Invoke-DeploymentRollback -Backup $lastBackup -FailedDeploymentId $appliedDeploymentId
                    $child = Start-Runtime
                    if (-not (Wait-ForHealthyRuntime -Process $child)) {
                        Stop-ChildProcess -Process $child
                        throw "The restored runtime also failed its health check."
                    }
                    Set-SupervisorState -Status "ready-after-rollback" -ChildPid $child.Id -DeploymentId $currentDeploymentId
                    Save-KnownGoodSnapshot -DeploymentId (Get-DeploymentIdFromRuntime) | Out-Null
                    Send-Toast -EventId "deploy-rollback-complete" -Title "SpringSuite restored" -Message "The previous executable JAR is healthy again. An incident report is available to AI." -Level "Recovery"
                } catch {
                    Send-Toast -EventId "deploy-rollback-failed" -Title "SpringSuite recovery failed" -Message $_.Exception.Message -Level "Error"
                    throw
                }
            } else {
                Send-Toast -EventId "runtime-start-failed" -Title "SpringSuite failed to start" -Message "The JVM did not become healthy within $activeStartupTimeoutSeconds seconds and did not pass the $activeStabilizationSeconds-second stability window." -Level "Error"
                throw "SpringSuite did not become healthy within $StartupTimeoutSeconds seconds."
            }
        } else {
            Set-SupervisorState -Status "ready" -ChildPid $child.Id -DeploymentId $currentDeploymentId
            Save-KnownGoodSnapshot -DeploymentId (Get-DeploymentIdFromRuntime) | Out-Null
            if (Test-Path -LiteralPath $currentIncident -PathType Leaf) {
                try {
                    $resolvedIncident = Get-Content -LiteralPath $currentIncident -Raw | ConvertFrom-Json
                    $resolvedIncident.ai.status = "runtime-recovered-awaiting-fix"
                    $resolvedIncident.recoveredAt = (Get-Date).ToString("o")
                    Write-JsonAtomic -Path $currentIncident -Value $resolvedIncident
                } catch {}
            }
            if ($appliedThisCycle) {
                Send-Toast -EventId "deploy-success" -Title "SpringSuite updated" -Message "Deployment $appliedDeploymentId is healthy. PID $($child.Id)." -Level "Success"
            } else {
                Send-Toast -EventId "runtime-ready" -Title "SpringSuite ready" -Message "Runtime PID $($child.Id) is healthy on port $Port." -Level "Success"
            }
            Write-SupervisorLog "runtime PID $($child.Id) is healthy"
        }

        Wait-ForRuntimeExit -Process $child
        $exitCode = $child.ExitCode
        Complete-RuntimeTermination -RootPid $child.Id
        Write-SupervisorLog "runtime PID $($child.Id) exited with code $exitCode"
        Set-SupervisorState -Status "runtime-exited" -DeploymentId $currentDeploymentId

        if ($exitCode -eq $restartExitCode) {
            Send-Toast -EventId "runtime-restarting" -Title "SpringSuite restarting" -Message "The runtime requested a supervised restart." -Level "Info"
            Start-Sleep -Milliseconds 500
            continue
        }

        if ($exitCode -eq 0) {
            Send-Toast -EventId "runtime-stopped" -Title "SpringSuite stopped" -Message "The runtime was shut down normally." -Level "Info"
            Set-SupervisorState -Status "stopped" -DeploymentId $currentDeploymentId
            break
        }

        if (Record-CrashAndCanRestart) {
            Send-Toast -EventId "runtime-crashed" -Title "SpringSuite recovered from a crash" -Message "Exit code $exitCode. Automatic restart will begin in 3 seconds." -Level "Warning"
            Set-SupervisorState -Status "crash-restart" -DeploymentId $currentDeploymentId -ErrorMessage "exit code $exitCode"
            Start-Sleep -Seconds 3
            continue
        }

        $reason = "More than three runtime crashes occurred within five minutes; last exit code $exitCode."
        Set-SupervisorState -Status "self-recovery" -DeploymentId $currentDeploymentId -ErrorMessage $reason
        if (Try-SelfRecovery -Reason $reason -ExitCode $exitCode -FailedDeploymentId $currentDeploymentId) {
            $crashTimes.Clear()
            Start-Sleep -Seconds 2
            continue
        }

        Send-Toast -EventId "runtime-recovery-wait" -Title "SpringSuite recovery waiting" -Message "No valid backup is currently available. The supervisor remains alive and will retry every 60 seconds. Incident data is ready for AI." -Level "Error"
        Set-SupervisorState -Status "recovery-wait" -DeploymentId $currentDeploymentId -ErrorMessage $reason
        while (-not (Try-SelfRecovery -Reason $reason -ExitCode $exitCode -FailedDeploymentId $currentDeploymentId)) {
            Start-Sleep -Seconds 60
        }
        $crashTimes.Clear()
        continue
    }
} catch {
    $message = $_.Exception.Message
    Write-SupervisorLog "FATAL: $message | stack: $($_.ScriptStackTrace)"
    if ($null -ne $child) {
        Stop-ChildProcess -Process $child
    }
    $incident = Write-IncidentReport `
        -Phase "supervisor" `
        -Severity "critical" `
        -Message $message `
        -DeploymentId $currentDeploymentId `
        -RecoveryAction "restore-known-good-and-retry" `
        -StackTrace $_.ScriptStackTrace
    Set-SupervisorState -Status "self-recovery" -DeploymentId $currentDeploymentId -ErrorMessage $message
    Send-Toast -EventId "supervisor-failed" -Title "SpringSuite supervisor recovery" -Message "A lifecycle error occurred. Incident $($incident.incidentId) was reported to AI; recovery is starting." -Level "Recovery"

    while ($true) {
        if (Try-SelfRecovery -Reason $message -FailedDeploymentId $currentDeploymentId) {
            try {
                $child = Start-Runtime
                if (Wait-ForHealthyRuntime -Process $child) {
                    Set-SupervisorState -Status "ready-after-recovery" -ChildPid $child.Id -DeploymentId $currentDeploymentId
                    Save-KnownGoodSnapshot -DeploymentId (Get-DeploymentIdFromRuntime) | Out-Null
                    Send-Toast -EventId "supervisor-recovered" -Title "SpringSuite recovered" -Message "The verified backup is running. Incident $($incident.incidentId) remains available to AI for a permanent fix." -Level "Recovery"
                    Wait-ForRuntimeExit -Process $child
                    $recoveryExitCode = $child.ExitCode
                    Complete-RuntimeTermination -RootPid $child.Id
                    if ($recoveryExitCode -eq $restartExitCode) {
                        continue
                    }
                } else {
                    Stop-ChildProcess -Process $child
                }
            } catch {
                Write-SupervisorLog "recovery attempt failed: $($_.Exception.Message)"
            }
        }
        Set-SupervisorState -Status "recovery-wait" -DeploymentId $currentDeploymentId -ErrorMessage $message
        Start-Sleep -Seconds 60
    }
} finally {
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
    }
}

exit 0
