package com.autobridge.util;

import com.autobridge.AutoBridgeClient;
import com.autobridge.config.BridgeConfig;
import com.autobridge.core.BridgeValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class DebugHud {

    private static final int WHITE = 0xFFFFFF;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final int YELLOW = 0xFFFF55;
    private static final int GRAY = 0xAAAAAA;
    private static final int AQUA = 0x55FFFF;

    private DebugHud() {
    }

    public static void render(DrawContext context) {
        if (!BridgeConfig.debugHud) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) {
            return;
        }

        ClientPlayerEntity player = client.player;
        BridgeValidator.Result result = AutoBridgeClient.CONTROLLER.getLastResult();

        int x = 6;
        int y = 6;
        int step = 10;

        String mode = BridgeConfig.autoMode ? "AUTO" : "已关闭";
        boolean bridging = AutoBridgeClient.CONTROLLER.isBridging();
        context.drawTextWithShadow(client.textRenderer,
                "AutoBridge  " + mode + "·" + BridgeConfig.bridgeMode.displayName()
                        + "  " + (bridging ? "搭路中" : "待机"), x, y, bridging ? GREEN : GRAY);
        y += step;

        int idleTicks = AutoBridgeClient.CONTROLLER.getIdleTicks();
        int timeout = Math.max(1, BridgeConfig.idleTimeoutTicks);
        if (!BridgeConfig.autoMode) {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 已关闭（ModMenu 里打开）", x, y, GRAY);
        } else if (bridging) {
            double left = Math.max(0.0D, (timeout - idleTicks) / 20.0D);
            context.drawTextWithShadow(client.textRenderer,
                    String.format("状态: 搭路中 —— 还有 %.1f 秒没有成功放置就结束", left),
                    x, y, GREEN);
        } else {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 待机 —— 蹲在边缘 + 低头 + 在脚下一格放一块启动", x, y, YELLOW);
        }
        y += step;

        HitResult target = client.crosshairTarget;
        BlockHitResult blockHit = (target instanceof BlockHitResult b && target.getType() == HitResult.Type.BLOCK)
                ? b : null;
        if (blockHit != null) {
            context.drawTextWithShadow(client.textRenderer,
                    "准星: " + fmt(blockHit.getBlockPos()) + " 面=" + blockHit.getSide().getName()
                            + " 距离=" + String.format("%.2f", player.getEyePos().distanceTo(blockHit.getPos())),
                    x, y, WHITE);
        } else {
            context.drawTextWithShadow(client.textRenderer, "准星: 未命中方块", x, y, RED);
        }
        y += step;

        String band = player.isSneaking() ? "77° ~ 83°" : "79° ~ 84°";
        context.drawTextWithShadow(client.textRenderer,
                "俯仰角: " + String.format("%.0f", player.getPitch()) + "°   正面瞄侧面 +" + band
                        + "（斜站角落可更低）",
                x, y, WHITE);
        y += step;

        boolean onEdge = BridgeValidator.isOnBlockEdge(player, client.world);
        context.drawTextWithShadow(client.textRenderer,
                "站在边缘: " + (onEdge ? "是" : "否")
                        + "  阈值=" + String.format("%.2f", BridgeConfig.edgeMargin) + "格",
                x, y, onEdge ? GREEN : GRAY);
        y += step;

        BlockPos foot = BridgeValidator.findFootBlock(player, client.world);
        context.drawTextWithShadow(client.textRenderer,
                "踩住方块: " + (foot == null ? "(悬空)" : fmt(foot)), x, y, YELLOW);
        y += step;

        BlockPos expected = BridgeValidator.expectedPos(player, foot);
        context.drawTextWithShadow(client.textRenderer,
                "期望位: " + (expected == null ? "(不判断)" : fmt(expected)), x, y, YELLOW);
        y += step;

        context.drawTextWithShadow(client.textRenderer,
                "实放位: " + (result != null && result.placePos() != null ? fmt(result.placePos()) : "-"),
                x, y, YELLOW);
        y += step;

        boolean godBridgeNow = BridgeConfig.bridgeMode == BridgeConfig.BridgeMode.GOD_BRIDGE;
        context.drawTextWithShadow(client.textRenderer,
                "潜行: " + (player.isSneaking() ? "是" : "否")
                        + (godBridgeNow ? "（模组不接管）" : "  强制潜行=" + BridgeConfig.forceSneak)
                        + "  手上=" + player.getMainHandStack().getItem(),
                x, y, WHITE);
        y += step;

        int confirmed = AutoBridgeClient.CONTROLLER.getConfirmedPlaceCount();
        double avg = AutoBridgeClient.CONTROLLER.getAverageBlocksPerSecond();
        context.drawTextWithShadow(client.textRenderer,
                "放成: " + confirmed + " 次   平均 " + String.format("%.2f", avg) + " 块/秒",
                x, y, confirmed > 0 ? GREEN : GRAY);
    }

    private static String fmt(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
