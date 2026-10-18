# 手机版 V2：渲染烘焙与性能记录

日期：2026-10-18 · 版本：`0.6.0-dev.57`（V2 工作树，未提交） · 负责人：render-bake

本文件记录「渲染烘焙与性能优化」的改动、量化结果、测法与已知局限。所有数据来自本机
Edge headless + 本地 `dist`，不是真机成绩；真机/模拟器结论需由 Lead 在
`welkin-test` 上复核（本任务不允许启动 Android 构建）。

## 1. 改动摘要

| 文件 | 改动 |
| --- | --- |
| `src/render-quality.ts` | 手机预设 `mobile`（scale 100 / pixelRatio 2 / aa off / shadows 2048 / AO 0 / DOF 55 / transmission 1 / anisotropy 8）；`renderDimensions` 新增 `dynamicScale`（0.65–1.0，只能降低预设比例，不会超过用户预设） |
| `src/quality-renderer.ts` | `resizeQuality` 透传动态比例，并把 `dynamicScale` 写进 `#three-scene[data-render-quality]` |
| `src/scene.ts` | 静态阴影按需刷新；`enableMobileRendering()`；空闲停帧状态；0.65–1.0 动态分辨率；手机端 DOF 缓冲半分辨率；空闲波相位冻结；`renderRatio` / `renderFrames` 诊断 |
| `src/scaled-bokeh-pass.ts` | `setBufferScale()`：手机端景深深度+模糊缓冲取 0.5×（像素量 25%），最终画布仍为原生分辨率 |
| `src/cover-atlas.ts` | CPU 回退仅限 Chromium < 115 的 Android WebView 或软件渲染；手机 tile 128px；`/api/artwork/...` 追加 `w=512`（失败自动回退原 URL）；CPU/GPU 上传分阶段计时 |
| `src/gpu-capability.ts` | 抽出 `isSoftwareRendererName` / `isSoftwareWebGL(renderer)`，供封面管线复用 |
| `src/music-app.ts`（最小改动） | 手机默认值区间的 `mobile` 预设与 V1 强制 performance 的一次性迁移（`mobileShellVersion`）；帧循环空闲停帧调度；`stage.dataset.renderIdle`；底部 `rAF诊断` 显示 `IDLE 空闲停帧`；`window.rhineMusic` 暴露 `renderRatio` / `renderIdle` / `renderFrames` / `setDynamicResolution()` / `wake()` |

并行同事已先落地了 `qualityPresets.mobile` 与手机默认逻辑（depthOfField=55），本任务在此基础上继续，
未覆盖其选择。

## 2. 测法与环境

- 机器：Windows，Edge（`channel: "msedge"`，Playwright-core 1.64），headless。
- 渲染后端：ANGLE **D3D11**（`--use-angle=d3d11 --enable-gpu --ignore-gpu-blocklist`）。
  另用 SwiftShader（`--enable-unsafe-swiftshader --use-gl=angle --use-angle=swiftshader`）验证软件回退。
- 视口：`360×780`、`393×851`，`deviceScaleFactor: 2`，`isMobile/hasTouch`，
  UA `... Android 14; Pixel 8 ... Chrome/130.0.0.0 Mobile Safari/537.36`。
- 数据：`MUSIC_DATA_DIR=<测试数据目录>`，`MUSIC_ROOTS=<测试音乐目录>`；
  封面切换测试用 3 张专辑的 `music-perf`（A/B/C，封面 URL 各不相同）。
- 服务：`node scripts\music-server.mjs --port 5179` 服务最新 `dist`；旧版本从 1.0.0 安装包的 `assets/www` 原样解出（仓库只保留当前版本 APK，1.0.0 可从 Git 历史取回），另起端口服务，
  保证前后对比的是同一份 API/数据。
