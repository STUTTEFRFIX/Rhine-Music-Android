# Observatory / 观测室

三个 Ogg 文件为本项目原创程序编配的同步循环声部，MP3 是带首尾淡变的独立试听版。配乐无原片采样、无外部录音、无人声。

源谱及合成：`scripts/render-audio.mjs`；音效合成：`src/audio.ts`。
生成数据：`score.json`。随项目采用仓库 LICENSE。

## 打字短音（手机版差异说明）

Windows 上游曾使用用户提供的《明日方舟》特别映像[「莱茵生命：访问」](https://www.bilibili.com/video/BV1rr4y1b7sz/)中三个约 38 ms 的短音；原片短音及其衍生片段**不属于 MIT 授权范围**，上游 NOTICE 亦未获得再分发许可。

本仓库（Rhine Music 手机版）**不再分发**这些受版权保护的采样：

- 已移除 `public/audio/typing-preview.wav`、`public/audio/typing-source.json` 与提取脚本 `scripts/extract-typing-audio.mjs`；
- `src/typing-samples.ts` 改为本项目用纯数学生成的三个 38 ms 衰减音（48 kHz、单声道、16-bit PCM），随 MIT 代码分发；
- 界面上的打字反馈逻辑完全不变，只是音色不同。

`atmosphere.ogg`、`motif.ogg`、`pulse.ogg`、`observatory-preview.mp3` 仍为上游程序编配，按其原始说明随项目 LICENSE 使用。
