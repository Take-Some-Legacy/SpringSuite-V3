[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Title,

    [Parameter(Mandatory = $true)]
    [string]$Message,

    [ValidateSet("Info", "Success", "Warning", "Error", "Recovery")]
    [string]$Level = "Info",

    [string]$Root = "",

    [string]$EventId = "",

    [switch]$Silent,

    [switch]$NoFallback,

    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-XmlText {
    param([AllowEmptyString()][string]$Value)
    return [System.Security.SecurityElement]::Escape(($Value | Out-String).Trim())
}

function ConvertTo-FileUri {
    param([string]$Path)
    $uri = New-Object System.Uri ([System.IO.Path]::GetFullPath($Path))
    return $uri.AbsoluteUri
}

function New-StatusIcon {
    param(
        [string]$IconRoot,
        [string]$Status
    )

    Add-Type -AssemblyName System.Drawing
    New-Item -ItemType Directory -Path $IconRoot -Force | Out-Null
    $normalized = $Status.ToLowerInvariant()
    $path = Join-Path $IconRoot ("toast-" + $normalized + ".png")
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        return $path
    }

    $color = $null
    $symbol = "i"
    switch ($Status) {
        "Success" {
            $color = [System.Drawing.Color]::FromArgb(35, 150, 75)
            $symbol = [string][char]0x2713
        }
        "Warning" {
            $color = [System.Drawing.Color]::FromArgb(218, 145, 0)
            $symbol = "!"
        }
        "Error" {
            $color = [System.Drawing.Color]::FromArgb(202, 48, 48)
            $symbol = [string][char]0x00D7
        }
        "Recovery" {
            $color = [System.Drawing.Color]::FromArgb(112, 70, 190)
            $symbol = [string][char]0x21BB
        }
        default {
            $color = [System.Drawing.Color]::FromArgb(45, 110, 210)
            $symbol = "i"
        }
    }

    $bitmap = New-Object System.Drawing.Bitmap -ArgumentList 96, 96
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([System.Drawing.Color]::Transparent)

        $backgroundBrush = New-Object System.Drawing.SolidBrush $color
        try {
            $graphics.FillEllipse($backgroundBrush, 5, 5, 86, 86)
        } finally {
            $backgroundBrush.Dispose()
        }

        $fontName = if ($Status -eq "Recovery") { "Segoe UI Symbol" } else { "Segoe UI" }
        $font = New-Object System.Drawing.Font -ArgumentList $fontName, 48, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
        $textBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
        $format = New-Object System.Drawing.StringFormat
        $rectangle = New-Object System.Drawing.RectangleF -ArgumentList 0, 0, 96, 92
        try {
            $format.Alignment = [System.Drawing.StringAlignment]::Center
            $format.LineAlignment = [System.Drawing.StringAlignment]::Center
            $graphics.DrawString($symbol, $font, $textBrush, $rectangle, $format)
        } finally {
            $format.Dispose()
            $textBrush.Dispose()
            $font.Dispose()
        }

        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }

    return $path
}

function Write-NotificationJournal {
    param(
        [string]$JournalRoot,
        [bool]$Displayed,
        [string]$Backend,
        [string]$Failure,
        [string]$IconPath
    )

    if ([string]::IsNullOrWhiteSpace($JournalRoot)) {
        return
    }

    try {
        $stateRoot = Join-Path ([System.IO.Path]::GetFullPath($JournalRoot)) ".springsuite"
        New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
        $entry = [ordered]@{
            timestamp = (Get-Date).ToString("o")
            eventId = $EventId
            level = $Level.ToLowerInvariant()
            title = $Title
            message = $Message
            displayed = $Displayed
            backend = $Backend
            icon = $IconPath
            error = $Failure
        }
        Add-Content -LiteralPath (Join-Path $stateRoot "notifications.jsonl") -Value ($entry | ConvertTo-Json -Compress) -Encoding UTF8
    } catch {
        # Notifications must never break deployment, restart, or recovery.
    }
}

$displayed = $false
$backend = "none"
$failure = ""
$iconPath = ""