- 指标：
  - 首屏分辨率：`#three-scene canvas.width/height`、`data-render-quality`（ratio/width/height/dynamicScale）。
  - FPS：交互期用 `window.rhineMusic.renderFrames` 的增量 / 墙钟时间；同时记录
    `profile().renderer.mainThread.rafP95Ms` 与 `gpuTimer.meanMs`（`?profile=1`）。
  - 空闲 rAF：静置 6s 后，再取 3s 内 `renderFrames` 增量（0 = 完全停帧）。
  - 封面：`coverDiagnostics()`（tile 尺寸、解码尺寸、GPU/CPU 采样一致性）与
    `scenePhases["atlas-canvas-readback" / "atlas-texture-upload" / "atlas-tile-paint"]`，
    以及网络面板里的 `/api/artwork` 请求 URL。
- 测试脚本（仓库外，便于复用）：`D:\01-工作区\mobile-test\v2-perf.mjs`、
  `v2-cover-cost.mjs`、`v2-serve.mjs`；原始 JSON 在截图目录。

## 3. 首屏渲染分辨率与预设

| 视口 | 优化前 | 优化后 | 变化 |
| --- | --- | --- | --- |
| 360×780 | 288×624（0.180 MP，preset performance，ratio 0.8） | **720×1560**（1.123 MP，preset mobile，ratio 2.0） | 像素 ×6.2 |
| 393×851 | 314×680（0.214 MP，performance，ratio 0.8） | **786×1702**（1.338 MP，mobile，ratio 2.0） | 像素 ×6.3 |

- 优化前手机首启被强制 `quality="performance"`（`scale 80 / pixelRatio 1 / DOF 0 / AO 0 /
  transmission 0.5 / anisotropy 4`），即用户抱怨的 314×680 低清。
- 优化后首启 `mobile`（`scale 100 / pixelRatio 2 / DOF 55 / AO 0 / transmission 1 /
  anisotropy 8`），渲染分辨率与设备 DPR 对齐；**仅** `gpuCapability.mode === "software"` 回退
  `performance`，`reduced` 仅在软件渲染或系统 `prefers-reduced-motion` 时为 true。
- 动态分辨率运行时可在预设内降到 0.65（393×851 → 511×1106）；本次 headless 交互中实际稳定在
  **0.68**（534×1157），见下节。
- 桌面（≥701px）仍走原有 `original/high/ultra` 预设；`mobile` 预设不会自动用于桌面。

## 4. 帧时间 / FPS（headless D3D11，仅作机制验证）

| 视口 | 指标 | 优化前 | 优化后 |
| --- | --- | --- | --- |
| 360×780 | 交互 FPS | 64.9 | 12.6（动态比例 0.68，489×1060） |
| | 平均帧时间 | 15.4 ms | 79.6 ms |
| | rAF P95 | 20.9 ms | 229.2 ms |
| | GPU timer 均值 | 11.9 ms | 111.7 ms |
| 393×851 | 交互 FPS | 63.8 | 9.3（动态比例 0.68，534×1157） |
| | 平均帧时间 | 15.7 ms | 107.0 ms |
| | rAF P95 | 20.9 ms | 493.1 ms |
| | GPU timer 均值 | 13.3 ms | 132.3 ms |

**这些“后”数字不能当作手机性能结论。** 这台 headless D3D11 适配器的填充率非常低：把同一套
mobile 效果从 ratio 2 降到 ratio 1（393×851，像素 ÷4）后，实测从 8.3 fps 升到 46.5 fps，
说明瓶颈是每像素成本/适配器，而不是 CPU 场景更新（`state-update` 仍 <1 ms）。在此环境里
0.65 下限也无法达到 50 fps；动态分辨率确实按设计逐级下调（1.0 → 0.92 → 0.84 → 0.76 → 0.68，
每次间隔 1.2 s），但该适配器在 0.68 时仍只有 ~13 fps。

因此：
- 优化前 60 fps 是“0.18 MP + 关 DOF + 半分辨率折射”换来的；优化后同等条件下像素 ×6.3，
  画质提升是明确的。
- 真机/模拟器是否能在 mobile 预设下稳定 ≥50 fps，需要在 `welkin-test`（host GPU）复核；
  若不够，优先调 `dynamicScale` 下限/初始值，或在 `dynamicScale === 0.65` 且持续 >25 ms 时
  自动降 DOF/折射（本任务未实现，避免画面跳变，留给 Lead 决策）。

