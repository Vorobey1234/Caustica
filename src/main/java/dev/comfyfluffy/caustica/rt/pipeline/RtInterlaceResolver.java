package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Maintains native-resolution checkerboard history for Ray Budget. A validity image records which
 * exact pixels have received a real ray. Missing pixels are copied from a current traced neighbour only
 * during warm-up; once a pixel's rotating phase is traced, subsequent unsampled frames preserve that
 * native pixel instead of repeatedly stretching one sample over the whole tile.
 */
public final class RtInterlaceResolver {
    private static final int IMAGE_COUNT = 8;
    private static final int PUSH_BYTES = Integer.BYTES * 5;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;

    private RtInterlaceResolver(RtContext ctx, long descriptorSetLayout, long descriptorPool,
                                long descriptorSet, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtInterlaceResolver create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }

            var out = stack.mallocLong(1);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out),
                    "vkCreateDescriptorSetLayout(interlace resolve)");
            long dsl = out.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMAGE_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out),
                    "vkCreateDescriptorPool(interlace resolve)");
            long pool = out.get(0);

            VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            check(VK10.vkAllocateDescriptorSets(vk, setInfo, out),
                    "vkAllocateDescriptorSets(interlace resolve)");
            long set = out.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out),
                    "vkCreatePipelineLayout(interlace resolve)");
            long layout = out.get(0);

            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, 0L, pipelineInfo, null, out),
                    "vkCreateComputePipelines(interlace resolve)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "ray-budget interlace resolve");
            return new RtInterlaceResolver(ctx, dsl, pool, set, layout, pipeline);
        }
    }

    /** Called only after the owning images were created or resized under a device-idle boundary. */
    public void setImages(RtImage color, RtImage normal, RtImage albedo, RtImage depth,
                          RtImage motion, RtImage specAlbedo, RtImage specMotion,
                          RtImage validity) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtImage[] images = {color, normal, albedo, depth, motion, specAlbedo, specMotion, validity};
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; i++) {
                infos.get(i).imageView(images[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                infos.position(i);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(infos);
            }
            infos.position(0);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    public void dispatch(VkCommandBuffer cmd, int width, int height, int divisor,
                         boolean jitterEnabled, int frameIndex) {
        int tileWidth = divisor >= 8 ? 4 : (divisor >= 2 ? 2 : 1);
        int tileHeight = divisor >= 16 ? 4 : (divisor >= 2 ? 2 : 1);
        int tilesX = (width + tileWidth - 1) / tileWidth;
        int tilesY = (height + tileHeight - 1) / tileHeight;
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "ray-budget interlace resolve")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putInt(8, divisor);
            push.putInt(12, jitterEnabled ? 1 : 0);
            push.putInt(16, frameIndex);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (tilesX + 7) / 8, (tilesY + 7) / 8, 1);
        }
    }

    public void destroy() {
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = RtInterlaceResolver.class.getResourceAsStream(
                "/caustica/rt/interlace_resolve.comp.spv")) {
            if (in == null) throw new IllegalStateException("missing interlace resolve SPIR-V");
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read interlace resolve SPIR-V", e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            var out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, out),
                    "vkCreateShaderModule(interlace resolve)");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
