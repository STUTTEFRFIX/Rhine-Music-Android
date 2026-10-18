// 获取一份真实的 B 站缓存测试样本（entry.json + audio.m4s + cover.jpg）。
//
// 用途：验证「B 站缓存导入」链路。脚本只调用公开的 view / playurl 接口并下载
// 一个公开视频的音频流作为测试文件；不做批量抓取。请在遵守 B 站用户协议的前提下
// 仅用于测试你自己的缓存解析逻辑。
//
// 用法：node tools/device/fetch-bili-fixture.mjs [输出目录] [BV号]
// 默认输出到 ./bili-fixture（可用第一个参数覆盖）。

import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const outDir = resolve(process.argv[2] ?? "bili-fixture");
const bvid = process.argv[3] ?? "BV1GJ411x7h7";
const UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
const headers = { "User-Agent": UA, "Referer": "https://www.bilibili.com/" };

mkdirSync(outDir, { recursive: true });

const viewResponse = await fetch(`https://api.bilibili.com/x/web-interface/view?bvid=${bvid}`, { headers });
const view = await viewResponse.json();
if (view.code !== 0) throw new Error(`view API failed: ${view.code} ${view.message}`);
const video = view.data;
console.log("video:", video.title, "|", video.owner?.name, "| pages:", video.pages?.length);

const cid = video.pages[0].cid;
let playurl = null;
for (const url of [
  `https://api.bilibili.com/x/player/playurl?bvid=${bvid}&cid=${cid}&fnval=16&fourk=0&platform=pc`,
  `https://api.bilibili.com/x/player/playurl?avid=${video.aid}&cid=${cid}&fnval=16&fourk=0&platform=html5&high_quality=1`,
]) {
  const response = await fetch(url, { headers });
  const json = await response.json();
  if (json.code === 0 && json.data?.dash?.audio?.length) {
    playurl = json;
    break;
  }
  console.log("playurl attempt failed:", json.code, json.message);
}
if (!playurl) throw new Error("playurl returned no DASH audio stream (the video may require login).");

const audio = playurl.data.dash.audio[0];
console.log("audio stream:", audio.mimeType, audio.codecs, "bandwidth:", audio.bandwidth);
const mediaResponse = await fetch(audio.baseUrl, { headers: { ...headers, Referer: "https://www.bilibili.com/" } });
if (!mediaResponse.ok) throw new Error(`audio download failed: ${mediaResponse.status}`);
const audioBytes = Buffer.from(await mediaResponse.arrayBuffer());
writeFileSync(resolve(outDir, "audio.m4s"), audioBytes);
console.log("saved audio.m4s", audioBytes.length, "bytes");

const entry = {
  media_type: 2,
  has_dash_audio: true,
  is_completed: true,
  total_bytes: audioBytes.length,
  downloaded_bytes: audioBytes.length,
  title: video.title,
  type_tag: video.tname,
  cover: video.pic,
  preference: 2,
  page_data: {
    cid,
    page: 1,
    part: video.title,
    vid: String(video.aid),
    from: "vupload",
    download_title: video.title,
  },
  owner_name: video.owner?.name,
  owner_id: video.owner?.mid,
};
writeFileSync(resolve(outDir, "entry.json"), JSON.stringify(entry, null, 2));
console.log("saved entry.json");

const coverResponse = await fetch(video.pic, { headers });
if (coverResponse.ok) {
  const coverBytes = Buffer.from(await coverResponse.arrayBuffer());
  writeFileSync(resolve(outDir, "cover.jpg"), coverBytes);
  console.log("saved cover.jpg", coverBytes.length, "bytes");
}

console.log("\nstaged fixture at", outDir);
console.log("device path suggestion:");
console.log("  adb shell mkdir -p /sdcard/Download/BiliCache/80433022/137649199/0");
console.log("  adb push <dir>/audio.m4s /sdcard/Download/BiliCache/80433022/137649199/0/audio.m4s");
console.log("  adb push <dir>/entry.json /sdcard/Download/BiliCache/80433022/137649199/entry.json");

