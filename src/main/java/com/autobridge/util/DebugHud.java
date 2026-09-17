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

/**
 * 调试 HUD：直接显示"现在为什么没触发"，并给出下一步该怎么做。
 * 开发阶段这个比翻日志有用得多。
 */
public final class DebugHud {

    private static final int WHITE = 0xFFFFFF;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final int YELLOW = 0xFFFF55;
    private static final int GRAY = 0xAAAAAA;
    private static final int AQUA = 0x55FFFF;

    private DebugHud() {
    }

    public static void render(DrawContext context, float tickDelta) {
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

        // 标题：模式后面带上当前搭路方式（蹲搭 / 神桥）—— 两个方式来回测时一眼能看出在跑哪个
        String mode = BridgeConfig.autoMode ? "AUTO" : "已关闭";
        boolean bridging = AutoBridgeClient.CONTROLLER.isBridging();
        context.drawTextWithShadow(client.textRenderer,
                "AutoBridge  " + mode + "·" + BridgeConfig.bridgeMode.displayName()
                        + "  " + (bridging ? "搭路中" : "待机"), x, y, bridging ? GREEN : GRAY);
        y += step;

        // 状态：待机阶段在等启动信号；接管阶段显示还有多久超时
        int idleTicks = AutoBridgeClient.CONTROLLER.getIdleTicks();
        int timeout = Math.max(1, BridgeConfig.idleTimeoutTicks);
        if (!BridgeConfig.autoMode) {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 已关闭（ModMenu 里打开）", x, y, GRAY);
        } else if (bridging) {
            // 状态标签（[接管中·不潜行·A路线]）按用户要求去掉：搭路中只留"还有多久超时"。
            double left = Math.max(0.0D, (timeout - idleTicks) / 20.0D);
            context.drawTextWithShadow(client.textRenderer,
                    String.format("状态: 搭路中 —— 还有 %.1f 秒没有成功放置就结束", left),
                    x, y, GREEN);
        } else {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 待机 —— 蹲在边缘 + 低头 + 在脚下一格放一块启动", x, y, YELLOW);
        }
        y += step;

        // 准星
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

        // 俯仰角：命中侧面需要把视角压下去。
        // ⚠️ 正数 = 向下看（+90 是垂直看脚底），负数 = 抬头看天。
        // 可用区间随眼高变（用户实测）：站立 79°~84°，潜行 77°~83°。
        String band = player.isSneaking() ? "77° ~ 83°" : "79° ~ 84°";
        context.drawTextWithShadow(client.textRenderer,
                "俯仰角: " + String.format("%.0f", player.getPitch()) + "°   正面瞄侧面 +" + band
                        + "（斜站角落可更低）",
                x, y, WHITE);
        y += step;

        // 是否站在边缘 —— 接管期间就是靠这个决定潜行按不按
        boolean onEdge = BridgeValidator.isOnBlockEdge(player, client.world);
        context.drawTextWithShadow(client.textRenderer,
                "站在边缘: " + (onEdge ? "是" : "否")
                        + "  阈值=" + String.format("%.2f", BridgeConfig.edgeMargin) + "格",
                x, y, onEdge ? GREEN : GRAY);
        y += step;

        // 玩家真正踩着的方块 —— 排查几何问题的关键坐标
        BlockPos foot = BridgeValidator.findFootBlock(player, client.world);
        context.drawTextWithShadow(client.textRenderer,
                "踩住方块: " + (foot == null ? "(悬空)" : fmt(foot)), x, y, YELLOW);
        y += step;

        // 期望位
        BlockPos expected = BridgeValidator.expectedPos(player, foot);
        context.drawTextWithShadow(client.textRenderer,
                "期望位: " + (expected == null ? "(不判断)" : fmt(expected)), x, y, YELLOW);
        y += step;

        // 实放位
        context.drawTextWithShadow(client.textRenderer,
                "实放位: " + (result != null && result.placePos() != null ? fmt(result.placePos()) : "-"),
                x, y, YELLOW);
        y += step;

        // 判定行与「→ 下一步怎么做」提示行已按用户要求从 HUD 移除。

        // 潜行 + 手上物品
        // 蹲搭模式模组会强制潜行，所以显示那个开关；神桥模式模组完全不接管潜行。
        boolean godBridgeNow = BridgeConfig.bridgeMode == BridgeConfig.BridgeMode.GOD_BRIDGE;
        context.drawTextWithShadow(client.textRenderer,
                "潜行: " + (player.isSneaking() ? "是" : "否")
                        + (godBridgeNow ? "（模组不接管）" : "  强制潜行=" + BridgeConfig.forceSneak)
                        + "  手上=" + player.getMainHandStack().getItem(),
                x, y, WHITE);
        y += step;

        // A 路线（按住右键）下「按键次数」不是有效口径：按住期间每 tick 都在按，4 tick 才放一块。
        // 所以只显示**世界确认的放成数**，再加**平均块/秒** —— 后者才是判断
        // "跟不跟得上玩家走路（4.317 格/秒）"的那个数字，也是两条路线对比时看的数。
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
