// 极简 WebView DevTools 协议客户端。
// 用途：连接 adb forward 出来的 WebView 调试端口，在真实 App 内执行断言。
// 依赖：Node.js 22+（内置全局 fetch / WebSocket），无需第三方包。
// 用法见同目录 README.md。

import { writeFileSync, mkdirSync } from "node:fs";

export async function connect(port = 9222) {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const target = list.find((entry) => entry.type === "page" && entry.url.includes("127.0.0.1"))
    ?? list.find((entry) => entry.type === "page");
  if (!target) throw new Error("No WebView page target. Is the app running and the port forwarded?");

  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = () => reject(new Error("WebSocket connection failed"));
  });

  let nextId = 1;
  const pending = new Map();
  socket.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result);
    }
  };

  const send = (method, params = {}, timeoutMs = 30000) => {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      socket.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (pending.has(id)) {
          pending.delete(id);
          reject(new Error(`CDP timeout: ${method}`));
        }
      }, timeoutMs);
    });
  };

  await send("Runtime.enable");
  await send("Page.enable");

  /** Evaluates an expression inside the page and returns its value. */
  const evaluate = async (expression) => {
    const result = await send("Runtime.evaluate", {
      expression: `(async () => { try { return (${expression}); } catch (error) { return { __error: String(error && error.stack || error) }; } })()`,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true,
    });
    if (result.exceptionDetails) return { __exception: result.exceptionDetails.text };
    return result.result.value;
  };

  const shot = async (name, dir = "device-shots") => {
    mkdirSync(dir, { recursive: true });
    const { data } = await send("Page.captureScreenshot", { format: "png", fromSurface: true });
    writeFileSync(`${dir}/${name}.png`, Buffer.from(data, "base64"));
    return `${dir}/${name}.png`;
  };

  return { socket, send, evaluate, shot, target };
}
