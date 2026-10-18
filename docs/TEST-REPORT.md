# 验证记录（Android 手机版）

本文件记录**实际执行过**的验证，命令与结果均可复现。验证环境：

| 项 | 值 |
| --- | --- |
| 构建机 | Windows 10 19045，JDK 17.0.20，Node.js 24.21，Android SDK build-tools 36.0.0 / platform android-35 |
| 运行设备 | Android 模拟器 API 34（x86_64，1080×2340 @440 dpi → CSS 393×754，DPR 2.75） |
| GPU | 模拟器 `-gpu host`（宿主 NVIDIA RTX 2070 的 GLES 转译），WebView `Chrome/113.0.5672.136` |
| 应用 | `com.rhine.music` 1.0.0（versionCode 1），自签名 |

自动化脚本：[`tools/device/`](../tools/device/)（Node.js + WebView DevTools 协议）。执行方式见该目录 README。

## 1. 构建与安装

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `node node_modules/typescript/bin/tsc` | 通过（无错误） |
| 前端构建 | `pwsh -File build-web.ps1` | 通过，`dist/` 74 文件 / 28.3 MiB |
| APK 构建 | `pwsh -File android\build.ps1` | 通过，签名校验 v2+v3 OK，assets 91 条且全为 `/` 分隔 |
| 安装 | `adb install -r release\Rhine-Music-Mobile-1.0.0.apk` | `Success` |
| 冷启动 | `adb shell am start -n com.rhine.music/.MainActivity` | 正常打开，进入 3D 专辑架 |

## 2. 运行时与 API（`tools/device/smoke-test.mjs`）

| 用例 | 结果 |
| --- | --- |
| 视口 | `innerWidth×innerHeight = 393×754`，`devicePixelRatio = 2.75`，`#stage[data-layout="portrait"]` |
| 原生桥 | `window.RhineNative` 暴露 13 个方法（含 `pickMusicFolder` / `downloadAudio` / `setTheme` / `setKeepAwake`） |
| `/api/library` | 冷启动 3 张专辑（内置演示 + 下载音乐 + 设备音乐库），导入 SAF 后为 4 张 / 10 首；`roots` 状态均 `online` |
| `/api/artwork/<albumId>` | `200`，`80494 bytes`（jpeg） |
| `/api/audio/<trackId>` + `Range: bytes=0-1023` | `206`，`Content-Range: bytes 0-1023/537407`，返回 1024 字节 |
| `/api/lyrics/<trackId>` | `{found:true, text:232 字符}` |
| `/api/playlists` CRUD | 新建 → 加曲（added=1, tracks=1）→ 列表 → 删除 → 列表为空，全部 `200` |
| `/api/library/enrich` | `400`（符合手机版「不提供在线资料」的约定） |

## 3. 播放链路

`<audio>` 元素由播放器动态创建，测试通过劫持 `HTMLMediaElement.prototype.play` 捕获：

| 指标 | 值 |
| --- | --- |
| `src` | `http://127.0.0.1:8766/api/audio/track-…` |
| `paused` | `false` |
| `readyState` | `4` |
| `duration` | `53.333333` 秒 |
| `currentTime` 采样 | `2.144876` → （3 s 后）`4.835552`，**前进确认** |
| 歌词 dock | 「玻璃之后，光在缓慢移动」（与 `currentTime` 对应行） |
| 迷你播放条 | 显示当前曲目标题与封面，播放按钮为暂停态 |

## 4. 音乐来源

### 4.1 内置演示专辑（开箱即用）

| 检查 | 结果 |
| --- | --- |
| 首次启动自动入库 | 专辑「观测室 Observatory」，4 首（OGG×3 + MP3×1），流派「氛围」 |
| 封面 | `/api/artwork/album-754721130b6d391745931065` → `200`，80 KB |
| 歌词 | 01、02 两首 `found=true`，正在播放页 11 行、逐行高亮 |

### 4.2 导入手机音乐（SAF）

用系统文件选择器导入 `/sdcard/Download/RhineImport`（内含 `Glass Album/` 两首 OGG + `cover.jpg` + `.lrc`）：

