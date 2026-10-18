# 读取 B 站缓存

手机版可以把 **你自己在哔哩哔哩 Android 客户端里缓存的内容** 导入曲库播放。整条链路只读取本机文件，
不上传、不分享、不绕过会员或版权限制；识别的是标准缓存结构（`entry.json` + `audio.m4s`）。

> 本项目与哔哩哔哩无隶属、合作或授权关系。缓存文件的版权归原权利人，请仅用于个人在自己的设备上离线播放，
> 遵守哔哩哔哩用户协议与当地法律，不要分发导入后的文件。

## 1. 缓存结构

Android 客户端缓存通常位于（需要 Root 或 ADB 才能读到，见下节）：

```
/data/media/0/Android/data/tv.danmaku.bili/download/
  <avid>/<cid>/
    entry.json                 # 标题、UP 主、封面、分 P 信息（JSON）
    0/audio.m4s                # DASH 音频轨（fMP4/AAC）
    0/video.m4s                # DASH 视频轨（本应用不导入）
  bangumi/<season_id>/<ep_id>/
    entry.json                 # 番剧剧集信息
    audio.m4s                  # 部分版本无 0/ 子目录
```

其他常见包名：`com.bilibili.app.in`（国际版）。应用会自动探测存在的包名。

`entry.json` 中本应用使用的字段：`title`、`cover`、`owner_name`、`page_data.part`、`page_data.cid`、
`media_type`、`has_dash_audio`。`audio.m4s` 会被转换为应用私有目录里的 `.m4a`（`MediaExtractor` +
`MediaMuxer`，不重新编码，因此速度取决于文件大小、音质无损）。

## 2. 三种导入方式

### 方式 A：应用内 Root 导入（最省事，需要已 Root 设备）

1. 打开 App → 顶栏「网络下载」→「B 站缓存」区块；
2. 点「检测 Root」：应用会执行 `su -c id`，成功后显示「已授权」；
3. 点「Root 导入」：应用用 `su` 把缓存目录复制到私有目录，再逐个解析、转换、入库；
4. 完成后自动刷新曲库，出现「B站缓存」来源；播放、歌词（无歌词时显示占位）与普通歌曲一致。

Root 不可用或系统弹窗被拒绝时，会给出提示并引导使用方式 B/C。

### 方式 B：ADB 导出（无需 Root，需要电脑）

Android 的 shell 用户可以读取 `/sdcard/Android/data`，因此可以用 ADB 把缓存复制到公共目录：

```bash
adb shell "cp -r /sdcard/Android/data/tv.danmaku.bili/download /sdcard/Download/BiliCache"
```

然后在 App 的「B 站缓存」区块点「选择已导出的缓存目录」，选中 `Download/BiliCache` 即可。
（也可以 `adb pull` 到电脑再 `adb push` 回来，效果相同。）

### 方式 C：手动选择已导出的文件夹

如果缓存已经被第三方工具（例如 PC 端的合并工具）导出为 `entry.json + audio.m4s` 结构，
直接把该文件夹拷到手机任意位置，用方式 B 的「选择已导出的缓存目录」导入即可。

## 3. 导入后会发生什么

| 阶段 | 行为 |
| --- | --- |
| 扫描 | 在缓存目录递归查找 `entry.json`，定位同目录或 `0/` 子目录的 `audio.m4s` |
| 去重 | 以「原缓存路径 + 文件大小」为键，重复导入不会产生重复曲目 |
| 转换 | `audio.m4s` → 应用私有目录 `files/bili-media/<hash>.m4a`，后台线程执行并汇报进度 |
| 元数据 | 专辑标题 `entry.json.title`；曲目标题优先 `page_data.part`；作者 `owner_name` |
| 封面 | 尝试联网下载 `entry.json.cover`（带 `Referer: https://www.bilibili.com/`）；失败则显示占位 |
| 入库 | 新来源标签「B站缓存」，来源页可查看条目数；未转换成功的条目会在进度事件中报告 |

## 4. 常见问题

| 现象 | 处理 |
| --- | --- |
| 提示「未找到 Root」 | 使用方式 B/C；或确认 Root 管理器已授权给 Rhine Music |
| 导入 0 条 | 确认目录里是 `entry.json` + `audio.m4s` 结构；旧版「单文件 flv/lua」缓存不支持 |
| 曲目在列表里但无法播放 | 转换失败（文件损坏或被清理）；重新缓存后再导入 |
| 没有封面 | 导入时无网络或封面地址失效；不影响播放 |
| 想同时保留视频 | 本应用只导入音频轨用于音乐播放，视频轨不会复制或删除 |

## 5. 合规边界

- 应用**不提供**任何 B 站视频/音频的下载源，不会绕过登录、会员或付费限制；
- 网络下载功能只处理用户自己提供的直链或公开链接，是否下载由用户自行判断并承担合规责任；
- 导入的内容仅保存在应用私有目录，卸载 App 即清除；应用不收集、不上传任何曲库信息。
