# Build the Rhine Music Android APK with a local Android SDK, without Gradle.
#
# Pipeline: aapt2 (resources) -> javac -> d8 (dex) -> manual asset packaging ->
# zipalign -> apksigner. Assets are added with .NET ZipArchive because Windows
# aapt2 writes asset entry names with backslashes, which Android cannot open.
#
# Requirements:
#   * Android SDK with build-tools (>= 34) and platforms/android-35 (or -34).
#     Set ANDROID_HOME (or ANDROID_SDK_ROOT) or install to the default location.
#   * JDK 17; set JAVA_HOME or install Temurin to the default location.
#   * dist/ produced by build-web.ps1 (run it first).
#
# Some SDK tools cannot open paths with non-ASCII characters, so the project is
# staged into an ASCII-only work directory before aapt2 runs.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path   # .../android
$Project = Split-Path -Parent $Root                           # repository root
$OutApk  = Join-Path $Project 'release\Rhine-Music-Mobile-1.1.0.apk'
$VersionCode = 2
$VersionName = '1.1.0'

function Find-AndroidSdk {
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'C:\Android\Sdk',
        'D:\Android\Sdk'
    ) | Where-Object { $_ }
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'build-tools')) { return $candidate }
    }
    throw 'Android SDK not found. Set ANDROID_HOME to a folder that contains build-tools/ and platforms/.'
}

function Find-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($base in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java')) {
        if (-not (Test-Path $base)) { continue }
        foreach ($entry in Get-ChildItem $base -Directory) { $candidates.Add($entry.FullName) }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'bin\javac.exe')) { return $candidate }
    }
    throw 'JDK 17 not found. Set JAVA_HOME to a JDK 17 installation.'
}

function Find-BuildTools($sdk) {
    $directories = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+(\.\d+)+$' } |
        Sort-Object { [version]$_.Name } -Descending
    if (-not $directories) { throw 'No build-tools installed in the Android SDK.' }
    return $directories[0].FullName
}

function Find-Platform($sdk) {
    $directories = Get-ChildItem (Join-Path $sdk 'platforms') -Directory |
        Where-Object { $_.Name -match '^android-\d+$' } |
        Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending
    foreach ($directory in $directories) {
        $jar = Join-Path $directory.FullName 'android.jar'
        if (Test-Path $jar) { return $jar }
    }
    throw 'No Android platform (android-34 or newer) installed in the SDK.'
}

$SDK         = Find-AndroidSdk
$BUILD_TOOLS = Find-BuildTools $SDK
$PLATFORM    = Find-Platform $SDK
$JAVA_HOME   = Find-JavaHome
$JAVAC       = Join-Path $JAVA_HOME 'bin\javac.exe'
$KEYTOOL     = Join-Path $JAVA_HOME 'bin\keytool.exe'
$AAPT2       = Join-Path $BUILD_TOOLS 'aapt2.exe'
$D8          = Join-Path $BUILD_TOOLS 'd8.bat'
$ZIPALIGN    = Join-Path $BUILD_TOOLS 'zipalign.exe'
$APKSIGNER   = Join-Path $BUILD_TOOLS 'apksigner.bat'

# ASCII-only work directory: aapt2 on Windows cannot open non-ASCII paths.
$Work = if ($env:RHINE_BUILD_DIR) { $env:RHINE_BUILD_DIR } else { 'C:\rhine-build' }
try {
    New-Item -ItemType Directory -Force -Path $Work | Out-Null
} catch {
    $Work = Join-Path $env:TEMP 'rhine-build'
    Write-Host "WARNING: cannot write to C:\rhine-build; using $Work (must be an ASCII path)."
    New-Item -ItemType Directory -Force -Path $Work | Out-Null
}

