// 抓取 V2 手机界面截图（真机/模拟器，App 内 WebView）。
//
// 前置：安装 1.1.0 APK 并启动；adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
// 运行：node tools/device/capture-v2-screens.mjs [输出目录]

import { connect } from "./cdp.mjs";

const outDir = process.argv[2] ?? "v2-final-shots";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let session = await connect();

async function evaluate(expression) {
  for (let attempt = 1; attempt <= 6; attempt++) {
    try {
      return await session.evaluate(expression);
    } catch (error) {
      const message = String(error?.message ?? error);
      if (!/context was destroyed|Cannot find context|Inspected target navigated|target closed/i.test(message)) throw error;
      await sleep(2500);
      try { session.socket.close(); } catch {}
      session = await connect();
    }
  }
  throw new Error("evaluate failed");
}
const shot = async (name) => {
  const file = await session.shot(name, outDir);
  console.log("shot", file);
};
const log = (label, value) => console.log(label, JSON.stringify(value));

// Wait for a settled shell.
for (let i = 0; i < 40; i++) {
  const ready = await evaluate(`(() => {
    const loading = document.querySelector('#music-loading');
    return !!document.querySelector('#stage') && (!loading || loading.hidden || loading.style.display === 'none');
  })()`);
  if (ready === true) break;
  await sleep(1000);
}
await sleep(2500);

const reset = async () => {
  await evaluate(`(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const close = (selector) => document.querySelector(selector)?.click();
    close('#mobile-net-close');
    close('#mobile-now-close');
    close('#music-panel-root .panel-heading button');
    close('#music-panel-root [data-action="dismiss-panel"]');
    await sleep(700);
    if (document.querySelector('#stage')?.dataset.mode !== 'archive') {
      document.querySelector('#music-detail [data-action=back]')?.click();
      await sleep(1500);
    }
    const day = [...document.querySelectorAll('button[data-theme]')].find((b) => b.dataset.theme === 'day');
    if (day && day.getAttribute('aria-pressed') !== 'true') { day.click(); await sleep(1100); }
    return 'reset';
  })()`);
};

/* 1. 首屏（暖昼） */
await reset();
// Prefer the demo album (ambient column) so the cover card is visible.
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  for (let i = 0; i < 4; i++) {
    const genre = document.querySelector('#genre-name')?.textContent ?? '';
    if (/氛围/.test(genre)) break;
    document.querySelector('[data-action=genre-next]')?.click();
    await sleep(1200);
  }
  return 'genre';
})()`);
await sleep(2200);
await shot("01-home-day");

/* 2. 首屏（深夜） */
await evaluate(`(async () => {
  [...document.querySelectorAll('button[data-theme]')].find((b) => b.dataset.theme === 'night')?.click();
  await new Promise((r) => setTimeout(r, 1800));
  return 'night';
})()`);
await shot("02-home-night");
await evaluate(`(async () => {
  [...document.querySelectorAll('button[data-theme]')].find((b) => b.dataset.theme === 'day')?.click();
  await new Promise((r) => setTimeout(r, 1200));
  return 'day';
})()`);

/* 3. 专辑详情 + 播放 */
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  document.querySelector('[data-action=open]')?.click();
  await sleep(2300);
  document.querySelector('[data-action=play-album-all]')?.click();
  await sleep(2600);
  return 'playing';
})()`);
await shot("03-detail-playing");

/* 4. 正在播放 + 歌词 */
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  document.querySelector('#mobile-mini-info')?.click();
  await sleep(1600);
  return 'now';
})()`);
await shot("04-now-playing");
await evaluate(`(async () => {
  document.querySelector('#mobile-now-lyrics-section')?.scrollIntoView({ block: 'start' });
  await new Promise((r) => setTimeout(r, 1200));
  return 'lyrics';
})()`);
await shot("05-now-lyrics");
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  [...document.querySelectorAll('button[data-theme]')].find((b) => b.dataset.theme === 'night')?.click();
  await sleep(1500);
  return 'night';
})()`);
await shot("06-now-night");
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  document.querySelector('#mobile-now-close')?.click();
  await sleep(900);
  [...document.querySelectorAll('button[data-theme]')].find((b) => b.dataset.theme === 'day')?.click();
  await sleep(1100);
  return 'closed';
})()`);

/* 7. 网络下载面板 */
const openNetwork = async () => {
  const opened = await evaluate(`(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const entry = document.querySelector('[data-action="network"], [data-native-action="network"]');
    entry?.click();
    await sleep(1300);
    return { open: document.querySelector('#mobile-net-sheet')?.hidden === false };
  })()`);
  log("network", opened);
};
await openNetwork();
await shot("07-network-idle");

/* 8. 模拟一次解析成功，展示结果卡 */
await evaluate(`(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  document.dispatchEvent(new CustomEvent('rhine:resolve', { detail: {
    type: 'done', kind: 'bili',
    title: '【官方 MV】Never Gonna Give You Up - Rick Astley',
    artist: '索尼音乐中国', duration: 213,
    cover: '', url: 'https://example.com/audio.m4s',
    referer: 'https://www.bilibili.com/',
  }}));
  await sleep(900);
  return 'resolved';
})()`);
await shot("08-network-resolved");

/* 9. B 站缓存区块 */
await evaluate(`(async () => {
  document.querySelector('.mobile-bili')?.scrollIntoView({ block: 'start' });
  await new Promise((r) => setTimeout(r, 1000));
  return 'bili';
})()`);
await shot("09-network-bili");
await evaluate(`(() => { document.querySelector('#mobile-net-close')?.click(); return 'closed'; })()`);
await sleep(900);

/* 10. 搜索 / 设置 / 库 */
for (const [action, name] of [
  ["search", "10-search"],
  ["settings", "11-settings"],
  ["library", "12-library"],
]) {
  const opened = await evaluate(`(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    document.querySelector('[data-action=${action}]')?.click();
    await sleep(1400);
    return document.querySelector('#music-panel-title')?.textContent ?? null;
  })()`);
  log("panel:" + action, opened);
  await shot(name);
  await evaluate(`(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const close = document.querySelector('#music-panel-root .panel-heading button') ||
      document.querySelector('#music-panel-root [data-action="dismiss-panel"]');
    close?.click();
    await sleep(700);
    return 'closed';
  })()`);
}

try { session.socket.close(); } catch {}
console.log("done");
