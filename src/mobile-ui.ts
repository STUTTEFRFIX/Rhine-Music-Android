/**
 * Phone shell for Rhine Music.
 *
 * Owns three things the desktop layout does not need: a fixed bottom mini
 * player, the full-screen Now Playing page, and the native bridge entry points
 * (import / download / theme). Everything here stays inert above 700 px so the
 * desktop experience is untouched.
 */
import { activeLyricIndex, type LyricLine } from "./lyrics";
import { bridge, nativeAvailable, nativeToast, type Theme } from "./mobile-native";
import { mountMobileNetwork } from "./mobile-network";
import type { MusicPlayer, MusicPlayerState } from "./music-player";
import type { MusicAlbum } from "./music-types";

export { nativeAvailable };

type MobilePanel = "library" | "search" | "settings" | "playlists" | "queue" | "lyrics";

export interface MobileUiOptions {
  stage: HTMLElement;
  player: MusicPlayer;
  albums: () => MusicAlbum[];
  notify: (message: string) => void;
  openPanel: (panel: MobilePanel) => void;
  closePanel: () => void;
  loadLibrary: (force?: boolean) => Promise<void> | void;
  playAlbum: (trackId?: string) => void;
  revealAlbum: (albumId: string, trackId?: string) => void;
  lyrics: () => { lines: readonly LyricLine[]; status: string };
  reduced: () => boolean;
}

export interface MobileUiHandle {
  /** The music library changed: refresh covers, counts and native hints. */
  libraryChanged: () => void;
  /** Force a transport refresh (cheap, event driven). */
  sync: () => void;
}

let shellQuery: MediaQueryList | undefined;
/** One source of truth for "this is the phone shell" (mirrors mobile.css). */
export function isMobileShell(): boolean {
  shellQuery ??= window.matchMedia("(max-width: 700px)");
  return shellQuery.matches;
}

