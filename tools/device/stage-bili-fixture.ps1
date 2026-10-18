# 把 B 站缓存测试样本放到设备上，用于验证「B 站缓存导入」。
#
# 用法（需要 adb 可用，设备已 root 或使用 adb root）：
#   pwsh -File tools\device\stage-bili-fixture.ps1 [-Fixture <dir>] [-Serial <serial>]
#
# 步骤：
#   1. 生成样本（若不存在）: node tools\device\fetch-bili-fixture.mjs <fixture>
#   2. 复制到 /sdcard/Download/BiliCache/<avid>/<cid>/ （SAF 导入用）
#   3. 复制到 App 私有 files/bili-inbox/ 并 chown 到应用 uid（模拟 Root 导入结果，启动即扫描）
#   4. 复制到 /data/media/0/Android/data/tv.danmaku.bili/download/（模拟真实缓存目录，供 Root 探测）

param(
    [string]$Fixture = (Join-Path $env:TEMP "rhine-bili-fixture"),
    [string]$Serial = "",
    [string]$Package = "com.rhine.music"
)

$ErrorActionPreference = 'Stop'
$Adb = 'D:\MobileGL-build\android-sdk\platform-tools\adb.exe'
$AdbArgs = @()
if ($Serial) { $AdbArgs += @('-s', $Serial) }

function Invoke-Adb([string[]]$Arguments) {
    & $Adb @AdbArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed" }
}

if (-not (Test-Path (Join-Path $Fixture 'audio.m4s'))) {
    Write-Host "Fixture missing, fetching a public sample ..."
    $Node = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $Node) { throw 'node not found; run tools\device\fetch-bili-fixture.mjs manually first.' }
    & $Node tools\device\fetch-bili-fixture.mjs $Fixture
}

$avid = '80433022'
$cid = '137649199'

Write-Host '[1/4] adb root ...'
& $Adb @AdbArgs root | Out-Null
Start-Sleep -Seconds 2
& $Adb @AdbArgs wait-for-device

Write-Host '[2/4] push to /sdcard/Download/BiliCache (SAF import path) ...'
Invoke-Adb @("shell", "mkdir -p /sdcard/Download/BiliCache/$avid/$cid/0")
Invoke-Adb @("push", (Join-Path $Fixture 'audio.m4s'), "/sdcard/Download/BiliCache/$avid/$cid/0/audio.m4s")
Invoke-Adb @("push", (Join-Path $Fixture 'entry.json'), "/sdcard/Download/BiliCache/$avid/$cid/entry.json")

Write-Host '[3/4] push to app-private files/bili-inbox (root import simulation) ...'
$uidLine = (& $Adb @AdbArgs shell "stat -c '%u %g' /data/data/$Package")
$uid = ($uidLine -split '\s+')[0]
$gid = ($uidLine -split '\s+')[1]
Write-Host "      app uid=$uid gid=$gid"
Invoke-Adb @("shell", "mkdir -p /data/data/$Package/files/bili-inbox/$avid/$cid/0")
Invoke-Adb @("push", (Join-Path $Fixture 'audio.m4s'), "/data/data/$Package/files/bili-inbox/$avid/$cid/0/audio.m4s")
Invoke-Adb @("push", (Join-Path $Fixture 'entry.json'), "/data/data/$Package/files/bili-inbox/$avid/$cid/entry.json")
Invoke-Adb @("shell", "chown -R ${uid}:${gid} /data/data/$Package/files/bili-inbox")
Invoke-Adb @("shell", "chmod -R u+rwX /data/data/$Package/files/bili-inbox")

Write-Host '[4/4] push to simulated Bilibili cache dir (root path probe) ...'
Invoke-Adb @("shell", "mkdir -p /data/media/0/Android/data/tv.danmaku.bili/download/$avid/$cid/0")
Invoke-Adb @("push", (Join-Path $Fixture 'audio.m4s'), "/data/media/0/Android/data/tv.danmaku.bili/download/$avid/$cid/0/audio.m4s")
Invoke-Adb @("push", (Join-Path $Fixture 'entry.json'), "/data/media/0/Android/data/tv.danmaku.bili/download/$avid/$cid/entry.json")

Write-Host ''
Write-Host 'Done. Restart the app (or trigger a rescan) to scan files/bili-inbox.'
Write-Host 'For SAF import, pick /sdcard/Download/BiliCache in the app.'

