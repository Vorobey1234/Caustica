package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.FloatBuffer;

/** Selectable NRD 4.17.3 integration based on NVIDIA's vk_denoise_nrd sample. */
public final class RtNrdDenoiser {
    private static final int METHOD_RELAX = 0;
    private static final int METHOD_REBLUR = 1;
    private static final int METHOD_REFERENCE = 2;

    private final Matrix4f inverseProjection = new Matrix4f();
    private RtNrdPacker packer;
    private RtNrdResolver resolver;
    private RtImage signal;
    private RtImage nrdNormalRoughness;
    private RtImage viewZ;
    private RtImage denoised;
    private NrdLibrary library;
    private MemorySegment nativeContext = MemorySegment.NULL;
    private int width = -1, height = -1;
    private boolean failed;
    private boolean firstFrame = true;
    private int lastMethod = -1;

    public boolean active() {
        return !failed && !nativeContext.equals(MemorySegment.NULL);
    }

    public boolean dispatch(RtContext ctx, VkCommandBuffer cmd, int width, int height,
                            RtImage noisyColor, RtImage hardwareDepth, RtImage motion,
                            RtImage normalRoughness, RtImage diffuseAlbedoAndNrdHitDistance,
                            Matrix4fc projection, Matrix4fc previousProjection,
                            Matrix4fc worldToView, Matrix4fc previousWorldToView, int frameIndex,
                            boolean resetHistory) {
        if (failed || !ensure(ctx, width, height)) return false;
        try {
            int method = selectedMethod();
            boolean methodChanged = method != lastMethod;
            packer.dispatch(cmd, noisyColor, hardwareDepth, normalRoughness, signal, nrdNormalRoughness, viewZ,
                    diffuseAlbedoAndNrdHitDistance,
                    inverseProjection.set(projection).invert(), method);
            barrier(cmd);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment p = matrix(arena, projection);
                MemorySegment pp = matrix(arena, previousProjection);
                MemorySegment v = matrix(arena, worldToView);
                MemorySegment pv = matrix(arena, previousWorldToView);
                if (!library.denoise(nativeContext, cmd.address(), signal.image, motion.image,
                        nrdNormalRoughness.image, viewZ.image, denoised.image,
                        p, pp, v, pv, frameIndex, method,
                        resetHistory || firstFrame || methodChanged)) {
                    throw new IllegalStateException("NRD bridge rejected denoise dispatch: " + library.lastError());
                }
            }
            firstFrame = false;
            lastMethod = method;
            barrier(cmd);
            // NRD never writes sky/miss pixels. Resolve valid NRD pixels selectively instead of
            // copying undefined output over the ray-traced sky.
            resolver.dispatch(cmd, signal, viewZ, denoised, noisyColor, method);
            barrier(cmd);
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(false);
            CausticaMod.LOGGER.error("NRD dispatch failed; falling back to another denoiser", t);
            return false;
        }
    }

    private boolean ensure(RtContext ctx, int width, int height) {
        if (active() && this.width == width && this.height == height) return true;
        destroy(ctx);
        library = NrdRuntime.INSTANCE.acquire();
        if (library == null) {
            failed = true;
            return false;
        }
        try {
            packer = RtNrdPacker.create(ctx);
            resolver = RtNrdResolver.create(ctx);
            signal = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "NRD radiance + hit distance");
            nrdNormalRoughness = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "NRD packed normal roughness");
            viewZ = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT, "NRD linear view-Z");
            denoised = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "NRD output");
            var device = ctx.vk();
            var physical = device.getPhysicalDevice();
            nativeContext = library.create(physical.getInstance().address(), physical.address(), device.address(),
                    ctx.graphicsQueueFamilyIndex(), width, height);
            if (nativeContext.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("NRD context creation failed: " + library.lastError());
            }
            this.width = width;
            this.height = height;
            firstFrame = true;
            lastMethod = -1;
            CausticaMod.LOGGER.info("NRD 4.17.3 ReLAX/ReBLUR/Reference ready: {}x{}", width, height);
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(false);
            CausticaMod.LOGGER.error("Could not initialize NRD", t);
            destroy(ctx);
            return false;
        }
    }

    public void destroy(RtContext ctx) {
        if (library != null && !nativeContext.equals(MemorySegment.NULL)) {
            if (ctx != null) ctx.waitIdle();
            library.destroy(nativeContext);
        }
        nativeContext = MemorySegment.NULL;
        if (packer != null) packer.destroy();
        if (resolver != null) resolver.destroy();
        if (signal != null) signal.destroy();
        if (nrdNormalRoughness != null) nrdNormalRoughness.destroy();
        if (viewZ != null) viewZ.destroy();
        if (denoised != null) denoised.destroy();
        packer = null;
        resolver = null;
        signal = nrdNormalRoughness = viewZ = denoised = null;
        width = height = -1;
        firstFrame = true;
        lastMethod = -1;
    }

    private static int selectedMethod() {
        return switch (CausticaConfig.Rt.Denoiser.NRD_METHOD.get()) {
            case "reblur" -> METHOD_REBLUR;
            case "reference" -> METHOD_REFERENCE;
            default -> METHOD_RELAX;
        };
    }

    private static MemorySegment matrix(Arena arena, Matrix4fc matrix) {
        MemorySegment segment = arena.allocate(16L * Float.BYTES, 16);
        FloatBuffer values = segment.asByteBuffer().asFloatBuffer();
        matrix.get(values);
        return segment;
    }

    private static void barrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT
                            | VK10.VK_ACCESS_TRANSFER_READ_BIT | VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }
    }
}
