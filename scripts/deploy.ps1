[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Target = "",

    [int]$Port = 8090,

    [switch]$SkipTests,

    [switch]$SkipBuild,

    [switch]$NoStart,

    [switch]$ReplaceConfig,

    [switch]$ForceStop,

    [switch]$KeepBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory ".."))
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$deployImage = Join-Path $repositoryRoot "build\deploy"
$applyScript = Join-Path $scriptDirectory "apply-deploy.ps1"

function Resolve-DeploymentTarget {
    param([string]$ConfiguredTarget)

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredTarget)) {
        return [System.IO.Path]::GetFullPath($ConfiguredTarget)
    }

    if (-not [string]::IsNullOrWhiteSpace($env:SPRING_SUITE_RUNTIME)) {
        return [System.IO.Path]::GetFullPath($env:SPRING_SUITE_RUNTIME)
    }

    $documents = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
    return [System.IO.Path]::GetFullPath((Join-Path $documents "Take Some\NorthStar-Suite-V3"))
}

function Invoke-GradleBuild {
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper is missing: $gradleWrapper"
    }

    $arguments = @("clean")
    if (-not $SkipTests) {
        $arguments += "test"
    }
    $arguments += @("assembleDeploy", "verifyDeployLayout", "--no-daemon")

    Write-Host "[deploy] Gradle $($arguments -join ' ')"
    & $gradleWrapper @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

function Get-RuntimeStatus {
    $statusUri = "http://127.0.0.1:$Port/api/system/status"
    try {
        $response = Invoke-RestMethod -Method Get -Uri $statusUri -TimeoutSec 2
        $components = $response.data.components
        $launchRoot = [string]$components.launchDirectory
        if ([string]::IsNullOrWhiteSpace($launchRoot)) {
            $launchRoot = [string]$components.projectRoot
        }

        return [pscustomobject]@{
            pid = [int]$components.pid
            launchRoot = $launchRoot
            status = [string]$response.data.status
        }
    } catch {
        return $null
    }
}

function Get-BridgeToken {
    if (-not [string]::IsNullOrWhiteSpace($env:NORTHSTAR_BRIDGE_ACCESS_TOKEN)) {
        return $env:NORTHSTAR_BRIDGE_ACCESS_TOKEN.Trim()
    }

    $candidatePaths = @(
        (Join-Path $env:LOCALAPPDATA "NoesisSuite\authority\bridge_access_token.txt"),
        (Join-Path $targetRoot "authority\bridge_access_token.txt")
    )

    foreach ($candidate in $candidatePaths) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            $token = (Get-Content -LiteralPath $candidate -Raw).Trim()
            if (-not [string]::IsNullOrWhiteSpace($token)) {
                return $token
            }
        }
    }

    return ""
}

function Request-GracefulRestart {
    param([string]$Token)

    if ([string]::IsNullOrWhiteSpace($Token)) {
        return $false
    }

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = "deploy-$([Guid]::NewGuid().ToString('N'))"
        method = "tools/call"
        params = [ordered]@{
            name = "command.execute"
            arguments = [ordered]@{
                line = "restart --force --delay 1"
            }
        }
    }

    $headers = @{
        "X-NorthStar-Bridge-Token" = $Token
    }

    try {
        $response = Invoke-RestMethod `
            -Method Post `
            -Uri "http://127.0.0.1:$Port/mcp" `
            -Headers $headers `
            -ContentType "application/json" `
            -Body ($request | ConvertTo-Json -Depth 8) `
            -TimeoutSec 10

        if ($null -ne $response.error) {
            Write-Warning "SpringSuite rejected the restart request: $($response.error.message)"
            return $false
        }

        return $true
    } catch {
        Write-Warning "Could not request graceful restart: $($_.Exception.Message)"
        return $false
    }
}

function Copy-DeployImageToStage {
    param(
        [string]$ImageRoot,
        [string]$StageRoot
    )

    New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null

    $preservedRoots = @("data", "logs", ".springsuite")
    if (-not $ReplaceConfig -and (Test-Path -LiteralPath (Join-Path $targetRoot "config") -PathType Container)) {
        $preservedRoots += "config"
    }

    Get-ChildItem -LiteralPath $ImageRoot -Force | ForEach-Object {
        if ($preservedRoots -contains $_.Name) {
            Write-Host "[deploy] preserving runtime directory: $($_.Name)"
            return
        }

        $destination = Join-Path $StageRoot $_.Name
        if ($_.PSIsContainer) {
            Copy-Item -LiteralPath $_.FullName -Destination $destination -Recurse -Force
        } else {
            Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
        }
    }
}

