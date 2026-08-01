package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Composites the NRD result only where NRD has a valid primary-surface depth.
 * NRD intentionally leaves rays that miss the scene unwritten; copying its whole output made the sky black.
 */
final class RtNrdResolver {
    private static final int IMAGE_COUNT = 6;
    private final RtContext ctx;
    private final long descriptorSetLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline;

    private RtNrdResolver(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        descriptorSetLayout = dsl;
        descriptorPool = pool;
        descriptorSet = set;
        pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    static RtNrdResolver create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int i = 0; i < IMAGE_COUNT; i++) bindings.get(i).binding(i)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            var out = stack.mallocLong(1);
            var dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslInfo, null, out), "vkCreateDescriptorSetLayout(NRD resolve)");
            long dsl = out.get(0);
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMAGE_COUNT);
            var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSize);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, out), "vkCreateDescriptorPool(NRD resolve)");
            long pool = out.get(0);
            var setInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                    .pSetLayouts(stack.longs(dsl));
            check(VK10.vkAllocateDescriptorSets(vk, setInfo, out), "vkAllocateDescriptorSets(NRD resolve)");
            long set = out.get(0);
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).size(Float.BYTES + Integer.BYTES);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(range);
            check(VK10.vkCreatePipelineLayout(vk, layoutInfo, null, out), "vkCreatePipelineLayout(NRD resolve)");
            long layout = out.get(0);
            long module = loadModule(vk, stack);
            var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            var pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(vk, 0L, pipelineInfo, null, out), "vkCreateComputePipelines(NRD resolve)");
            long pipeline = out.get(0);
            VK10.vkDestroyShaderModule(vk, module, null);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "NRD sky-safe resolve");
            return new RtNrdResolver(ctx, dsl, pool, set, layout, pipeline);
        }
    }

    void dispatch(VkCommandBuffer cmd, RtImage signal, RtImage viewZ, RtImage denoised, RtImage output,
                  RtImage diffuseAlbedoAndNrdHitDistance, RtImage relaxResidual, int method) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            long[] views = {signal.view, viewZ.view, denoised.view, output.view,
                    diffuseAlbedoAndNrdHitDistance.view, relaxResidual.view};
            for (int i = 0; i < IMAGE_COUNT; i++) infos.get(i).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
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
            ByteBuffer push = stack.malloc(Float.BYTES + Integer.BYTES);
            push.putFloat(0, 50000.0f).putInt(Float.BYTES, method);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (output.width + 7) / 8, (output.height + 7) / 8, 1);
        }
    }

    void destroy() {
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = RtNrdResolver.class.getResourceAsStream("/caustica/rt/nrd_resolve.comp.spv")) {
            if (in == null) throw new IllegalStateException("missing NRD resolve SPIR-V");
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read NRD resolve SPIR-V", e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            var out = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, info, null, out), "vkCreateShaderModule(NRD resolve)");
            return out.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
