/**
 * 网络下载 / B 站缓存 sheet (phone only).
 *
 * The V2 bridge is entirely event driven: resolveLink() answers through
 * `rhine:resolve`, downloads reuse `rhine:download`, Bilibili imports report
 * through `rhine:root` / `rhine:bili`. Nothing here calls a method that is not
 * on the bridge, and in a plain browser every bridge button is disabled with an
 * explanation instead of silently doing nothing.
 */
import "./mobile-download.css";
import {
  bridge,
  clock,
  looksLikeBilibili,
  looksLikeLink,
  normalizeLink,
  parseBiliStatus,
  type BiliEventDetail,
  type BiliStatus,
  type DownloadEventDetail,
  type ResolveDetail,
  type RootEventDetail,
} from "./mobile-native";

export interface MobileNetworkOptions {
  stage: HTMLElement;
  notify: (message: string) => void;
  loadLibrary: (force?: boolean) => Promise<void> | void;
  reduced: () => boolean;
  isMobile: () => boolean;
}

export interface MobileNetworkHandle {
  open: () => void;
  close: () => boolean;
  isOpen: () => boolean;
}

const HISTORY_KEY = "rhine-music-download-history";
const HISTORY_LIMIT = 20;
const ADB_COMMAND =
  'adb shell "cp -r /sdcard/Android/data/tv.danmaku.bili/download /sdcard/Download/BiliCache"';

type HistoryStatus = "resolved" | "downloading" | "done" | "error";
type HistoryEntry = {
  url: string;
  title: string;
  time: number;
  status: HistoryStatus;
};

