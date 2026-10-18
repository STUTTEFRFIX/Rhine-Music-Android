# 架构与接口契约

手机版不重写 Web 应用：`assets/www` 就是 Windows 版同一份 Vite 构建产物。Android 侧只负责三件事：
**提供回环 HTTP 服务**（静态资源 + 与原 Windows 版一致的本地音乐 API）、**提供手机独有的原生能力**（存储访问、下载、系统栏），
以及**在 ≤700 px 时换上竖屏 UI 外壳**。

## 1. 运行时形态

```
MainActivity
 ├── WebView  → http://127.0.0.1:<port>/     （竖屏锁定；系统栏颜色/图标随主题）
 ├── LocalServer（Java 线程池，仅绑定 127.0.0.1）
 │    ├── GET  /、/assets/*、/fonts/* …     → APK assets/www（构建产物 dist/）
 │    └── *    /api/*                       → MusicLibrary / PlaylistStore
 ├── MusicLibrary（后台线程扫描，索引落盘 files/music-data/index.json）
 └── NativeBridge（addJavascriptInterface "RhineNative"）
```

- 端口优先 `8766`，被占用则随机；WebView 始终加载 `127.0.0.1` 字面地址（避免 `localhost` 解析到 `::1`）。
- 音频通过 `GET /api/audio/<trackId>` 以 `Range` 流式返回，不使用 `file://`。
- 静态资源直接从 APK `assets/www` 读取；音乐文件在应用私有目录 / SAF / MediaStore 中，不复制歌曲本身。

## 2. REST API 契约

与 Windows 版 `scripts/music-server.mjs` 保持一致，前端代码未做 API 分支。
所有 JSON 响应为 UTF-8，错误为 `{ "error": "消息" }`。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/library` | `MusicLibrary` 快照（结构见 `src/music-types.ts`） |
| POST | `/api/library/scan` | 触发后台扫描，返回 202 + 快照；body 可带 `{roots}`（手机版忽略） |
| GET/POST | `/api/config` | `{version,roots,onlineEnabled:false,foobarBaseUrl:null,musicBrainzContact:""}` |
| GET/POST | `/api/genre-rules` | `{version:1,genres:[…],albumOverrides:{}}` |
| POST | `/api/library/enrich` | 固定返回 400（手机版不做 MusicBrainz 补充） |
| POST | `/api/library/introductions` | 返回 202 + 快照（不执行网络请求） |
| GET | `/api/playlists` | `{version:1,playlists:[…]}` |
| POST | `/api/playlists` | `{name}` → 201 `{playlist}` |
| PATCH/DELETE | `/api/playlists/{uuid}` | 改名 / 删除 |
| POST/PUT | `/api/playlists/{uuid}/tracks` | `{trackIds}` 加曲 / `{itemIds}` 排序 |
| DELETE | `/api/playlists/{uuid}/tracks/{itemId}` | 移除曲目 |
| GET/HEAD | `/api/audio/{trackId}` | 音频字节；单段 `Range`，206/416，`Accept-Ranges: bytes` |
| GET/HEAD | `/api/artwork/{albumId}` | 专辑封面（jpeg/png/webp），`Cache-Control: private, max-age=3600` |
| GET | `/api/lyrics/{trackId}` | `{found,text}`（同名 `.lrc`） |
| GET | `/api/foobar/status` | `{configured:false,connected:false}` |
| GET | `/api/health` | `{service:"rhine-local-music",platform:"android"}` |

标识符规则：

- 曲目 id：`track-<sha256(uri).hex[0:24]>`；
- 专辑 id：`album-<sha256("title:"+NFKC 小写去空白标题).hex[0:24]>`，同名专辑跨来源合并（与桌面版一致）；
- 播放地址：`track.audioUrl = /api/audio/<trackId>`；
- 封面地址：`album.coverUrl = /api/artwork/<albumId>?v=<封面哈希前 10 位>`（无封面时省略字段，前端显示占位）。

`roots` 为人类可读来源标签：`内置演示`、`下载音乐`、SAF 文件夹名、`设备音乐库`。

## 3. 原生桥（JS ↔ Android）

`MainActivity` 通过 `addJavascriptInterface(new NativeBridge(), "RhineNative")` 暴露：

```ts
interface RhineNative {
  platform(): "android";
  version(): string;
  pickMusicFolder(): void;                        // SAF 目录选择器
  requestAudioPermission(): void;                 // 申请读取设备音乐
  hasAudioPermission(): boolean;
  downloadAudio(url: string, name: string): void; // 直链下载到私有目录
  cancelDownload(): void;
  rescan(): void;
  toast(message: string): void;
  setTheme(theme: "day" | "dusk" | "night"): void; // 系统栏配色与图标明暗
  setKeepAwake(awake: boolean): void;              // 播放时保持屏幕常亮
  vibrate(): void;
  exitApp(): void;
}
```

原生 → 页面事件使用 `document.dispatchEvent(new CustomEvent(...))`：

| 事件 | detail |
| --- | --- |
| `rhine:native` | `{type:"ready" \| "imported" \| "library-changed"}` |
| `rhine:permission` | `{granted:boolean}` |
| `rhine:download` | `{type:"progress"\|"done"\|"error", url, received, total, error?}` |

浏览器中 `window.RhineNative` 不存在，`src/mobile-ui.ts` 会降级为提示文案，不会产生死按钮。

### 返回键契约

Android 返回键/返回手势会先调用页面内的 `window.__rhineBack()`，返回 `true` 表示页面已消费，`false` 则退出应用。
`src/mobile-ui.ts` 的实现顺序：关闭打开的面板 → 关闭「正在播放」→ 从专辑详情返回专辑架 → 返回 `false`。

## 4. 音乐索引（MusicLibrary）

四个来源统一进入同一份索引，扫描在后台线程执行，前端每 1.4–12 s 轮询 `/api/library`：

| 来源 | 枚举方式 | 元数据 | 封面 | 歌词 |
| --- | --- | --- | --- | --- |
| 内置演示 | `files/demo` 下的 `manifest.json` | manifest + `MediaMetadataRetriever` 补时长 | 文件夹 `cover.jpg` | 同名 `.lrc` |
| 下载音乐 | `files/downloads` 递归 | `MediaMetadataRetriever` | 文件夹图片 / 内嵌图 | 同名 `.lrc` |
| SAF 导入 | `DocumentsContract` 递归子文档 | `MediaMetadataRetriever` | 文件夹 `cover/folder/front` 图片 | 同目录同名 `.lrc` |
| 设备媒体库 | `MediaStore.Audio`（需 `READ_MEDIA_AUDIO`） | MediaStore 列 | 曲目内嵌图（仅首曲读取一次/专辑） | `DATA` 同目录 `.lrc`（尽力而为） |

要点：

- **去重**：同一物理文件既可能出现在 SAF 导入、又可能被媒体库索引到。`dedupe()` 用规范化路径
  （`/storage/emulated/0/` 或 SAF `primary:` 相对路径，统一小写）只保留先扫描到的来源，导入来源优先，
  这样文件夹封面与歌词仍然可用。
- **缓存**：索引持久化 `files/music-data/index.json`；`uri + size + mtime` 未变的曲目直接复用元数据与封面哈希。
- **封面**：内嵌图或文件夹图按内容 SHA-256 存到 `files/music-data/artwork/<hash>.<ext>`，`/api/artwork` 读取缓存。
- **音频读取**：`ContentResolver.openAssetFileDescriptor` 取得可 seek 的 fd 并从 `startOffset + Range.start` 读；
  少数 provider 只给管道流时，回退为一次性缓存到 `cache/audio/<trackId>.<ext>` 再提供 Range。

## 5. 手机竖屏 UI（Web 侧）

- `src/mobile-ui.ts`：挂载 `#mobile-mini`（底部迷你播放条）与 `#mobile-now`（全屏正在播放）、订阅 `MusicPlayer` 状态、
  处理原生事件、播放时调用 `setKeepAwake`、同步主题、原生模式隐藏桌面专属控件、注册 `__rhineBack`。