$Build     = Join-Path $Work 'build'
# A maintainer release key can be supplied through the environment so repeated
# releases keep one signing identity; otherwise a local self-signed key is
# generated in the (non-committed) build work directory.
$Keystore  = if ($env:RHINE_KEYSTORE) { $env:RHINE_KEYSTORE } else { Join-Path $Work 'rhine-release.keystore' }
$StorePass = if ($env:RHINE_KEYSTORE_PASS) { $env:RHINE_KEYSTORE_PASS } else { 'rhine-music' }
$KeyAlias  = if ($env:RHINE_KEYSTORE_ALIAS) { $env:RHINE_KEYSTORE_ALIAS } else { 'rhine' }
$AppMain   = Join-Path $Root 'app\src\main'
$Dist      = Join-Path $Project 'dist'

foreach ($tool in @($AAPT2, $D8, $ZIPALIGN, $APKSIGNER, $JAVAC, $PLATFORM)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}
if (-not (Test-Path (Join-Path $Dist 'index.html'))) {
    throw "Web build missing at $Dist (run build-web.ps1 first)."
}

Write-Host "[1/9] Staging project into ASCII workspace ($Work) ..."
if (Test-Path (Join-Path $Work 'app')) { Remove-Item (Join-Path $Work 'app') -Recurse -Force }
if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $Work 'app'), $Build, (Join-Path $Build 'flat'), (Join-Path $Build 'classes'), (Join-Path $Build 'dex') | Out-Null
Copy-Item -Path (Join-Path $AppMain 'res') -Destination (Join-Path $Work 'app\res') -Recurse -Force
Copy-Item -Path (Join-Path $AppMain 'java') -Destination (Join-Path $Work 'app\java') -Recurse -Force
Copy-Item -Path (Join-Path $AppMain 'AndroidManifest.xml') -Destination (Join-Path $Work 'app\AndroidManifest.xml') -Force
$ResDir   = Join-Path $Work 'app\res'
$Manifest = Join-Path $Work 'app\AndroidManifest.xml'
$SrcDir   = Join-Path $Work 'app\java'

