# 渲染模式与后端选项

本项目是 **WebView + WebGL 2** 应用：画面最终由 Chromium 的 WebGL 实现经 ANGLE 转成 GLES，
再交给系统的 GLES/EGL 驱动。因此“换渲染器”分两层：

1. **系统 / 模拟器层**：决定 WebView 拿到哪一条 GLES 驱动；
2. **应用层**：在同一驱动上选择画质预设、分辨率、后处理和帧循环策略。

---

## 1. 应用内选项（设置 → 渲染模式 / 渲染画质）

| 选项 | 取值 | 说明 |
| --- | --- | --- |
| 画质预设 | 省电 / 性能 / 手机 / 原始 / 高 / 极高 / 自定义 | 「手机」是安卓默认；「省电」给低端机/省电模式；精细设置一旦改动自动变为「自定义」 |
| 渲染比例 | 50–200% | 相对屏幕像素的整体缩放，影响细线清晰度 |
| 像素密度上限 | 1× / 1.5× / 2× / 3× | 限制高 DPI 屏上的原生像素倍率 |
| 抗锯齿 | 原始 / SMAA | SMAA 平滑模型边缘，消耗一次后处理 |
| 纹理过滤 | 1×–16× | 各向异性过滤，改善倾斜标签 |
| 透明材质分辨率 | 25%–100% | 玻璃盖板折射缓冲的清晰度，不降低最终画布 |
| 阴影分辨率 | 关闭 / 1024 / 2048 / 4096 | 烘焙阴影贴图，静止时不重绘 |
| 环境遮蔽 | 关闭 / 16 / 32 / 64 采样 | 手机默认关闭（全屏 AO 是最贵的通道） |
| 景深强度 | 0–150% | 手机默认 40%，半分辨率缓冲 |
| 动态分辨率 | 开 / 关 | 帧时间 >22ms 时把内部比例降到 0.5×–1×，只降不升 |
| 空闲停帧 | 开 / 关 | 静止 2.5s 后停 rAF，每 2s 补一帧；触摸/点击立即唤醒 |
| 渲染信息 | 只读 | WebGL 后端、驱动字符串、画布尺寸、内部比例、当前预设与开关 |

推荐：

- **日常**：手机（默认），动态分辨率 + 空闲停帧都开；
- **低端机/发热大**：省电，或手机 + 动态分辨率开；
- **截图/展示**：原始 或 高（动态分辨率可关，画面不会中途变分辨率）；
- **对比测试**：空闲停帧关，保证静止时也在出帧。

---

## 2. 模拟器的 GPU 后端（系统层）

Android 模拟器没有真实手机 GPU，`-gpu` 决定宿主如何提供 GLES/Vulkan：

| 模式 | 含义 | 适用 |
| --- | --- | --- |
| `host` | 借宿主 GPU（gfxstream/Vulkan 转译），最快 | 默认；画质/性能验收 |
| `swiftshader_indirect` | 纯软件光栅化（SwiftShader） | 验证软件渲染回退、兼容性 |
| `angle_indirect` | 经 ANGLE 间接渲染（模拟器版本支持时可用） | 对比 ANGLE 路径 |
| `guest` | 客户机软件渲染 | 仅排障，极慢 |

脚本（会停掉当前模拟器并以指定后端重启）：

```powershell
pwsh -File tools\device\emulator-gpu.ps1 -Mode host
pwsh -File tools\device\emulator-gpu.ps1 -Mode swiftshader
```

验证当前落到了哪条路径：打开 App → 设置 → 渲染模式 → **渲染信息**，或读
`#three-scene[data-gpu-capability]` 的 `mode`（`hardware` / `software`）与 `renderer` 字符串。
软件渲染时应用会自动切到 `performance` 预设并开启 `reduced`（减少动效），这是设计行为。

---

## 3. MobileGL（MG / MGL）能用在 WebView 上吗？

结论：**不能直接替换 WebView 的渲染器**。

- [MobileGL](https://github.com/MobileGL-Dev/MobileGL) 是一个 **LGPL-3.0 的桌面 OpenGL 实现**
  （`DirectGLES` / `DirectVulkan` 后端），定位是“把桌面 GL 调用翻译到 GLES/Vulkan”，
  常见用法是配合 Winlator / FCL 这类容器运行桌面 GL 程序。
- 本机 `D:\MobileGL-build\MobileGL-plugin.apk`（包名 `top.mobilegl.plugin`）是它的
  **FCL 插件**（manifest 里是 `fclPlugin_V2` 元数据）：只对 **FCL 启动的 Minecraft 进程**注入 GL，
  不是系统级 GLES/EGL 驱动，也不 hook 其它 App 的 WebView。
- 想让 WebView 用 ANGLE/Vulkan 等其它后端，可行路径是 WebView 的命令行开关
  （`/data/local/tmp/webview-command-line`，需 debuggable 构建/userdebug 系统），
  或者换模拟器 GPU 后端；对已发布 APK 普通用户不可见。

因此本项目的做法是：**系统层用模拟器 GPU 后端做对比，应用层提供多档预设 + 运行时开关**，
两者组合覆盖“更多渲染模式和选项”的需求，同时不引入不可控的驱动注入。

---

## 4. 性能参考（模拟器 1080×2400 @420dpi → CSS 412×843，`-gpu host`）

| 场景 | 结果 |
| --- | --- |
| 手机预设（1.5× CSS、2048 阴影、景深 40、折射 75%） | 交互 ~60 FPS @ 618×1227 |
| 空闲停帧 | 静止 2.5s 后帧循环停止，每 2s 补 1 帧；触摸立即恢复 |
| 软件渲染（SwiftShader / 软件 WebGL） | 自动 `performance` + `reduced`，画面简化但可操作 |
| headless Edge（ANGLE D3D11，桌面） | 仅作相对对比，不代表手机（详见 `docs/PERF.md`） |

> 模拟器是 GLES 转译路径，数字会随宿主负载波动；真机通常更快，但低端机仍可能触发动态分辨率下调。
