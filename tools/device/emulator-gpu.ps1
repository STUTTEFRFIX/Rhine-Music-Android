# 用不同的 GPU 后端重启 Android 模拟器，用于对比「渲染模式」。
#
# 用法：
#   pwsh -File tools\device\emulator-gpu.ps1 -Mode host        # 借宿主 GPU（默认，转译）
#   pwsh -File tools\device\emulator-gpu.ps1 -Mode swiftshader # 纯软件光栅化（兼容性/软件渲染回退）
#   pwsh -File tools\device\emulator-gpu.ps1 -Mode angle       # ANGLE（部分模拟器版本支持）
#   pwsh -File tools\device\emulator-gpu.ps1 -Mode guest       # 客户机软件渲染（仅排障）
#   pwsh -File tools\device\emulator-gpu.ps1 -Mode host -NoLaunch   # 只停掉当前模拟器
#
# 说明：模拟器的 GPU 后端决定 WebView 拿到的 GLES 驱动；应用内只能改「画质预设 /
# 动态分辨率 / 空闲停帧」等运行时选项，不能更换 GLES 驱动本身。

param(
    [ValidateSet("host", "swiftshader", "angle", "guest")]
    [string]$Mode = "host",
    [string]$Avd = "welkin-test",
    [string]$Sdk = "D:\MobileGL-build\android-sdk",
    [int]$Memory = 3072,
    [switch]$NoLaunch,
    [int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $Sdk "platform-tools\adb.exe"
$emulator = Join-Path $Sdk "emulator\emulator.exe"
$logDir = Join-Path $env:TEMP "rhine-emulator"
New-Item -ItemType Directory -Force $logDir | Out-Null

$gpu = switch ($Mode) {
    "host" { "host" }
    "swiftshader" { "swiftshader_indirect" }
    "angle" { "angle_indirect" }
    "guest" { "guest" }
}

Write-Host "Stopping emulator ..."
Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match "qemu-system|emulator\.exe" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
& $adb kill-server 2>&1 | Out-Null
Start-Sleep -Seconds 3

$avdDir = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd"
if (Test-Path $avdDir) {
    Get-ChildItem $avdDir -Force |
        Where-Object { $_.Name -match "lock" } |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($NoLaunch) { Write-Host "Stopped (no launch requested)."; exit 0 }

$out = Join-Path $logDir "emulator-$Mode.out.log"
$err = Join-Path $logDir "emulator-$Mode.err.log"
Write-Host "Launching AVD '$Avd' with -gpu $gpu ..."
Start-Process -FilePath $emulator -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -ArgumentList @(
    "-avd", $Avd, "-no-snapshot-load", "-no-boot-anim",
    "-gpu", $gpu, "-memory", "$Memory", "-no-audio", "-no-window"
)

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
& $adb start-server 2>&1 | Out-Null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $booted = & $adb shell getprop sys.boot_completed 2>$null
    if ("$booted".Trim() -eq "1") {
        Write-Host "Booted. Logs: $out"
        Write-Host "Check the WebView renderer: adb shell dumpsys package com.rhine.music >/dev/null;"
        Write-Host "then open the app and read 设置 → 渲染模式 → 渲染器。"
        exit 0
    }
}
throw "Emulator did not boot within $BootTimeoutSeconds s; see $out / $err"
