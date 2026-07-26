package dev.comfyfluffy.caustica.client;

/**
 * Bounded sub-pixel camera-ray jitter for DLSS Ray Reconstruction and sparse Ray Budget sampling.
 *
 * <p>Generates a Halton(2,3) low-discrepancy sequence in render-pixel space, with the DLSS phase-count
 * rule {@code ceil(8 * (display/render)^2)} and RR's recommended floor of 32 phases.
 * {@link dev.comfyfluffy.caustica.rt.RtComposite} reads the per-frame offset and applies it inside the
 * selected native pixel. DLSS-RR also receives the same offset in its evaluate call.
 */
public final class CausticaJitter {
	public static final CausticaJitter INSTANCE = new CausticaJitter();

	private int frameIndex;
	private float pixelsX;
	private float pixelsY;

	private CausticaJitter() {
	}

	/** Advance one frame. Call once per frame before the level projection is built. */
	public void prepare(int renderWidth, int renderHeight, int displayWidth) {
		int phaseCount = jitterPhaseCount(renderWidth, displayWidth);
		int index = (this.frameIndex++ % phaseCount) + 1; // Halton(0) is degenerate
		this.pixelsX = halton(index, 2) - 0.5f;
		this.pixelsY = halton(index, 3) - 0.5f;
	}

	/** Jitter offset in render-pixel space, applied to the primary ray and reported to RR evaluate. */
	public float jitterPixelsX() {
		return this.pixelsX;
	}

	public float jitterPixelsY() {
		return this.pixelsY;
	}

	private static int jitterPhaseCount(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		return Math.max(32, (int) Math.ceil(8.0f * ratio * ratio));
	}

	private static float halton(int index, int base) {
		float f = 1.0f;
		float result = 0.0f;
		int i = index;
		while (i > 0) {
			f /= base;
			result += f * (i % base);
			i /= base;
		}
		return result;
	}
}
