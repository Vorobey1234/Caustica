package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.oidn.OidnRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.lang.foreign.MemorySegment;

/**
 * CPU Open Image Denoise reference backend. Vulkan captures tightly packed RGBA16F buffers; OIDN views
 * their RGB lanes as HALF3 with an 8-byte pixel stride, preserving HDR without a conversion pass.
 */
public final class RtOidnDenoiser {
    private RtBuffer color;
    private RtBuffer albedo;
    private RtBuffer normal;
    private RtBuffer denoised;
    private MemorySegment filter = MemorySegment.NULL;
    private OidnRuntime.Session session;
    private int width = -1;
    private int height = -1;
    private boolean realtime;
    private boolean captured;
    private boolean failed;
    private boolean loggedFirstResult;

    /** Denoise the preceding completed capture. Returns true when a result is ready for this frame. */
    public boolean prepare(RtContext ctx, int width, int height, boolean realtime) {
        if (failed || !ensure(ctx, width, height, realtime) || !captured) {
            return false;
        }
        try {
            // The previous frame wrote the three capture buffers on Vulkan's graphics queue.
            ctx.waitIdle();
            long bytes = byteSize(width, height);
            color.invalidate(0L, bytes);
            albedo.invalidate(0L, bytes);
            normal.invalidate(0L, bytes);
            MemoryUtil.memCopy(color.mapped, denoised.mapped, bytes); // preserve the unused alpha lane
            session.library().executeFilter(filter);
            OidnRuntime.INSTANCE.check("oidnExecuteFilter");
            denoised.flush(0L, bytes);
            if (!loggedFirstResult) {
                loggedFirstResult = true;
                CausticaMod.LOGGER.info("OIDN result is now driving the RT beauty output");
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("Open Image Denoise execution failed; using the built-in denoiser", t);
            return false;
        }
    }

    /** True after the native backend and its Vulkan staging resources were created successfully. */
    public boolean active() {
        return !failed && color != null && filter != null && !filter.equals(MemorySegment.NULL);
    }

    /** Force the next one-shot request to capture a fresh frame instead of filtering an older capture. */
    public void discardCapture() {
        captured = false;
    }

    /** Capture current AOVs and/or replace color with the last prepared result. */
    public void record(VkCommandBuffer cmd, RtImage colorImage, RtImage albedoImage, RtImage normalImage,
                       int width, int height, boolean captureCurrent, boolean applyPrepared) {
        if (failed || color == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(width, height, 1);
            if (captureCurrent) {
                VK10.vkCmdCopyImageToBuffer(cmd, colorImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color.handle, region);
                VK10.vkCmdCopyImageToBuffer(cmd, albedoImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, albedo.handle, region);
                VK10.vkCmdCopyImageToBuffer(cmd, normalImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, normal.handle, region);

                VkMemoryBarrier.Buffer toHost = VkMemoryBarrier.calloc(1, stack);
                toHost.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        0, toHost, null, null);
                captured = true;
            }

            if (applyPrepared) {
                VkMemoryBarrier.Buffer fromHost = VkMemoryBarrier.calloc(1, stack);
                fromHost.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, fromHost, null, null);
                VK10.vkCmdCopyBufferToImage(cmd, denoised.handle, colorImage.image,
                        VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            }
        }
    }

    private boolean ensure(RtContext ctx, int width, int height, boolean realtime) {
        if (this.width == width && this.height == height && this.realtime == realtime && color != null) {
            return true;
        }
        destroyResources();
        session = OidnRuntime.INSTANCE.acquire();
        if (session == null) {
            failed = true;
            return false;
        }
        try {
            long bytes = byteSize(width, height);
            int captureUsage = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            color = ctx.createBuffer(bytes, captureUsage, true, "OIDN color capture " + width + "x" + height);
            albedo = ctx.createBuffer(bytes, captureUsage, true, "OIDN albedo capture " + width + "x" + height);
            normal = ctx.createBuffer(bytes, captureUsage, true, "OIDN normal capture " + width + "x" + height);
            denoised = ctx.createBuffer(bytes, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true,
                    "OIDN denoised output " + width + "x" + height);
            filter = session.library().newRtFilter(session.device());
            if (filter == null || filter.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("oidnNewFilter returned null");
            }
            session.library().setHalf3Image(filter, "color", color.mapped, width, height);
            session.library().setHalf3Image(filter, "albedo", albedo.mapped, width, height);
            session.library().setHalf3Image(filter, "normal", normal.mapped, width, height);
            session.library().setHalf3Image(filter, "output", denoised.mapped, width, height);
            session.library().setBool(filter, "hdr", true);
            session.library().setBool(filter, "cleanAux", true);
            session.library().setInt(filter, "quality", realtime
                    ? dev.comfyfluffy.caustica.oidn.OidnLibrary.QUALITY_FAST
                    : dev.comfyfluffy.caustica.oidn.OidnLibrary.QUALITY_HIGH);
            session.library().commitFilter(filter);
            OidnRuntime.INSTANCE.check("oidnCommitFilter");
            this.width = width;
            this.height = height;
            this.realtime = realtime;
            captured = false;
            CausticaMod.LOGGER.info("OIDN {} denoiser ready: {}x{} HALF3 guides",
                    realtime ? "real-time" : "reference", width, height);
            return true;
        } catch (Throwable t) {
            destroyResources();
            failed = true;
            CausticaMod.LOGGER.error("Could not create Open Image Denoise filter", t);
            return false;
        }
    }

    public void destroy() {
        destroyResources();
        OidnRuntime.INSTANCE.shutdown();
        failed = false;
    }

    private void destroyResources() {
        if (session != null && filter != null && !filter.equals(MemorySegment.NULL)) {
            session.library().releaseFilter(filter);
        }
        filter = MemorySegment.NULL;
        if (color != null) color.destroy();
        if (albedo != null) albedo.destroy();
        if (normal != null) normal.destroy();
        if (denoised != null) denoised.destroy();
        color = albedo = normal = denoised = null;
        width = height = -1;
        realtime = false;
        captured = false;
        loggedFirstResult = false;
    }

    private static long byteSize(int width, int height) {
        return Math.multiplyExact(Math.multiplyExact((long) width, height), 8L);
    }
}