const icon = (path: string) =>
  `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const glyph = {
  play: icon('<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>'),
  pause: icon('<path d="M7 5h3v14H7zM14 5h3v14h-3z" fill="currentColor" stroke="none"/>'),
  previous: icon(
    '<path d="M19 5v14l-9.4-7Z" fill="currentColor" stroke="none"/><rect x="5" y="5.2" width="1.8" height="13.6" rx=".6" fill="currentColor" stroke="none"/>',
  ),
  next: icon(
    '<path d="M5 5v14l9.4-7Z" fill="currentColor" stroke="none"/><rect x="17.2" y="5.2" width="1.8" height="13.6" rx=".6" fill="currentColor" stroke="none"/>',
  ),
  down: icon('<path d="m5.5 9 6.5 6 6.5-6"/>'),
  queue: icon(
    '<path d="M4 6h12M4 12h12M4 18h7"/><path d="m16 14.5 5 2.5-5 2.5Z" fill="currentColor" stroke="none"/>',
  ),
  download: icon('<path d="M12 4v10"/><path d="m7.5 10 4.5 4.5 4.5-4.5"/><path d="M4.5 19h15"/>'),
  upload: icon('<path d="M12 20V9"/><path d="m7.5 13.5 4.5-4.5 4.5 4.5"/><path d="M4.5 5h15"/>'),
};

const SHELL_MARKUP = `<div class="mobile-now" id="mobile-now" role="dialog" aria-modal="true"
  aria-label="正在播放" hidden>
  <div class="mobile-now-bar">
    <button type="button" class="mobile-now-collapse" id="mobile-now-close" aria-label="收起正在播放">
      ${glyph.down}
    </button>
    <div class="mobile-now-bar-copy">
      <small>NOW PLAYING</small>
      <strong id="mobile-now-album">RHINE MUSIC</strong>
    </div>
    <button type="button" class="mobile-now-bar-queue" id="mobile-now-queue" aria-label="播放队列">
      ${glyph.queue}
    </button>
  </div>
  <div class="mobile-now-scroll" id="mobile-now-scroll">
    <p class="mobile-now-kicker" aria-hidden="true">
      <span>PLAYBACK TERMINAL</span><i></i><span id="mobile-now-code">RHINE / 00</span>
    </p>
    <div class="mobile-now-art">
      <p class="mobile-now-art-meta" aria-hidden="true"><span>FRONT COVER</span><span>GLASS / 01</span></p>
      <div class="mobile-now-cover" id="mobile-now-cover"><span class="mobile-cover-fallback">♪</span></div>
    </div>
    <div class="mobile-now-meta">
      <small class="mobile-now-meta-overline">NOW PLAYING</small>
      <strong id="mobile-now-title">尚未播放</strong>
      <span id="mobile-now-artist">选择一张专辑开始</span>
    </div>
    <div class="mobile-now-seek">
      <div class="mobile-now-times"><span id="mobile-now-current">0:00</span><span id="mobile-now-total">0:00</span></div>
      <div class="mobile-now-track" id="mobile-now-track" role="slider" tabindex="0"
        aria-label="播放进度" aria-valuemin="0" aria-valuemax="0" aria-valuenow="0">
        <i id="mobile-now-fill"></i><b id="mobile-now-knob"></b>
      </div>
    </div>
    <div class="mobile-now-controls">
      <button type="button" id="mobile-now-prev" class="mobile-now-skip" aria-label="上一首">
        ${glyph.previous}
      </button>
      <button type="button" id="mobile-now-toggle" class="mobile-now-play" aria-label="播放" aria-pressed="false">
        <span class="mobile-glyph mobile-glyph-play" aria-hidden="true">${glyph.play}</span>
        <span class="mobile-glyph mobile-glyph-pause" aria-hidden="true">${glyph.pause}</span>
      </button>
      <button type="button" id="mobile-now-next" class="mobile-now-skip" aria-label="下一首">
        ${glyph.next}
      </button>
    </div>
    <div class="mobile-now-actions" role="group" aria-label="播放相关入口">
      <button type="button" id="mobile-now-lyrics">同步歌词</button>
      <button type="button" id="mobile-now-queue-open">播放队列</button>
      <button type="button" id="mobile-now-playlists">我的歌单</button>
      <button type="button" id="mobile-now-locate">定位专辑</button>
    </div>
    <section class="mobile-now-lyrics" id="mobile-now-lyrics-section" aria-label="同步歌词">
      <div class="mobile-now-lyrics-head">
        <small>SYNCHRONIZED LYRICS</small>
        <span id="mobile-now-lyric-status" role="status" aria-live="polite"></span>
      </div>
      <div class="mobile-now-lyric-lines" id="mobile-now-lyric-lines"></div>
    </section>
  </div>
</div>
<div class="mobile-mini" id="mobile-mini" hidden>
  <span class="mobile-mini-line" aria-hidden="true"><i id="mobile-mini-fill"></i></span>
  <button type="button" class="mobile-mini-info" id="mobile-mini-info" aria-label="展开正在播放">
    <span class="mobile-mini-cover" id="mobile-mini-cover"><span class="mobile-cover-fallback">♪</span></span>
    <span class="mobile-mini-copy">
      <strong id="mobile-mini-title">尚未播放</strong>
      <small id="mobile-mini-artist">轻触展开播放器</small>
    </span>
  </button>
  <div class="mobile-mini-controls">
    <button type="button" id="mobile-mini-prev" aria-label="上一首" class="mobile-mini-skip">
      ${glyph.previous}
    </button>
    <button type="button" id="mobile-mini-toggle" class="mobile-mini-play" aria-label="播放" aria-pressed="false">
      <span class="mobile-glyph mobile-glyph-play" aria-hidden="true">${glyph.play}</span>
      <span class="mobile-glyph mobile-glyph-pause" aria-hidden="true">${glyph.pause}</span>
    </button>
    <button type="button" id="mobile-mini-next" aria-label="下一首" class="mobile-mini-skip">
      ${glyph.next}
    </button>
  </div>
