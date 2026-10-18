# 许可与合规说明

本仓库是 [Bong712/Rhine-Music-Windows](https://github.com/Bong712/Rhine-Music-Windows) 的 Android 移植分支，
在 MIT 许可的代码基础上新增手机外壳、本地音乐服务与竖屏界面。**代码开源不等于所有素材都开源**，使用前请阅读本文件
与 [`NOTICE.md`](../NOTICE.md)、[`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)、
[`NOTICE-ANDROID.md`](../NOTICE-ANDROID.md)。

## 1. 代码许可

| 范围 | 许可 | 版权 |
| --- | --- | --- |
| 上游 Windows 版全部代码（`src/`、`scripts/`、`DesktopProgram.cs`、`content/` 等） | MIT | LBEILC、RonaldDeng、Bong712（见 [`LICENSE`](../LICENSE)） |
| 本移植新增的 Android 代码（`android/**`） | MIT | 本仓库维护者（STUTTEFRFIX）与上游作者 |
| 本移植新增/修改的 Web 代码（`src/mobile-ui.ts`、`src/mobile.css`、`src/music-app.ts`、`src/cover-atlas.ts` 等改动） | MIT | 同上 |
| 本文档与 `docs/**` | MIT | 本仓库维护者 |

分发代码或重要部分时，必须保留 [`LICENSE`](../LICENSE) 中的全部版权声明与许可文本；MIT 允许商业使用、修改与再分发，
软件按原样提供、不含担保。

## 2. 非代码资源（重要）

| 资源 | 状态 | 说明 |
| --- | --- | --- |
| `public/assets/*.glb`、`art/*.blend` | **不自动适用 MIT** | 三维档案模型与建模脚本来自上游/原作相关素材，上游 NOTICE 明确不将其视为 MIT 授权的一部分；再分发前需核对并取得必要许可 |
| 界面图标、截图、动图、原作相关演示文本 | **不自动适用 MIT** | 同上，权利归相应权利人 |
| `public/fonts/MiSans-*.woff2` | **MiSans 字体协议**（非 MIT） | 小米字体，随应用原样嵌入；不得作为独立字体商品分发。协议见 `public/fonts/NOTICE.txt`、`public/fonts/MiSans-license.pdf` |
| `public/audio/atmosphere.ogg`、`motif.ogg`、`pulse.ogg`、`observatory-preview.mp3` | 随项目 LICENSE（程序编配，无外部采样） | 上游 `public/audio/README.md` 说明；不含原片采样、无人声 |
| `public/demo-covers/*.png` | 项目生成的抽象图像 | 用于演示封面，不冒充真实唱片 |
| 内置演示专辑（`android/app/src/main/assets/demo/`） | 音频同 `public/audio`；封面同 `public/demo-covers`；歌词为本移植编写 | 仅用于演示，未包含任何商业歌曲或专辑封面 |

本项目**不包含**：商业歌曲、真实专辑封面、用户曲库索引、在线资料缓存。用户导入或下载的音乐文件始终留在用户设备上。

## 3. 本移植对受限素材的处理

上游 Windows 版曾使用《明日方舟》特别映像「莱茵生命：访问」中的三个约 38 ms 短音作为打字音效，
上游 NOTICE 明确这些采样**不属于 MIT 授权范围、来源标注不构成再分发许可**。为保证本仓库可公开分发，本移植：

1. **删除** `public/audio/typing-preview.wav`、`public/audio/typing-source.json`、`scripts/extract-typing-audio.mjs`；
2. 将 `src/typing-samples.ts` 替换为本项目**纯数学生成**的三个 38 ms 衰减音（48 kHz、单声道、16-bit PCM）；
3. 在 `public/audio/README.md` 与 `NOTICE.md` 中记录该差异。

界面逻辑与听感位置不变，音色与原采样不同。若你从上游 Windows 版迁移，请自行评估并替换相应素材。

## 4. 第三方依赖

运行时直接依赖（Three.js、Rolling Number、music-metadata、OpenCC-JS）均为 MIT / Apache-2.0 等宽松许可，
构建工具为 Vite、TypeScript、Prettier 等，版本与许可文本见 [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)、
`public/licenses/`、`package-lock.json`。

Android 侧不引入新的第三方库：WebView 外壳、本地 HTTP 服务、音乐索引全部使用 Android SDK 与 JDK 标准 API。

## 5. 原作与商标

视觉参考为《明日方舟》特别映像「莱茵生命：访问」。相关作品名称、标志、设定、视觉设计、原 PV 及声音的权利
归相应权利人所有。本项目是独立的爱好者工程，**与官方制作方无隶属、赞助或授权合作关系**，不主张原作版权或商标权，
不提供任何官方素材下载。

## 6. 合规使用建议

1. 保留 `LICENSE`、`NOTICE.md`、`NOTICE-ANDROID.md`、`THIRD-PARTY-NOTICES.md` 与 `public/licenses/`；
2. 若要再分发 `.glb` 模型、字体或原作相关图标，请自行确认许可，必要时替换为自建素材；
3. 不要把用户音乐、专辑封面或在线资料缓存打进发行包；
4. 商用或上架应用商店前，请替换自签名证书、核对全部素材权利，并自行承担合规责任。

## 7. 用户导入内容与第三方平台

手机版提供「网络下载」与「B 站缓存导入」两项纯客户端能力。与本仓库的 MIT 代码许可的关系如下：

| 事项 | 说明 |
| --- | --- |
| 内容归属 | 用户下载或从本机缓存导入的音频/视频，版权归原权利人；**不适用本仓库的 MIT 许可**，也不随仓库分发 |
| 项目角色 | 本仓库不提供内容源、不托管内容、不索引第三方曲库，仅是读取用户本机文件或用户自行提供的链接 |
| 与哔哩哔哩 | 无隶属、合作、授权关系；不绕过登录、会员、付费或 DRM；链接解析失败时明确提示 |
| 用户责任 | 下载、缓存解析与使用需遵守所在地区法律与平台用户协议；维护者不对用户行为负责 |
| 隐私 | 所有曲库、封面、下载与缓存文件都保存在应用私有目录，卸载即清除；应用不收集、不上传用户数据 |

如果你在二次分发本仓库的构建产物，请自行评估目标平台关于"下载工具/缓存导入"的合规要求，
并保留应用内的合规提示。

## 8. 联系与下架

若你是权利人并认为仓库中的某个文件不应被分发，请通过
[Issues](https://github.com/STUTTEFRFIX/Rhine-Music-Android/issues) 说明**涉及文件、权利依据与联系方式**，
维护者会核对后移除或替换。合理的下架请求优先于代码可用性。
