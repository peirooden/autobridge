package com.autobridge.mixin;

import com.autobridge.AutoBridgeClient;
import com.autobridge.core.BridgeController;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「神桥」搭路方式的全部机制：接管期间打开原版的「边缘钳制」，别的什么都不做。
 *
 * <p>蹲搭模式一律不生效（门在 {@link BridgeController#isClampActive()} 里）——
 * 那时模组靠强制潜行，原版的钳制本来就生效，不需要也不该由注入去干预。
 *
 * <p><b>为什么注入这里，而不是自己重写钳制算法</b>
 * 原版这条防掉保护的正门只有一个：{@code PlayerEntity.clipAtLedge()}，方法体就一行
 * {@code return this.isSneaking();}。它被两处消费：
 * <ol>
 *   <li>{@code PlayerEntity.adjustMovementForSneaking(Vec3d, MovementType)}
 *       —— 按 0.05 格步长逐次收水平位移，收到「碰撞箱下沉 stepHeight 后就碰不到方块了」为止，
 *       也就是把人钉在边缘上，不会走出去掉下去。
 *       （{@code Entity} 上的同名方法是<b>空实现</b>，只有 PlayerEntity 这个覆盖版本干活。）</li>
 *   <li>{@code ClientPlayerEntity} 的自动跳跃判定 —— {@code clipAtLedge()} 为真时不自动跳。
 *       这对我们正好有益：站在边缘上自动跳会把人弹出去。</li>
 * </ol>
 * 所以这里只把开关置真，让原版原样跑，钳制粒度与边界判定都与原版一致。
 *
 * <p><b>它不是潜行</b>：不改姿态、不改移速、不改视角、不改朝向，服务端也看不到潜行。
 * 原版方法里剩下的前置条件（{@code !abilities.flying}、{@code movement.y <= 0}、
 * {@code MovementType.SELF/PLAYER}、{@code method_30263()} = 在地上或即将落地）
 * 全部保持原样 —— 创造飞行时不受这条保护。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityClipMixin {

    /** 只打一条「注入真的生效了」的证据，免得后面一直在猜钳制到底有没有开。 */
    private static boolean autobridge$clampLogged;

    @Inject(method = "clipAtLedge", at = @At("HEAD"), cancellable = true)
    private void autobridge$clipAtLedgeWhileBridging(CallbackInfoReturnable<Boolean> cir) {
        BridgeController controller = AutoBridgeClient.CONTROLLER;
        if (controller != null && controller.isClampActive()) {
            if (!autobridge$clampLogged) {
                autobridge$clampLogged = true;
                AutoBridgeClient.LOGGER.info(
                        "[AutoBridge] 神桥：边缘钳制已开启（clipAtLedge 注入生效，接管期间不会走出方块边缘）");
            }
            cir.setReturnValue(true);
        }
    }
}