const icon = (path: string) =>
  `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const glyph = {
  download: icon('<path d="M12 4v10"/><path d="m7.5 10 4.5 4.5 4.5-4.5"/><path d="M4.5 19h15"/>'),
  close: icon('<path d="m6 6 12 12M18 6 6 18"/>'),
  copy: icon('<rect x="8" y="8" width="11" height="11" rx="1.5"/><path d="M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8"/>'),
  arrow: icon('<path d="M5 12h13"/><path d="m13 7 5 5-5 5"/>'),
};

const esc = (value: string) =>
  value.replace(/[&<>"']/g, (char) =>
    char === "&" ? "&amp;" : char === "<" ? "&lt;" : char === ">" ? "&gt;" : char === '"' ? "&quot;" : "&#39;",
  );

const MARKUP = `<div class="mobile-sheet" id="mobile-net-sheet" role="dialog" aria-modal="true"
  aria-labelledby="mobile-net-title" hidden>
  <div class="mobile-sheet-panel" id="mobile-net-panel">
    <header class="mobile-sheet-head" id="mobile-net-head">
      <span class="mobile-sheet-handle" aria-hidden="true"></span>
      <div class="mobile-sheet-title">
        <small>NETWORK / CACHE</small>
        <h2 id="mobile-net-title">网络下载</h2>
      </div>
      <button type="button" class="mobile-sheet-close" id="mobile-net-close" aria-label="关闭网络下载">
        ${glyph.close}
      </button>
    </header>
    <div class="mobile-sheet-scroll" id="mobile-net-scroll">
      <section class="mobile-net-resolve" aria-label="解析链接">
        <div class="mobile-net-label">
          <span>LINK / URL</span>
          <small id="mobile-net-native-note">在 App 内可用</small>
        </div>
        <div class="mobile-net-field">
          <input id="mobile-net-input" type="url" inputmode="url" autocomplete="off"
            spellcheck="false" enterkeyhint="go" aria-label="音频直链或 B 站链接"
            placeholder="https://… ／ b23.tv ／ BV ／ av">
          <button type="button" class="mobile-net-resolve-button" id="mobile-net-resolve">解析</button>
        </div>
        <p class="mobile-net-tip">音频直链、b23.tv 短链、BV / av 号与带 p= 的播放页都可以解析。</p>
      </section>
      <section class="mobile-net-result" id="mobile-net-result" hidden aria-live="polite">
        <div class="mobile-net-cover" id="mobile-net-cover" aria-hidden="true"><span>♪</span></div>
        <div class="mobile-net-result-copy">
          <small id="mobile-net-kind">AUDIO STREAM</small>
          <strong id="mobile-net-result-title"></strong>
          <span id="mobile-net-result-meta"></span>
        </div>
        <p class="mobile-net-result-note" id="mobile-net-result-note" hidden></p>
        <button type="button" class="mobile-net-download" id="mobile-net-download" hidden>
          <span>开始下载</span><i aria-hidden="true">${glyph.download}</i>
        </button>
      </section>
      <section class="mobile-net-progress" id="mobile-net-progress" hidden aria-label="下载进度">
        <div class="mobile-net-progress-head">
          <small id="mobile-net-progress-phase">CONNECTING</small>
          <b id="mobile-net-progress-pct">0%</b>
        </div>
        <div class="mobile-progress-rail" id="mobile-net-progress-rail" role="progressbar"
          aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i id="mobile-net-progress-fill"></i></div>
        <p class="mobile-net-progress-text" id="mobile-net-progress-text" role="status" aria-live="polite"></p>
      </section>
      <section class="mobile-net-history" aria-label="下载历史">
        <div class="mobile-net-section-head">
          <small>RECENT / ${HISTORY_LIMIT}</small>
          <span>历史记录</span>
          <button type="button" class="mobile-net-quiet" id="mobile-net-history-clear">清空</button>
        </div>
        <div class="mobile-net-history-list" id="mobile-net-history-list"></div>
      </section>
      <section class="mobile-bili" aria-label="B 站缓存">
        <div class="mobile-net-section-head">
          <small>BILIBILI CACHE</small>
          <span>B 站缓存</span>
        </div>
        <div class="mobile-bili-status" id="mobile-bili-status"></div>
        <div class="mobile-bili-actions">
          <button type="button" class="mobile-bili-action" id="mobile-bili-root">Root 导入</button>
          <button type="button" class="mobile-bili-action" id="mobile-bili-folder">选择已导出的缓存目录</button>
        </div>
        <div class="mobile-bili-progress" id="mobile-bili-progress" hidden aria-label="缓存导入进度">
          <div class="mobile-net-progress-head">
            <small id="mobile-bili-phase">COPYING CACHE</small>
            <b id="mobile-bili-pct"></b>
          </div>
          <div class="mobile-progress-rail" id="mobile-bili-rail" role="progressbar"
            aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i id="mobile-bili-fill"></i></div>
          <p class="mobile-net-progress-text" id="mobile-bili-message" role="status" aria-live="polite"></p>
        </div>
        <div class="mobile-bili-help">
          <h3>无需 Root 的三种方式</h3>
          <ol>
            <li><b>ADB 导出</b>：连接电脑执行下方命令，把缓存复制到公共目录。</li>
            <li><b>文件管理器</b>：复制 <code>Android/data/tv.danmaku.bili/download</code> 到 <code>Download/BiliCache</code>。</li>
            <li><b>选择目录</b>：复制完成后点「选择已导出的缓存目录」，选中 BiliCache 即可导入。</li>
          </ol>
          <div class="mobile-adb">
            <code id="mobile-adb-command">${esc(ADB_COMMAND)}</code>
            <button type="button" class="mobile-net-quiet" id="mobile-adb-copy" aria-label="复制 ADB 命令">
              <span class="mobile-adb-copy-icon">${glyph.copy}</span>复制
            </button>
          </div>
          <p class="mobile-bili-foot">导入后会转换为 <code>.m4a</code> 并归入「B站缓存」专辑；转换完成自动刷新曲库。</p>
        </div>
      </section>
    </div>
  </div>