- `src/mobile.css`：只在 `@media (max-width: 700px)` 生效；使用 `--mobile-mini-h`、`env(safe-area-inset-*)` 保证
  刘海屏与手势条安全区；面板统一为全屏上滑 sheet。
- `src/music-app.ts`：只增加 `import "./mobile.css"` 与 `mountMobileUi(...)` 挂载点，不改变桌面逻辑。
- `src/cover-atlas.ts`：Android WebView（Chromium 113）拒绝加速 canvas 的 `copyTextureToTexture` 上传，
  表现为 `GL_INVALID_VALUE: glCopySubTextureCHROMIUM` 且封面全黑。手机 UA 下改为 `getImageData` CPU 回读像素后
  更新 `DataTexture`（256 px 图块、1024² 选中封面），桌面浏览器仍走 GPU 路径。

## 6. 权限与安全

- 仅申请 `INTERNET`（回环 socket 必需）、`VIBRATE`、`READ_MEDIA_AUDIO`（API ≤32 为 `READ_EXTERNAL_STORAGE`）、
  `POST_NOTIFICATIONS`（仅系统提示）。
- 服务只绑定 `127.0.0.1`，不监听局域网；外部链接交给系统浏览器打开，应用内不跳走。
- 应用数据（索引、歌单、封面缓存、下载音频）位于私有目录，卸载即清除；不读取、不上传用户音乐。
- 直链下载仅接受 http/https，文件名做路径穿越与保留字符清洗，重复文件自动加序号。

## 7. 与 Windows 版的差异

| 能力 | Windows 版 | Android 版 |
| --- | --- | --- |
| 宿主 | WinForms + WebView2 | Activity + Android WebView |
| 音乐服务 | Node.js 子进程 / C# 本地服务 | Java 进程内 `LocalServer` |
| 曲库来源 | 文件系统目录 | 内置演示 / 下载 / SAF / MediaStore |
| 在线资料（MusicBrainz） | 支持 | 不提供（接口返回 400，相关 UI 隐藏） |
| foobar2000 / Beefweb | 支持 | 不提供 |
| 同步歌词 | 支持 | 支持 |
| 歌单 | 支持 | 支持（JSON 持久化） |
| 构建 | `.NET` + WebView2 SDK | aapt2/javac/d8/apksigner 无 Gradle |
