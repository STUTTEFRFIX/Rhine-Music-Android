/** Vendor-neutral GPU capability probe. WebView2 and ordinary browsers use the
 * same WebGL 2 API. `powerPreference` is a selection hint, not a guarantee that
 * a runtime will choose a particular adapter; report the actual GL renderer. */
export interface GpuCapability {
  api: "WebGL 2" | "unavailable";
  renderer: string;
  mode: "hardware" | "software" | "unknown";
  maxTextureSize: number;
}

/** SwiftShader, llvmpipe, WARP and the Microsoft basic renderer are CPU paths.
 * Keep this list in one place: the capability probe and the cover-atlas upload
 * strategy both need the same answer. */
const SOFTWARE_RENDERER = /swiftshader|llvmpipe|softpipe|software|microsoft basic render|\bwarp\b/i;

export function isSoftwareRendererName(renderer: string) {
  return SOFTWARE_RENDERER.test(renderer);
}

type GlProvider = {
  getContext(): WebGLRenderingContext | WebGL2RenderingContext;
};

/** Read the unmasked adapter string from an existing renderer without creating
 * a second WebGL context. */
export function isSoftwareWebGL(provider?: GlProvider | null) {
  if (!provider) return false;
  try {
    const gl = provider.getContext();
    const debug = gl.getExtension("WEBGL_debug_renderer_info");
    const renderer = debug
      ? String(gl.getParameter(debug.UNMASKED_RENDERER_WEBGL))
      : String(gl.getParameter(gl.RENDERER) ?? "");
    return isSoftwareRendererName(renderer);
  } catch {
    return false;
  }
}

export function probeGpuCapability(): GpuCapability {
  const canvas = document.createElement("canvas");
  let gl: WebGL2RenderingContext | null = null;
  try {
    gl = canvas.getContext("webgl2", { powerPreference: "high-performance", failIfMajorPerformanceCaveat: false });
    if (!gl) return { api: "unavailable", renderer: "", mode: "unknown", maxTextureSize: 0 };
    const debug = gl.getExtension("WEBGL_debug_renderer_info");
    const renderer = debug ? String(gl.getParameter(debug.UNMASKED_RENDERER_WEBGL)) : "";
    const mode = isSoftwareRendererName(renderer)
      ? "software" : renderer ? "hardware" : "unknown";
    return { api: "WebGL 2", renderer, mode, maxTextureSize: Number(gl.getParameter(gl.MAX_TEXTURE_SIZE)) };
  } catch {
    return { api: "unavailable", renderer: "", mode: "unknown", maxTextureSize: 0 };
  } finally {
    gl?.getExtension("WEBGL_lose_context")?.loseContext();
  }
}
