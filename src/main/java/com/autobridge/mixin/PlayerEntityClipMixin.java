package com.autobridge.mixin;

import com.autobridge.AutoBridgeClient;
import com.autobridge.core.BridgeController;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityClipMixin {

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
