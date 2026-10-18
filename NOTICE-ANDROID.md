# 手机版（Android）修改声明

本文件补充 [`NOTICE.md`](NOTICE.md) 与 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，
说明本仓库相对上游 Windows 版 **新增、修改与移除** 的内容及其许可状态。

## 新增

| 路径 | 说明 | 许可 |
| --- | --- | --- |
| `android/**` | Android 原生工程：WebView 外壳、回环 HTTP 服务、音乐索引、歌单存储、构建脚本、图标资源 | MIT |
| `src/mobile-ui.ts` | 手机外壳：底部迷你播放条、全屏正在播放、原生桥调用、返回键处理、原生模式面板裁剪 | MIT |
| `src/mobile.css` | ≤700 px 的竖屏样式（面板 sheet、安全区、播放条、歌词页） | MIT |
| `src/mobile-native.ts`、`src/mobile-network.ts`、`src/mobile-download.css` | 网络下载 / B 站缓存面板与原生桥 UI | MIT |
| `android/.../BiliCache.java`、`MediaResolver.java`、`Net.java` | B 站缓存解析/音轨转换、网络链接解析、HTTP 下载器 | MIT |
| `build-web.ps1` | 不依赖 npm/pnpm 脚本运行器的前端构建脚本 | MIT |
| `docs/**` | 构建、架构、测试、许可文档 | MIT |
| `screenshots/**` | 本移植在 Android 模拟器上运行时的截图 | 随仓库，截图中的字体/模型/界面元素各自仍受其原许可约束 |
| `release/**` | 构建产物（APK 与校验和） | 二进制包含上述全部内容，许可状态同本文件与 `NOTICE.md` |
| `tools/device/**` | 模拟器/真机自动化验证脚本（Node.js + WebView DevTools 协议） | MIT |
| `android/app/src/main/assets/demo/**` | 内置演示专辑：音频取自 `public/audio`（程序编配），封面取自 `public/demo-covers`（项目生成），LRC 为本移植编写，清单 `manifest.json` 为本移植编写 | 见 `NOTICE.md` 中对应素材说明；LRC 与清单为 MIT |

## 修改

| 路径 | 修改内容 |
| --- | --- |
| `src/music-app.ts` | 仅新增 `import "./mobile.css"` 与 `mountMobileUi(...)` 挂载点，桌面逻辑不变 |
| `src/cover-atlas.ts` | Android WebView 下封面图集改用 CPU 像素上传（保留 GPU 路径给桌面浏览器） |
| `src/typing-samples.ts` | 改为纯数学生成的三个 38 ms 衰减音，见下节 |
| `public/audio/README.md` | 记录打字短音的替换与移除 |
| `NOTICE.md` | 追加「Android 手机版说明（本仓库）」一节 |
| `README.md` | 替换为手机版说明；上游原文保留在 [`README.upstream.md`](README.upstream.md) |
| `index.html`、`public/manifest.webmanifest` | 未修改（沿用上游） |

## 移除（为可公开分发清理受限素材）

| 路径 | 原因 |
| --- | --- |
| `public/audio/typing-preview.wav` | 来自《明日方舟》原 PV 的短音，上游 NOTICE 明确不属于 MIT、未获再分发许可 |
| `public/audio/typing-source.json` | 上述采样的来源与处理记录 |
| `scripts/extract-typing-audio.mjs` | 上述采样的提取脚本 |

`src/typing-samples.ts` 中的 PCM 数据已替换为本项目生成的合成音（48 kHz、单声道、16-bit PCM、三个 38 ms 衰减音），
不包含任何第三方录音。上游 Windows 版如需原音色，请从其自身仓库获取并自行评估授权。

## 未改动

`public/assets/*.glb`、`art/*.blend`、`public/fonts/MiSans-**`、`public/demo-covers/**`、`content/`、`scripts/`
（除已删除的提取脚本）、`sdk/lib/**`（WebView2，仅 Windows 构建使用）均保持上游原样，权利状态以 `NOTICE.md`、
`THIRD-PARTY-NOTICES.md` 与各目录内许可文本为准。**代码 MIT 不覆盖这些素材。**

## 网络下载与 B 站缓存功能（合规声明）

手机版提供两项用户侧的内容导入能力，**项目本身不提供、不托管、不索引任何内容源**：

1. **网络下载**：用户粘贴自己的直链或公开链接（含 B 站视频页链接）后，应用在本地发起 HTTP 请求下载到应用私有目录。
   下载内容与链接均由用户自行提供，是否下载、如何使用由用户自行判断。
2. **B 站缓存导入**：只读取用户自己设备上已存在的哔哩哔哩客户端缓存文件（`entry.json` + `audio.m4s`），
   在本地完成解析与音轨封装，不访问 B 站内容接口、不绕过登录/会员/付费限制。

| 事项 | 声明 |
| --- | --- |
| 与哔哩哔哩的关系 | 本项目与哔哩哔哩（Bilibili）无隶属、合作、赞助或授权关系；"B 站"仅用于描述兼容的本地文件格式 |
| 内容版权 | 下载或导入的内容版权归原权利人；请仅用于个人合法用途，遵守平台用户协议与当地法律 |
| 再分发 | 用户导入/下载的文件保存在应用私有目录，卸载即清除；项目不收集、不上传、不随仓库分发任何用户内容 |
| 接口来源 | B 站链接解析使用其公开的页面/播放地址接口，失败（登录、地区、会员限制）时给出提示，不尝试绕过 |
| 权利主张 | 如权利人认为该功能描述或实现不当，请通过 Issues 说明，维护者会评估调整 |

## 免责声明

本移植不表示 LBEILC、RonaldDeng、Bong712 或《明日方舟》官方参与、认可或为本仓库提供支持。
如权利人认为某些文件不应随本仓库分发，请通过 Issues 说明涉及文件与依据，维护者会核对并移除。

## 截图素材说明

`README.md` 的界面截图中出现的「【官方 MV】Never Gonna Give You Up - Rick Astley」
封面与曲名来自**测试时从 B 站缓存导入的本地样本**，仅用于演示「B 站缓存导入」功能，
不属于本项目分发的素材，也不随源码或安装包提供；实际使用时请只处理你有权使用的内容。