| 检查 | 结果 |
| --- | --- |
| 授权 | `takePersistableUriPermission` 成功，`roots` 出现「RhineImport」 |
| 专辑 | 「Glass Album」2 首，曲目 `01-atmosphere.ogg` / `03-pulse.ogg` |
| 文件夹封面 | `cover=true`，`/api/artwork` 返回 200 |
| 同名歌词 | `found=true`，232 字符 |
| Range 播放 | `206`，可正常播放 |

### 4.3 系统媒体库（MediaStore）

向 `/sdcard/Music/Rhine Lab/` 推入两首 OGG 并触发媒体扫描：

| 检查 | 结果 |
| --- | --- |
| 专辑 | 「Rhine Lab」2 首（`is_music!=0`） |
| 未知艺术家归一 | `<unknown>` → 「未知艺术家」 |
| 同名歌词 | 通过 `DATA` 路径找到并返回（232 字符） |
| 与 SAF 去重 | 同一文件被「导入」与「媒体库」同时发现的场景验证后，专辑曲目数从 4 降为 2，不再重复 |

### 4.4 直链下载

调用 `RhineNative.downloadAudio('http://127.0.0.1:8766/audio/motif.ogg', '下载测试-主题.ogg')`：

| 事件 | 结果 |
| --- | --- |
| progress | `{received:8192,total:674580}` → `{received:674580,total:674580}` |
| done | `{received:674580,total:674580}` |
| 入库 | 自动重扫后出现「下载音乐」专辑，`/api/audio` Range `206` 可播放 |
| 重名处理 | 再次下载生成 `… (2).ogg`，不覆盖 |

## 5. UI 与交互（真机截图见 [`screenshots/`](../screenshots/)）

| 用例 | 结果 |
| --- | --- |
| 专辑架（暖昼/深夜） | 3D 书架 + 选中封面卡，标题/导航不重叠，底部迷你条常驻 |
| 专辑详情 | 「播放全部 2 首」黑底白字清晰；曲目行 ≥48 px；无内容被播放条遮挡（底部预留 `--mobile-mini-total`） |
| 正在播放 | 大封面、拖动进度、上一首/暂停/下一首、四个动作入口均可见 |
| 同步歌词 | 深色主题下当前行高亮、滚动跟随、点击跳转 |
| 面板（音乐库/搜索/歌单/队列/设置） | 全屏上滑 sheet；原生模式下隐藏「音乐文件夹路径」「MusicBrainz/在线资料」「foobar」等桌面专属区块 |
| 主题切换 | day/dusk/night 三套；`RhineNative.setTheme` 同步状态栏与导航栏配色 |
| 返回键层级 | `__rhineBack()`：面板 → `true`；详情 → 离开详情 `true`（mode 回到 `archive`）；专辑架 → `false`（退出） |
| 触控目标 | 主要按钮 ≥48 px，图标按钮 ≥44 px（脚本审计 `mobile-ui.ts` 与 `mobile.css` 中的 min-height） |
| 封面纹理 | 修复前 Android WebView 上 3D 封面为空白并刷 `GL_INVALID_VALUE`；改用 CPU 像素上传后封面正常显示 |

## 6. 已知问题与环境差异

1. **模拟器 GL 警告**：宿主 GPU 转译下，个别帧仍会打印 `glCopySubTextureCHROMIUM` 警告；手机版已用
   CPU 回退路径保证封面可见，真机（硬件 GL）未观察到该问题。
2. **系统媒体库封面**：Android 13+ 未申请图片读取权限，媒体库来源只使用音频内嵌封面；文件夹 `cover.jpg`
   仅在 SAF 导入来源生效（可读取同目录文件）。
3. **后台播放**：锁屏/省电策略可能暂停 WebView 音频，属平台行为，未做前台服务通知。
4. **横屏/平板**：应用锁定竖屏，未针对大屏做专门布局。

## 7. 复现方式

```powershell
# 1) 安装 APK 并打开
adb install -r release\Rhine-Music-Mobile-1.0.0.apk
adb shell am start -n com.rhine.music/.MainActivity

# 2) 打开 WebView 调试端口
$pid = (adb shell pidof com.rhine.music).Trim()
adb forward tcp:9222 localabstract:webview_devtools_remote_$pid

# 3) 运行 API / 播放 / 歌词检查
node tools\device\smoke-test.mjs
```

