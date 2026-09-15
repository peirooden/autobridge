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

        // 标题
        String mode = BridgeConfig.autoMode ? "AUTO(常开)" : "已关闭";
        boolean active = AutoBridgeClient.CONTROLLER.isActive();
        context.drawTextWithShadow(client.textRenderer,
                "AutoBridge  " + mode + "  " + (active ? "激活中" : "未激活"), x, y, active ? GREEN : GRAY);
        y += step;

        // 方向
        context.drawTextWithShadow(client.textRenderer,
                "方向: " + BridgeConfig.directionName(), x, y, WHITE);
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

        // 俯仰角：命中侧面需要把视角压下去
        context.drawTextWithShadow(client.textRenderer,
                "俯仰角: " + String.format("%.0f", player.getPitch()) + "°   命中侧面通常要 -45° ~ -70°",
                x, y, WHITE);
        y += step;

        // 是否站在边缘 —— AUTO 就是靠这个激活的
        boolean onEdge = BridgeValidator.isOnBlockEdge(player, client.world);
        context.drawTextWithShadow(client.textRenderer,
                "站在边缘: " + (onEdge ? "是" : "否")
                        + "  阈值=" + String.format("%.2f", BridgeConfig.edgeMargin) + "格 [N键切换]"
                        + "  往外看=" + BridgeConfig.edgeLookAhead + "格"
                        + (BridgeConfig.autoMode ? "  [AUTO 靠这个激活]" : ""),
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

        // 判定
        if (result == null) {
            context.drawTextWithShadow(client.textRenderer, "判定: (未激活)", x, y, GRAY);
        } else if (result.ok()) {
            context.drawTextWithShadow(client.textRenderer, "判定: OK —— 模拟右键", x, y, GREEN);
        } else {
            context.drawTextWithShadow(client.textRenderer, "判定: " + result.reason(), x, y, RED);
        }
        y += step;

        // 下一步该怎么做
        String hint = buildHint(result, blockHit);
        if (hint != null) {
            context.drawTextWithShadow(client.textRenderer, "→ " + hint, x, y, AQUA);
            y += step;
        }

        // 潜行 + 手上物品
        context.drawTextWithShadow(client.textRenderer,
                "潜行: " + (player.isSneaking() ? "是" : "否")
                        + "  强制潜行=" + BridgeConfig.forceSneak
                        + "  手上=" + player.getMainHandStack().getItem(),
                x, y, WHITE);
        y += step;

        // 触发计数：用来确认模组真的在动
        int count = AutoBridgeClient.CONTROLLER.getPlaceCount();
        int lastAt = AutoBridgeClient.CONTROLLER.getLastPlaceAt();
        int now = AutoBridgeClient.CONTROLLER.getTickCounter();
        String ago = (lastAt < 0) ? "从未" : ((now - lastAt) / 20) + "秒前";
        context.drawTextWithShadow(client.textRenderer,
                "已模拟右键: " + count + " 次   最近: " + ago,
                x, y, count > 0 ? GREEN : GRAY);
    }

    /** 把"被哪一条挡下"翻译成"下一步该做什么"。 */
    private static String buildHint(BridgeValidator.Result result, BlockHitResult blockHit) {
        if (!BridgeConfig.autoMode) {
            return "自动搭路已关闭 → 在 ModMenu 里打开，或按右 Alt";
        }

        // ---- 边缘搭路的提示 ----
        if (!AutoBridgeClient.CONTROLLER.isActive()) {
            return "AUTO：走到方块边缘就会自动潜行并尝试放置";
        }
        if (blockHit == null) {
            return "准星没打到方块：低头看脚边的方块";
        }
        if (blockHit.getSide() == Direction.UP) {
            return "命中的是顶面 → 站到方块边缘 + 再低头，让准星落到侧面";
        }
        if (result == null || result.ok()) {
            return null;
        }
        String r = result.reason();
        if (r.contains("脚下层")) {
            return "再低头一点，别让射线越过脚下方块落到下一层";
        }
        if (r.contains("水平距离")) {
            return "离脚下方块太远，往目标方块靠近一点";
        }
        if (r.contains("方向")) {
            return "方向不对：按 B 轮换方向模式";
        }
        if (r.contains("未潜行")) {
            return "潜行刚按下（要 1 tick 同步到服务端），按住别松";
        }
        if (r.contains("支撑")) {
            return "要站在方块上（别跳、别飞、别游泳）";
        }
        if (r.contains("canPlace")) {
            return "那个位置放不了：已经有方块，或者不是可替换方块";
        }
        if (r.contains("碰撞")) {
            return "会卡住自己，换个站位";
        }
        if (r.contains("交互距离")) {
            return "太远了，靠近一点";
        }
        return null;
    }

    private static String fmt(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
