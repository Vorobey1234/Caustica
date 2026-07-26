package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Converts Caustica's HW-depth beauty AOV into NRD's radiance+hit-distance and linear view-Z inputs. */
final class RtNrdPacker {
    private static final int IMAGE_COUNT = 7;
    private final RtContext ctx;
    private final long descriptorSetLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline;

    private RtNrdPacker(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    static RtNrdPacker create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; i++) bindings.get(i).binding(i)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            long dsl = createDescriptorSetLayout(vk, stack, bindings);
            long pool = createDescriptorPool(vk, stack);
            long set = allocateSet(vk, stack, dsl, pool);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).size(16 * Float.BYTES + Integer.BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range);
            var out = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out), "vkCreatePipelineLayout(NRD pack)");
            long layout = out.get(0);
            long module = loadModule(vk, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, 0L, pipelineInfo, null, out), "vkCreateComputePipelines(NRD pack)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "NRD input packer");
            return new RtNrdPacker(ctx, dsl, pool, set, layout, pipeline);
        }
    }

    void dispatch(VkCommandBuffer cmd, RtImage color, RtImage depth, RtImage sourceNormalRoughness,
                  RtImage signal, RtImage packedNormalRoughness, RtImage viewZ,
                  RtImage diffuseAlbedoAndNrdHitDistance,
                  Matrix4fc inverseProjection, int method) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            long[] views = {color.view, depth.view, sourceNormalRoughness.view, signal.view,
                    packedNormalRoughness.view, viewZ.view, diffuseAlbedoAndNrdHitDistance.view};
            for (int i = 0; i < IMAGE_COUNT; i++) infos.get(i).imageView(views[i])
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; i++) {
                infos.position(i);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(infos);
            }
            infos.position(0);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(16 * Float.BYTES + Integer.BYTES);
            inverseProjection.get(push.asFloatBuffer());
            push.putInt(16 * Float.BYTES, method);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (color.width + 7) / 8, (color.height + 7) / 8, 1);
        }
    }

    void destroy() {
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
    }

    private static long createDescriptorSetLayout(VkDevice vk, MemoryStack stack,
                                                   VkDescriptorSetLayoutBinding.Buffer bindings) {
        var info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
        var out = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(vk, info, null, out), "vkCreateDescriptorSetLayout(NRD pack)");
        return out.get(0);
    }

    private static long createDescriptorPool(VkDevice vk, MemoryStack stack) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
        size.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMAGE_COUNT);
        var info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(size);
        var out = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(vk, info, null, out), "vkCreateDescriptorPool(NRD pack)");
        return out.get(0);
    }

    private static long allocateSet(VkDevice vk, MemoryStack stack, long dsl, long pool) {
        var info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(stack.longs(dsl));
        var out = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, info, out), "vkAllocateDescriptorSets(NRD pack)");
        return out.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = RtNrdPacker.class.getResourceAsStream("/caustica/rt/nrd_pack.comp.spv")) {
            if (in == null) throw new IllegalStateException("missing NRD pack SPIR-V");
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read NRD pack SPIR-V", e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            var out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, out), "vkCreateShaderModule(NRD pack)");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
