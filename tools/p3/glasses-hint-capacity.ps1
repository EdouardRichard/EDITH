# T-P2.1 — 提示呈现容量校准探针（P3.2 Stage 0）
#
# 用**合成文本**（`测` 重复 N 次）经真实链路（手机仲裁器 → CXR → 眼镜 TextView）推送已知长度的提示，
# 再从**眼镜**的 logcat 读 `hint rendered lines=… clipped=…`，得到 chars → lines 的实测表。
#
# 前置（脚本会检查并在缺失时明确报错，不会假装成功）：
#   1. 手机 debug APK 已安装且包含 `DebugHintInjector`；
#   2. 手机 App 已开始会话、当前插件为「提示探针（调试）」(p2-hint)，且眼镜显示已就绪（时钟映射已活）；
#      —— 未就绪时广播会得到 `accepted=false`，脚本据此报错而不是把 0 行当结论；
#   3. 眼镜端已是当前 APK；眼镜序列号与手机序列号都必须显式给出（不指定会把手机 APK 装到眼镜上）。
#
# 隐私：只推送合成文本；日志只记字符数、行数与裁切标志。
#
# 用法：
#   pwsh -File tools/p3/glasses-hint-capacity.ps1 -PhoneSerial 2696813b -GlassesSerial 1901092544027662 `
#        -Chars 20,40,60,80,100,120 -OutFile docs/project/evidence/2026-10-09-p3-2-hint-capacity-table.md

param(
    [Parameter(Mandatory = $true)][string]$PhoneSerial,
    [Parameter(Mandatory = $true)][string]$GlassesSerial,
    [int[]]$Chars = @(20, 40, 60, 80, 100, 120),
    [string]$Adb = "D:\Tools\Android\Sdk\platform-tools\adb.exe",
    [string]$OutFile = "",
    [int]$SettleMillis = 2500
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    # NOT `-Args`: that name collides with PowerShell's automatic `$Args`, the parameter silently receives nothing,
    # and adb then prints its help text - which this script would otherwise read as a device response.
    param([string]$Serial, [string[]]$AdbArgs)
    $all = @("-s", $Serial) + $AdbArgs
    $out = & $Adb @all 2>&1
    return ($out | Out-String).Trim()
}

# --- 前置检查 -------------------------------------------------------------------------------------
$devices = & $Adb devices | Out-String
if ($devices -notmatch [regex]::Escape($PhoneSerial)) { throw "phone $PhoneSerial not attached" }
if ($devices -notmatch [regex]::Escape($GlassesSerial)) { throw "glasses $GlassesSerial not attached" }

$glassesModel = Invoke-Adb -Serial $GlassesSerial -AdbArgs @("shell", "getprop", "ro.product.model")
if ($glassesModel -notmatch "glasses") {
    throw "serial $GlassesSerial does not look like the glasses (model=$glassesModel) - refusing to run"
}

# --- 逐档测量 -------------------------------------------------------------------------------------
$rows = @()

foreach ($n in $Chars) {
    # 清空眼镜日志缓冲，保证读到的是本次这一条提示
    Invoke-Adb -Serial $GlassesSerial -AdbArgs @("logcat", "-c") | Out-Null

    $broadcast = Invoke-Adb -Serial $PhoneSerial -AdbArgs @(
        "shell", "am", "broadcast", "-a", "edith.DEBUG_HINT",
        "-n", "edith.phone/.DebugHintInjector", "--ei", "chars", "$n")
    Start-Sleep -Milliseconds $SettleMillis

    $phoneLine = (Invoke-Adb -Serial $PhoneSerial -AdbArgs @("logcat", "-d", "-s", "EdithP3")) -split "`n" |
        Where-Object { $_ -match "P3_HINT_INJECT" } | Select-Object -Last 1
    $accepted = if ($phoneLine -match "accepted=true") { $true } elseif ($phoneLine -match "accepted=false") { $false } else { $null }

    $glassesLines = (Invoke-Adb -Serial $GlassesSerial -AdbArgs @("logcat", "-d", "-s", "EdithGlasses")) -split "`n"
    $rendered = $glassesLines | Where-Object { $_ -match "hint rendered" } | Select-Object -Last 1

    $lines = $null; $maxLines = $null; $clipped = $null
    if ($rendered -match "lines=(\d+)\s+maxLines=(\d+)\s+chars=(\d+)\s+clipped=(true|false)") {
        $lines = [int]$Matches[1]; $maxLines = [int]$Matches[2]; $clipped = $Matches[4] -eq "true"
    }

    $rows += [pscustomobject]@{
        chars    = $n
        accepted = $accepted
        lines    = $lines
        maxLines = $maxLines
        clipped  = $clipped
        charsPerLine = if ($lines -and $lines -gt 0) { [math]::Round($n / $lines, 1) } else { $null }
        broadcast = ($broadcast -split "`n" | Select-Object -First 1)
    }
}

# --- 输出 -----------------------------------------------------------------------------------------
$table = $rows | Format-Table -AutoSize | Out-String
Write-Output $table

if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $md = @()
    $md += "| chars | accepted | lines | maxLines | clipped | chars/line |"
    $md += "|---|---|---|---|---|---|"
    foreach ($r in $rows) {
        $md += "| $($r.chars) | $($r.accepted) | $($r.lines) | $($r.maxLines) | $($r.clipped) | $($r.charsPerLine) |"
    }
    Set-Content -Path $OutFile -Value ($md -join "`n") -Encoding UTF8
    Write-Output "WROTE=$OutFile"
}