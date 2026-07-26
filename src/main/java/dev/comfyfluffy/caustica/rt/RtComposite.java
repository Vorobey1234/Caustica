package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.PointLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEmissionSemantics;
import dev.comfyfluffy.caustica.rt.material.RtMaterialOverrides;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtBmfrDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtNrdDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtOidnDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtSpatialDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtTemporalDenoiser;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtInterlaceResolver;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;






// AI slopified



public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex and raygen's debugView avoid unnecessary global-memory dereferences;
    // WorldPushConstantsData is generated from the same Slang module and owns this second ABI as well.
    private static final int GUIDE_COUNT = 9; // six RR guides + raw accumulation history at bindings 3..11
    // Frames a retired per-frame TLAS must outlive before it's freed (> frames-in-flight); matches
    // RtTerrain's deferred-free horizon. The frame TLAS is built + traced this frame, then freed once
    // the composite frame counter has advanced this far past it (so no in-flight frame still reads it).
    private static final int KEEP_FRAMES = 4;

    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Celestial rotation axis (the pole the sun/moon arc about): perpendicular to the east-west arc,
    // tilted by SUN_NOON_SOUTH_TILT. Pushed so the sky shader can build the sun/moon square's tangent
    // frame (right = travel direction) and wheel the starfield. = normalize(noonDir x sunriseDir).
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    private static float sunNoonTilt() {
        return CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.value();
    }

    private static float sunNoonY() {
        return Mth.cos(sunNoonTilt());
    }

    private static float sunNoonZ() {
        return Mth.sin(sunNoonTilt());
    }

    private static float celestialAxisY() {
        return -sunNoonZ();
    }

    private static float celestialAxisZ() {
        return sunNoonY();
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundBlockAlbedoAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundBlockAlbedoAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // Set when a new material epoch is published. The first composite returns to vanilla so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data (including a compact local-light array) lives in a host-visible BDA ring; only the 8-byte slot address is pushed
    // inline (256-byte NVIDIA push constant ceiling is otherwise exhausted by the world push struct).
    // One slot per in-flight frame, cycled per frame so an in-flight slot is never overwritten.
    private static final int PUSH_RING = 6;
    private RtBuffer[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    private RtTemporalDenoiser temporalDenoiser;
    private RtSpatialDenoiser spatialDenoiser;
    private RtBmfrDenoiser bmfrDenoiser;
    private RtInterlaceResolver interlaceResolver;
    private boolean bmfrHasPrevious;
    private boolean bmfrPauseStateKnown;
    private boolean bmfrPaused;
    private final RtOidnDenoiser oidnDenoiser = new RtOidnDenoiser();
    private final RtNrdDenoiser nrdDenoiser = new RtNrdDenoiser();
    private final Matrix4f nrdPrevProjection = new Matrix4f();
    private final Matrix4f nrdPrevView = new Matrix4f();
    private final Matrix4f nrdPreviousViewForDispatch = new Matrix4f();
    private double nrdPrevCamX;
    private double nrdPrevCamY;
    private double nrdPrevCamZ;
    private boolean nrdHasPrevious;
    private boolean nrdPauseStateKnown;
    private boolean nrdPaused;
    private RtImage temporalHistory;
    private RtImage temporalDepthHistory;
    private RtImage temporalNormalHistory;
    private RtImage temporalOutput;
    private RtImage output;
    private RtImage displayImage;
    // Snapshot of the vanilla/DH raster world. In hybrid mode display.comp uses it only where the
    // primary RT depth is zero, preserving distant LODs without mixing raster geometry into RT hits.
    private RtImage distantHorizonsBackground;
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private RtImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private RtImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private RtImage[] fgInterp = new RtImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    // Per-pixel checkerboard history validity. A bit is set only after that exact native pixel was
    // genuinely traced; spatial warm-up fills never mark it valid, so rotating phases eventually
    // replace every reconstructed pixel with its own ray result instead of leaving a block-upscaled image.
    private RtImage interlaceValidity;
    // Previous complete checkerboard frame. Sparse rays overwrite only the active phase, so the resolver
    // needs an immutable copy for motion-vector reprojection of every unsampled native pixel.
    private RtImage interlaceHistoryColor;
    private RtImage interlaceHistoryNormal;
    private RtImage interlaceHistoryDepth;
    private RtImage interlaceHistoryMotion;
    private boolean interlaceHistoryReady;
    // Native per-pixel path-tracing accumulation. These images are written only by raygen for pixels
    // that received a genuine ray this frame. They must stay separate from the complete checkerboard
    // history, because that history also contains spatially/reprojected pixels. Feeding reconstructed
    // pixels back into path accumulation creates the stationary radial streak/runaway artifact.
    private RtImage accumulationHistoryColor;
    private RtImage accumulationHistoryNormal;
    private RtImage accumulationHistoryDepth;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private RtImage blueNoiseImage;
    private int accumFrameCounter;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    // 0 = native/reference, 1 = DLSS-RR optimal input size, 2 = half-res OIDN real-time.
    private int renderSizeMode = Integer.MIN_VALUE;
    private int renderSizeRrQuality = Integer.MIN_VALUE;
    private int renderSizeDenoiserPercent = Integer.MIN_VALUE;
    private int activeRayBudgetDivisor = Integer.MIN_VALUE;
    private boolean activeRayBudgetJitter = false;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final Matrix4f dhInvViewProj = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos cloudBiomePos = new BlockPos.MutableBlockPos();
    private float cloudTemperature = 0.5f;
    private float cloudMoisture = 0.65f;
    private float targetCloudTemperature = 0.5f;
    private float targetCloudMoisture = 0.65f;
    private long nextCloudClimateSample;
    private double previousCloudTimeSeconds = Double.NaN;
    // Nearby static emissive blocks are rescanned at a low cadence; held/dropped lights are rebuilt every
    // frame. The shader receives only the strongest/nearest entries, so direct-light cost remains one
    // reservoir-selected shadow ray rather than scaling with the number of emitters.
    private static final int BLOCK_LIGHT_RADIUS_XZ = 14;
    private static final int BLOCK_LIGHT_RADIUS_Y = 8;
    private static final int BLOCK_LIGHT_RESCAN_FRAMES = 16;
    private static final int BLOCK_LIGHT_MOVE_RESCAN = 3;
    private final ArrayList<LocalLight> cachedBlockLights = new ArrayList<>();
    private final ArrayList<LocalLight> pendingBlockLights = new ArrayList<>(64);
    private ClientLevel cachedBlockLightLevel;
    private ClientLevel scanningBlockLightLevel;
    private boolean blockLightScanActive;
    private int blockLightScanOriginX;
    private int blockLightScanOriginY;
    private int blockLightScanOriginZ;
    private int blockLightScanMinY;
    private int blockLightScanYCount;
    private int blockLightScanIndex;
    private int blockLightScanTotal;
    private int cachedBlockLightX = Integer.MIN_VALUE;
    private int cachedBlockLightY = Integer.MIN_VALUE;
    private int cachedBlockLightZ = Integer.MIN_VALUE;
    private long nextBlockLightScanFrame;
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    // NRD consumes Caustica's non-jittered 2D screen-space guide motion. Reference has no motion input,
    // so it remains a stationary-camera accumulator while ReLAX/ReBLUR retain history during movement.
    private boolean cameraMotionThisFrame;
    private long atlasSampler;
    private long materialNormalSampler;
    private boolean failed;
    private boolean loggedActive;
    private boolean loggedDhLightingReady;
    /** A completed one-shot full-resolution OIDN result, reused until the camera changes. */
    private boolean oidnReferenceHeld;
    private boolean oidnReferenceRequestSeen;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private long pendingTerrainGraphicsUse;

    private record LightColor(float red, float green, float blue) {
    }

    private record LocalLight(double x, double y, double z, float radius,
                              float red, float green, float blue, float intensity,
                              float priorityBoost) {
        double priority(double cameraX, double cameraY, double cameraZ) {
            double dx = x - cameraX;
            double dy = y - cameraY;
            double dz = z - cameraZ;
            return priorityBoost * intensity / (dx * dx + dy * dy + dz * dz + 1.0);
        }
    }

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code dev.comfyfluffy.caustica.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /**
     * Whether the current frame must retain vanilla world rendering while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This method only prevents {@code LevelRenderer} from being cancelled before
     * a deliberately transient {@link #composite} return. Such a return is not a renderer failure and must
     * not trip {@code VanillaRenderController}'s permanent safety latch.</p>
     */
    public boolean requiresVanillaWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so RtTerrain can apply the matching full clear. Keep vanilla alive for that bring-up
        // frame; otherwise LevelRenderer is cancelled before composite() discovers it must fall back and
        // VanillaRenderController permanently latches the resulting missing replacement frame.
        if (worldPipeline == null || !materialBindingsReady) {
            return true;
        }
        if (materialEpochTraceGate) {
            return true;
        }
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) {
            return true;
        }
        if (reloadRebindRequested) {
            long atlas = blockAlbedoAtlasView();
            return atlas == 0L || atlas == boundBlockAlbedoAtlasHandle;
        }
        return false;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code dev.comfyfluffy.caustica.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingTerrainGraphicsUse != 0L) {
            throw new IllegalStateException("Previous RT terrain graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    /** Record terrain retirement completion after the frame's final TLAS consumer (world overlay). */
    public void finishTerrainGraphicsUse() {
        long graphicsUse = pendingTerrainGraphicsUse;
        if (graphicsUse == 0L) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before terrain graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        ctx.gpuExecutor().endGraphicsTerrainUse(encoder, graphicsUse);
        pendingTerrainGraphicsUse = 0L;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Count-bounded terrain streaming (dispatch/drain/build kick) runs here once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            RtTerrain.frame(ctx);
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No world this frame (incl. after quitting to the title — terrain residency + frameCaptured can
            // linger until an explicit invalidate, which would otherwise present a stale/empty HDR image as a
            // black menu background). Skip RT so the present path falls back to vanilla SDR / the PQ SDR
            // convert path, which shows the menu + panorama correctly.
            return false;
        }
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAlbedoAtlasView();
                if (atlas == 0L || atlas == boundBlockAlbedoAtlasHandle) {
                    return false;
                }
            }
            if (oidnReferenceHeld && cameraChangedSincePreviousFrame()) {
                oidnReferenceHeld = false;
                CausticaMod.LOGGER.info("Released held OIDN reference frame after camera movement");
            }
            if (CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.value()) {
                oidnReferenceHeld = false;
            }
            boolean referenceRequest = CausticaConfig.Rt.Denoiser.OIDN_ENABLED.value();
            if (referenceRequest && !oidnReferenceRequestSeen) {
                oidnDenoiser.discardCapture();
            }
            oidnReferenceRequestSeen = referenceRequest;
            ensureOutput(ctx, width, height);
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            RtPipeline active = ensureWorld(ctx);
            if (materialEpochTraceGate) {
                materialEpochTraceGate = false;
                return false;
            }
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so the immutable material snapshot is available to
     * the first worker section. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAlbedoAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, RtDeviceBringup.worldRaygenShader(),
                    new String[]{RtDeviceBringup.worldMissShader()},
                    RtDeviceBringup.worldClosestHitShader(), RtDeviceBringup.worldAnyHitShader(),
                    WorldPushConstantsData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true);
            // Per-frame world data lives in this BDA ring; the pipeline pushes its address and hot fields.
            if (pushRing == null) {
                pushRing = new RtBuffer[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i);
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the canonical material page bundles in reserved bindless slots. Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry, recreates material pages, builds
     * the shared material registry, and invalidates old-epoch geometry before tracing resumes.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long normalSampler = materialNormalSampler(ctx);
        long atlasView = blockAlbedoAtlasView();
        boundBlockAlbedoAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler, normalSampler);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        materialBindingsReady = true;
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
        }
        setCelestialUvAtlas(celView);
        // Bind the blue noise texture (created lazily in ensureOutput) so raygen can sample it for
        // spatially decorrelated seed scrambling instead of falling back to the hardcoded Bayer-style
        // table in world.rgen, which produces visible concentric-ring / checker artifacts on flat surfaces.
        if (blueNoiseImage != null) {
            worldPipeline.setBlueNoise(blueNoiseImage.view, sampler);
        }
        // Atlas UVs and material IDs are one resource epoch. Drop old terrain as a unit rather than
        // incrementally displaying old UVs/IDs against the new atlas/table.
        RtTerrain.requestFullClear();
        materialEpochTraceGate = true;
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears terrain before trace.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtEntities.INSTANCE.onResourceReload();
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null) {
            ctx.waitIdle();
            if (worldPipeline != null) {
                worldPipeline.destroy();
                worldPipeline = null;
                bindlessTextureCapacity = 0;
            }
            RtMaterialRegistry.INSTANCE.destroy();
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
        // Accumulation uses a native-only history written directly by raygen. Do not bind the complete
        // checkerboard history here: unsampled pixels in it are reconstructed from neighbours/history and
        // are not independent Monte Carlo samples. Accumulating those values caused the runaway streaks.
        worldPipeline.setExtraStorageImage(6, accumulationHistoryColor.view);
        worldPipeline.setExtraStorageImage(7, accumulationHistoryNormal.view);
        worldPipeline.setExtraStorageImage(8, accumulationHistoryDepth.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (interlaceValidity != null) {
            interlaceValidity.destroy();
            interlaceValidity = null;
        }
        if (interlaceHistoryColor != null) {
            interlaceHistoryColor.destroy();
            interlaceHistoryColor = null;
        }
        if (interlaceHistoryNormal != null) {
            interlaceHistoryNormal.destroy();
            interlaceHistoryNormal = null;
        }
        if (interlaceHistoryDepth != null) {
            interlaceHistoryDepth.destroy();
            interlaceHistoryDepth = null;
        }
        if (interlaceHistoryMotion != null) {
            interlaceHistoryMotion.destroy();
            interlaceHistoryMotion = null;
        }
        if (accumulationHistoryColor != null) {
            accumulationHistoryColor.destroy();
            accumulationHistoryColor = null;
        }
        if (accumulationHistoryNormal != null) {
            accumulationHistoryNormal.destroy();
            accumulationHistoryNormal = null;
        }
        if (accumulationHistoryDepth != null) {
            accumulationHistoryDepth.destroy();
            accumulationHistoryDepth = null;
        }
        interlaceHistoryReady = false;
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
        if (temporalHistory != null) {
            temporalHistory.destroy();
            temporalHistory = null;
        }
        if (temporalDepthHistory != null) {
            temporalDepthHistory.destroy();
            temporalDepthHistory = null;
        }
        if (temporalNormalHistory != null) {
            temporalNormalHistory.destroy();
            temporalNormalHistory = null;
        }
        if (temporalOutput != null) {
            temporalOutput.destroy();
            temporalOutput = null;
        }
    }

    private static boolean rayBudgetJitterEnabled() {
        return CausticaConfig.Rt.Composite.RAY_BUDGET_JITTER.value();
    }

    private static int rayBudgetDivisor() {
        int requested = Math.clamp(CausticaConfig.Rt.Composite.RAY_BUDGET_DIVISOR.value(), 1, 16);
        if (requested <= 1) return 1;
        if (requested <= 2) return 2;
        if (requested <= 4) return 4;
        if (requested <= 8) return 8;
        return 16;
    }

    private static int interlacedTraceWidth(int fullWidth, int divisor) {
        if (divisor == 2) return fullWidth;
        int tileWidth = divisor >= 8 ? 4 : (divisor >= 4 ? 2 : 1);
        return (fullWidth + tileWidth - 1) / tileWidth;
    }

    private static int interlacedTraceHeight(int fullHeight, int divisor) {
        if (divisor == 2) return (fullHeight + 1) / 2;
        int tileHeight = divisor >= 16 ? 4 : (divisor >= 4 ? 2 : 1);
        return (fullHeight + tileHeight - 1) / tileHeight;
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        // Reference OIDN is native resolution. The real-time OIDN and NRD paths trace/filter at the
        // user-selected linear resolution and use the existing GPU fallback upscale.
        boolean oidnRealtime = CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.value();
        boolean oidnReference = (CausticaConfig.Rt.Denoiser.OIDN_ENABLED.value() || oidnReferenceHeld)
                && !oidnRealtime;
        boolean bmfrEnabled = CausticaConfig.Rt.Denoiser.BMFR_ENABLED.value();
        boolean nrdEnabled = CausticaConfig.Rt.Denoiser.NRD_ENABLED.value() && !bmfrEnabled;
        // BMFR is deliberately native-resolution. Feeding Minecraft's pixel-art textures through the
        // shared 50% realtime-denoiser path and then linearly stretching them made the entire scene
        // look soft even when the regression itself preserved an edge. Only OIDN realtime and NRD use
        // the user-selectable reduced input resolution.
        boolean reducedResolutionDenoiser = oidnRealtime || nrdEnabled;
        boolean realtimeDenoiser = reducedResolutionDenoiser || bmfrEnabled;
        int denoiserPercent = reducedResolutionDenoiser
                ? Math.clamp(CausticaConfig.Rt.Denoiser.REALTIME_RESOLUTION_PERCENT.value(), 25, 100)
                : 100;
        boolean rrEnabled = RtDlssRr.enabled() && !oidnReference && !oidnRealtime
                && !nrdEnabled && !bmfrEnabled;
        int sizeMode = realtimeDenoiser ? 2 : (rrEnabled ? 1 : 0);
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && displayImage != null && distantHorizonsBackground != null
                && hdrDisplayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeMode == sizeMode && renderSizeRrQuality == rrQuality
                && renderSizeDenoiserPercent == denoiserPercent) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (distantHorizonsBackground != null) {
            distantHorizonsBackground.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at the denoiser/DLSS input resolution. Ray Budget no
        // longer shrinks these images: it launches a sparse rotating interlace pattern into the same native
        // grid and reconstructs complete pixel-sharp color/guides before the selected denoiser/upscaler.
        // With RR on, ask NGX what render resolution its chosen quality mode actually expects rather
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = realtimeDenoiser
                ? Math.max(1, (int) Math.ceil(width * denoiserPercent / 100.0))
                : (optimal != null ? optimal[0] : width);
        renderH = realtimeDenoiser
                ? Math.max(1, (int) Math.ceil(height * denoiserPercent / 100.0))
                : (optimal != null ? optimal[1] : height);
        renderSizeMode = sizeMode;
        renderSizeRrQuality = rrQuality;
        renderSizeDenoiserPercent = denoiserPercent;

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        distantHorizonsBackground = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                "Distant Horizons raster background " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT,
                "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        interlaceValidity = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_UINT,
                "ray-budget native-pixel validity " + renderW + "x" + renderH);
        interlaceHistoryColor = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ray-budget previous color " + renderW + "x" + renderH);
        interlaceHistoryNormal = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "ray-budget previous normal " + renderW + "x" + renderH);
        interlaceHistoryDepth = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R32_SFLOAT, "ray-budget previous depth " + renderW + "x" + renderH);
        interlaceHistoryMotion = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R16G16_SFLOAT, "ray-budget previous motion " + renderW + "x" + renderH);
        accumulationHistoryColor = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "native accumulation color/count " + renderW + "x" + renderH);
        accumulationHistoryNormal = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "native accumulation normal " + renderW + "x" + renderH);
        accumulationHistoryDepth = ctx.createStorageImage(renderW, renderH,
                VK10.VK_FORMAT_R32_SFLOAT, "native accumulation depth " + renderW + "x" + renderH);
        interlaceHistoryReady = false;
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        temporalHistory = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "temporal denoiser history " + renderW + "x" + renderH);
        temporalDepthHistory = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT,
                "temporal depth history " + renderW + "x" + renderH);
        temporalNormalHistory = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "temporal normal history " + renderW + "x" + renderH);
        temporalOutput = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "temporal denoiser output " + renderW + "x" + renderH);
        if (interlaceResolver == null) {
            interlaceResolver = RtInterlaceResolver.create(ctx);
        }
        interlaceResolver.setImages(output, gNormal, gAlbedo, gDepth, gMotion, gSpecAlbedo, gSpecMotion,
                interlaceValidity);
        clearImageToZero(ctx, interlaceValidity, renderW, renderH);
        clearImageToZero(ctx, interlaceHistoryColor, renderW, renderH);
        clearImageToZero(ctx, interlaceHistoryNormal, renderW, renderH);
        clearImageToZero(ctx, interlaceHistoryDepth, renderW, renderH);
        clearImageToZero(ctx, interlaceHistoryMotion, renderW, renderH);
        clearImageToZero(ctx, accumulationHistoryColor, renderW, renderH);
        clearImageToZero(ctx, accumulationHistoryNormal, renderW, renderH);
        clearImageToZero(ctx, accumulationHistoryDepth, renderW, renderH);
        clearImageToZero(ctx, temporalHistory, renderW, renderH);
        clearImageToZero(ctx, temporalDepthHistory, renderW, renderH);
        clearImageToZero(ctx, temporalNormalHistory, renderW, renderH);

        // Blue noise texture for spatially decorrelated random sampling
        if (blueNoiseImage == null) {
            ByteBuffer bnData = BlueNoiseGenerator.generate();
            blueNoiseImage = createBlueNoiseTexture(ctx, bnData);
        }

        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        bmfrHasPrevious = false;
        bmfrPauseStateKnown = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view,
                distantHorizonsBackground.view, gDepth.view,
                vkImageView(Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView()));
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        boolean cameraTransformChanged = false;
        if (mvHasPrev) {
            cameraTransformChanged = !mvPrevProjView.equals(mvCurProjView, 1.0e-6f);
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        cameraMotionThisFrame = mvHasPrev && (cameraTransformChanged
                || mvCamDeltaX != 0.0f || mvCamDeltaY != 0.0f || mvCamDeltaZ != 0.0f);
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
        // Rotation and projection changes invalidate accumulation just as camera translation does.
        if (accumFrameCounter > 0 && cameraMotionThisFrame) {
            accumFrameCounter = 0;
        }
    }

    private boolean cameraChangedSincePreviousFrame() {
        return mvHasPrev && (!mvPrevProjView.equals(new Matrix4f(frameProjection).mul(frameViewRotation), 1.0e-6f)
                || camX != mvPrevCamX || camY != mvPrevCamY || camZ != mvPrevCamZ);
    }

    private PointLight[] collectPointLights(ClientLevel level, RtTerrain terrain) {
        // Surface-emissive lighting is the only supported path. Analytic point/proxy lights are disabled
        // permanently so placed blocks, held items and dropped items cannot create spherical light halos.
        cachedBlockLights.clear();
        cachedBlockLightLevel = null;
        scanningBlockLightLevel = null;
        blockLightScanActive = false;
        return new PointLight[0];
    }

    private void refreshBlockLights(ClientLevel level) {
        if (cachedBlockLightLevel != null && cachedBlockLightLevel != level) {
            cachedBlockLights.clear();
            cachedBlockLightLevel = null;
        }
        int cx = Mth.floor(camX);
        int cy = Mth.floor(camY);
        int cz = Mth.floor(camZ);
        boolean cacheMoved = Math.abs(cx - cachedBlockLightX) >= BLOCK_LIGHT_MOVE_RESCAN
                || Math.abs(cy - cachedBlockLightY) >= BLOCK_LIGHT_MOVE_RESCAN
                || Math.abs(cz - cachedBlockLightZ) >= BLOCK_LIGHT_MOVE_RESCAN;
        boolean scanMovedFar = blockLightScanActive && (Math.abs(cx - blockLightScanOriginX) >= 6
                || Math.abs(cy - blockLightScanOriginY) >= 6
                || Math.abs(cz - blockLightScanOriginZ) >= 6);
        if (scanningBlockLightLevel != level || scanMovedFar) {
            beginBlockLightScan(level, cx, cy, cz);
        } else if (!blockLightScanActive
                && (level != cachedBlockLightLevel || frameCounter >= nextBlockLightScanFrame || cacheMoved)) {
            beginBlockLightScan(level, cx, cy, cz);
        }
        if (blockLightScanActive) {
            continueBlockLightScan(level, 2048);
        }
    }

    private void beginBlockLightScan(ClientLevel level, int cx, int cy, int cz) {
        scanningBlockLightLevel = level;
        blockLightScanOriginX = cx;
        blockLightScanOriginY = cy;
        blockLightScanOriginZ = cz;
        blockLightScanMinY = Math.max(level.getMinY(), cy - BLOCK_LIGHT_RADIUS_Y);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 1, cy + BLOCK_LIGHT_RADIUS_Y);
        blockLightScanYCount = Math.max(maxY - blockLightScanMinY + 1, 0);
        int diameter = BLOCK_LIGHT_RADIUS_XZ * 2 + 1;
        blockLightScanTotal = diameter * diameter * blockLightScanYCount;
        blockLightScanIndex = 0;
        pendingBlockLights.clear();
        blockLightScanActive = blockLightScanTotal > 0;
    }

    private void continueBlockLightScan(ClientLevel level, int stateBudget) {
        int diameter = BLOCK_LIGHT_RADIUS_XZ * 2 + 1;
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        int processed = 0;
        while (blockLightScanIndex < blockLightScanTotal && processed < stateBudget) {
            int index = blockLightScanIndex++;
            int localX = index % diameter;
            int plane = index / diameter;
            int localZ = plane % diameter;
            int localY = plane / diameter;
            int x = blockLightScanOriginX + localX - BLOCK_LIGHT_RADIUS_XZ;
            int z = blockLightScanOriginZ + localZ - BLOCK_LIGHT_RADIUS_XZ;
            int y = blockLightScanMinY + localY;
            int dx = x - blockLightScanOriginX;
            int dz = z - blockLightScanOriginZ;
            if (dx * dx + dz * dz > BLOCK_LIGHT_RADIUS_XZ * BLOCK_LIGHT_RADIUS_XZ) continue;
            processed++;
            scan.set(x, y, z);
            BlockState state = level.getBlockState(scan);
            int emission = state.getLightEmission();
            if (emission <= 0) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String path = id == null ? "" : id.getPath();
            LightColor color = emissiveColor(path);
            float normalized = emission / 15.0f;
            float radius = 5.5f + 8.5f * (float) Math.sqrt(normalized);
            float intensity = 7.0f + 15.0f * normalized;
            offerBlockLight(new LocalLight(x + 0.5, y + 0.55, z + 0.5,
                    radius, color.red, color.green, color.blue, intensity, 1.0f));
        }
        if (blockLightScanIndex < blockLightScanTotal) return;

        blockLightScanActive = false;
        cachedBlockLights.clear();
        cachedBlockLights.addAll(pendingBlockLights);
        cachedBlockLightLevel = level;
        cachedBlockLightX = blockLightScanOriginX;
        cachedBlockLightY = blockLightScanOriginY;
        cachedBlockLightZ = blockLightScanOriginZ;
        nextBlockLightScanFrame = frameCounter + BLOCK_LIGHT_RESCAN_FRAMES;
    }

    private void offerBlockLight(LocalLight candidate) {
        if (pendingBlockLights.size() < 64) {
            pendingBlockLights.add(candidate);
            return;
        }
        int weakestIndex = 0;
        double weakestPriority = pendingBlockLights.get(0).priority(camX, camY, camZ);
        for (int i = 1; i < pendingBlockLights.size(); i++) {
            double priority = pendingBlockLights.get(i).priority(camX, camY, camZ);
            if (priority < weakestPriority) {
                weakestPriority = priority;
                weakestIndex = i;
            }
        }
        if (candidate.priority(camX, camY, camZ) > weakestPriority) {
            pendingBlockLights.set(weakestIndex, candidate);
        }
    }

    private void appendHeldLights(ArrayList<LocalLight> lights) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        Vec3 look = minecraft.player.getLookAngle();
        if (look.lengthSqr() < 1.0e-8) look = new Vec3(0.0, 0.0, 1.0);
        look = look.normalize();
        Vec3 right = look.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-8) right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();
        Vec3 base = new Vec3(camX, camY, camZ).add(look.scale(0.72)).add(0.0, -0.20, 0.0);
        appendItemLight(lights, minecraft.player.getMainHandItem(), base.add(right.scale(0.27)), 1.55f, 8.0f);
        appendItemLight(lights, minecraft.player.getOffhandItem(), base.add(right.scale(-0.27)), 1.45f, 7.5f);
    }

    private void appendDroppedLights(ClientLevel level, ArrayList<LocalLight> lights) {
        int accepted = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            double dx = entity.getX() - camX;
            double dy = entity.getY() - camY;
            double dz = entity.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > 36.0 * 36.0) continue;
            int before = lights.size();
            appendItemLight(lights, itemEntity.getItem(),
                    new Vec3(entity.getX(), entity.getY() + 0.28, entity.getZ()), 1.90f, 6.5f);
            if (lights.size() != before && ++accepted >= 10) break;
        }
    }

    private static void appendItemLight(ArrayList<LocalLight> lights, ItemStack stack, Vec3 position,
                                        float intensityMultiplier, float priorityBoost) {
        int emission = itemEmission(stack);
        if (emission <= 0) return;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath();
        LightColor color = emissiveColor(path);
        float normalized = emission / 15.0f;
        float radius = (6.0f + 8.5f * (float) Math.sqrt(normalized))
                * (0.94f + 0.06f * intensityMultiplier);
        float intensity = (8.0f + 14.0f * normalized) * intensityMultiplier;
        lights.add(new LocalLight(position.x, position.y, position.z, radius,
                color.red, color.green, color.blue, intensity, priorityBoost));
    }

    private static int itemEmission(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.getItem() instanceof BlockItem blockItem) {
            int emission = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (emission > 0) return emission;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath();
        if (path.contains("lava_bucket") || path.contains("blaze_rod") || path.contains("blaze_powder")) return 15;
        if (path.contains("glow_berries") || path.contains("glow_ink") || path.contains("glowstone")) return 12;
        if (path.contains("magma_cream") || path.contains("fire_charge")) return 10;
        if (path.contains("amethyst") || path.contains("echo_shard")) return 7;
        return 0;
    }

    private static LightColor emissiveColor(String path) {
        if (path.contains("soul")) return new LightColor(0.20f, 0.72f, 1.00f);
        if (path.contains("redstone")) return new LightColor(1.00f, 0.08f, 0.025f);
        if (path.contains("sea_lantern") || path.contains("conduit")) return new LightColor(0.52f, 0.90f, 1.00f);
        if (path.contains("verdant_froglight")) return new LightColor(0.62f, 1.00f, 0.62f);
        if (path.contains("pearlescent_froglight")) return new LightColor(1.00f, 0.68f, 0.92f);
        if (path.contains("ochre_froglight")) return new LightColor(1.00f, 0.76f, 0.38f);
        if (path.contains("end_rod") || path.contains("amethyst")) return new LightColor(0.78f, 0.68f, 1.00f);
        if (path.contains("glow_lichen") || path.contains("glow_berries")) return new LightColor(0.82f, 1.00f, 0.34f);
        if (path.contains("lava") || path.contains("magma") || path.contains("fire")
                || path.contains("campfire") || path.contains("blaze")) {
            return new LightColor(1.00f, 0.28f, 0.055f);
        }
        if (path.contains("shroomlight")) return new LightColor(1.00f, 0.42f, 0.16f);
        return new LightColor(1.00f, 0.62f, 0.24f);
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        // During the first progressive stream, raster DH fills sections not yet represented by an RT
        // checkpoint. Once bootstrap completes it is disabled permanently; later refreshes retain the old
        // RT proxy until each progressive replacement checkpoint is ready.
        boolean distantHorizonsHybrid = DistantHorizonsCompat.enabled()
                && !RtDistantHorizonsTerrain.INSTANCE.bootstrapComplete();
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtEntities.FrameEntities frameEntities = null;
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            if (distantHorizonsHybrid) {
                // LevelRenderer (including DH's injected LOD pass) has completed by this seam. Preserve
                // its color before the RT result overwrites the main target later in this command buffer.
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                VK10.vkCmdCopyImage(cmd, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        distantHorizonsBackground.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        copyRegion(stack, displayW, displayH));
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            int debugView = debugView();
            boolean bmfrRequested = CausticaConfig.Rt.Denoiser.BMFR_ENABLED.value() && debugView == 0;
            boolean nrdRequested = CausticaConfig.Rt.Denoiser.NRD_ENABLED.value()
                    && !bmfrRequested && debugView == 0;
            boolean oidnRealtimeRequested = CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.value()
                    && !bmfrRequested && !nrdRequested && debugView == 0;
            boolean oidnReferenceRequested = CausticaConfig.Rt.Denoiser.OIDN_ENABLED.value()
                    && !bmfrRequested && !nrdRequested && !oidnRealtimeRequested && debugView == 0;
            boolean holdOidnReference = oidnReferenceHeld && !bmfrRequested && !oidnReferenceRequested
                    && !oidnRealtimeRequested && debugView == 0;
            boolean oidnRequested = oidnRealtimeRequested || oidnReferenceRequested || holdOidnReference;
            // OIDN and BMFR are alternative beauty denoisers, so do not run DLSS-RR over either one.
            boolean rrPath = RtDlssRr.enabled() && debugView == 0 && !oidnRequested
                    && !nrdRequested && !bmfrRequested;
            // Resolve the effective sparse policy once and use it for jitter, flags, history resets,
            // dispatch, and reconstruction. Reference OIDN deliberately overrides both controls.
            int rayBudgetDivisor = holdOidnReference ? 1 : rayBudgetDivisor();
            boolean rayBudgetJitter = !holdOidnReference && rayBudgetDivisor > 1
                    && rayBudgetJitterEnabled();
            // Raw history is strictly per native pixel and is never populated by the interlace resolve.
            // It is therefore safe for all Ray Budget rates now that Ray Budget Jitter only rotates
            // exact-pixel phases and never shifts the primary camera ray between Minecraft texels.
            // Progressive Infinite always accumulates its independent per-frame paths. It keeps refining
            // while the camera is stationary and resets through the existing camera-motion path.
            boolean accumEnabled = CausticaConfig.Rt.Composite.ACCUMULATION_ENABLED.value()
                    || maxBounces() == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            // Ray Budget jitter rotates the native sampling phase; it must not also move the camera
            // ray. DLSS-RR remains the sole owner of sub-pixel camera jitter.
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            RtTerrain terrain = RtTerrain.currentOrNull();
            RtDistantHorizonsTerrain.INSTANCE.frame(ctx, terrain.blockX, terrain.blockY, terrain.blockZ);
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            RtBuffer pushBuf = pushRing[pushSlot];
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: PBR BRDF (bit 1, always on) + camera-in-water (so the path tracer starts in the water
            // medium when the eye is submerged, fixing the air→water first-segment orientation).
            int flags = 0b10;
            var level = Minecraft.getInstance().level;
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla's own Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block, even well
                // above its actual surface (shallow/flowing water, or standing with your head just over a
                // source block).
                FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER) && camY < cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos)) {
                    flags |= 0b01;
                }
            }
            if (waterWaves()) {
                flags |= 0b10000; // W1: animated water wave normals
            }
            if (accumEnabled) flags |= 0x100000;

            // W1/W2 water parameters: camera-biome tint plus wrapped animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            Float4 waterParams = new Float4(wtr, wtg, wtb,
                    (float) (System.nanoTime() / 1.0e9 % 3600.0));
            // W1 wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            // z is the live vanilla/DH transition radius read by DH any-hit every frame.
            // Begin accepting the DH proxy exactly at the vanilla render-distance boundary. The previous
            // +16 block margin delayed DH by one complete chunk and made the hand-off visibly too far away.
            float dhVanillaRadius = Minecraft.getInstance().options.renderDistance().get() * 16f;
            double cloudTimeSeconds = level != null
                    ? (level.getGameTime() + Minecraft.getInstance().getDeltaTracker()
                            .getGameTimeDeltaPartialTick(false)) / 20.0
                    : 0.0;
            float cloudDeltaSeconds = Double.isFinite(previousCloudTimeSeconds)
                    ? (float) Math.clamp(cloudTimeSeconds - previousCloudTimeSeconds, 0.0, 0.25)
                    : 0.0f;
            previousCloudTimeSeconds = cloudTimeSeconds;
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, dhVanillaRadius, cloudDeltaSeconds);

            // Average a 3x3 biome neighbourhood, then ease the result so crossing a biome boundary does
            // not pop the entire cloud deck. Temperature controls vertical development; precipitation
            // controls coverage and billow density. Absolute terrain origin gives the shader stable
            // kilometre-scale regional variation without the water field's 4096-block wrap.
            if (level != null && frameCounter >= nextCloudClimateSample) {
                float temperatureSum = 0f;
                float moistureSum = 0f;
                int climateSamples = 0;
                for (int dz = -64; dz <= 64; dz += 64) {
                    for (int dx = -64; dx <= 64; dx += 64) {
                        cloudBiomePos.set(cameraBlockPos.getX() + dx, cameraBlockPos.getY(),
                                cameraBlockPos.getZ() + dz);
                        var biome = level.getBiome(cloudBiomePos).value();
                        temperatureSum += Math.clamp((biome.getBaseTemperature() + 0.5f) / 2.5f,
                                0.0f, 1.0f);
                        moistureSum += biome.hasPrecipitation() ? 1.0f : 0.08f;
                        climateSamples++;
                    }
                }
                targetCloudTemperature = temperatureSum / climateSamples;
                targetCloudMoisture = moistureSum / climateSamples;
                nextCloudClimateSample = frameCounter + 20;
            }
            cloudTemperature = Mth.lerp(0.02f, cloudTemperature, targetCloudTemperature);
            cloudMoisture = Mth.lerp(0.02f, cloudMoisture, targetCloudMoisture);
            Float4 cloudParams = new Float4(cloudTemperature, cloudMoisture,
                    terrain.blockX, terrain.blockZ);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            List<RtAccel.Instance> staticInstances = RtDistantHorizonsTerrain.INSTANCE.appendInstances(
                    terrain.staticInstances(), terrain.blockX, terrain.blockY, terrain.blockZ);
            frameEntities = RtEntities.INSTANCE.beginFrame(ctx, staticInstances,
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            RtEntities.FrameEntities fe = frameEntities;
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            PointLight[] pointLights = collectPointLights(level, terrain);
            SkyPush sky = skyPush();
            // Index 0 replaces stale output on the first frame after a reset. Increment only after
            // taking this frame's index; disabling accumulation also discards the old sequence.
            int accumulationFrameIndex = accumEnabled ? accumFrameCounter++ : 0;
            if (!accumEnabled) accumFrameCounter = 0;
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    terrain.tableAddress(),
                    accumEnabled ? accumulationFrameIndex : (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    fe.geomTableAddr(),
                    flags,
                    maxBounces(),
                    sky.sunDir(),
                    sky.lightDir(),
                    sky.lightRadiance(),
                    sky.moonDir(),
                    sky.celestial(),
                    sky.sunUv(),
                    sky.moonUv(),
                    sky.weather(),
                    cloudParams,
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    breaking,
                    pointLights.length,
                    pointLights
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS this frame, then the TLAS that references them (+ the already-built
            // terrain BLAS), then the trace — each separated by a barrier. The frame TLAS is retired
            // KEEP_FRAMES later (entity meshes/BLAS are retired by RtEntities on the same horizon).
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.baseInstances(), fe.dynamicInstances(), tlasRing);
            }
            active.setTlas(frameTlas.accel.handle);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            if (activeRayBudgetDivisor != rayBudgetDivisor || activeRayBudgetJitter != rayBudgetJitter) {
                activeRayBudgetDivisor = rayBudgetDivisor;
                activeRayBudgetJitter = rayBudgetJitter;
                bmfrHasPrevious = false;
                nrdHasPrevious = false;
                mvHasPrev = false;
                accumFrameCounter = 0;
                fgReset = true;
                RtDlssRr.INSTANCE.requestHistoryReset();
                clearTemporalHistory(cmd, stack);
                clearInterlaceValidity(cmd, stack);
                clearInterlaceHistory(cmd, stack);
                clearAccumulationHistory(cmd, stack);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            // Camera movement, toggling accumulation, and other sequence resets set the accumulation
            // frame index back to zero. Clear every native per-pixel sample count before tracing so a
            // sparse phase cannot revive an old-camera sample several frames later.
            if (accumEnabled && accumulationFrameIndex == 0) {
                clearAccumulationHistory(cmd, stack);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            // The reference Ray Budget contract carries its phase toggle in bit 16. Both raygen and
            // the resolve pass use this same global phase, so every native pixel is refreshed once per
            // divisor frames while stationary-camera detail remains intact.
            int packedDebugAndBudget = (debugView & 0xff) | (rayBudgetDivisor << 8)
                    | (rayBudgetJitter ? 0x10000 : 0);
            new WorldPushConstantsData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                    RtDistantHorizonsTerrain.INSTANCE.tableAddress(), RtMaterialRegistry.INSTANCE.tableAddress(),
                    (int) frameCounter, packedDebugAndBudget).write(pushConstants);
            if (!holdOidnReference) {
                int traceWidth = interlacedTraceWidth(renderW, rayBudgetDivisor);
                int traceHeight = interlacedTraceHeight(renderH, rayBudgetDivisor);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world trace");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.trace")) {
                    active.trace(cmd, traceWidth, traceHeight, pushConstants);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // sparse RT writes visible to reconstruction
                if (rayBudgetDivisor > 1) {
                    try (RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.interlaceResolve")) {
                        interlaceResolver.dispatch(cmd, renderW, renderH, rayBudgetDivisor,
                                rayBudgetJitter, (int) frameCounter);
                    }
                    VulkanCommandEncoder.memoryBarrier(cmd, stack); // reconstructed color/guides visible downstream
                }
            } else {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            boolean bmfrDone = false;
            if (!rrDone && bmfrRequested) {
                if (bmfrDenoiser == null) {
                    bmfrDenoiser = RtBmfrDenoiser.create(ctx);
                }
                boolean paused = Minecraft.getInstance().isPaused();
                boolean pauseStateChanged = bmfrPauseStateKnown && paused != bmfrPaused;
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "BMFR");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.bmfr")) {
                    bmfrDenoiser.dispatch(cmd, renderW, renderH, output, gDepth, gMotion, gNormal, gAlbedo,
                            temporalHistory, temporalDepthHistory, temporalNormalHistory, temporalOutput,
                            frameInvViewProj, (int) frameCounter, !bmfrHasPrevious || pauseStateChanged);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                VkImageCopy.Buffer bmfrCopy = VkImageCopy.calloc(1, stack);
                bmfrCopy.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                bmfrCopy.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                bmfrCopy.get(0).extent().set(renderW, renderH, 1);
                VK10.vkCmdCopyImage(cmd, temporalOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        output.image, VK10.VK_IMAGE_LAYOUT_GENERAL, bmfrCopy);
                VK10.vkCmdCopyImage(cmd, temporalOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        temporalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, bmfrCopy);
                VK10.vkCmdCopyImage(cmd, gDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        temporalDepthHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, bmfrCopy);
                VK10.vkCmdCopyImage(cmd, gNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        temporalNormalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, bmfrCopy);
                bmfrDone = true;
                bmfrHasPrevious = true;
                bmfrPaused = paused;
                bmfrPauseStateKnown = true;
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            } else {
                bmfrHasPrevious = false;
                bmfrPauseStateKnown = false;
            }

            boolean nrdDone = false;
            if (!rrDone && nrdRequested) {
                Matrix4fc previousProjection = nrdHasPrevious ? nrdPrevProjection : frameProjection;
                // Terrain vertices are camera-relative. Convert the previous rotation-only view matrix
                // so it maps a current-frame camera-relative position into previous-frame view space.
                // This is the camera-delta convention used by NVIDIA's vk_denoise_nrd sample when
                // object motion vectors are unavailable.
                Matrix4fc previousView = nrdHasPrevious
                        ? nrdPreviousViewForDispatch.set(nrdPrevView).translate(
                                (float) (camX - nrdPrevCamX),
                                (float) (camY - nrdPrevCamY),
                                (float) (camZ - nrdPrevCamZ))
                        : frameViewRotation;
                String nrdMethod = CausticaConfig.Rt.Denoiser.NRD_METHOD.get();
                boolean referenceCameraReset = "reference".equals(nrdMethod) && cameraMotionThisFrame;
                boolean paused = Minecraft.getInstance().isPaused();
                boolean pauseStateChanged = nrdPauseStateKnown && paused != nrdPaused;
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "NRD " + nrdMethod);
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.nrd")) {
                    nrdDone = nrdDenoiser.dispatch(ctx, cmd, renderW, renderH,
                            output, gDepth, gMotion, gNormal, gAlbedo,
                            frameProjection, previousProjection, frameViewRotation, previousView,
                            (int) frameCounter, !nrdHasPrevious || referenceCameraReset || pauseStateChanged);
                }
                if (nrdDone) {
                    nrdPrevProjection.set(frameProjection);
                    nrdPrevView.set(frameViewRotation);
                    nrdPrevCamX = camX;
                    nrdPrevCamY = camY;
                    nrdPrevCamZ = camZ;
                    nrdHasPrevious = true;
                    nrdPaused = paused;
                    nrdPauseStateKnown = true;
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                } else {
                    nrdHasPrevious = false;
                    nrdPauseStateKnown = false;
                }
            } else {
                nrdHasPrevious = false;
                nrdPauseStateKnown = false;
            }

            // OIDN consumes the complete path-traced beauty AOV, unlike FidelityFX Denoiser's specialized
            // shadow/reflection signals. Capture this frame's raw HDR color + first-hit guides before any
            // built-in filtering. A prepared preceding result is used only for an unchanged camera.
            boolean oidnReady = false;
            if (!rrDone && oidnRequested) {
                if (holdOidnReference) {
                    oidnReady = true;
                    oidnDenoiser.record(cmd, output, gAlbedo, gNormal,
                            renderW, renderH, false, true);
                } else {
                    oidnReady = oidnDenoiser.prepare(ctx, renderW, renderH, oidnRealtimeRequested);
                    oidnDenoiser.record(cmd, output, gAlbedo, gNormal,
                            renderW, renderH, true, oidnReady);
                    if (oidnReady && oidnReferenceRequested) {
                        oidnReferenceHeld = true;
                        CausticaConfig.Rt.Denoiser.OIDN_ENABLED.set(false);
                        if (oidnReferenceHeld) {
                            CausticaMod.LOGGER.info("OIDN reference capture complete; reusing it until camera movement");
                        }
                    }
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                if (oidnReady) {
                    // Keep SVGF history coherent while OIDN owns the beauty output. If the camera starts
                    // moving next frame, the fallback temporal pass reprojects this recent OIDN result
                    // instead of history that may be seconds old.
                    VkImageCopy.Buffer oidnHistoryCopy = VkImageCopy.calloc(1, stack);
                    oidnHistoryCopy.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    oidnHistoryCopy.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    oidnHistoryCopy.get(0).extent().set(renderW, renderH, 1);
                    VK10.vkCmdCopyImage(cmd, output.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, oidnHistoryCopy);
                    VK10.vkCmdCopyImage(cmd, gDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalDepthHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, oidnHistoryCopy);
                    VK10.vkCmdCopyImage(cmd, gNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalNormalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, oidnHistoryCopy);
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
            }

            // AMD-compatible denoiser runs BEFORE the upscale on the render-res output.
            // The two passes share a working buffer (temporalOutput): the temporal pass writes it and the
            // spatial pass reads it. When only one pass is enabled we must bridge the gap so the enabled
            // pass's result reaches `output` (which the upscale below reads) — otherwise a lone temporal
            // pass writes temporalOutput and the upscale still reads the noisy output, and a lone spatial
            // pass reads stale temporalOutput and writes garbage to output.
            boolean temporalDenoiserEnabled = CausticaConfig.Rt.Denoiser.TEMPORAL_ENABLED.value() && debugView == 0;
            boolean spatialDenoiserEnabled = CausticaConfig.Rt.Denoiser.SPATIAL_ENABLED.value() && debugView == 0;
            // Once OIDN initialized it is the selected beauty backend, including its one-frame warm-up;
            // silently running SVGF over that warm-up made the toggle look like it did nothing. Fall back
            // to SVGF only if OIDN failed to initialize, or when the option is disabled.
            boolean builtInDenoiserFallback = !bmfrDone && !nrdDone
                    && (!oidnRequested || !oidnDenoiser.active());
            if (!rrDone && builtInDenoiserFallback && (temporalDenoiserEnabled || spatialDenoiserEnabled)) {
                // Source the spatial pass from the right buffer: temporalOutput when temporal ran, output
                // (the noisy trace) when it didn't. The spatial pass writes back to `output` either way.
                RtImage spatialInput = temporalDenoiserEnabled ? temporalOutput : output;
                RtImage spatialOutput = output;
                if (temporalDenoiserEnabled) {
                    if (temporalDenoiser == null) {
                        temporalDenoiser = RtTemporalDenoiser.create(ctx);
                    }
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "temporal denoiser");
                         RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.temporalDenoiser")) {
                        // Reads from output (noisy) + temporalHistory (prev denoised), writes to temporalOutput
                        float temporalBlend = switch (rayBudgetDivisor) {
                            case 2 -> 0.84f;
                            case 4 -> 0.74f;
                            case 8 -> 0.64f;
                            case 16 -> 0.56f;
                            default -> 0.97f;
                        };
                        temporalDenoiser.dispatch(cmd, renderW, renderH,
                                output, gDepth, gMotion, gNormal,
                                temporalHistory, temporalOutput,
                                temporalDepthHistory, temporalNormalHistory,
                                temporalBlend, 0.08f, 0.90f, 1.35f);
                    }
                    // Copy temporalOutput -> temporalHistory for next frame's history
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    VkImageCopy.Buffer histCopy = VkImageCopy.calloc(1, stack);
                    histCopy.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    histCopy.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    histCopy.get(0).extent().set(renderW, renderH, 1);
                    VK10.vkCmdCopyImage(cmd, temporalOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, histCopy);
                    VK10.vkCmdCopyImage(cmd, gDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalDepthHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, histCopy);
                    VK10.vkCmdCopyImage(cmd, gNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                            temporalNormalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, histCopy);
                    // When the spatial pass is disabled, the temporal pass's output (temporalOutput) must
                    // still reach `output` so the upscale below reads the denoised result, not the noisy trace.
                    if (!spatialDenoiserEnabled) {
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        VkImageCopy.Buffer temporalToOutput = VkImageCopy.calloc(1, stack);
                        temporalToOutput.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        temporalToOutput.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        temporalToOutput.get(0).extent().set(renderW, renderH, 1);
                        VK10.vkCmdCopyImage(cmd, temporalOutput.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                                output.image, VK10.VK_IMAGE_LAYOUT_GENERAL, temporalToOutput);
                    }
                }
                if (spatialDenoiserEnabled) {
                    if (spatialDenoiser == null) {
                        spatialDenoiser = RtSpatialDenoiser.create(ctx);
                    }
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "spatial denoiser");
                         RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.spatialDenoiser")) {
                        // Reads from spatialInput (temporally smoothed when temporal ran, noisy trace
                        // otherwise), writes back to output. luminanceSigma is scaled to the HDR range:
                        // 0.1 on linear radiance (values can be 0..50+) would treat any MC noise as an
                        // edge and kill the filter, so we use a value large enough to bridge typical
                        // Monte-Carlo luminance variance without smearing genuine albedo edges.
                        spatialDenoiser.dispatch(cmd, renderW, renderH,
                                spatialInput, gDepth, gNormal, spatialOutput,
                                2.0f, 0.01f, 32.0f, 1.0f);
                    }
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }

            // Upscale the (denoised or raw) render-res output to display-res rrOutput
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rrOutput visible to exposure histogram

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            long dhDepthView = distantHorizonsHybrid ? DistantHorizonsCompat.depthTextureView() : 0L;
            boolean dhLightingReady = dhDepthView != 0L
                    && RtDistantHorizonsTerrain.INSTANCE.tableAddress() != 0L
                    && DistantHorizonsCompat.inverseViewProjection(dhInvViewProj);
            if (dhLightingReady && !loggedDhLightingReady) {
                loggedDhLightingReady = true;
                CausticaMod.LOGGER.info("Distant Horizons RT receiver lighting active: native DH depth + inverse matrix");
            }
            // DH owns a separate depth attachment. Rebind when it creates/resizes that texture; using
            // Minecraft's depth here shades unrelated near geometry and produces floating black blobs.
            displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view,
                    hdrDisplayImage.view, distantHorizonsBackground.view, gDepth.view,
                    dhDepthView != 0L ? dhDepthView
                            : vkImageView(Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView()));
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        CausticaConfig.Rt.Hdr.paperWhiteNits(), CausticaConfig.Rt.Hdr.headroom(),
                        distantHorizonsHybrid, dhLightingReady, frameTlas.accel.handle,
                        dhLightingReady ? dhInvViewProj : frameInvViewProj,
                        (float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                        (float) (camZ - terrain.blockZ),
                        sky.lightDir().x(), sky.lightDir().y(), sky.lightDir().z(),
                        sky.lightRadiance().x(), sky.lightRadiance().y(), sky.lightRadiance().z(), 0.0f);
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        long graphicsUse = gpuExecutor.beginGraphicsTerrainUse(encoder);
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        RtEntities.INSTANCE.markGraphicsUse(frameEntities, graphicsUse);
        pendingTerrainGraphicsUse = graphicsUse;
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private BreakEntry[] breakingEntries(RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= result.length) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                result[count++] = new BreakEntry(new Int4(
                        pos.getX() - terrain.blockX,
                        pos.getY() - terrain.blockY,
                        pos.getZ() - terrain.blockZ,
                        slot));
            }
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }

    private record SkyPush(Float4 sunDir, Float4 lightDir, Float4 lightRadiance, Float4 moonDir,
                           Float4 celestial, Float4 sunUv, Float4 moonUv, Float4 weather) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /**
     * Derive the celestial light from Minecraft's time of day as typed values for {@link WorldPushData}.
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code caustica.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     */
    private SkyPush skyPush() {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        float moonX, moonY, moonZ, moonPhase, starAngle, starBrightness;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float rain = mc.level != null ? mc.level.getRainLevel(partial) : 0.0f;
        float thunder = mc.level != null ? mc.level.getThunderLevel(partial) : 0.0f;
        rain = Math.clamp(rain, 0.0f, 1.0f);
        thunder = Math.clamp(thunder, 0.0f, 1.0f);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
        float sunNoon = Mth.cos(sunAngle);
        sunX = -Mth.sin(sunAngle); sunY = sunNoonY() * sunNoon; sunZ = sunNoonZ() * sunNoon;
        float moonNoon = Mth.cos(moonAngle);
        moonX = -Mth.sin(moonAngle); moonY = sunNoonY() * moonNoon; moonZ = sunNoonZ() * moonNoon;
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new
        // Stars: use Minecraft's actual celestial rotation + brightness (the same values vanilla's
        // SkyRenderer uses), so the starfield wheels about the celestial pole tied to world time and
        // fades in/out at dusk/dawn exactly like vanilla. STAR_ANGLE is in degrees -> radians.
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        float[] trans = new float[3];
        if (sunY > -0.05f) {
            // Sun stays the NEE light through the whole sunset: its colour/intensity is the atmosphere's
            // own transmittance (same Rayleigh+Mie+ozone march as the sky shader — see
            // atmosphereTransmittance), so it whitens overhead and reddens+dims into the horizon on
            // exactly the curve the visible sky follows. The old hand-tuned warmth ramp switched to the
            // moon at sunY == 0 while the sun was still at ~16% strength, which read as a hard light pop
            // at sunset/sunrise; transmittance is already near zero at the horizon, and the short
            // smoothstep below carries the remainder to exactly zero before the moon takes over.
            atmosphereTransmittance(sunX, sunY, sunZ, trans);
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            float sunPeak = 21.0f;
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * trans[0] * fade;
            rg = sunPeak * trans[1] * fade;
            rb = sunPeak * trans[2] * fade;
            lightRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moon: dim cool light, ramping up from zero at the sun→moon handoff (sunY = -0.05, where
            // the sun fade also reaches zero) so the switch is invisible. Scaled by the lit fraction so
            // a new moon gives near-zero moonlight, and tinted by the same transmittance so a low moon
            // is warm amber, silver once high (or zero while it is below the horizon).
            atmosphereTransmittance(moonX, moonY, moonZ, trans);
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.20f * (0.15f + 0.85f * litFraction);
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength * trans[0];
            rg = 0.36f * moonPeak * moonStrength * trans[1];
            rb = 0.55f * moonPeak * moonStrength * trans[2];
            lightRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        // Cloud cover scatters and blocks the directional source. Keep a small diffuse
        // component during storms so interiors and moonlit nights do not collapse to black.
        float weatherTransmission = (1.0f - 0.78f * rain) * (1.0f - 0.35f * thunder);
        rr *= weatherTransmission;
        rg *= weatherTransmission;
        rb *= weatherTransmission;
        starBrightness *= 1.0f - rain;
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunX, sunY, sunZ, dayFactor),
                new Float4(lx, ly, lz, lightRadius),
                new Float4(rr, rg, rb, starBrightness),
                new Float4(moonX, moonY, moonZ, moonPhase),
                new Float4(0f, celestialAxisY(), celestialAxisZ(), starAngle),
                uv.sun(),
                uv.moon(),
                new Float4(rain, thunder, Math.max(rain, thunder * 0.75f),
                        mc.level != null ? (mc.level.getGameTime() + partial) / 20.0f : 0.0f));
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. Atlas-not-ready (early
     * boot / no resources) leaves full-range UVs and the shader's block-atlas fallback covers it.
     */
    private CelestialUv celestialUv(float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        return new CelestialUv(
                new Float4(sunU0, sunV0, sunU1, sunV1),
                new Float4(moonU0, moonV0, moonU1, moonV1));
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            if (celestialUvAtlasHandle != 0L) {
                TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
                TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
                sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
                TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
                moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
            }
        } catch (Exception ignored) {
            // celestials atlas not yet loaded — keep full-range UVs (fallback texture is the block atlas)
        }
        celestialUvMoonPhase = moonPhase;
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * RGB transmittance from the camera to space along {@code dir} — a verbatim port of
     * {@code world.rmiss}'s {@code transmittanceToSpace} (Rayleigh + Mie + ozone optical depth, 8-step
     * march from 2 km altitude; constants must stay in lock-step with the shader). This is what colours
     * the NEE sun/moonlight: because the sky shader tints its visible discs with the identical function,
     * the light on terrain and the sky's sunset can never disagree. A direction below the geometric
     * horizon accumulates enormous optical depth, so the result rolls to zero smoothly on its own —
     * no explicit planet-shadow test needed.
     */
    private static void atmosphereTransmittance(float dx, float dy, float dz, float[] out) {
        final double planetR = 6371000.0, atmosR = 6471000.0;
        final double[] rayBeta = {5.5e-6, 13.0e-6, 22.4e-6};
        final double mieBeta = 21.0e-6 * 1.1;
        final double[] ozoneBeta = {0.650e-6, 1.881e-6, 0.085e-6};
        final double oy = planetR + 2000.0;
        // Larger root of ray vs atmosphere sphere, origin (0, oy, 0).
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        double seg = tEnd / 8.0;
        double odR = 0.0, odM = 0.0, odO = 0.0;
        for (int i = 0; i < 8; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            odR += Math.exp(-h / 8000.0) * seg;
            odM += Math.exp(-h / 1200.0) * seg;
            odO += Math.max(0.0, 1.0 - Math.abs(h - 25000.0) / 15000.0) * seg;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = (float) Math.exp(-(rayBeta[i] * odR + mieBeta * odM + ozoneBeta[i] * odO));
        }
    }

    private static RtImage createBlueNoiseTexture(RtContext ctx, ByteBuffer data) {
        int size = BlueNoiseGenerator.SIZE;
        int format = VK10.VK_FORMAT_R8_UNORM;
        RtImage img = ctx.createStorageImage(size, size, format, "blue noise " + size + "x" + size);
        // Upload via a temporary staging buffer
        long bytes = data.remaining();
        RtBuffer staging = ctx.createBuffer(bytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, "blue noise staging");
        MemoryUtil.memCopy(data, MemoryUtil.memByteBuffer(staging.mapped, (int) bytes));
        staging.flush(0L, bytes);
        ctx.submitSync(cmd -> {
            try (MemoryStack s2 = MemoryStack.stackPush()) {
                // Transition image to TRANSFER_DST
                VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s2);
                b.get(0).sType$Default()
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(img.image);
                b.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1).layerCount(1);
                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, b);
                // Copy buffer to image
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, s2);
                copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                copy.get(0).imageExtent().set(size, size, 1);
                VK10.vkCmdCopyBufferToImage(cmd, staging.handle, img.image,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                // Transition back to GENERAL
                VkImageMemoryBarrier.Buffer b2 = VkImageMemoryBarrier.calloc(1, s2);
                b2.get(0).sType$Default()
                        .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(img.image);
                b2.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(1).layerCount(1);
                VK10.vkCmdPipelineBarrier(cmd,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, null, null, b2);
            }
        });
        staging.destroy();
        return img;
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so the TLAS ring's slots are no
        // longer in flight and can be freed immediately.
        tlasRing.destroy();
        if (RtDlssRr.enabled()) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        if (distantHorizonsBackground != null) {
            distantHorizonsBackground.destroy();
            distantHorizonsBackground = null;
        }
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (temporalDenoiser != null) {
            temporalDenoiser.destroy();
            temporalDenoiser = null;
        }
        if (spatialDenoiser != null) {
            spatialDenoiser.destroy();
            spatialDenoiser = null;
        }
        if (bmfrDenoiser != null) {
            bmfrDenoiser.destroy();
            bmfrDenoiser = null;
        }
        if (interlaceResolver != null) {
            interlaceResolver.destroy();
            interlaceResolver = null;
        }
        bmfrHasPrevious = false;
        bmfrPauseStateKnown = false;
        oidnDenoiser.destroy();
        nrdDenoiser.destroy(RtContext.currentOrNull());
        nrdHasPrevious = false;
        nrdPauseStateKnown = false;
        if (temporalHistory != null) {
            temporalHistory.destroy();
            temporalHistory = null;
        }
        if (temporalDepthHistory != null) {
            temporalDepthHistory.destroy();
            temporalDepthHistory = null;
        }
        if (temporalNormalHistory != null) {
            temporalNormalHistory.destroy();
            temporalNormalHistory = null;
        }
        if (temporalOutput != null) {
            temporalOutput.destroy();
            temporalOutput = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (blueNoiseImage != null) {
            blueNoiseImage.destroy();
            blueNoiseImage = null;
        }
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtMaterialRegistry.INSTANCE.destroy();
        if (pushRing != null) {
            for (RtBuffer b : pushRing) {
                if (b != null) {
                    b.destroy();
                }
            }
            pushRing = null;
        }
        cachedBlockLights.clear();
        pendingBlockLights.clear();
        cachedBlockLightLevel = null;
        scanningBlockLightLevel = null;
        blockLightScanActive = false;
        blockLightScanIndex = 0;
        blockLightScanTotal = 0;
        cachedBlockLightX = Integer.MIN_VALUE;
        cachedBlockLightY = Integer.MIN_VALUE;
        cachedBlockLightZ = Integer.MIN_VALUE;
        nextBlockLightScanFrame = 0L;
        if (atlasSampler != 0L || materialNormalSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                if (atlasSampler != 0L) VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
                if (materialNormalSampler != 0L) {
                    VK10.vkDestroySampler(ctx.vk(), materialNormalSampler, null);
                }
            }
            atlasSampler = 0L;
            materialNormalSampler = 0L;
        }
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        // Preserve Minecraft's pixel-art texels inside each mip, but blend adjacent mip
                        // levels continuously. Nearest spatial filtering keeps texels crisp while linear
                        // mip interpolation removes camera-centred rings at footprint boundaries.
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private long materialNormalSampler(RtContext ctx) {
        if (materialNormalSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        // Normal/data texels remain point sampled spatially. Adjacent semantic mips blend
                        // continuously so their physical detail reduction cannot form distance rings.
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(material normals) failed");
                }
                materialNormalSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, materialNormalSampler,
                        "material normal sampler");
            }
        }
        return materialNormalSampler;
    }

    private static long blockAlbedoAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.paperWhiteNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.paperWhiteNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Copies a native-resolution image exactly, or point-upscales a genuinely lower-resolution image.
     * The fallback denoisers may render below display resolution; nearest upscaling keeps their output
     * pixel-sharp instead of applying a final full-screen bilinear blur over every material texture.
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        if (src.width == dst.width && src.height == dst.height) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).extent().set(src.width, src.height, 1);
            VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            return;
        }
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_NEAREST);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!RtDlssFg.enabled() || !RtUiOverlay.enabled() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (fgHudlessImage == null || fgHudlessImage.width != main.width || fgHudlessImage.height != main.height) {
            if (fgHudlessImage != null) {
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(main.width, main.height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + main.width + "x" + main.height);
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, main.width, main.height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into Minecraft's
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined {@link RtUiOverlay} texture used by both present paths (only the *compositing* math that
     * consumes it differs, done separately by {@code presentHdr}/{@code RtUiOverlay}, not here).
     */
    public RtImage fgInterpolate(VulkanCommandEncoder enc, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer) {
        if (failed || gDepth == null || gMotion == null || !frameCaptured) {
            return null;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        RtImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        RtImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH
                && RtUiOverlay.overlayColorView() != 0L && RtUiOverlay.overlayColorImage() != 0L;
        long uiView = uiReady ? RtUiOverlay.overlayColorView() : 0L;
        long uiImg = uiReady ? RtUiOverlay.overlayColorImage() : 0L;

        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        boolean ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                backbufferView, backbufferImage, fmt,
                gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                out.view, out.image, fmt,
                swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                true /* cameraMotionIncluded (in mvecs) */, fgReset,
                fgClipToPrev, fgPrevToClip);
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        if (!ok) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
        }
        enc.execute(cmd);
        return out;
    }

    private boolean ensureFgFeature(RtContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(RtContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }

    /** Capture the current complete raw frame immediately after trace/checkerboard resolve. */
    private void copyInterlaceHistory(VkCommandBuffer cmd, MemoryStack stack) {
        if (interlaceHistoryColor == null || interlaceHistoryNormal == null || interlaceHistoryDepth == null
                || interlaceHistoryMotion == null) {
            return;
        }
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        copy.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.get(0).extent().set(renderW, renderH, 1);
        VK10.vkCmdCopyImage(cmd, output.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                interlaceHistoryColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
        VK10.vkCmdCopyImage(cmd, gNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                interlaceHistoryNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
        VK10.vkCmdCopyImage(cmd, gDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                interlaceHistoryDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
        VK10.vkCmdCopyImage(cmd, gMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                interlaceHistoryMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
    }

    /** Clear native-only accumulation after camera/pattern/option resets. */
    private void clearAccumulationHistory(VkCommandBuffer cmd, MemoryStack stack) {
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        if (accumulationHistoryColor != null) {
            VK10.vkCmdClearColorImage(cmd, accumulationHistoryColor.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        if (accumulationHistoryNormal != null) {
            VK10.vkCmdClearColorImage(cmd, accumulationHistoryNormal.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        if (accumulationHistoryDepth != null) {
            VK10.vkCmdClearColorImage(cmd, accumulationHistoryDepth.image,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    /** Clear immutable checkerboard history after a rate/phase change or image recreation. */
    private void clearInterlaceHistory(VkCommandBuffer cmd, MemoryStack stack) {
        interlaceHistoryReady = false;
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        if (interlaceHistoryColor != null) {
            VK10.vkCmdClearColorImage(cmd, interlaceHistoryColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        if (interlaceHistoryNormal != null) {
            VK10.vkCmdClearColorImage(cmd, interlaceHistoryNormal.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        if (interlaceHistoryDepth != null) {
            VK10.vkCmdClearColorImage(cmd, interlaceHistoryDepth.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
        if (interlaceHistoryMotion != null) {
            VK10.vkCmdClearColorImage(cmd, interlaceHistoryMotion.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    /** Clear native-pixel validity when the checkerboard rate or phase policy changes. */
    private void clearInterlaceValidity(VkCommandBuffer cmd, MemoryStack stack) {
        if (interlaceValidity == null) {
            return;
        }
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, interlaceValidity.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
    }

    /** Clear temporal history in the current frame when the sparse sampling pattern changes. */
    private void clearTemporalHistory(VkCommandBuffer cmd, MemoryStack stack) {
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, temporalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        VK10.vkCmdClearColorImage(cmd, temporalDepthHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        VK10.vkCmdClearColorImage(cmd, temporalNormalHistory.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
    }

    /**
     * Clear a GENERAL-layout storage image to zero. Used after creating the temporal denoiser's
     * history image so the first frame's EMA blend does not read UNDEFINED-layout garbage.
     */
    private static void clearImageToZero(RtContext ctx, RtImage img, int width, int height) {
        ctx.submitSync(cmd -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkClearColorValue color = VkClearColorValue.calloc(stack);
                VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
                range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VK10.vkCmdClearColorImage(cmd, img.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
            }
        });
    }
}
