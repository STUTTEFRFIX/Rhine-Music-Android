# Rhine Music · Android 原生工程

不需要 Gradle：`build.ps1` 直接调用 Android SDK 的命令行工具完成资源编译、Java 编译、dex、打包与签名。
构建前置与完整步骤见 [`../docs/BUILD.md`](../docs/BUILD.md)。

## 目录

```
app/src/main/
  AndroidManifest.xml        包名 com.rhine.music，竖屏锁定，targetSdk 34，minSdk 24
  java/com/rhine/music/
    MainActivity.java        WebView 外壳、权限、SAF 选择器、直链下载、系统栏、返回键
    LocalServer.java         仅监听 127.0.0.1 的 HTTP 服务：静态资源 + 音乐 API + Range
    MusicLibrary.java        曲库索引：内置演示 / 下载 / SAF / MediaStore
    PlaylistStore.java       歌单 JSON 持久化
  res/values/                主题、颜色、应用名
  res/mipmap-*/              启动图标（由 public/icons/icon-512.png 缩放生成）
  assets/www/                构建时由 dist/ 复制（不提交）
  assets/demo/               内置演示专辑（提交）
```

## 常用命令

```powershell
# 构建
pwsh -File ..\..\build-web.ps1      # 先构建前端
pwsh -File build.ps1                # 生成 ..\..\release\Rhine-Music-Mobile-1.0.0.apk

# 安装并启动
adb install -r ..\..\release\Rhine-Music-Mobile-1.0.0.apk
adb shell am start -n com.rhine.music/.MainActivity

# 查看日志
adb logcat -s chromium:V RhineMain:V RhineServer:V RhineLibrary:V

# 打开 WebView 调试（Chrome DevTools 或 tools/device 脚本）
$pid = (adb shell pidof com.rhine.music).Trim()
adb forward tcp:9222 localabstract:webview_devtools_remote_$pid
```

## 调试提示

- 服务端口优先 8766，若被占用会自动改用随机端口；`adb forward tcp:8766 tcp:8766` 后可直接
  `curl http://127.0.0.1:8766/api/library` 检查索引。
- 应用数据在 `/data/data/com.rhine.music/files/`（非 debuggable，需用 `run-as` 或 root 查看）：
  - `music-data/index.json` 曲库索引
  - `music-data/playlists.json` 歌单
  - `music-data/artwork/` 封面缓存
  - `demo/` 内置演示专辑副本
  - `downloads/` 直链下载的音乐
- 冷启动时若索引异常，可 `adb shell pm clear com.rhine.music` 清空应用数据后重启（演示专辑会重新解压）。
- 源码为 Java 8 语法（`-source 8 -target 8`，`android.jar` 作为 bootclasspath），**不要使用 lambda/var**，
  否则 `javac` 会因为 `LambdaMetafactory` 不在 bootclasspath 而失败。

## 签名

首次构建会在构建工作目录（默认 `C:\rhine-build\rhine-release.keystore`）生成自签名证书，口令与别名见 `build.ps1` 顶部变量。
该证书不提交仓库；正式发布请替换为自有证书。
