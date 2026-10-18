# 构建说明

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Windows | 10 / 11 | Android 构建脚本使用 PowerShell 5.1+ 与 .NET ZipArchive |
| Node.js | 22.12+ | 前端构建（Vite 7 / TypeScript 5） |
| JDK | 17 | `javac` / `d8` / `apksigner` 运行时 |
| Android SDK | build-tools ≥ 34.0.0、platform android-34 或 android-35 | 无需安装 Android Studio，也无需 Gradle |

`android/build.ps1` 会按以下顺序自动查找 SDK：`ANDROID_HOME` → `ANDROID_SDK_ROOT` →
`%LOCALAPPDATA%\Android\Sdk` → `C:\Android\Sdk` → `D:\Android\Sdk`；
JDK 依次查找 `JAVA_HOME`、`C:\Program Files\Eclipse Adoptium\*`、`C:\Program Files\Java\*`。

## 2. 安装依赖

```powershell
npm ci
```

## 3. 构建前端

```powershell
pwsh -File build-web.ps1
```

等价于 `node scripts/export-records.mjs && tsc && vite build && node scripts/build-pwa.mjs`，
产物输出到 `dist/`（约 28 MB，包含字体、3D 模型与音频）。

## 4. 打包 APK

```powershell
pwsh -File android\build.ps1
```

脚本流程：

1. 把 `android/app/src/main` 与 `dist/` 复制到 ASCII 工作目录（默认 `C:\rhine-build`，可用 `RHINE_BUILD_DIR` 覆盖）。
   *aapt2 在 Windows 上无法打开含中文等非 ASCII 字符的路径。*
2. `aapt2 compile/link` 生成资源与 `base.apk`；
3. `javac` 以 `android.jar` 为 bootclasspath 编译 Java 8 源码；
4. `d8` 生成 `classes.dex`；
5. 用 .NET ZipArchive 以 `/` 路径写入 `classes.dex`、`assets/www/**`、`assets/demo/**`；
   *Windows 版 `aapt2 -A` 会写出反斜杠资源名，Android 的 AssetManager 无法读取，这是刻意绕开的坑。*
6. `zipalign -p -f 4`，首次构建时用 `keytool` 生成自签名证书，`apksigner` 签名；
7. 校验 asset 条目数量、分隔符、`index.html` 与内置演示专辑是否齐全。

产物：`release/Rhine-Music-Mobile-1.0.0.apk`，脚本会打印 SHA-256。

### 签名说明

默认使用构建工作目录中的本地自签名证书（`C:\rhine-build\rhine-release.keystore`，口令 `rhine-music`，别名 `rhine`），
**不会**提交到仓库。它足够侧载安装，但**换一台构建机就会生成不同证书**：用不同签名覆盖安装会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需要先卸载。

若希望多台机器/多次发布保持同一签名，用环境变量指定一个固定证书：

```powershell
$env:RHINE_KEYSTORE      = 'D:\keys\rhine-release.keystore'
$env:RHINE_KEYSTORE_PASS = '<your-store-password>'
$env:RHINE_KEYSTORE_ALIAS = 'rhine'
pwsh -File android\build.ps1
```

发布到应用商店前请改用正式证书；证书文件不要提交到公开仓库。

## 5. 安装到设备

```powershell
adb install -r release\Rhine-Music-Mobile-1.0.0.apk
adb shell am start -n com.rhine.music/.MainActivity
```

模拟器建议使用宿主 GPU（`emulator -avd <name> -gpu host`），WebGL 性能明显好于软件渲染。

## 6. 更新内置演示专辑

`android/app/src/main/assets/demo/` 是安装包内置的演示专辑：

```
demo/
  manifest.json                 专辑、曲目、封面、歌词映射
  observatory/01-atmosphere.ogg 音频（48 kHz Ogg Vorbis）
  observatory/01-atmosphere.lrc 同名歌词
  observatory/cover.jpg         专辑封面（800×800）
```

新增曲目时同时更新 `manifest.json` 的 `tracks` 数组，改完重新执行 `android\build.ps1`。
首次启动会把该目录复制到应用私有目录（`files/demo`），并用 `.version` 标记避免重复复制。

## 7. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `aapt2 ... failed` 且路径含中文 | 换用 ASCII 工作目录：设置 `RHINE_BUILD_DIR=C:\rhine-build` |
| `javac failed` | 确认 `JAVA_HOME` 指向 JDK 17（不是 JRE） |
| `apksigner failed`（文件被占用） | 关闭正在安装/杀毒的进程后重试；脚本可重复执行 |
| 安装时报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 之前用其他证书装过同包名应用，先 `adb uninstall com.rhine.music` |
| 启动后白屏 | 先看 `adb logcat -s chromium:V RhineMain:V RhineServer:V`；WebView 版本过低时请更新 Android System WebView |
