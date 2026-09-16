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
        boolean bridging = AutoBridgeClient.CONTROLLER.isBridging();
        context.drawTextWithShadow(client.textRenderer,
                "AutoBridge  " + mode + "  " + (bridging ? "搭路中" : "待机"), x, y, bridging ? GREEN : GRAY);
        y += step;

        // 状态：待机阶段在等启动信号；接管阶段显示还有多久超时
        int idleTicks = AutoBridgeClient.CONTROLLER.getIdleTicks();
        int timeout = Math.max(1, BridgeConfig.idleTimeoutTicks);
        var ctrl = AutoBridgeClient.CONTROLLER;
        if (!BridgeConfig.autoMode) {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 已关闭（ModMenu 里打开）", x, y, GRAY);
        } else if (bridging) {
            // 潜行是「在边缘才按」——所以要显示当前到底按没按，否则看不出它是不是卡住了
            boolean sneakingNow = ctrl.isActive();
            double left = Math.max(0.0D, (timeout - idleTicks) / 20.0D);
            context.drawTextWithShadow(client.textRenderer,
                    String.format("状态: 搭路中 [%s] —— 还有 %.1f 秒无放置就结束",
                            sneakingNow ? "边缘潜行中" : "不在边缘·潜行已松", left),
                    x, y, sneakingNow ? GREEN : YELLOW);
        } else {
            context.drawTextWithShadow(client.textRenderer,
                    "状态: 待机 —— 蹲在边缘 + 低头 + 在脚下一格放一块启动", x, y, YELLOW);
            y += step;

            // 五个启动条件各自的实时状态：一眼看出卡在哪一条
            context.drawTextWithShadow(client.textRenderer,
                    "  条件: 蹲" + mark(ctrl.isIdleSneaking())
                            + " 边缘" + mark(ctrl.isIdleOnEdge())
                            + " 低头" + mark(ctrl.isIdleHeadDown())
                            + " 手持" + mark(ctrl.isIdleHolding())
                            + " 脚下已放" + mark(ctrl.isIdleFootSolid()),
                    x, y, WHITE);
        }
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

        // 俯仰角：命中侧面需要把视角压下去。
        // ⚠️ 正数 = 向下看（+90 是垂直看脚底），负数 = 抬头看天。
        context.drawTextWithShadow(client.textRenderer,
                "俯仰角: " + String.format("%.0f", player.getPitch()) + "°   瞄到侧面约 +77° ~ +83°",
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

        // 触发计数：用来确认模组真的在动。发起次数 vs 世界确认成功次数分开显示，
        // 两者差得多就说明右键发出去了但没放成（被服务端拒绝或还在冷却）。
        int count = AutoBridgeClient.CONTROLLER.getPlaceCount();
        int confirmed = AutoBridgeClient.CONTROLLER.getConfirmedPlaceCount();
        int lastAt = AutoBridgeClient.CONTROLLER.getLastPlaceAt();
        int now = AutoBridgeClient.CONTROLLER.getTickCounter();
        String ago = (lastAt < 0) ? "从未" : ((now - lastAt) / 20) + "秒前";
        context.drawTextWithShadow(client.textRenderer,
                "模拟右键: " + count + " 次   确认放成: " + confirmed + " 次   最近: " + ago,
                x, y, count > 0 ? GREEN : GRAY);
    }

    /** 把"被哪一条挡下"翻译成"下一步该做什么"。 */
    private static String buildHint(BridgeValidator.Result result, BlockHitResult blockHit) {
        if (!BridgeConfig.autoMode) {
            return "自动搭路已关闭 → 在 ModMenu 里打开";
        }

        // ---- 还没启动：告诉玩家怎么启动 ----
        if (!AutoBridgeClient.CONTROLLER.isBridging()) {
            return "启动方式：蹲在方块边缘 + 低头看脚底 + 在脚下一格放一块方块";
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
            return "方向不对：方块要放在视角反方向（身后）";
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

    /** 条件满足打 ✓，不满足打 ✗。 */
    private static String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }
}
