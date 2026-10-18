import {
  LinearFilter,
  ShaderMaterial,
  UniformsUtils,
  WebGLRenderTarget,
  type WebGLRenderer,
} from "three";
import { BokehPass } from "three/addons/postprocessing/BokehPass.js";
import { FullScreenQuad } from "three/addons/postprocessing/Pass.js";
import { CopyShader } from "three/addons/shaders/CopyShader.js";
import { auxiliaryResolutionScale } from "./render-quality";

/** Runs both depth and bokeh shading at a bounded size, then restores the full
 * composer target with a single linear upscale. The final canvas stays native
 * resolution; only the intentionally blurred depth-of-field effect is scaled. */
export class ScaledBokehPass extends BokehPass {
  private readonly scaledTarget = new WebGLRenderTarget(1, 1, {
    minFilter: LinearFilter,
    magFilter: LinearFilter,
    depthBuffer: false,
    stencilBuffer: false,
  });
  private readonly copyMaterial = new ShaderMaterial({
    uniforms: UniformsUtils.clone(CopyShader.uniforms),
    vertexShader: CopyShader.vertexShader,
    fragmentShader: CopyShader.fragmentShader,
    depthTest: false,
    depthWrite: false,
    toneMapped: false,
  });
  private readonly copyQuad = new FullScreenQuad(this.copyMaterial);
  private bufferScale = 1;
  private fullWidth = 1;
  private fullHeight = 1;

  get bufferWidth() { return this.scaledTarget.width; }
  get bufferHeight() { return this.scaledTarget.height; }

  /** Phones run the depth+blur at half resolution; the effect is a blur, so the
   * loss is invisible at phone size while the pixel cost drops to ~25%. */
  setBufferScale(scale: number) {
    const next = Math.min(1, Math.max(0.25, scale));
    if (next === this.bufferScale) return;
    this.bufferScale = next;
    this.setSize(this.fullWidth, this.fullHeight);
  }

  override setSize(width: number, height: number) {
    this.fullWidth = width;
    this.fullHeight = height;
    const scale = auxiliaryResolutionScale(this.bufferScale, width, height);
    const scaledWidth = Math.max(1, Math.floor(width * scale));
    const scaledHeight = Math.max(1, Math.floor(height * scale));
    super.setSize(scaledWidth, scaledHeight);
    this.scaledTarget.setSize(scaledWidth, scaledHeight);
  }

  override render(
    renderer: WebGLRenderer,
    writeBuffer: WebGLRenderTarget,
    readBuffer: WebGLRenderTarget,
    deltaTime: number,
    maskActive: boolean,
  ) {
    const renderToScreen = this.renderToScreen;
    this.renderToScreen = false;
    try {
      super.render(renderer, this.scaledTarget, readBuffer, deltaTime, maskActive);
    } finally {
      this.renderToScreen = renderToScreen;
    }

    this.copyMaterial.uniforms.tDiffuse.value = this.scaledTarget.texture;
    renderer.setRenderTarget(renderToScreen ? null : writeBuffer);
    this.copyQuad.render(renderer);
  }

  override dispose() {
    super.dispose();
    this.scaledTarget.dispose();
    this.copyMaterial.dispose();
    this.copyQuad.dispose();
  }
}
