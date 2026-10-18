// V2 验收脚本：手机画质/动效默认、空闲渲染、B 站缓存导入、网络下载与解析。
//
// 前置：
//   1. 安装 1.1.0 APK 并启动；
//   2. adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>；
//   3. 如需 B 站导入测试，先把缓存样本放进应用私有目录 files/bili-inbox/（见 docs/BILI-CACHE.md）。
//
// 运行：node tools/device/smoke-test-v2.mjs [截图目录]

import { connect } from "./cdp.mjs";

const outDir = process.argv[2] ?? "device-shots-v2";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (label, value) => console.log(label, JSON.stringify(value, null, 1));
let failures = 0;
const check = (label, condition, detail) => {
  console.log(`${condition ? "PASS" : "FAIL"}  ${label}${detail === undefined ? "" : "  " + JSON.stringify(detail)}`);
  if (!condition) failures++;
};

let session = await connect();

async function evaluate(expression) {
  for (let attempt = 1; attempt <= 6; attempt++) {
    try {
      return await session.evaluate(expression);
    } catch (error) {
      const message = String(error?.message ?? error);
      if (!/context was destroyed|Cannot find context|Inspected target navigated|target closed/i.test(message)) throw error;
      console.log(`  (页面重载，重连重试 ${attempt}/6)`);
      await sleep(2500);
      try { session.socket.close(); } catch {}
      session = await connect();
    }
  }
  throw new Error("evaluate failed after reconnect retries");
}

// Wait for the shell and any initial scan reload to settle.
for (let i = 0; i < 40; i++) {
  const ready = await evaluate(`(() => {
    const loading = document.querySelector('#music-loading');
    return !!document.querySelector('#stage') && (!loading || loading.hidden || loading.style.display === 'none');
  })()`);
  if (ready === true) break;
  await sleep(1000);
}
await sleep(2500);

/* ---------------------------------------------------------------- 画质/动效 */
const quality = await evaluate(`(() => {
  const prefs = JSON.parse(localStorage.getItem('rhine-music-preferences') || '{}');
  const canvas = document.querySelector('.three-scene canvas');
  return {
    viewport: [innerWidth, innerHeight, devicePixelRatio],
    quality: prefs.quality,
    reduced: prefs.reduced,
    scale: prefs.renderQuality?.scale,
    pixelRatio: prefs.renderQuality?.pixelRatio,
    canvas: canvas ? [canvas.width, canvas.height, canvas.clientWidth, canvas.clientHeight] : null,
    layout: document.querySelector('#stage')?.dataset.layout,
    renderIdle: document.querySelector('#stage')?.dataset.renderIdle,
    runtime: document.querySelector('#runtime-info')?.innerText ?? '',
  };
})()`);
log("QUALITY", quality);
check("mobile uses signature preset", quality?.quality === "mobile" || quality?.quality === "original" || quality?.quality === "high", quality?.quality);
check("motion not forced off", quality?.reduced === false, quality?.reduced);
const pixelW = quality?.canvas?.[0] ?? 0;
const cssW = quality?.canvas?.[2] ?? 1;
check("render resolution >= 1.5x CSS width", pixelW >= cssW * 1.5, { pixelW, cssW });
check("no forced software preset on hardware GPU", !/性能/.test(quality?.runtime ?? "") || !/软件渲染/.test(quality?.runtime ?? ""), quality?.runtime);

