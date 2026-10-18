# 设备端自动化验证

这些脚本通过 **WebView DevTools 协议**在真实 App 内执行断言，验证的是「用户装到手机上的那个包」，
而不是浏览器里的开发版。仅依赖 Node.js 22+（内置 `fetch` / `WebSocket`），无需 `npm install`。

## 前置

```powershell
# 1. 安装并启动 App
adb install -r release\Rhine-Music-Mobile-1.0.0.apk
adb shell am start -n com.rhine.music/.MainActivity

# 2. 转发 WebView 调试端口（应用内已开启 setWebContentsDebuggingEnabled）
$pid = (adb shell pidof com.rhine.music).Trim()
adb forward tcp:9222 localabstract:webview_devtools_remote_$pid
```

## 运行

```powershell
node tools\device\smoke-test.mjs            # 结果写入 device-shots/
node tools\device\smoke-test.mjs my-shots    # 自定义截图目录
```

`smoke-test.mjs` 会检查：

1. 视口为竖屏布局、`RhineNative` 与 `__rhineBack` 存在；
2. `/api/library` 有专辑、`/api/artwork` 返回 200、`/api/audio` 的 `Range` 返回 206 与正确 `Content-Range`；
3. 歌单创建 + 加曲往返；
4. 点「播放全部」后 `<audio>` 处于播放态且 `currentTime` 前进、无媒体错误、迷你播放条出现；
5. 返回键层级：详情 → 专辑架返回 `true`，专辑架返回 `false`（允许退出）。

全部通过时退出码为 0，否则为 1。

## 文件

| 文件 | 说明 |
| --- | --- |
| `cdp.mjs` | 极简 CDP 客户端（连接 `/json` 目标、`Runtime.evaluate`、截图） |
| `smoke-test.mjs` | 上述端到端检查 |

## 备注

- 应用在非 debuggable 构建下 `run-as` 不可用，因此断言都通过 API 与 DOM 完成。
- 模拟器建议 `-gpu host`；软件渲染时截图与交互会明显变慢，但断言逻辑不变。
- 若 `adb forward` 后 `/json` 无目标，说明 WebView 尚未创建或应用进程已退出，重开应用后重新 forward。