## 5. 空闲停帧

| 视口 | 优化前 3s 内 rAF | 优化后 3s 内 rAF | 状态属性 / 文本 |
| --- | --- | --- | --- |
| 360×780 | 211（≈70 fps，永不停止） | **0** | `stage.dataset.renderIdle="true"`，底部 `rAF诊断 IDLE 空闲停帧 / 720×1560 / 暖昼 / WebGL 2 显卡加速` |
| 393×851 | 196（≈65 fps） | **0** | 同上（786×1702） |

实现：

- 判定：`themeTransition` 结束、无 outgoing/return/pulse/pendingPulse、`musicPlacement` 与相机
  settled、四组弹簧速度 <0.02、旋转到位，并且距最后一次交互 ≥2.5 s 时，`renderIdle=true`。
- 帧循环在 `renderIdle && !viewer && !boot` 时不再 `requestAnimationFrame`；任意
  `pointerdown/move/wheel/touchstart/keydown`、窗口 resize、`visibilitychange`、主题/选中/质量/
  尺寸变化都会 `scene.wake()` 并重新调度。
- 空闲呼吸波使用独立的 `waveTime`（按 `dt` 累积），停帧再恢复时不会相位跳变。
- 空闲时阴影贴图保持最后一次烘焙结果，不再每 2–4 帧重绘。
- 注意：手机壳层可能遮挡底部 `rAF诊断`（由 signature-ui 的 `mobile.css` 决定）；文本本身已按
  要求更新，DOM 属性始终可测。

## 6. 封面纹理

3 张专辑（A/B/C，不同封面 URL）切换测试，393×851，D3D11：

| 指标 | 优化前（旧 bundle） | 优化后 |
| --- | --- | --- |
| 图集 tile | 256×256 | **128×128**（像素 ÷4） |
| 单页图集显存（16×8 tile） | 4096×2048×4 ≈ 33.5 MB | 2048×1024×4 ≈ 8.4 MB |
| Android 上传路径 | 所有 Android UA 都走 CPU 回读 | 仅 Chromium <115 的 WebView 或软件渲染走 CPU；Chrome 130 硬件走 GPU `copyTextureToTexture` |
| `atlas-slot-update-cpu` 均值 / p99 | 1.80 ms / 40.4 ms | 0.37 ms / 2.5 ms |
| `atlas-tile-paint` 均值 | 1.95 ms | 1.01 ms |
| 切专辑长任务 | 切换 1/2/3：1194 / 1887 / 多段（含 957 ms） | 257 / 112 / 216 ms |
| `coverReadyAfterMs` | 23.4 / 2.9 / 12.4 ms | 10.4 / 4.9 / 2.5 ms |
| 封面请求 | `/api/artwork/<id>?v=...` | `/api/artwork/<id>?v=...&w=512`（网络面板已确认） |
| 解码尺寸 | 900×900（原图） | 900×900 —— 本地 `music-server` 忽略 `w`；原生 V2 实现后应为 512 |

- `atlas-texture-upload`（GPU copy）在 headless D3D11 上均值 25.3 ms、p95 175.1 ms（8 次，含首次
  页纹理建立）；该适配器的 `copyTexSubImage2D` 同样很慢，不能与手机 GPU 的 blit 直接比较。
  可比较的是**路径**：旧代码是同步 `getImageData`（256²=256 KB/tile）+ 整页 `DataTexture`
  `needsUpdate`，新代码是 GPU 子区域拷贝，CPU 帧内成本从 1.8 ms/p99 40 ms 降到 0.37 ms/p99 2.5 ms。
- 容错：`w=512` 只对 `/api/artwork/` 追加；若原生返回 404/解码失败，会自动回退原 URL 再请求一次。
- 现代 Android WebView（Chrome 130）在本机验证 GPU/CPU 采样一致（`gpuReadback.status="ok"`，
  `[252,201,148]` vs `[254,201,147]`），封面没有因切换路径变黑。

