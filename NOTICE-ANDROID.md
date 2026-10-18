# 手机版（Android）修改声明

本文件补充 [`NOTICE.md`](NOTICE.md) 与 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，
说明本仓库相对上游 Windows 版 **新增、修改与移除** 的内容及其许可状态。

## 新增

| 路径 | 说明 | 许可 |
| --- | --- | --- |
| `android/**` | Android 原生工程：WebView 外壳、回环 HTTP 服务、音乐索引、歌单存储、构建脚本、图标资源 | MIT |
| `src/mobile-ui.ts` | 手机外壳：底部迷你播放条、全屏正在播放、原生桥调用、返回键处理、原生模式面板裁剪 | MIT |
| `src/mobile.css` | ≤700 px 的竖屏样式（面板 sheet、安全区、播放条、歌词页） | MIT |
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

## 免责声明

本移植不表示 LBEILC、RonaldDeng、Bong712 或《明日方舟》官方参与、认可或为本仓库提供支持。
如权利人认为某些文件不应随本仓库分发，请通过 Issues 说明涉及文件与依据，维护者会核对并移除。
