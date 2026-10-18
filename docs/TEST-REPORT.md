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

## 8. V2（1.1.0）验收记录

环境与第 1 节相同（模拟器 1080×2400 @420dpi → CSS 412×843，DPR 2.625，WebView Chrome/113），
APK 为 `release/Rhine-Music-Mobile-1.1.0.apk`。以下均为**实际执行过**的检查，截图在
[`screenshots/`](../screenshots/)（设备帧缓冲 `adb exec-out screencap`，含 WebGL 层）。

### 8.1 手机首次启动 / 画质

| 检查 | 结果 |
| --- | --- |
| 首次启动（清空数据后） | `quality="mobile"`、`reduced=false`，画布 `618×1227`（1.5× CSS，ratio 1） |
| 旧版偏好迁移 | 已保存 `quality="performance"` 的 V1 用户启动后自动升级为 `mobile`，写入 `mobileShellVersion=2`，不会反复覆盖 |
| 软件渲染回退 | `gpuCapability.mode="software"` 时仍为 `performance` + `reduced=true` |
| 桌面回归 | 1600×900 仍为 `original` 预设、`renderIdle=false`，未停帧 |

### 8.2 性能与空闲

| 指标 | 结果 |
| --- | --- |
| 交互 FPS（连续拖动 4.3s，`renderFrames` 增量） | **60.1 FPS** @ 618×1227（`rAF诊断 60 FPS`） |
| 静态停帧 | 静置 2.5s 后 `stage.dataset.renderIdle="true"`，帧循环停止；每 2s 补 1 帧避免部分 WebView 丢失最后一帧 |
| 唤醒 | 真实触摸（`Input.dispatchTouchEvent`）立刻 `renderIdle=false`，帧计数继续增长 |
| 动态分辨率 | 帧率不足时在 0.5–1.0 内自动下调，只降不升过用户预设；底层仍保留 1024 阴影 |
| 封面缩略图 | `/api/artwork/...&w=512` 返回 17 KB JPEG（原图 651 KB），3D 封面清晰 |

### 8.3 网络下载与 B 站缓存

| 用例 | 结果 |
| --- | --- |
| 直链解析 | `resolveLink('http://127.0.0.1:8766/audio/motif.ogg')` → `rhine:resolve {type:"done", kind:"audio"}` |
| 直链下载 | `downloadResolved` 进度 8192/674580 → 674580/674580，`done`；自动入库「下载音乐」专辑（3 首），Range `206` |
| B 站链接解析 | 模拟器无外网 DNS（`api.bilibili.com` 无法解析），返回可读错误并提示改用缓存导入；宿主侧已确认 B 站 `view` + `playurl(fnval=16)` 可用 |
| B 站缓存导入（无 Root 路径） | 把真实缓存样本放入应用 `files/bili-inbox` 后启动自动扫描 → 生成 `.m4a` + 元数据，曲库出现「【官方 MV】Never Gonna Give You Up - Rick Astley / 索尼音乐中国」，时长 3:32、封面正常 |
| B 站缓存条目显示 | 面板显示 `ROOT 不可用 / SU 未授权 / CACHE ENTRIES 1 条`，并给出 ADB 导出命令与手动选目录入口 |
| 桥接方法 | `biliStatus` / `requestRoot` / `importBilibiliCache` / `pickBiliFolder` / `resolveLink` / `downloadResolved` 均存在 |

### 8.4 界面

| 检查 | 结果 |
| --- | --- |
| 竖屏首屏 | 3D 玻璃书架占上半屏，下半屏为玻璃信息层；长标题（B 站标题）两行完整显示，不再被艺术家行裁切 |
| 顶部导航 | 音乐库 / 搜索 / 歌单 / **网络下载** / 设置 五个入口，触控目标 ≥ 44 px |
| 网络下载面板 | URL 输入 + 解析、结果卡（封面/标题/作者/时长/Referer 标记）、开始下载、历史记录、B 站缓存区块 |
| 正在播放 | 播放终端式版式：玻璃封面框、进度条、播放控制、同步歌词/队列/歌单/定位专辑四个入口 |
| 三种主题 | 暖昼 / 薄暮 / 深夜均正常，夜间封面卡与玻璃边缘可读 |

### 8.5 遗留限制

1. **模拟器外网**：B 站在线解析需要在能解析 `api.bilibili.com` 的真机/网络环境验证；离线时功能会给出明确提示。
2. **Root 探测**：模拟器 `adb root` 可用但 App 无法执行 `su`，因此 Root 导入在本机只验证了“检测到不可用并降级到手动导入”，未验证真实 Root 设备。
3. **截图方式**：WebView 的硬件合成层不会被 CDP `Page.captureScreenshot` 捕获（3D 区域为空白），仓库截图均用 `adb exec-out screencap`。
4. **模拟器性能波动**：宿主为 GLES 转译，帧率随宿主负载波动；60 FPS 为清空后台后的实测值，低端真机可能触发动态分辨率下调。