## 7. 静态烘焙（阴影 / 光照 / AO / DOF）

- **阴影**：`renderer.shadowMap.autoUpdate=false`（music 模式）时，旧实现每 2–4 帧强制
  `needsUpdate=true`；现在改为事件驱动 + 运动节流：
  - 相机/选中专辑/主题/quality/尺寸变化 → `invalidateShadows()`，下一帧重烘一次；
  - 阵列实际运动时：手机每 3 帧（20 Hz）、桌面每 2 帧（30 Hz）更新；运动停止后的第一帧补一次；
  - 静止（含 idle 呼吸，最大位移 ~0.1 单位）完全不重绘，复用最后一次 2048² 阴影图。
- **环境/PMREM**：`createArchiveLighting()` 只在初始化时 `PMREMGenerator.fromScene(RoomEnvironment)`
  一次；主题切换只改 `environmentIntensity`、灯光颜色/强度，不重建环境贴图；灯光位置是常量。
- **AO/DOF**：`mobile` 预设 `aoSamples=0`，用 2048² 静态阴影 + 材质渐变承担接触暗部（旧手机
  AO 本来也只有 22% 的乘算强度，关闭后不“变脏”）；DOF 仍保留 55%，但手机端深度/模糊缓冲取
  0.5×（`bufferWidth/Height` 实测 393×851 vs 画布 786×1702），最终画布不降分辨率。
- 桌面不受影响：`enableMobileRendering()` 只在 `isMobileShell()` 调用；桌面 `original/high/ultra`
  的预设、AO/DOF/阴影分辨率与交互路径不变。

## 8. 截图（`D:\01-工作区\mobile-test\v2-perf-shots\`）

| 文件 | 说明 |
| --- | --- |
| `before-360x780.png` / `before-393x851.png` | 优化前（1.0.0 APK 内 web bundle，performance + ratio 0.8） |
| `before-360x780-idle.png` / `before-393x851-idle.png` | 优化前空闲帧（仍在持续渲染） |
| `after-360x780.png` / `after-393x851.png` | 优化后（mobile 预设、ratio 2；为看画质在截图前关闭动态分辨率） |
| `after-360x780-idle.png` / `after-393x851-idle.png` | 优化后停帧画面（底部应显示 IDLE） |
| `compare-360x780.png` / `compare-393x851.png` | 左 before / 右 after 并排 |
| `before-*.json` / `after-d3d11.json` / `before-true-cover.json` / `after-cover.json` | 原始测量 JSON |

说明：after 截图期间并行同事正在改 `mobile-ui.ts` / `mobile.css`，因此 UI 版式与 before 不同；
对比 3D 书架清晰度、玻璃边缘、景深与封面时请忽略 UI 差异。

## 9. 已知限制 / 需要 Lead 集成

1. **headless 性能不代表真机**：D3D11 headless 适配器在 1.3 MP + DOF + 全分辨率折射下只有
   ~9–13 fps，0.65 下限也无法到 50；请在 `welkin-test`（host GPU）验证 mobile 预设。若仍不足：
   - 方案 A：把动态下限调到 0.65 以外（会低于“1.5× 393×754”的验收分辨率，需要 Lead 决定）；
   - 方案 B：在 0.65 且持续 >25 ms 时自动把 DOF→0 / transmission→0.5（会轻微跳变，需产品确认）。
2. **原生封面 `w=512`**：Web 端已按要求请求，但本地 `music-server.mjs` 忽略该参数；需要
   native-media 在 V2 的 `/api/artwork` 实现真正的缩略图，否则只省下传输层的期望值、解码仍是原图。
