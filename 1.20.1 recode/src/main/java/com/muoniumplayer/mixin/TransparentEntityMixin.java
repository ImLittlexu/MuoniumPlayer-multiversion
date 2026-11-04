package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.TransparentMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「通透模式」的实体压缩：把果冻矩阵包住原版的实体渲染括号。
 *
 * <p>1.20.1/1.21.1 走单段式 {@code render(Entity, ...)}：在首次
 * {@code translate} 落地后压矩阵，在火焰动画读取前还原——火焰、阴影、
 * 碰撞箱调试框都画在括号外。26.x 的 extract/submit 双段式见各该树的
 * 同名 mixin。缩放只动本机画面，不碰碰撞箱与服务端状态。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class TransparentEntityMixin {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void transmode$begin(Entity entity, double x, double y, double z, float yaw, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!(entity instanceof LivingEntity) || !TransparentMode.isEnabled()) return;
        float heightScale = TransparentMode.heightScale();
        // 零节拍、回弹拉高与只调宽度时也须 push，与 end 的同一开关条件配对。
        pose.pushPose();
        pose.scale(TransparentMode.widthScale(), heightScale, 1f);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"))
    private void transmode$end(Entity entity, double x, double y, double z, float yaw, float partialTick,
                               PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        // 与 begin 使用相同条件，不能按高度缩放值决定是否压栈。
        if (entity instanceof LivingEntity && TransparentMode.isEnabled()) pose.popPose();
    }
}