/* ---------------------------------------------------------------- 空闲停帧 */
const idle = await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  await sleep(2600);
  const stage = document.querySelector('#stage');
  const idle1 = stage?.dataset.renderIdle;
  document.body.dispatchEvent(new PointerEvent('pointermove', { bubbles: true }));
  await sleep(900);
  const idle2 = stage?.dataset.renderIdle;
  return { idle: idle1, afterInteraction: idle2 };
})()`);
log("IDLE", idle);
check("idle rendering flag is reported", idle?.idle === "true" || idle?.idle === "false", idle?.idle);

/* ------------------------------------------------------------ B 站缓存导入 */
const bili = await evaluate(`(async () => {
  const native = window.RhineNative;
  if (!native) return { missing: true };
  const before = await (await fetch('/api/library')).json();
  const out = { status: typeof native.biliStatus === 'function' ? native.biliStatus() : null };
  // The staged inbox is scanned on startup; also allow an explicit import.
  if (typeof native.importBilibiliCache === 'function') {
    window.__biliEvents = [];
    document.addEventListener('rhine:bili', (event) => window.__biliEvents.push(event.detail));
    native.importBilibiliCache();
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 500));
      if (window.__biliEvents.some((e) => e.type === 'done' || e.type === 'error')) break;
    }
    out.events = window.__biliEvents.slice(-4);
  }
  await new Promise((r) => setTimeout(r, 3000));
  const after = await (await fetch('/api/library')).json();
  const album = after.albums.find((entry) => /B站|B 站|bili/i.test(entry.title) || entry.folder?.includes('bili'));
  out.albumsBefore = before.albums.map((a) => a.title);
  out.albumsAfter = after.albums.map((a) => a.title);
  if (album) {
    const track = album.tracks[0];
    const range = await fetch(track.audioUrl, { headers: { Range: 'bytes=0-1023' } });
    out.album = { title: album.title, artist: album.artist, tracks: album.tracks.length, cover: !!album.coverUrl };
    out.rangeStatus = range.status;
    out.rangeBytes = (await range.arrayBuffer()).byteLength;
  }
  return out;
})()`);
log("BILI", bili);
if (bili?.missing) {
  check("native bridge present", false);
} else {
  check("bilibili album imported", !!bili?.album, bili?.albumsAfter);
  check("bilibili track streams via Range", bili?.rangeStatus === 206, bili?.rangeStatus);
}

/* ------------------------------------------------------------ 网络下载解析 */
const download = await evaluate(`(async () => {
  const native = window.RhineNative;
  const out = {};
  if (!native) return { missing: true };
  window.__resolveEvents = [];
  document.addEventListener('rhine:resolve', (event) => window.__resolveEvents.push(event.detail));
  window.__downloadEvents = [];
  document.addEventListener('rhine:download', (event) => window.__downloadEvents.push(event.detail));
  // 1) direct audio URL served by the app's own loopback server
  if (typeof native.resolveLink === 'function') {
    native.resolveLink('http://127.0.0.1:8766/audio/motif.ogg');
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 400));
      if (window.__resolveEvents.some((e) => e.type === 'done' || e.type === 'error')) break;
    }
    out.directResolve = window.__resolveEvents.slice(-2);
  }
  // 2) Bilibili page link (best effort; network dependent)
  window.__resolveEvents.length = 0;
  if (typeof native.resolveLink === 'function') {
    native.resolveLink('https://www.bilibili.com/video/BV1GJ411x7h7');
    for (let i = 0; i < 40; i++) {
      await new Promise((r) => setTimeout(r, 500));
      if (window.__resolveEvents.some((e) => e.type === 'done' || e.type === 'error')) break;
    }
    out.biliResolve = window.__resolveEvents.slice(-2);
  }
  // 3) download the direct stream if resolution succeeded
  const direct = (out.directResolve ?? []).find((e) => e.type === 'done');
  if (direct && typeof native.downloadResolved === 'function') {
    native.downloadResolved(direct.url, 'v2-download-test.ogg', direct.referer ?? '');
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 500));
      if (window.__downloadEvents.some((e) => e.type === 'done' || e.type === 'error')) break;
    }
    out.download = window.__downloadEvents.slice(-3);
    await new Promise((r) => setTimeout(r, 3000));
    const library = await (await fetch('/api/library')).json();
    const album = library.albums.find((entry) => entry.title.includes('下载'));
    out.downloadedAlbum = album ? { title: album.title, tracks: album.tracks.length } : null;
  }
  return out;
})()`);
log("DOWNLOAD", download);
if (!download?.missing) {
  check("direct link resolves", (download?.directResolve ?? []).some((e) => e.type === "done"), download?.directResolve);
  check("bilibili link resolves or fails with a reason", (download?.biliResolve ?? []).length > 0, download?.biliResolve);
  check("download completes", (download?.download ?? []).some((e) => e.type === "done"), download?.download);
  check("downloaded track enters library", !!download?.downloadedAlbum, download?.downloadedAlbum);
}

/* ------------------------------------------------------------------ 截图 */
await sleep(800);
const shot = await session.shot("v2-acceptance", outDir);
console.log("screenshot:", shot);

try { session.socket.close(); } catch {}
console.log(failures === 0 ? "\nALL V2 CHECKS PASSED" : `\n${failures} V2 CHECK(S) FAILED`);
process.exitCode = failures === 0 ? 0 : 1;