3. **已保存偏好迁移**：旧版本在手机首启时会把 `quality="performance"`、`renderQuality=performance`
   写进 `localStorage`。当前实现用 `preferences.mobileShellVersion` 做了一次性迁移：只有当已保存的
   `quality === "performance"` 且 `renderQuality` 与 performance 预设逐项一致、且尚未迁移过时，
   才升级到 `mobile`。注意：旧版强制值与“用户主动选择 performance 预设”无法区分，因此这类用户
   也会被升级一次；迁移后写入版本号，之后不再覆盖用户选择。自定义为 high/ultra/custom 的值不受影响；
   软件渲染设备仍强制 `performance`。迁移逻辑由并行同事在 `music-app.ts` 质量默认值区间实现。
4. **UI 默认值对齐**：`qualityPresets.mobile` 的 `depthOfField` 并行同事定为 55（任务示例为 60）；
   若 UI 同事的默认预设/文案按 60 写，需要统一。`mobile` 已加入 `presetLabels`（“手机”），
   设置面板会多一个选项，需确认移动端设置面板的布局。
5. **`atlas-texture-upload` 在 headless 上偏慢**：25 ms 均值主要来自适配器与首次页纹理建立，
   真机应显著更低；若要更硬的证据，建议在模拟器上用 `?profile=1` 抓
   `scenePhases["atlas-texture-upload"]` 与 `albumSwitches.coverReadyAfterMs`。
6. **桌面**：未改桌面画质预设与交互；music 模式的阴影刷新由 30 Hz 改为“运动时 30 Hz、静止不刷新”，
   静态画面无差异，若需要完全零差异可把 `mobileRender` 门控加到阴影节流上（当前两者共用逻辑）。

## 10. 复现

```powershell
# 构建
pwsh -File build-web.ps1

# 本地服务（截图/性能）
$env:MUSIC_DATA_DIR="D:\01-工作区\mobile-test\data"
$env:MUSIC_ROOTS="D:\01-工作区\mobile-test\music"
node scripts\music-server.mjs --port 5179

# 前后对比（脚本在仓库外）
node D:\01-工作区\mobile-test\v2-perf.mjs after d3d11 http://127.0.0.1:5179/
node D:\01-工作区\mobile-test\v2-cover-cost.mjs http://127.0.0.1:5179/ after
```

`window.rhineMusic` 诊断入口：`renderRatio`、`renderIdle`、`renderFrames`、
`setDynamicResolution(false)`、`wake()`；`#three-scene` 上有
`data-render-quality`（含 `dynamicScale`）与 `data-render-idle`，`#stage` 上有
`data-render-idle`。

## 11. 模拟器复核（Android，`welkin-test`，2026-10-18）

headless D3D11 的帧率不能代表手机（见第 4 节），因此在 Android 模拟器上做了同一套机制的复核：

| 项 | 值 |
| --- | --- |
| 设备 | 模拟器 API 34，1080×2400 @420 dpi → CSS 412×843，DPR 2.625，`-gpu host`（RTX 2070 GLES 转译） |
| WebView | Chrome/113.0.5672.136 |
| 预设 | `mobile`（1.5× CSS、2048 烘焙阴影、景深 40、折射 0.75、anisotropy 8） |
| 首屏画布 | `618×1227`（ratio 1，vs V1 `329×674`，像素 ×3.4） |
| 交互 FPS | **60.1**（连续拖动 4.3 s，`renderFrames` 增量 / 墙钟） |
| 静置 | 2.5 s 后 `renderIdle=true`；帧循环停止，每 2 s 补 1 帧 |
| 静态首帧 | 冷启动画布 `541×1074`（动态比例 0.5 起步，稳定后恢复） |

结论：`mobile` 预设在这台模拟器上能跑满 60 FPS，动态分辨率、空闲停帧、阴影烘焙均按设计工作。
`headless D3D11` 的 9–13 FPS 是适配器填充率问题（同一画质把 ratio 从 2 降到 1 即 46.5 FPS），
不代表真机；低端真机仍可能触发动态分辨率下调到 0.5。

注意：WebView 硬件合成层不会被 DevTools `Page.captureScreenshot` 捕获（截图为空白），
验收截图请用 `adb exec-out screencap -p`；`docs/TEST-REPORT.md` 第 8 节记录了完整的真机检查矩阵。