function Start-ApplyProcess {
    param(
        [string]$PayloadRoot,
        [int]$OldProcessId,
        [bool]$StartAfterDeploy,
        [string]$ApplyLog
    )

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $applyScript,
        "-Target", $targetRoot,
        "-Payload", $PayloadRoot,
        "-OldProcessId", $OldProcessId,
        "-Port", $Port,
        "-LogPath", $ApplyLog
    )
    if (-not $StartAfterDeploy) {
        $arguments += "-NoStart"
    }

    return Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList $arguments `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -PassThru
}

function Wait-ForDeployment {
    param(
        [System.Diagnostics.Process]$ApplyProcess,
        [string]$ApplyLog,
        [bool]$ExpectHealthyRuntime
    )

    if (-not $ApplyProcess.WaitForExit(120000)) {
        throw "Deployment helper did not finish within 120 seconds. Log: $ApplyLog"
    }

    if ($ApplyProcess.ExitCode -ne 0) {
        $tail = if (Test-Path -LiteralPath $ApplyLog) {
            (Get-Content -LiteralPath $ApplyLog -Tail 40) -join [Environment]::NewLine
        } else {
            "deployment log was not created"
        }
        throw "Deployment helper failed with exit code $($ApplyProcess.ExitCode).`n$tail"
    }

    if ($ExpectHealthyRuntime) {
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            $status = Get-RuntimeStatus
            if ($null -ne $status) {
                Write-Host "[deploy] SpringSuite is READY, PID $($status.pid)"
                return
            }
            Start-Sleep -Seconds 1
        }
        throw "Deployment finished, but SpringSuite status did not become available on port $Port"
    }
}

$targetRoot = Resolve-DeploymentTarget -ConfiguredTarget $Target

if ($targetRoot.TrimEnd('\') -eq $repositoryRoot.TrimEnd('\')) {
    throw "Deployment target must not be the source repository"
}

if (-not (Test-Path -LiteralPath $applyScript -PathType Leaf)) {
    throw "Deployment helper is missing: $applyScript"
}

if (-not $SkipBuild) {
    Invoke-GradleBuild
}

if (-not (Test-Path -LiteralPath (Join-Path $deployImage "spring-suite.jar") -PathType Leaf)) {
    throw "Deploy image is missing. Run without -SkipBuild or execute gradlew.bat assembleDeploy"
}

$deploymentId = Get-Date -Format "yyyyMMdd-HHmmss"
$stageRoot = Join-Path $targetRoot ".springsuite\deploy-staging\$deploymentId"
$payloadRoot = Join-Path $stageRoot "payload"
$applyLog = Join-Path $stageRoot "apply.log"

if (-not $PSCmdlet.ShouldProcess($targetRoot, "Deploy SpringSuite image $deploymentId")) {
    return
}

New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
Copy-DeployImageToStage -ImageRoot $deployImage -StageRoot $payloadRoot

$runtimeStatus = Get-RuntimeStatus
if ($null -ne $runtimeStatus -and $null -ne $runtimeStatus.launchRoot) {
    $activeLaunchRoot = [System.IO.Path]::GetFullPath([string]$runtimeStatus.launchRoot).TrimEnd('\')
    if ($activeLaunchRoot -ne $targetRoot.TrimEnd('\')) {
        Write-Host "[deploy] port $Port belongs to another runtime: $activeLaunchRoot"
        $runtimeStatus = $null
    }
}

$oldProcessId = 0
if ($null -ne $runtimeStatus -and $null -ne $runtimeStatus.pid) {
    $oldProcessId = [int]$runtimeStatus.pid
    Write-Host "[deploy] active SpringSuite detected: PID $oldProcessId"
}

$startAfterDeploy = -not $NoStart
$applyProcess = Start-ApplyProcess `
    -PayloadRoot $payloadRoot `
    -OldProcessId $oldProcessId `
    -StartAfterDeploy $startAfterDeploy `
    -ApplyLog $applyLog

if ($oldProcessId -gt 0) {
    $token = Get-BridgeToken
    $restartRequested = Request-GracefulRestart -Token $token

    if (-not $restartRequested) {
        if ($ForceStop) {
            Write-Warning "Graceful restart was unavailable; force-stopping PID $oldProcessId"
            Stop-Process -Id $oldProcessId -Force
        } else {
            Stop-Process -Id $applyProcess.Id -Force -ErrorAction SilentlyContinue
            throw "Runtime is active and graceful restart could not be requested. Configure NORTHSTAR_BRIDGE_ACCESS_TOKEN or rerun with -ForceStop. Staged payload: $payloadRoot"
        }
    }
}

Wait-ForDeployment `
    -ApplyProcess $applyProcess `
    -ApplyLog $applyLog `
    -ExpectHealthyRuntime $startAfterDeploy

if (-not $KeepBuild) {
    & (Join-Path $scriptDirectory "clean.ps1") -Mode Build -Quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Deployment succeeded, but build cleanup returned exit code $LASTEXITCODE"
    }
}

Write-Host "[deploy] deployment completed"
Write-Host "[deploy] target: $targetRoot"
Write-Host "[deploy] log: $applyLog"