try {
    if ($env:OS -ne "Windows_NT") {
        throw "Windows toast notifications are unavailable on this platform."
    }

    $iconRoot = if ([string]::IsNullOrWhiteSpace($Root)) {
        Join-Path $env:LOCALAPPDATA "NoesisSuite\toast-icons"
    } else {
        Join-Path ([System.IO.Path]::GetFullPath($Root)) ".springsuite\toast-icons"
    }
    $iconPath = New-StatusIcon -IconRoot $iconRoot -Status $Level

    if ($DryRun) {
        $backend = "dry-run"
    } else {
        $appId = "KaylasSystems.SpringSuite"
        $appKey = "HKCU:\Software\Classes\AppUserModelId\$appId"
        if (-not (Test-Path -LiteralPath $appKey)) {
            New-Item -Path $appKey -Force | Out-Null
        }
        New-ItemProperty -Path $appKey -Name "DisplayName" -Value "SpringSuite" -PropertyType String -Force | Out-Null
        New-ItemProperty -Path $appKey -Name "ShowInSettings" -Value 1 -PropertyType DWord -Force | Out-Null

        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
        [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
        [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null

        $safeTitle = ConvertTo-XmlText -Value $Title
        $safeMessage = ConvertTo-XmlText -Value $Message
        $iconUri = ConvertTo-XmlText -Value (ConvertTo-FileUri -Path $iconPath)
        $audio = if ($Silent) {
            '<audio silent="true"/>'
        } elseif ($Level -eq "Error") {
            '<audio src="ms-winsoundevent:Notification.Looping.Alarm2" loop="false"/>'
        } elseif ($Level -eq "Warning" -or $Level -eq "Recovery") {
            '<audio src="ms-winsoundevent:Notification.Default"/>'
        } else {
            '<audio src="ms-winsoundevent:Notification.Default"/>'
        }

        $xmlText = @"
<toast duration="short">
  <visual>
    <binding template="ToastGeneric">
      <image placement="appLogoOverride" hint-crop="circle" src="$iconUri"/>
      <text>$safeTitle</text>
      <text>$safeMessage</text>
    </binding>
  </visual>
  $audio
</toast>
"@

        $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        $xml.LoadXml($xmlText)
        $toast = New-Object Windows.UI.Notifications.ToastNotification $xml
        if (-not [string]::IsNullOrWhiteSpace($EventId)) {
            $toast.Tag = if ($EventId.Length -le 64) { $EventId } else { $EventId.Substring(0, 64) }
            $toast.Group = "SpringSuite"
        }
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)
        $displayed = $true
        $backend = "windows-toast"
    }
} catch {
    $failure = $_.Exception.Message
    if (-not $DryRun -and -not $NoFallback -and $env:OS -eq "Windows_NT") {
        try {
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            $notifyIcon = New-Object System.Windows.Forms.NotifyIcon
            $notifyIcon.Icon = switch ($Level) {
                "Error" { [System.Drawing.SystemIcons]::Error }
                "Warning" { [System.Drawing.SystemIcons]::Warning }
                "Recovery" { [System.Drawing.SystemIcons]::Shield }
                default { [System.Drawing.SystemIcons]::Information }
            }
            $notifyIcon.Visible = $true
            $toolTipIcon = switch ($Level) {
                "Error" { [System.Windows.Forms.ToolTipIcon]::Error }
                "Warning" { [System.Windows.Forms.ToolTipIcon]::Warning }
                "Recovery" { [System.Windows.Forms.ToolTipIcon]::Warning }
                default { [System.Windows.Forms.ToolTipIcon]::Info }
            }
            $notifyIcon.ShowBalloonTip(4500, $Title, $Message, $toolTipIcon)
            Start-Sleep -Milliseconds 1200
            $notifyIcon.Visible = $false
            $notifyIcon.Dispose()
            $displayed = $true
            $backend = "notify-icon"
        } catch {
            $failure = $failure + "; fallback: " + $_.Exception.Message
        }
    }
}

Write-NotificationJournal -JournalRoot $Root -Displayed $displayed -Backend $backend -Failure $failure -IconPath $iconPath

[pscustomobject]@{
    ok = $true
    displayed = $displayed
    backend = $backend
    eventId = $EventId
    level = $Level.ToLowerInvariant()
    icon = $iconPath
    error = $failure
} | ConvertTo-Json -Compress

exit 0
