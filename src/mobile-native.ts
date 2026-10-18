/**
 * The Android shell bridge.
 *
 * Every member is optional: the same bundle also runs in a desktop browser and
 * in plain mobile browsers, where the UI must degrade instead of calling into
 * an object that does not exist. The V2 methods follow docs/MOBILE-V2.md.
 */
export type Theme = "day" | "dusk" | "night";

export interface BiliStatus {
  rootAvailable?: boolean;
  suGranted?: boolean;
  manualPath?: string;
  entries?: number;
  packages?: string[];
  supported?: boolean;
  message?: string;
}

export interface ResolveDetail {
  type?: "done" | "error";
  kind?: "audio" | "bili";
  title?: string;
  albumTitle?: string;
  artist?: string;
  cover?: string;
  duration?: number | string;
  url?: string;
  referer?: string;
  /** Native filename suggestion (already sanitized). */
  name?: string;
  error?: string;
}

export interface BiliEventDetail {
  type?: "progress" | "done" | "error";
  copied?: number;
  total?: number;
  converted?: number;
  message?: string;
}

export interface DownloadEventDetail {
  type?: "progress" | "done" | "error";
  received?: number;
  total?: number;
  error?: string;
}

export interface RootEventDetail {
  granted?: boolean;
  message?: string;
}

export interface NativeBridge {
  // V1
  platform?: () => string;
  version?: () => string;
  pickMusicFolder?: () => void;
  requestAudioPermission?: () => void;
  hasAudioPermission?: () => boolean;
  downloadAudio?: (url: string, name: string) => void;
  cancelDownload?: () => void;
  rescan?: () => void;
  toast?: (message: string) => void;
  setTheme?: (theme: Theme) => void;
  setKeepAwake?: (awake: boolean) => void;
  vibrate?: () => void;
  exitApp?: () => void;
  // V2
  biliStatus?: () => string;
  requestRoot?: () => void;
  importBilibiliCache?: () => void;
  pickBiliFolder?: () => void;
  resolveLink?: (url: string) => void;
  downloadResolved?: (url: string, name: string, referer: string) => void;
  vignette?: () => void;
}

declare global {
  interface Window {
    RhineNative?: NativeBridge;
  }
}

export function bridge(): NativeBridge | undefined {
  const candidate = window.RhineNative;
  return candidate && typeof candidate === "object" ? candidate : undefined;
}

export function nativeAvailable(): boolean {
  return !!bridge();
}

export function nativeToast(message: string) {
  bridge()?.toast?.(message);
}

/** The bridge returns JSON; tolerate a missing/older implementation. */
export function parseBiliStatus(raw: string | undefined): BiliStatus | undefined {
  if (!raw) return undefined;
  try {
    const value = JSON.parse(raw) as unknown;
    return value && typeof value === "object" ? (value as BiliStatus) : undefined;
  } catch {
    return undefined;
  }
}

export function clock(value: number | undefined): string {
  const n = Math.max(0, Math.floor(Number.isFinite(value ?? NaN) ? (value as number) : 0));
  return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, "0")}`;
}

/** Accepts the forms the native resolver understands. */
export function looksLikeLink(value: string): boolean {
  const text = value.trim();
  if (!text) return false;
  if (/^https?:\/\/\S+$/i.test(text)) return true;
  // Scheme-less Bilibili links are common when copied from the share sheet.
  if (/^(?:[\w-]+\.)*(?:bilibili\.com|b23\.tv)\/\S+$/i.test(text)) return true;
  // Bare identifiers pasted from the Bilibili app.
  return /^(?:BV[0-9A-Za-z]{8,}|av\d+)$/i.test(text);
}

/** Add https:// to a copied Bilibili URL, leave everything else untouched. */
export function normalizeLink(value: string): string {
  const text = value.trim();
  if (/^https?:\/\//i.test(text)) return text;
  if (/^(?:[\w-]+\.)*(?:bilibili\.com|b23\.tv)\/\S+$/i.test(text)) return `https://${text}`;
  return text;
}

export function looksLikeBilibili(value: string): boolean {
  return /bilibili\.com|b23\.tv|^(?:BV[0-9A-Za-z]{8,}|av\d+)$/i.test(value);
}