Write-Host '[2/9] Staging web build into assets/www ...'
$www = Join-Path $Work 'app\assets\www'
New-Item -ItemType Directory -Force -Path $www | Out-Null
Copy-Item -Path (Join-Path $Dist '*') -Destination $www -Recurse -Force
$assetCount = (Get-ChildItem $www -Recurse -File).Count
$assetSize = [math]::Round(((Get-ChildItem $www -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host "      $assetCount web files, $assetSize MB"

Write-Host '[3/9] Compiling and linking resources ...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
& $AAPT2 compile --dir $ResDir -o (Join-Path $Build 'flat\res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
$flatDir = Join-Path $Build 'flat\res'
if (Test-Path $flatDir) { Remove-Item $flatDir -Recurse -Force }
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $Build 'flat\res.zip'), $flatDir)
$flats = Get-ChildItem $flatDir -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
if (-not $flats) { throw 'aapt2 compile produced no .flat resources' }
& $AAPT2 link `
    --manifest $Manifest `
    -o (Join-Path $Build 'base.apk') `
    -I $PLATFORM `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    --version-code $VersionCode `
    --version-name $VersionName `
    @flats
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[4/9] Compiling Java sources ...'
$sources = Get-ChildItem $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
& $JAVAC -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:none `
    -bootclasspath $PLATFORM -classpath $PLATFORM `
    -d (Join-Path $Build 'classes') @sources 2>&1 | ForEach-Object { if ($_ -notmatch 'warning') { Write-Host "      $_" } }
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[5/9] Dexing with d8 ...'
$classFiles = Get-ChildItem (Join-Path $Build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --release --min-api 24 --lib $PLATFORM --output (Join-Path $Build 'dex') @classFiles 2>&1 | ForEach-Object { Write-Host "      $_" }
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[6/9] Packaging dex and assets into the APK ...'
$apkPath = Join-Path $Build 'unsigned.apk'
Copy-Item (Join-Path $Build 'base.apk') $apkPath -Force

function Add-ZipFile($archive, $entryName, $sourcePath, $level) {
    $entry = $archive.CreateEntry($entryName, $level)
    $stream = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes($sourcePath)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}

$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
    # classes.dex stays uncompressed so older runtimes can map it directly.
    Add-ZipFile $zip 'classes.dex' (Join-Path $Build 'dex\classes.dex') ([System.IO.Compression.CompressionLevel]::NoCompression)
    $staged = Get-ChildItem $www -Recurse -File
    foreach ($file in $staged) {
        $relative = $file.FullName.Substring($www.Length + 1).Replace('\', '/')
        Add-ZipFile $zip ("assets/www/" + $relative) $file.FullName ([System.IO.Compression.CompressionLevel]::Optimal)
    }
    $demo = Join-Path $AppMain 'assets\demo'
    $demoFiles = Get-ChildItem $demo -Recurse -File
    foreach ($file in $demoFiles) {
        $relative = $file.FullName.Substring($demo.Length + 1).Replace('\', '/')
        # The demo album is extracted to app storage on first launch, so these
        # entries only need to be present, not seekable inside the APK.
        Add-ZipFile $zip ("assets/demo/" + $relative) $file.FullName ([System.IO.Compression.CompressionLevel]::NoCompression)
    }
} finally { $zip.Dispose() }
Write-Host "      packed $($staged.Count) web files, $($demoFiles.Count) demo files + classes.dex"

Write-Host '[7/9] Aligning and signing ...'
& $ZIPALIGN -p -f 4 $apkPath (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path $Keystore)) {
    # A local self-signed key is enough for sideloading. It is never committed;
    # keep the generated file to upgrade an existing installation in place.
    & $KEYTOOL -genkeypair -v -keystore $Keystore -storepass $StorePass -keypass $StorePass `
        -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Rhine Music, OU=Mobile, O=Rhine Lab, L=Shanghai, ST=Shanghai, C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutApk) | Out-Null
& $APKSIGNER sign --ks $Keystore --ks-pass "pass:$StorePass" --ks-key-alias $KeyAlias `
    --key-pass "pass:$StorePass" --min-sdk-version 24 `
    --out $OutApk (Join-Path $Build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Host '[8/9] Verifying ...'
& $APKSIGNER verify --verbose --print-certs $OutApk | Select-Object -First 6
& $AAPT2 dump badging $OutApk | Select-Object -First 3

# Guard against the Windows aapt2 backslash asset bug and packaging slips.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$checkZip = [System.IO.Compression.ZipFile]::OpenRead($OutApk)
try {
    $assetEntries = @($checkZip.Entries | Where-Object { $_.FullName -like 'assets/*' })
    $badNames = @($assetEntries | Where-Object { $_.FullName.Contains('\') })
    if ($badNames.Count -gt 0) {
        throw ("asset entries contain backslashes and would be unreadable on Android: " + $badNames[0].FullName)
    }
    $expected = (Get-ChildItem $www -Recurse -File).Count + $demoFiles.Count
    if ($assetEntries.Count -ne $expected) {
        throw "asset count mismatch: packed $($assetEntries.Count), expected $expected"
    }
    foreach ($required in @('assets/www/index.html', 'assets/demo/manifest.json', 'assets/demo/observatory/cover.jpg')) {
        if (-not ($assetEntries | Where-Object { $_.FullName -eq $required })) { throw "$required is missing from the APK" }
    }
    Write-Host ("      assets ok: {0} entries, / separators, index.html + demo present" -f $assetEntries.Count)
} finally { $checkZip.Dispose() }

Write-Host '[9/9] Done.'
$info = Get-Item $OutApk
Write-Host ""
Write-Host ("APK:        {0}" -f $info.FullName)
Write-Host ("Size:       {0:N2} MB" -f ($info.Length / 1MB))
Write-Host ("SHA256:     {0}" -f (Get-FileHash $OutApk -Algorithm SHA256).Hash)
