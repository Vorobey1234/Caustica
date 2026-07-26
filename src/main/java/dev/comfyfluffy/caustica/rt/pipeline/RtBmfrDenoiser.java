package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4fc;
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
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Cross-vendor real-time Blockwise Multi-Order Feature Regression denoiser.
 *
 * <p>The compute shader fits a regularized polynomial of primary position, normal and albedo to each
 * staggered 8x8 block, then reprojects the fitted image through Caustica's motion/depth/normal guides.
 * It follows the BMFR reconstruction described by Koskela et al. while using a compact per-workgroup
 * normal-equation solve instead of the reference implementation's very large full-frame QR scratch.</p>
 */
public final class RtBmfrDenoiser {
    private static final String SHADER_DIR = "/caustica/rt/";
    private static final int IMAGE_COUNT = 9;
    private static final int PUSH_BYTES = 96;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] boundViews = new long[IMAGE_COUNT];
    private boolean destroyed;

    private RtBmfrDenoiser(RtContext ctx, long descriptorSetLayout, long descriptorPool,
                           long descriptorSet, long pipelineLayout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static RtBmfrDenoiser create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; ++i) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, descriptorLayoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(BMFR)");
            long descriptorSetLayout = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, descriptorSetLayout,
                    "BMFR descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMAGE_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, handle),
                    "vkCreateDescriptorPool(BMFR)");
            long descriptorPool = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, descriptorPool,
                    "BMFR descriptor pool");

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer setHandle = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, setHandle),
                    "vkAllocateDescriptorSets(BMFR)");
            long descriptorSet = setHandle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorSet,
                    "BMFR descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(BMFR)");
            long pipelineLayout = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, pipelineLayout,
                    "BMFR pipeline layout");

            long module = loadModule(vk, stack, "bmfr.comp.spv");
            long pipeline;
            try {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module,
                        "BMFR shader module");
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                LongBuffer pipelineHandle = stack.mallocLong(1);
                check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, pipelineInfo,
                                null, pipelineHandle),
                        "vkCreateComputePipelines(BMFR)");
                pipeline = pipelineHandle.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "BMFR pipeline");
            } finally {
                VK10.vkDestroyShaderModule(vk, module, null);
            }
            return new RtBmfrDenoiser(ctx, descriptorSetLayout, descriptorPool, descriptorSet,
                    pipelineLayout, pipeline);
        }
    }

    public void dispatch(VkCommandBuffer cmd, int width, int height,
                         RtImage noisyColor, RtImage depth, RtImage motion, RtImage normal, RtImage albedo,
                         RtImage historyColor, RtImage historyDepth, RtImage historyNormal, RtImage output,
                         Matrix4fc inverseViewProjection, int frameIndex, boolean resetHistory) {
        if (destroyed) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "BMFR realtime denoiser")) {
            long view0 = noisyColor.view;
            long view1 = depth.view;
            long view2 = motion.view;
            long view3 = normal.view;
            long view4 = albedo.view;
            long view5 = historyColor.view;
            long view6 = historyDepth.view;
            long view7 = historyNormal.view;
            long view8 = output.view;
            if (boundViews[0] != view0 || boundViews[1] != view1 || boundViews[2] != view2
                    || boundViews[3] != view3 || boundViews[4] != view4 || boundViews[5] != view5
                    || boundViews[6] != view6 || boundViews[7] != view7 || boundViews[8] != view8) {
                // Image views are stable between resizes. Avoid nine descriptor writes and a driver call on
                // every frame; refresh only when ensureOutput has recreated one of the guide/history images.
                VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
                imageInfos.get(0).imageView(view0).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(1).imageView(view1).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(2).imageView(view2).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(3).imageView(view3).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(4).imageView(view4).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(5).imageView(view5).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(6).imageView(view6).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(7).imageView(view7).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(8).imageView(view8).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_COUNT, stack);
                for (int i = 0; i < IMAGE_COUNT; ++i) {
                    imageInfos.position(i);
                    writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                            .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(imageInfos);
                }
                imageInfos.position(0);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
                boundViews[0] = view0;
                boundViews[1] = view1;
                boundViews[2] = view2;
                boundViews[3] = view3;
                boundViews[4] = view4;
                boundViews[5] = view5;
                boundViews[6] = view6;
                boundViews[7] = view7;
                boundViews[8] = view8;
            }

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            inverseViewProjection.get(0, push);
            push.putFloat(64, 0.035f); // stricter relative reversed-Z depth rejection
            push.putFloat(68, 0.95f);  // stricter normal agreement at disocclusions
            push.putFloat(72, 0.64f);  // preserve texture detail while retaining temporal stability
            push.putFloat(76, 0.012f); // regression ridge
            push.putInt(80, frameIndex);
            push.putInt(84, resetHistory ? 1 : 0);
            push.putInt(88, 0).putInt(92, 0);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, push);
            // A -4 pixel stagger needs exactly ceil((extent + 4) / 8) groups. The old ceil(extent/8)+1
            // over-dispatched a complete guard row/column for several common non-multiple-of-eight sizes.
            VK10.vkCmdDispatch(cmd, Math.ceilDiv(width + 4, 8), Math.ceilDiv(height + 4, 8), 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream input = RtBmfrDenoiser.class.getResourceAsStream(SHADER_DIR + name)) {
            if (input == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer moduleHandle = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, moduleInfo, null, moduleHandle),
                    "vkCreateShaderModule(" + name + ")");
            return moduleHandle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