</div>`;

function readHistory(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (!raw) return [];
    const value = JSON.parse(raw) as unknown;
    if (!Array.isArray(value)) return [];
    return value
      .filter(
        (entry): entry is HistoryEntry =>
          !!entry && typeof entry === "object" &&
          typeof (entry as HistoryEntry).url === "string" &&
          ["resolved", "downloading", "done", "error"].includes(
            (entry as HistoryEntry).status,
          ),
      )
      .slice(0, HISTORY_LIMIT);
  } catch {
    return [];
  }
}

function saveHistory(entries: HistoryEntry[]) {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(entries.slice(0, HISTORY_LIMIT)));
  } catch {
    /* Private mode: history is a convenience, never a requirement. */
  }
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "0 B";
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  if (value >= 1024) return `${Math.round(value / 1024)} KB`;
  return `${Math.round(value)} B`;
}

function formatWhen(time: number): string {
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const STATUS_TEXT: Record<HistoryStatus, string> = {
  resolved: "待下载",
  downloading: "下载中",
  done: "已完成",
  error: "失败",
};

export function mountMobileNetwork(options: MobileNetworkOptions): MobileNetworkHandle {
  const { stage } = options;
  const pick = <T extends HTMLElement>(id: string) => {
    const node = stage.querySelector<T>(`#${id}`);
    if (!node) throw new Error(`missing mobile network node: ${id}`);
    return node;
  };

  /* The archive header gets one direct entry. It is hidden above 700 px. */
  const nav = stage.querySelector<HTMLElement>(".music-topnav");
  const entry = document.createElement("button");
  entry.type = "button";
  entry.id = "mobile-net-entry";
  entry.className = "mobile-net-entry";
  entry.setAttribute("aria-label", "网络下载与 B 站缓存");
  entry.title = "网络下载";
  entry.innerHTML = glyph.download;
  const settings = nav?.querySelector<HTMLElement>('[data-action="settings"]');
  if (nav) nav.insertBefore(entry, settings ?? null);
  else stage.append(entry);

  stage.insertAdjacentHTML("beforeend", MARKUP);

  const sheet = pick<HTMLElement>("mobile-net-sheet");
  const head = pick<HTMLElement>("mobile-net-head");
  const scroll = pick<HTMLElement>("mobile-net-scroll");
  const closeButton = pick<HTMLButtonElement>("mobile-net-close");
  const input = pick<HTMLInputElement>("mobile-net-input");
  const resolveButton = pick<HTMLButtonElement>("mobile-net-resolve");
  const nativeNote = pick<HTMLElement>("mobile-net-native-note");
  const result = pick<HTMLElement>("mobile-net-result");
  const cover = pick<HTMLElement>("mobile-net-cover");
  const kindLabel = pick<HTMLElement>("mobile-net-kind");
  const resultTitle = pick<HTMLElement>("mobile-net-result-title");
  const resultMeta = pick<HTMLElement>("mobile-net-result-meta");
  const resultNote = pick<HTMLElement>("mobile-net-result-note");
  const downloadButton = pick<HTMLButtonElement>("mobile-net-download");
  const progressBox = pick<HTMLElement>("mobile-net-progress");
  const progressPhase = pick<HTMLElement>("mobile-net-progress-phase");
  const progressPct = pick<HTMLElement>("mobile-net-progress-pct");
  const progressRail = pick<HTMLElement>("mobile-net-progress-rail");
  const progressFill = pick<HTMLElement>("mobile-net-progress-fill");
  const progressText = pick<HTMLElement>("mobile-net-progress-text");
  const historyList = pick<HTMLElement>("mobile-net-history-list");
  const historyClear = pick<HTMLButtonElement>("mobile-net-history-clear");
  const biliStatusHost = pick<HTMLElement>("mobile-bili-status");
  const biliRoot = pick<HTMLButtonElement>("mobile-bili-root");
  const biliFolder = pick<HTMLButtonElement>("mobile-bili-folder");
  const biliProgress = pick<HTMLElement>("mobile-bili-progress");
  const biliPhase = pick<HTMLElement>("mobile-bili-phase");
  const biliPct = pick<HTMLElement>("mobile-bili-pct");
  const biliRail = pick<HTMLElement>("mobile-bili-rail");
  const biliFill = pick<HTMLElement>("mobile-bili-fill");
  const biliMessage = pick<HTMLElement>("mobile-bili-message");
  const adbCopy = pick<HTMLButtonElement>("mobile-adb-copy");

  let sheetOpen = false;
  let openTimer = 0;
  let resolving = false;
  let resolved: ResolveDetail | undefined;
  let resolveSource = "";
  let resolveError = "";
  let downloadActive = false;
  let downloadReceived = 0;
  let downloadTotal = 0;
  let downloadError = "";
  let downloadDone = false;
  let bili: BiliStatus | undefined;
  let biliBusy = false;
  let biliMessageText = "";
  let biliStage = "";
  let biliOutcome: "idle" | "root" | "progress" | "done" | "error" = "idle";
  let biliCopied = 0;
  let biliTotal = 0;
  let biliConverted = 0;
  let pendingRoot = false;
  let history = readHistory();

  const nativeOutputAvailable = () => {
    const native = bridge();
    return !!(native?.resolveLink || native?.downloadResolved || native?.downloadAudio);
  };

  /* ----- history ----- */

  const remember = (url: string, title: string, status: HistoryStatus) => {
    if (!url) return;
    const now = Date.now();
    const previous = history.find((item) => item.url === url);
    history = [
      { url, title: title || previous?.title || url, time: now, status },
      ...history.filter((item) => item.url !== url),
    ].slice(0, HISTORY_LIMIT);
    saveHistory(history);
  };

  const markHistory = (status: HistoryStatus, title?: string) => {
    if (!resolveSource) return;
    remember(
      resolveSource,
      title || resolved?.title || resolved?.name || resolveSource,
      status,
    );
  };

  const renderHistory = () => {
    historyList.replaceChildren();
    historyClear.disabled = history.length === 0;
    if (!history.length) {
      const empty = document.createElement("p");
      empty.className = "mobile-net-history-empty";
      empty.textContent = "还没有下载记录。解析成功或开始下载后会出现在这里。";
      historyList.append(empty);
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const item of history) {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "mobile-net-history-item";
      row.dataset.historyUrl = item.url;
      const copy = document.createElement("span");
      copy.className = "mobile-net-history-copy";
      const title = document.createElement("strong");
      title.textContent = item.title || item.url;
      const url = document.createElement("small");
      url.textContent = item.url;
      copy.append(title, url);
      const meta = document.createElement("span");
      meta.className = "mobile-net-history-meta";
      const status = document.createElement("i");
      status.dataset.status = item.status;
      status.textContent = STATUS_TEXT[item.status];
      const when = document.createElement("small");
      when.textContent = formatWhen(item.time);
      meta.append(status, when);
      row.append(copy, meta);
      fragment.append(row);
    }
    historyList.append(fragment);
  };

  /* ----- resolve / download rendering ----- */

  const renderNativeAvailability = () => {
    const native = bridge();
    const resolveReady = !!native?.resolveLink;
    const downloadReady = !!(native?.downloadResolved || native?.downloadAudio);
    resolveButton.disabled = resolving || !resolveReady;
    resolveButton.textContent = resolving ? "解析中" : "解析";
    if (!native) {
      nativeNote.textContent = "在 App 内可用";
      nativeNote.dataset.tone = "muted";
    } else if (!resolveReady || !downloadReady) {
      nativeNote.textContent = "当前 App 版本功能不完整，请更新";
      nativeNote.dataset.tone = "warn";
    } else {
      nativeNote.textContent = "原生解析与下载已就绪";
      nativeNote.dataset.tone = "ok";
    }
  };

  const renderResult = () => {
    result.hidden = false;
    result.classList.toggle("is-loading", resolving);
    result.classList.toggle("is-error", !resolving && !!resolveError);
    result.classList.toggle("is-ready", !resolving && !resolveError && !!resolved);
    downloadButton.hidden = resolving || !!resolveError || !resolved;
    cover.replaceChildren();
    if (resolving) {
      kindLabel.textContent = "RESOLVING";
      resultTitle.textContent = "正在解析链接…";
      resultMeta.textContent = "读取标题、作者与音频流";
      resultNote.hidden = true;
      const ring = document.createElement("span");
      ring.className = "mobile-net-scan";
      cover.append(ring);
      return;
    }
    if (resolveError) {
      kindLabel.textContent = "RESOLVE FAILED";
      resultTitle.textContent = "这条链接没有解析成功";
      resultMeta.textContent = looksLikeBilibili(resolveSource)
        ? "请改用 B 站缓存导入，或换一条可公开访问的音频直链。"
        : "请确认链接是可直接访问的音频文件，或改用 B 站缓存导入。";
      resultNote.hidden = false;
      resultNote.textContent = resolveError;
      const mark = document.createElement("span");
      mark.className = "mobile-net-error-mark";
      mark.textContent = "!";
      cover.append(mark);
      return;
    }
    if (!resolved) {
      result.hidden = true;
      return;
    }
    kindLabel.textContent = resolved.kind === "bili"
      ? "BILIBILI AUDIO"
      : "AUDIO STREAM";
    resultTitle.textContent = resolved.title || resolved.name || "未命名音频";
    const parts: string[] = [];
    if (resolved.albumTitle && resolved.albumTitle !== resolved.title) {
      parts.push(resolved.albumTitle);
    }
    if (resolved.artist) parts.push(resolved.artist);
    if (resolved.duration !== undefined && resolved.duration !== "") {
      parts.push(
        typeof resolved.duration === "number"
          ? clock(resolved.duration)
          : String(resolved.duration),
      );
    }
    if (resolved.kind === "bili") parts.push("带 Referer 下载");
    resultMeta.textContent = parts.join("  ·  ");
    resultNote.hidden = true;
    if (resolved.cover) {
      const image = document.createElement("img");
      image.src = resolved.cover;
      image.alt = resolved.title ? `${resolved.title} 封面` : "音频封面";
      image.loading = "lazy";
      image.decoding = "async";
      image.draggable = false;
      image.addEventListener("error", () => {
        if (image.isConnected) image.remove();
        if (!cover.querySelector("span")) {
          const fallback = document.createElement("span");
          fallback.textContent = "♪";
          cover.append(fallback);
        }
      });
      cover.append(image);
    } else {
      const fallback = document.createElement("span");
      fallback.textContent = "♪";
      cover.append(fallback);
    }
    downloadButton.disabled = downloadActive;
    const label = downloadButton.querySelector("span");
    if (label) label.textContent = downloadActive ? "正在下载" : downloadDone ? "再次下载" : "开始下载";
  };

  const renderProgress = () => {
    const show = downloadActive || !!downloadError || downloadDone;
    progressBox.hidden = !show;
    if (!show) return;
    const percent = downloadTotal > 0
      ? Math.min(100, Math.max(0, Math.round((downloadReceived / downloadTotal) * 100)))
      : 0;
    const indeterminate = downloadActive && !downloadTotal;
    progressBox.dataset.tone = downloadError ? "error" : downloadDone ? "done" : "active";
    progressPhase.textContent = downloadError
      ? "DOWNLOAD FAILED"
      : downloadDone
        ? "DOWNLOAD COMPLETE"
        : indeterminate
          ? "CONNECTING"
          : "DOWNLOADING";
    progressPct.textContent = downloadDone
      ? "100%"
      : downloadTotal > 0
        ? `${percent}%`
        : downloadActive
          ? "…"
          : "";
    progressFill.style.transform = `scaleX(${downloadDone ? 1 : percent / 100})`;
    progressRail.classList.toggle("is-indeterminate", indeterminate);
    progressRail.setAttribute("aria-valuenow", String(downloadDone ? 100 : percent));
    progressRail.setAttribute("aria-valuetext", downloadError ? "下载失败" : `${percent}%`);
    progressText.textContent = downloadError
      ? `下载失败：${downloadError}`
      : downloadDone
        ? `已完成 · ${resolved?.title || resolveSource || "音频文件"}，正在刷新曲库`
        : indeterminate
          ? "正在连接音频源…"
          : `已接收 ${formatBytes(downloadReceived)} / ${formatBytes(downloadTotal)}`;
  };

  /* ----- Bilibili cache rendering ----- */

  const biliSupported = () => {
    const native = bridge();
    return !!native && bili?.supported !== false;
  };

  const renderBiliStatus = () => {
    const native = bridge();
    biliRoot.disabled = !native?.requestRoot || biliBusy || !biliSupported();
    biliFolder.disabled = !native?.pickBiliFolder || biliBusy || !biliSupported();
    biliProgress.hidden = biliOutcome === "idle";
    biliProgress.dataset.tone = biliOutcome;
    biliPhase.textContent = biliOutcome === "root"
      ? "CHECKING ROOT"
      : biliOutcome === "progress"
        ? "COPYING CACHE"
        : biliOutcome === "done"
          ? "IMPORT COMPLETE"
          : biliOutcome === "error"
            ? "IMPORT FAILED"
            : "BILIBILI CACHE";
    const percent = biliTotal > 0
      ? Math.min(100, Math.max(0, Math.round((biliCopied / biliTotal) * 100)))
      : 0;
    const finalPercent = biliOutcome === "done" ? 100 : percent;
    biliFill.style.transform = `scaleX(${finalPercent / 100})`;
    biliRail.classList.toggle("is-indeterminate", biliBusy && !biliTotal);
    biliRail.setAttribute("aria-valuenow", String(finalPercent));
    biliPct.textContent = biliOutcome === "done"
      ? "100%"
      : biliTotal > 0
        ? `${percent}%`
        : biliBusy
          ? "…"
          : "";
    const bits: string[] = [];
    if (biliOutcome === "root") bits.push("正在检查 Root 权限…");
    else if (biliBusy) bits.push(
      biliStage === "folder"
        ? "正在读取所选目录…"
        : biliTotal > 0
          ? `正在读取缓存 ${biliCopied} / ${biliTotal} 项`
          : "正在读取 B 站缓存…",
    );
    if (biliConverted > 0) bits.push(`已转换 ${biliConverted} 个音频`);
    if (biliMessageText) bits.push(biliMessageText);
    biliMessage.textContent = bits.join("  ·  ");

    if (!native) {
      biliStatusHost.innerHTML =
        `<p class="mobile-bili-muted">在 App 内可用：安装 Rhine Music for Android 后，可直接读取本机 B 站缓存，或用下方 ADB / 文件管理器方式导出。</p>`;
      return;
    }
    if (bili?.supported === false) {
      biliStatusHost.innerHTML =
        `<p class="mobile-bili-muted">当前 App 版本未提供 B 站缓存导入接口，请更新 App 后再试。下方仍可复制 ADB 导出命令。</p>`;
      return;
    }
    const status = bili;
    if (!status) {
      biliStatusHost.innerHTML =
        `<p class="mobile-bili-muted">尚未读取缓存状态。点「Root 导入」检测权限，或直接选择已导出的目录。</p>`;
      return;
    }
    const root = status.rootAvailable ? "可用" : "不可用";
    const su = status.suGranted ? "已授权" : "未授权";
    const entries = Number.isFinite(status.entries) ? Math.max(0, Number(status.entries)) : 0;
    const manual = status.manualPath?.trim();
    biliStatusHost.innerHTML = `
      <div class="mobile-bili-facts">
        <div><small>ROOT</small><span data-tone="${status.rootAvailable ? "ok" : "muted"}">${root}</span></div>
        <div><small>SU</small><span data-tone="${status.suGranted ? "ok" : "muted"}">${su}</span></div>
        <div><small>CACHE ENTRIES</small><span data-tone="${entries > 0 ? "ok" : "muted"}">${entries} 条</span></div>
      </div>
      <p class="mobile-bili-path">${
        manual
          ? `手动目录：<code>${esc(manual)}</code>`
          : "手动目录：尚未选择。导出后可用「选择已导出的缓存目录」。"
      }</p>
      ${status.message ? `<p class="mobile-bili-muted">${esc(status.message)}</p>` : ""}`;
  };

  const render = () => {
    renderNativeAvailability();
    if (resolving || resolveError || resolved || downloadActive || downloadError || downloadDone) {
      renderResult();
    } else {
      result.hidden = true;
    }
    renderProgress();
    renderHistory();
    renderBiliStatus();
  };

  const refreshBiliStatus = () => {
    const native = bridge();
    if (!native?.biliStatus) {
      bili = undefined;
      render();
      return;
    }
    try {
      bili = parseBiliStatus(native.biliStatus());
    } catch {
      bili = { supported: false, message: "读取缓存状态失败，请稍后重试。" };
    }
    renderBiliStatus();
  };

  /* ----- actions ----- */

  const startResolve = () => {
    const typed = input.value.trim();
    const value = normalizeLink(typed);
    if (!looksLikeLink(value)) {
      options.notify("请输入 http(s) 链接，或 B 站 b23.tv / BV / av 号。");
      input.focus({ preventScroll: true });
      return;
    }
    input.value = value;
    const native = bridge();
    if (!native?.resolveLink) {
      options.notify("在 App 内可用：浏览器预览无法解析网络链接。");
      render();
      return;
    }
    resolveSource = value;
    resolving = true;
    resolved = undefined;
    resolveError = "";
    downloadActive = false;
    downloadDone = false;
    downloadError = "";
    downloadReceived = 0;
    downloadTotal = 0;
    render();
    try {
      native.resolveLink(value);
    } catch (error) {
      resolving = false;
      resolveError = (error as Error).message || "解析请求失败";
      render();
    }
  };

  const startDownload = () => {
    const native = bridge();
    if (!resolved) return;
    const url = resolved.url || input.value.trim();
    if (!url) {
      options.notify("解析结果缺少音频地址，请重新解析。");
      return;
    }
    if (!native?.downloadResolved && !native?.downloadAudio) {
      options.notify("在 App 内可用：浏览器预览无法下载音频。");
      render();
      return;
    }
    downloadActive = true;
    downloadDone = false;
    downloadError = "";
    downloadReceived = 0;
    downloadTotal = 0;
    markHistory("downloading", resolved.title);
    render();
    try {
      if (native.downloadResolved) {
        native.downloadResolved(
          url,
          resolved.name || resolved.title || "audio",
          resolved.referer || "",
        );
      } else {
        native.downloadAudio?.(url, resolved.name || resolved.title || "audio");
      }
    } catch (error) {
      downloadActive = false;
      downloadError = (error as Error).message || "下载请求失败";
      markHistory("error");
      render();
    }
  };

  const startBiliImport = () => {
    const native = bridge();
    if (!native?.importBilibiliCache) {
      biliBusy = false;
      biliStage = "";
      biliOutcome = "error";
      biliMessageText = "当前 App 版本不支持 Root 导入。";
      renderBiliStatus();
      return;
    }
    biliBusy = true;
    biliStage = "import";
    biliOutcome = "progress";
    biliMessageText = "";
    biliCopied = 0;
    biliTotal = 0;
    biliConverted = 0;
    renderBiliStatus();
    try {
      native.importBilibiliCache();
    } catch (error) {
      biliBusy = false;
      biliOutcome = "error";
      biliMessageText = (error as Error).message || "Root 导入失败";
      renderBiliStatus();
    }
  };

  /* ----- sheet open / close ----- */

  const open = () => {
    if (sheetOpen || !options.isMobile()) return;
    window.clearTimeout(openTimer);
    sheetOpen = true;
    sheet.hidden = false;
    stage.dataset.mobileSheet = "net";
    scroll.scrollTop = 0;
    render();
    refreshBiliStatus();
    requestAnimationFrame(() => {
      if (sheetOpen) sheet.classList.add("is-open");
    });
    closeButton.focus({ preventScroll: true });
  };

  const close = (): boolean => {
    if (!sheetOpen) return false;
    sheetOpen = false;
    sheet.classList.remove("is-open");
    delete stage.dataset.mobileSheet;
    const finish = () => {
      if (!sheetOpen) sheet.hidden = true;
    };
    if (options.reduced()) finish();
    else openTimer = window.setTimeout(finish, 320);
    entry.focus({ preventScroll: true });
    return true;
  };

  /* ----- events ----- */

  entry.addEventListener("click", open);
  closeButton.addEventListener("click", () => close());
  resolveButton.addEventListener("click", startResolve);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      startResolve();
    }
  });
  downloadButton.addEventListener("click", startDownload);
  historyClear.addEventListener("click", () => {
    if (!history.length) return;
    history = [];
    saveHistory(history);
    renderHistory();
    options.notify("下载历史已清空。");
  });
  historyList.addEventListener("click", (event) => {
    const row = (event.target as HTMLElement).closest<HTMLElement>("[data-history-url]");
    if (!row?.dataset.historyUrl) return;
    input.value = row.dataset.historyUrl;
    startResolve();
  });
  biliRoot.addEventListener("click", () => {
    const native = bridge();
    if (!native?.requestRoot) return;
    biliMessageText = "";
    biliCopied = 0;
    biliTotal = 0;
    biliConverted = 0;
    if (bili?.rootAvailable && bili.suGranted && native.importBilibiliCache) {
      startBiliImport();
      return;
    }
    pendingRoot = true;
    biliBusy = true;
    biliStage = "root";
    biliOutcome = "root";
    renderBiliStatus();
    try {
      native.requestRoot();
    } catch (error) {
      pendingRoot = false;
      biliBusy = false;
      biliOutcome = "error";
      biliMessageText = (error as Error).message || "Root 检测失败";
      renderBiliStatus();
    }
  });
  biliFolder.addEventListener("click", () => {
    const native = bridge();
    if (!native?.pickBiliFolder) return;
    biliBusy = true;
    biliStage = "folder";
    biliOutcome = "progress";
    biliMessageText = "请选择已导出的 BiliCache 目录…";
    biliCopied = 0;
    biliTotal = 0;
    biliConverted = 0;
    renderBiliStatus();
    try {
      native.pickBiliFolder();
    } catch (error) {
      biliBusy = false;
      biliOutcome = "error";
      biliMessageText = (error as Error).message || "无法打开目录选择器";
      renderBiliStatus();
    }
  });
  adbCopy.addEventListener("click", () => {
    const done = () => options.notify("ADB 命令已复制。");
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(ADB_COMMAND).then(done).catch(() => {
        options.notify("复制失败，请长按上方命令手动复制。");
      });
      return;
    }
    try {
      const area = document.createElement("textarea");
      area.value = ADB_COMMAND;
      area.style.cssText = "position:fixed;left:-9999px;top:0;";
      document.body.append(area);
      area.select();
      document.execCommand("copy");
      area.remove();
      done();
    } catch {
      options.notify("复制失败，请长按上方命令手动复制。");
    }
  });

  document.addEventListener("rhine:resolve", (event) => {
    const detail = (event as CustomEvent<ResolveDetail>).detail;
    if (!detail?.type) return;
    resolving = false;
    if (detail.type === "error") {
      resolved = undefined;
      resolveError = detail.error || "解析失败，请检查链接或改用 B 站缓存导入。";
    } else {
      resolved = { ...detail, type: "done" };
      resolveError = "";
      downloadDone = false;
      downloadError = "";
      downloadActive = false;
      remember(
        resolveSource || detail.url || "",
        detail.title || detail.name || resolveSource,
        "resolved",
      );
    }
    render();
  });

  document.addEventListener("rhine:download", (event) => {
    if (!downloadActive) return;
    const detail = (event as CustomEvent<DownloadEventDetail>).detail;
    if (!detail?.type) return;
    if (detail.type === "progress") {
      downloadReceived = Math.max(0, detail.received ?? 0);
      downloadTotal = Math.max(0, detail.total ?? 0);
      renderProgress();
      return;
    }
    if (detail.type === "error") {
      downloadActive = false;
      downloadError = detail.error || "未知错误";
      markHistory("error");
      renderResult();
      renderProgress();
      renderHistory();
      options.notify(`下载失败：${downloadError}`);
      return;
    }
    downloadActive = false;
    downloadDone = true;
    downloadReceived = detail.total ?? downloadReceived;
    downloadTotal = detail.total ?? downloadReceived;
    markHistory("done");
    renderResult();
    renderProgress();
    renderHistory();
    options.notify("下载完成，正在刷新曲库…");
    void options.loadLibrary(true);
  });

  document.addEventListener("rhine:root", (event) => {
    const detail = (event as CustomEvent<RootEventDetail>).detail;
    const granted = !!detail?.granted;
    if (pendingRoot && granted) {
      pendingRoot = false;
      startBiliImport();
      return;
    }
    pendingRoot = false;
    if (biliBusy) {
      if (biliOutcome === "root" || !granted) {
        biliBusy = false;
        biliStage = "";
        biliOutcome = granted ? "done" : "error";
        biliMessageText = detail?.message ||
          (granted ? "Root 已授权。" : "未获得 Root 权限，可改用 ADB 或手动目录。");
        renderBiliStatus();
      }
      // A granted reply during an active import is just the import thread
      // re-checking su; keep the progress state instead of flashing "done".
    } else if (detail?.message) {
      options.notify(detail.message);
    }
    refreshBiliStatus();
  });

  document.addEventListener("rhine:bili", (event) => {
    const detail = (event as CustomEvent<BiliEventDetail>).detail;
    if (!detail?.type) return;
    if (detail.type === "progress") {
      biliBusy = true;
      biliStage = "import";
      biliOutcome = "progress";
      biliCopied = Math.max(0, detail.copied ?? biliCopied);
      biliTotal = Math.max(0, detail.total ?? biliTotal);
      biliConverted = Math.max(0, detail.converted ?? biliConverted);
      biliMessageText = "";
      renderBiliStatus();
      return;
    }
    if (detail.type === "error") {
      biliBusy = false;
      biliStage = "";
      biliOutcome = "error";
      biliMessageText = `导入失败：${detail.message || "未找到可用的缓存目录"}`;
      renderBiliStatus();
      options.notify(biliMessageText);
      return;
    }
    biliBusy = false;
    biliStage = "";
    biliOutcome = "done";
    biliCopied = detail.total ?? biliCopied;
    biliTotal = detail.total ?? biliTotal;
    biliConverted = detail.converted ?? biliConverted;
    biliMessageText = detail.message ||
      (biliConverted > 0 ? `导入完成：转换 ${biliConverted} 个音频` : "导入完成。");
    renderBiliStatus();
    options.notify("B 站缓存导入完成，正在刷新曲库…");
    refreshBiliStatus();
    void options.loadLibrary(true);
  });

  /* ----- keyboard, swipe, focus ----- */

  document.addEventListener("keydown", (event) => {
    if (!sheetOpen) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopImmediatePropagation();
      close();
      return;
    }
    if (event.key === "Tab") {
      const focusable = [
        ...sheet.querySelectorAll<HTMLElement>(
          "button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex='-1'])",
        ),
      ].filter((node) => node.offsetParent !== null);
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  }, true);

  let swipeStart = 0;
  let swipePointer = -1;
  head.addEventListener("pointerdown", (event) => {
    if (event.target instanceof HTMLElement && event.target.closest("button")) return;
    swipePointer = event.pointerId;
    swipeStart = event.clientY;
  });
  head.addEventListener("pointermove", (event) => {
    if (event.pointerId !== swipePointer) return;
    if (event.clientY - swipeStart > 76) {
      swipePointer = -1;
      close();
    }
  });
  const endSwipe = () => { swipePointer = -1; };
  head.addEventListener("pointerup", endSwipe);
  head.addEventListener("pointercancel", endSwipe);

  const mobileQuery = window.matchMedia("(max-width: 700px)");
  const onViewport = () => {
    if (!mobileQuery.matches && sheetOpen) {
      sheetOpen = false;
      sheet.classList.remove("is-open");
      sheet.hidden = true;
      delete stage.dataset.mobileSheet;
    }
  };
  mobileQuery.addEventListener("change", onViewport);

  render();
  return { open, close, isOpen: () => sheetOpen };
}
