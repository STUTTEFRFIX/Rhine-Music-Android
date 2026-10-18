// 在真实 Android 设备/模拟器上验证手机版：API、Range 播放、歌词、歌单、播放器状态。
//
// 前置：
//   1. 应用已安装并启动；
//   2. 已执行 adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>；
//   3. 设备上至少有一张专辑（内置演示专辑即可）。
//
// 运行：node tools/device/smoke-test.mjs [截图输出目录]

import { connect } from "./cdp.mjs";

const outDir = process.argv[2] ?? "device-shots";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (label, value) => console.log(label, JSON.stringify(value, null, 1));
let failures = 0;
const check = (label, condition, detail) => {
  console.log(`${condition ? "PASS" : "FAIL"}  ${label}${detail === undefined ? "" : "  " + JSON.stringify(detail)}`);
  if (!condition) failures++;
};

let session = await connect();

/**
 * The SPA reloads the page when a library scan finishes, which destroys the CDP
 * execution context mid-call. Retry: the app is expected to come back by itself.
 */
async function evaluate(expression) {
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      return await session.evaluate(expression);
    } catch (error) {
      const message = String(error?.message ?? error);
      if (!/context was destroyed|Cannot find context|Inspected target navigated|target closed/i.test(message)) throw error;
      console.log(`  (页面重载，重连后重试 ${attempt}/5)`);
      await sleep(2500);
      try { session.socket.close(); } catch { /* already closed */ }
      session = await connect();
    }
  }
  throw new Error("evaluate failed after reconnect retries");
}

// Wait until the archive shell is ready and any post-scan reload has settled.
for (let i = 0; i < 40; i++) {
  const ready = await evaluate(`(() => {
    const loading = document.querySelector('#music-loading');
    return !!document.querySelector('#stage') && (!loading || loading.hidden || loading.style.display === 'none');
  })()`);
  if (ready === true) break;
  await sleep(1000);
}
await sleep(2000);
const stable = await evaluate(`(async () => {
  const read = async () => (await (await fetch('/api/library')).json()).scan;
  let previous = await read();
  if (previous.running) {
    for (let i = 0; i < 30 && previous.running; i++) {
      await new Promise((r) => setTimeout(r, 500));
      previous = await read();
    }
  }
  await new Promise((r) => setTimeout(r, 1500));
  return (await read()).running === false;
})()`);
if (stable !== true) console.log("  (警告：扫描仍在进行，后续结果可能受重载影响)");

const runtime = await evaluate(`(() => {
  const stage = document.querySelector('#stage');
  return {
    viewport: [innerWidth, innerHeight, devicePixelRatio],
    layout: stage?.dataset.layout,
    native: typeof window.RhineNative === 'object',
    backHandler: typeof window.__rhineBack,
  };
})()`);
log("RUNTIME", runtime);
check("portrait layout", runtime?.layout === "portrait", runtime?.layout);
check("native bridge present", runtime?.native === true);
check("back handler exposed", runtime?.backHandler === "function");

const api = await evaluate(`(async () => {
  const out = {};
  const library = await (await fetch('/api/library')).json();
  out.albums = library.albums.length;
  out.tracks = library.albums.reduce((n, a) => n + a.tracks.length, 0);
  out.roots = library.roots.map((r) => r.path + ':' + r.status);
  const album = library.albums.find((entry) => entry.tracks.length && entry.coverUrl);
  const track = album?.tracks[0];
  if (album && track) {
    out.album = album.title;
    const cover = await fetch(album.coverUrl);
    out.coverStatus = cover.status;
    out.coverBytes = (await cover.arrayBuffer()).byteLength;
    const range = await fetch(track.audioUrl, { headers: { Range: 'bytes=0-1023' } });
    out.rangeStatus = range.status;
    out.rangeBytes = (await range.arrayBuffer()).byteLength;
    out.contentRange = range.headers.get('content-range');
    const lyrics = await (await fetch('/api/lyrics/' + track.id)).json();
    out.lyricsFound = lyrics.found;
    out.trackId = track.id;
  }
  return out;
})()`);
log("API", api);
check("library has albums", (api?.albums ?? 0) > 0, api?.albums);
check("artwork responds 200", api?.coverStatus === 200, api?.coverStatus);
check("audio range 206", api?.rangeStatus === 206, api?.contentRange);
check("range payload 1024 bytes", api?.rangeBytes === 1024, api?.rangeBytes);

const playlists = await evaluate(`(async () => {
  const library = await (await fetch('/api/library')).json();
  const trackId = library.albums.flatMap((a) => a.tracks)[0]?.id;
  const created = await (await fetch('/api/playlists', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: '自动验证歌单' }),
  })).json();
  let added = null;
  if (created.playlist?.id && trackId) {
    added = await (await fetch('/api/playlists/' + created.playlist.id + '/tracks', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trackIds: [trackId] }),
    })).json();
    await fetch('/api/playlists/' + created.playlist.id, { method: 'DELETE' });
  }
  return { created: !!created.playlist?.id, added: added?.added ?? 0 };
})()`);
log("PLAYLISTS", playlists);
check("playlist create + add track", playlists?.created === true && playlists?.added === 1);

// Open an album and play it. The selected shelf card may be any album, so open
// whatever is selected and fall back to the demo album if the tracklist is empty.
const playback = await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  window.__audios = [];
  if (!window.__patchedPlay) {
    window.__patchedPlay = true;
    const original = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      window.__audios.push(this);
      return original.apply(this, arguments);
    };
  }
  if (document.querySelector('#stage')?.dataset.mode !== 'detail') {
    document.querySelector('[data-action=open]')?.click();
    await sleep(2000);
  }
  window.__audios.length = 0;
  document.querySelector('[data-action=play-album-all]')?.click();
  await sleep(3000);
  const audio = window.__audios[window.__audios.length - 1];
  const t1 = audio?.currentTime;
  await sleep(2600);
  return {
    playing: !!audio && !audio.paused,
    advanced: typeof t1 === 'number' && typeof audio?.currentTime === 'number' && audio.currentTime > t1 + 0.2,
    duration: audio?.duration,
    error: audio?.error?.code ?? null,
    miniVisible: document.querySelector('#mobile-mini')?.hidden === false,
    lyric: document.querySelector('#mobile-now-lyric-status')?.textContent ??
      document.querySelector('#music-lyric-current')?.textContent ?? '',
  };
})()`);
log("PLAYBACK", playback);
check("audio element playing", playback?.playing === true);
check("playback time advances", playback?.advanced === true, playback?.duration);
check("no media error", playback?.error === null, playback?.error);
check("mini player visible", playback?.miniVisible === true);

await sleep(600);
const file = await session.shot("smoke-playing", outDir);
console.log("screenshot:", file);

const back = await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const out = {};
  document.querySelector('#mobile-now-close')?.click();
  const close = document.querySelector('#music-panel-root .panel-heading button') ||
    document.querySelector('#music-panel-root [data-action="dismiss-panel"]');
  close?.click();
  await sleep(700);
  out.leaveDetail = window.__rhineBack?.();
  await sleep(1600);
  out.mode = document.querySelector('#stage')?.dataset.mode;
  out.exit = window.__rhineBack?.();
  return out;
})()`);
log("BACK", back);
check("back leaves album detail", back?.leaveDetail === true && back?.mode === "archive", back?.mode);
check("back on shelf returns false (exit allowed)", back?.exit === false);

try { session.socket.close(); } catch { /* already closed */ }
console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
process.exitCode = failures === 0 ? 0 : 1;