</div>`;

/* ------------------------------------------------------------------ */
/* Mount                                                               */
/* ------------------------------------------------------------------ */

export function mountMobileUi(options: MobileUiOptions): MobileUiHandle {
  const { stage, player } = options;
  stage.insertAdjacentHTML("beforeend", SHELL_MARKUP);

  const pick = <T extends HTMLElement>(id: string) =>
    stage.querySelector<T>(`#${id}`) as T;

  const mini = pick<HTMLElement>("mobile-mini");
  const miniInfo = pick<HTMLButtonElement>("mobile-mini-info");
  const miniCover = pick<HTMLElement>("mobile-mini-cover");
  const miniTitle = pick<HTMLElement>("mobile-mini-title");
  const miniArtist = pick<HTMLElement>("mobile-mini-artist");
  const miniFill = pick<HTMLElement>("mobile-mini-fill");
  const miniToggle = pick<HTMLButtonElement>("mobile-mini-toggle");
  const miniPrev = pick<HTMLButtonElement>("mobile-mini-prev");
  const miniNext = pick<HTMLButtonElement>("mobile-mini-next");

  const now = pick<HTMLElement>("mobile-now");
  const nowAlbum = pick<HTMLElement>("mobile-now-album");
  const nowTitle = pick<HTMLElement>("mobile-now-title");
  const nowArtist = pick<HTMLElement>("mobile-now-artist");
  const nowCode = pick<HTMLElement>("mobile-now-code");
  const nowCover = pick<HTMLElement>("mobile-now-cover");
  const nowCurrent = pick<HTMLElement>("mobile-now-current");
  const nowTotal = pick<HTMLElement>("mobile-now-total");
  const nowTrack = pick<HTMLElement>("mobile-now-track");
  const nowFill = pick<HTMLElement>("mobile-now-fill");
  const nowKnob = pick<HTMLElement>("mobile-now-knob");
  const nowToggle = pick<HTMLButtonElement>("mobile-now-toggle");
  const nowPrev = pick<HTMLButtonElement>("mobile-now-prev");
  const nowNext = pick<HTMLButtonElement>("mobile-now-next");
  const nowClose = pick<HTMLButtonElement>("mobile-now-close");
  const nowScroll = pick<HTMLElement>("mobile-now-scroll");
  const nowLyricStatus = pick<HTMLElement>("mobile-now-lyric-status");
  const nowLyricLines = pick<HTMLElement>("mobile-now-lyric-lines");
  const panelRoot = document.querySelector<HTMLElement>("#music-panel-root");
  const network = mountMobileNetwork({
    stage,
    notify: options.notify,
    loadLibrary: options.loadLibrary,
    reduced: options.reduced,
    isMobile: isMobileShell,
  });

  let state: MusicPlayerState = player.state;
  let lyricKey = "";
  let lyricIndex = -2;
  let lyricScrollAt = 0;
  let programmaticScrollAt = 0;
  let closeTimer = 0;
  let frame = 0;
  let seeking = false;

  const clock = (value: number) => {
    const n = Math.max(0, Math.floor(Number.isFinite(value) ? value : 0));
    return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, "0")}`;
  };

  const albumOf = (albumId: string | undefined) =>
    albumId ? options.albums().find((album) => album.id === albumId) : undefined;

  const setCover = (
    host: HTMLElement,
    url: string | undefined,
    label: string,
    key: string,
  ) => {
    if (host.dataset.coverKey === key) return;
    host.dataset.coverKey = key;
    host.replaceChildren();
    if (url) {
      const image = document.createElement("img");
      image.src = url;
      image.alt = label;
      image.decoding = "async";
      image.draggable = false;
      host.append(image);
    } else {
      const fallback = document.createElement("span");
      fallback.className = "mobile-cover-fallback";
      fallback.textContent = "♪";
      host.append(fallback);
    }
  };

  /* ----- player rendering ----- */

  const toggleGlyphs = (button: HTMLButtonElement, playing: boolean) => {
    button.classList.toggle("is-playing", playing);
    button.setAttribute("aria-pressed", String(playing));
    button.setAttribute("aria-label", playing ? "暂停" : "播放");
  };

  const updateProgress = (source: MusicPlayerState = state) => {
    const duration = source.duration || source.currentTrack?.duration || 0;
    const raw = player.playbackTime;
    const time = duration > 0
      ? Math.min(duration, Math.max(0, raw))
      : Math.max(0, raw);
    const ratio = duration > 0 ? time / duration : 0;
    miniFill.style.transform = `scaleX(${ratio})`;
    if (seeking) return;
    nowFill.style.transform = `scaleX(${ratio})`;
    nowKnob.style.left = `${ratio * 100}%`;
    nowCurrent.textContent = clock(time);
    nowTotal.textContent = clock(duration);
    nowTrack.setAttribute("aria-valuenow", String(Math.round(time)));
    nowTrack.setAttribute("aria-valuetext", `${clock(time)} / ${clock(duration)}`);
  };

  const renderTransport = (next: MusicPlayerState) => {
    const track = next.currentTrack;
    const album = albumOf(track?.albumId);
    const hasLibrary = options.albums().length > 0;
    mini.hidden = !hasLibrary;
    stage.dataset.mobileTransport = hasLibrary ? "ready" : "empty";

    const title = track?.title ?? (hasLibrary ? "尚未播放" : "音乐库为空");
    const artist = track
      ? track.artist || album?.artist || "未知艺术家"
      : hasLibrary
        ? "轻触展开播放器"
        : "先导入手机音乐";
    if (miniTitle.textContent !== title) miniTitle.textContent = title;
    if (miniArtist.textContent !== artist) miniArtist.textContent = artist;

    const coverUrl = track ? album?.coverUrl : undefined;
    setCover(
      miniCover,
      coverUrl,
      track ? `${track.title} 封面` : "封面",
      track ? `mini:${track.albumId}` : "mini:empty",
    );
    setCover(
      nowCover,
      coverUrl,
      track ? `${track.title} 封面` : "封面",
      track ? `now:${track.albumId}` : "now:empty",
    );

    const albumLabel = album ? album.title : track ? track.artist : "RHINE MUSIC";
    if (nowAlbum.textContent !== albumLabel) nowAlbum.textContent = albumLabel;
    if (nowTitle.textContent !== title) nowTitle.textContent = title;
    if (nowArtist.textContent !== artist) nowArtist.textContent = artist;
    const queueTotal = next.queue.length;
    const queueIndex = track && next.currentIndex >= 0 ? next.currentIndex + 1 : 0;
    const code = track
      ? `TRACK ${String(queueIndex).padStart(2, "0")} / ${String(queueTotal).padStart(2, "0")}`
      : "RHINE / 00";
    if (nowCode.textContent !== code) nowCode.textContent = code;

    toggleGlyphs(miniToggle, next.playing);
    toggleGlyphs(nowToggle, next.playing);
    // Keep the screen awake while audio is playing in the Android shell.
    bridge()?.setKeepAwake?.(next.playing);
    const canSkipPrevious = !!track && next.currentIndex > 0;
    const canSkipNext = !!track && next.currentIndex >= 0 &&
      next.currentIndex < next.queue.length - 1;
    miniPrev.disabled = !canSkipPrevious;
    nowPrev.disabled = !canSkipPrevious;
    miniNext.disabled = !canSkipNext;
    nowNext.disabled = !canSkipNext;
    miniToggle.disabled = !hasLibrary;
    nowToggle.disabled = !hasLibrary;

    const duration = next.duration || track?.duration || 0;
    nowTrack.setAttribute("aria-valuemax", String(Math.round(duration)));
    nowTrack.classList.toggle("is-empty", duration <= 0);
    updateProgress(next);
    renderLyrics(false);
  };

  /* ----- lyrics ----- */

  const renderLyrics = (force: boolean) => {
    const { lines, status } = options.lyrics();
    const key = `${lines.length}\u0000${lines[0]?.text ?? ""}\u0000${status}`;
    if (!force && key === lyricKey) return;
    lyricKey = key;
    lyricIndex = -2;
    nowLyricStatus.textContent = lines.length
      ? `${lines.length} LINES`
      : status.length > 12
        ? "NO LRC"
        : status;
    nowLyricLines.replaceChildren();
    if (!lines.length) {
      const empty = document.createElement("p");
      empty.className = "mobile-now-lyric-empty";
      empty.textContent = status;
      nowLyricLines.append(empty);
      return;
    }
    const fragment = document.createDocumentFragment();
    lines.forEach((line, index) => {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "mobile-now-lyric";
      row.dataset.lyricIndex = String(index);
      row.textContent = line.text;
      fragment.append(row);
    });
    nowLyricLines.append(fragment);
  };

  const updateLyricPosition = () => {
    if (now.hidden) return;
    const { lines } = options.lyrics();
    if (!lines.length) return;
    const index = activeLyricIndex(lines, player.playbackTime);
    if (index === lyricIndex) return;
    lyricIndex = index;
    const rows = nowLyricLines.querySelectorAll<HTMLElement>(".mobile-now-lyric");
    rows.forEach((row, rowIndex) => row.classList.toggle("active", rowIndex === index));
    const active = index >= 0 ? rows[index] : undefined;
    if (!active) return;
    // Never pull the page away from the artwork: only follow the lyrics once
    // the listener has scrolled down to them.
    const section = pick<HTMLElement>("mobile-now-lyrics-section").getBoundingClientRect();
    const viewport = nowScroll.getBoundingClientRect();
    if (section.top > viewport.bottom - 90) return;
    if (Date.now() - lyricScrollAt < 2200) return;
    programmaticScrollAt = Date.now();
    active.scrollIntoView({
      block: "center",
      behavior: options.reduced() ? "auto" : "smooth",
    });
  };

  /* ----- animation frame loop ----- */

  const needsFrame = () => {
    if (document.hidden) return false;
    return !now.hidden || state.playing;
  };
  const tick = () => {
    frame = 0;
    updateProgress();
    updateLyricPosition();
    if (needsFrame()) frame = requestAnimationFrame(tick);
  };
  const requestTick = () => {
    if (!frame) frame = requestAnimationFrame(tick);
  };

  /* ----- Now Playing open / close ----- */

  const openNow = () => {
    window.clearTimeout(closeTimer);
    const wasHidden = now.hidden;
    now.hidden = false;
    stage.dataset.mobileNow = "open";
    if (wasHidden) {
      renderTransport(state);
      renderLyrics(true);
      nowScroll.scrollTop = 0;
      requestAnimationFrame(() => now.classList.add("is-open"));
    } else {
      now.classList.add("is-open");
    }
    requestTick();
    nowClose.focus({ preventScroll: true });
    bridge()?.vibrate?.();
  };

  const closeNow = () => {
    if (now.hidden) return;
    now.classList.remove("is-open");
    delete stage.dataset.mobileNow;
    window.clearTimeout(closeTimer);
    const finish = () => {
      if (!now.classList.contains("is-open")) now.hidden = true;
    };
    if (options.reduced()) finish();
    else closeTimer = window.setTimeout(finish, 320);
    miniInfo.focus({ preventScroll: true });
  };

  /* ----- transport wiring ----- */

  const playPause = () => {
    if (state.currentTrack || state.queue.length) void player.toggle();
    else options.playAlbum();
  };
  const skip = (direction: -1 | 1) => {
    if (direction < 0) void player.previous();
    else void player.next();
  };

  for (const button of [miniToggle, nowToggle]) button.addEventListener("click", playPause);
  miniPrev.addEventListener("click", () => skip(-1));
  nowPrev.addEventListener("click", () => skip(-1));
  miniNext.addEventListener("click", () => skip(1));
  nowNext.addEventListener("click", () => skip(1));
  miniInfo.addEventListener("click", openNow);
  nowClose.addEventListener("click", closeNow);
  pick("mobile-now-queue").addEventListener("click", () => options.openPanel("queue"));
  pick("mobile-now-queue-open").addEventListener("click", () => options.openPanel("queue"));
  pick("mobile-now-playlists").addEventListener("click", () => options.openPanel("playlists"));
  pick("mobile-now-lyrics").addEventListener("click", () => {
    programmaticScrollAt = Date.now();
    pick<HTMLElement>("mobile-now-lyrics-section").scrollIntoView({
      block: "start",
      behavior: options.reduced() ? "auto" : "smooth",
    });
  });
  pick("mobile-now-locate").addEventListener("click", () => {
    const track = state.currentTrack;
    if (!track) {
      options.notify("先播放一首歌曲，再定位它的专辑。");
      return;
    }
    closeNow();
    options.revealAlbum(track.albumId, track.id);
  });

  nowLyricLines.addEventListener("click", (event) => {
    const row = (event.target as HTMLElement).closest<HTMLElement>("[data-lyric-index]");
    if (!row) return;
    const index = Number(row.dataset.lyricIndex);
    const line = options.lyrics().lines[index];
    if (line) player.seek(line.time);
  });

  /* ----- seek bar (pointer driven, never scrolls the page) ----- */

  const seekTo = (clientX: number) => {
    const rect = nowTrack.getBoundingClientRect();
    if (rect.width <= 0) return;
    const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const duration = state.duration || state.currentTrack?.duration || 0;
    if (duration <= 0) return;
    player.seek(duration * ratio);
    nowFill.style.transform = `scaleX(${ratio})`;
    nowKnob.style.left = `${ratio * 100}%`;
    nowCurrent.textContent = clock(duration * ratio);
  };
  nowTrack.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    seeking = true;
    nowTrack.classList.add("is-seeking");
    nowTrack.setPointerCapture(event.pointerId);
    seekTo(event.clientX);
    event.preventDefault();
  });
  nowTrack.addEventListener("pointermove", (event) => {
    if (seeking) seekTo(event.clientX);
  });
  const endSeek = (event: PointerEvent) => {
    if (!seeking) return;
    seeking = false;
    nowTrack.classList.remove("is-seeking");
    if (nowTrack.hasPointerCapture(event.pointerId))
      nowTrack.releasePointerCapture(event.pointerId);
    requestTick();
  };
  nowTrack.addEventListener("pointerup", endSeek);
  nowTrack.addEventListener("pointercancel", endSeek);
  nowTrack.addEventListener("keydown", (event) => {
    const duration = state.duration || state.currentTrack?.duration || 0;
    if (!duration) return;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      player.seek(
        Math.max(
          0,
          Math.min(duration, player.playbackTime + (event.key === "ArrowLeft" ? -5 : 5)),
        ),
      );
      requestTick();
    }
  });

  /* ----- swipe down: close (Now Playing bar, panel heading) ----- */

  const swipeDown = (host: HTMLElement, onClose: () => void) => {
    let startY = 0;
    let startX = 0;
    let pointerId = -1;
    host.addEventListener("pointerdown", (event) => {
      if (event.target instanceof HTMLElement && event.target.closest("button[data-theme]"))
        return;
      pointerId = event.pointerId;
      startY = event.clientY;
      startX = event.clientX;
    });
    host.addEventListener("pointermove", (event) => {
      if (event.pointerId !== pointerId) return;
      if (event.clientY - startY > 84 && Math.abs(event.clientX - startX) < 70) {
        pointerId = -1;
        onClose();
      }
    });
    const stop = () => { pointerId = -1; };
    host.addEventListener("pointerup", stop);
    host.addEventListener("pointercancel", stop);
  };
  const nowBar = now.querySelector<HTMLElement>(".mobile-now-bar");
  if (nowBar) swipeDown(nowBar, closeNow);

  nowScroll.addEventListener("scroll", () => {
    if (Date.now() - programmaticScrollAt > 700) lyricScrollAt = Date.now();
  }, { passive: true });

  /* ----- Escape while Now Playing is open ----- */

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || now.hidden) return;
    event.stopPropagation();
    event.preventDefault();
    closeNow();
  }, true);

  /* ----- player subscription ----- */

  player.subscribe((next) => {
    const trackChanged = next.currentTrack?.id !== state.currentTrack?.id;
    state = next;
    renderTransport(next);
    if (trackChanged) {
      lyricKey = "";
      renderLyrics(true);
      lyricIndex = -2;
    }
    requestTick();
  });

  /* ----- archive plate: a quiet switch cue on the glass slab ----- */

  const callout = document.querySelector<HTMLElement>(".album-callout");
  const selectionTitle = document.querySelector<HTMLElement>("#selection-title");
  let switchTimer = 0;
  let appliedTitle = selectionTitle?.getAttribute("aria-label") ?? "";
  if (callout && selectionTitle) {
    const observer = new MutationObserver(() => {
      const next = selectionTitle.getAttribute("aria-label") ?? selectionTitle.textContent ?? "";
      if (next === appliedTitle) return;
      appliedTitle = next;
      if (options.reduced()) return;
      window.clearTimeout(switchTimer);
      callout.classList.remove("is-switching");
      // Restart the entrance cue even when two changes land in one frame.
      void callout.offsetWidth;
      callout.classList.add("is-switching");
      switchTimer = window.setTimeout(() => callout.classList.remove("is-switching"), 380);
    });
    observer.observe(selectionTitle, { attributes: true, attributeFilter: ["aria-label"] });
  }

  /* ----- native theme sync ----- */

  const applyNativeTheme = (theme: string | undefined) => {
    if (theme === "day" || theme === "dusk" || theme === "night")
      bridge()?.setTheme?.(theme);
  };
  applyNativeTheme(stage.dataset.theme);
  document.addEventListener("click", (event) => {
    const button = (event.target as HTMLElement).closest<HTMLElement>("button[data-theme]");
    if (button?.dataset.theme) applyNativeTheme(button.dataset.theme);
  }, true);

  /* ----- native import / network entry ----- */

  const views: Array<() => void> = [];
  let importPending = false;

  const createNativeView = (variant: "empty" | "panel"): HTMLElement => {
    const root = document.createElement("div");
    root.className = `mobile-native-block mobile-native-${variant}`;
    root.innerHTML = `<p class="mobile-native-note"></p>
      <div class="mobile-native-actions">
        <button type="button" class="mobile-native-primary" data-native-action="import">
          <span class="mobile-native-icon">${glyph.upload}</span>导入手机音乐
        </button>
        <button type="button" class="mobile-native-primary ghost" data-native-action="network">
          <span class="mobile-native-icon">${glyph.download}</span>网络下载 / B 站缓存
        </button>
      </div>
      <p class="mobile-native-hint" role="status" aria-live="polite"></p>
      ${
        variant === "empty"
          ? '<button type="button" class="mobile-native-quiet demo" data-native-action="demo">先查看演示封面</button>'
          : ""
      }`;
    const note = root.querySelector<HTMLElement>(".mobile-native-note")!;
    const hint = root.querySelector<HTMLElement>(".mobile-native-hint")!;

    views.push(() => {
      const native = bridge();
      note.hidden = false;
      note.textContent = variant === "empty"
        ? "把手机里的音乐导入这座档案馆，或从网络直链 / B 站缓存下载。曲目会以专辑卡片出现在这里。"
        : "从这里导入设备音乐，或打开网络下载与 B 站缓存。完成后自动刷新曲库。";
      hint.hidden = false;
      hint.dataset.tone = native ? "ok" : "muted";
      hint.textContent = native
        ? "原生导入、网络解析与 B 站缓存已就绪。"
        : "在 App 内可用：浏览器预览不能导入设备音乐或读取 B 站缓存。";
    });

    root.addEventListener("click", (event) => {
      const action = (event.target as HTMLElement)
        .closest<HTMLElement>("[data-native-action]")?.dataset.nativeAction;
      if (!action) return;
      if (action === "import") startImport();
      else if (action === "network") network.open();
      else if (action === "demo") {
        document.querySelector<HTMLButtonElement>('#music-empty [data-action="demo"]')?.click();
      }
    });

    return root;
  };

  const syncViews = () => {
    for (const view of views) view();
  };

  const startImport = () => {
    const native = bridge();
    if (!native?.pickMusicFolder) {
      options.notify("在 App 内可用：手机 App 里可以直接导入设备音乐。");
      syncViews();
      return;
    }
    importPending = true;
    if (
      native.hasAudioPermission &&
      native.hasAudioPermission() === false &&
      native.requestAudioPermission
    ) {
      native.requestAudioPermission();
      options.notify("请在系统提示中允许读取音乐，然后选择文件夹。");
      return;
    }
    nativeToast("正在打开系统文件夹选择器…");
    native.pickMusicFolder();
  };

  const emptyHost = document.querySelector<HTMLElement>("#music-empty");
  if (emptyHost) emptyHost.append(createNativeView("empty"));

  document.addEventListener("rhine:native", (event) => {
    const detail = (event as CustomEvent<{ type?: string }>).detail;
    const type = detail?.type;
    if (type !== "library-changed" && type !== "imported" && type !== "ready") return;
    if (type !== "ready")
      options.notify(
        type === "imported" ? "已导入手机音乐，正在刷新曲库…" : "音乐库已更新。",
      );
    importPending = false;
    void options.loadLibrary(true);
    syncViews();
  });
  document.addEventListener("rhine:permission", (event) => {
    const granted = (event as CustomEvent<{ granted?: boolean }>).detail?.granted;
    if (!granted) {
      importPending = false;
      options.notify("未获得音乐读取权限，可稍后在系统设置中开启。");
      return;
    }
    if (!importPending) return;
    importPending = false;
    bridge()?.pickMusicFolder?.();
  });

  /* ----- the library sheet gets the same import entry ----- */

  if (panelRoot) {
    const observer = new MutationObserver(() => {
      if (!isMobileShell()) return;
      hideDesktopOnly();
      const body = document.querySelector<HTMLElement>("#panel-body");
      const title = document.querySelector<HTMLElement>("#music-panel-title")?.textContent;
      if (!body || title !== "本地音乐库") return;
      if (body.querySelector(".mobile-native-block")) return;
      body.prepend(createNativeView("panel"));
      syncViews();
    });
    observer.observe(panelRoot, { childList: true });
  }

  /* ----- panel sheet: swipe down closes ----- */

  document.addEventListener("pointerdown", (event) => {
    const heading = (event.target as HTMLElement).closest<HTMLElement>(".panel-heading");
    if (!heading || heading.dataset.swipeWired === "true") return;
    heading.dataset.swipeWired = "true";
    swipeDown(heading, () => options.closePanel());
  }, true);

  const sync = () => {
    state = player.state;
    renderTransport(state);
    requestTick();
  };

  /* ----- hardware back (Android) ----- */

  /**
   * The Android shell calls this before finishing the activity. Returning true
   * means "the page consumed the gesture", false lets the app close.
   */
  const handleBack = (): boolean => {
    if (network.close()) return true;
    if (!now.hidden) {
      closeNow();
      return true;
    }
    const panel = panelRoot?.querySelector<HTMLElement>(
      ".music-panel-scrim, .music-panel",
    );
    if (panel) {
      options.closePanel();
      return true;
    }
    if (stage.dataset.mode === "detail") {
      const back = document.querySelector<HTMLButtonElement>(
        '#music-detail [data-action="back"]',
      );
      if (back) {
        back.click();
        return true;
      }
    }
    return false;
  };
  (window as unknown as { __rhineBack?: () => boolean }).__rhineBack = handleBack;

  /* ----- native shell trims desktop-only controls ----- */

  /**
   * In the Android shell a filesystem path textarea, MusicBrainz settings and
   * the foobar bridge have no meaning. Hide those blocks so the phone sheets
   * stay short and every remaining control actually works on the device.
   */
  const hideDesktopOnly = () => {
    if (!bridge()) return;
    const rootsLabel = document.querySelector<HTMLElement>('label[for="music-roots"]');
    if (rootsLabel) rootsLabel.hidden = true;
    const roots = document.querySelector<HTMLElement>("#music-roots");
    if (roots) roots.hidden = true;
    document
      .querySelectorAll<HTMLElement>('#music-panel-root [data-action="scan"]')
      .forEach((button) => {
        button.hidden = true;
      });
    document.querySelectorAll<HTMLElement>("#panel-body section").forEach((section) => {
      const heading = section.querySelector("h3")?.textContent ?? "";
      if (/在线资料|资料库连接|专辑介绍|MusicBrainz|foobar|流派归并/i.test(heading)) {
        section.hidden = true;
      }
    });
  };

  syncViews();
  sync();
  hideDesktopOnly();
  return {
    libraryChanged: () => {
      syncViews();
      sync();
      hideDesktopOnly();
    },
    sync,
  };
}
