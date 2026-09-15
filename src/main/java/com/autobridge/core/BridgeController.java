package com.autobridge.core;

import com.autobridge.AutoBridgeClient;
import com.autobridge.Edition;
import com.autobridge.config.BridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Random;

/**
 * 主状态机。每个客户端 tick 跑一次，负责：维护潜行、校验准星、模拟右键。
 *
 * <p>为什么用 {@code options.useKey.setPressed(true)} 而不是直接发放置包：
 * <ol>
 *   <li>完全走原版 {@code handleInputEvents() -> doItemUse()} 流程，
 *       sequence 递增、raycast 校验、交互距离检查全部由原版完成；</li>
 *   <li>原版自带 {@code itemUseCooldown}（约 4 tick / 5 CPS），
 *       等于自动把速度限制在真人手速区间，不会出现"超原版"的放置频率。</li>
 * </ol>
 */
public class BridgeController {

    private static final Random RANDOM = new Random();

    /** 诊断日志限流：最多每 20 tick（约 1 秒）打一条拒绝原因，避免刷屏。 */
    private static final int DIAG_INTERVAL_TICKS = 20;

    /** 本模组强制按下的潜行键（需要在我们失活时松开）。 */
    private boolean forcedSneak = false;

    /** 本模组本 tick 按下的右键（下一 tick 开头松开，模拟"点一下"）。 */
    private boolean forcedUse = false;

    /** 随机延迟倒计时。 */
    private int delayTicks = 0;

    /** 玩家自己在按右键时为 true —— 此时模组完全不介入。 */
    private boolean playerOwnUse = false;

    private BridgeValidator.Result lastResult = null;
    private boolean active = false;

    /** 累计模拟右键次数，给 HUD 和诊断日志用。 */
    private int placeCount = 0;

    /** 最近一次模拟右键的 tick 计数。 */
    private int lastPlaceAt = -1;
    private int tickCounter = 0;

    /** 上一次打诊断日志的 tick。 */
    private int lastDiagAt = -DIAG_INTERVAL_TICKS;

    public BridgeValidator.Result getLastResult() {
        return lastResult;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isForcedSneak() {
        return forcedSneak;
    }

    public int getPlaceCount() {
        return placeCount;
    }

    public int getLastPlaceAt() {
        return lastPlaceAt;
    }

    public int getTickCounter() {
        return tickCounter;
    }

    public void tickStart(MinecraftClient client) {
        tickCounter++;

        // ---------- 1. 先把上 tick 我们按下的右键松开 ----------
        boolean pressedAtStart = client.options.useKey.isPressed();
        boolean wePressedLastTick = forcedUse;
        if (wePressedLastTick) {
            client.options.useKey.setPressed(false);
            forcedUse = false;
        }
        // 玩家自己在按右键时，原版会自己处理，我们不要插手，也不要把它按掉
        playerOwnUse = pressedAtStart && !wePressedLastTick;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            active = false;
            lastResult = null;
            releaseSneak(client);
            return;
        }

        // ---------- 2. 打开界面时彻底不介入 ----------
        // 打开界面（背包 / 暂停菜单 / 聊天）时彻底不介入，也不要把潜行键一直按着不放
        if (client.currentScreen != null) {
            releaseSneak(client);
            active = false;
            lastResult = null;
            return;
        }

        // ---------- 3. 跑搭路状态机 ----------
        switch (BridgeConfig.bridgeMode) {
            case AUTO_BRIDGE -> tickAutoBridge(client, player);
        }
    }

    /**
     * 自动搭路：站到方块边缘就接管（强制潜行 + 尝试放置），离开边缘立刻松手。
     *
     * <p>额外要求「手上拿着方块」且「没按跳跃」：
     * <ul>
     *   <li>空手走到任何悬崖边都被强制潜行的话，正常跑图会被烦死；</li>
     *   <li>原版在潜行状态下会屏蔽跳跃，所以玩家想跳下去时必须让开。</li>
     * </ul>
     */
    private void tickAutoBridge(MinecraftClient client, ClientPlayerEntity player) {
        boolean onEdge = BridgeValidator.isOnBlockEdge(player, client.world);
        boolean holdingBlock = player.getMainHandStack().getItem() instanceof BlockItem;
        boolean wantsToJump = client.options.jumpKey.isPressed();
        active = BridgeConfig.autoMode && onEdge && holdingBlock && !wantsToJump;

        // ---------- 潜行维护 ----------
        if (active && BridgeConfig.forceSneak) {
            if (!client.options.sneakKey.isPressed()) {
                client.options.sneakKey.setPressed(true);
                forcedSneak = true;
            }
        } else {
            releaseSneak(client);
        }

        if (!active) {
            lastResult = null;
            delayTicks = 0;
            return;
        }

        // 玩家自己按着右键 -> 让原版去放
        if (playerOwnUse) {
            lastResult = null;
            delayTicks = 0;
            return;
        }

        lastResult = BridgeValidator.validate(client);
        if (!lastResult.ok()) {
            delayTicks = 0;
            logDenied(client, player);
            return;
        }

        fireUse(client);
    }

    /**
     * 模拟一次鼠标右键。
     *
     * <p>设置之后，本 tick 的 {@code MinecraftClient#handleInputEvents} 会读到 pressed=true
     * 并调用原版 {@code doItemUse()}，整个放置完全走原版路径。
     *
     * @return 本 tick 是否真的按下了（被随机延迟挡掉时返回 false）
     */
    private boolean fireUse(MinecraftClient client) {
        // 随机延迟，避免"零反应"这种非人类特征
        if (delayTicks > 0) {
            delayTicks--;
            return false;
        }
        delayTicks = nextDelay();

        client.options.useKey.setPressed(true);
        forcedUse = true;
        placeCount++;
        lastPlaceAt = tickCounter;

        // 开发版：把「放宽了的那一格放在哪、瞄的是什么面」打出来。
        // 只在头几次和每 10 次打一条，免得 5 CPS 刷屏。
        if (Edition.DEV && (placeCount <= 3 || placeCount % 10 == 0)) {
            HitResult target = client.crosshairTarget;
            String hitDesc = (target instanceof BlockHitResult bh)
                    ? bh.getBlockPos().toShortString() + "/" + bh.getSide().getName()
                    : "-";
            AutoBridgeClient.debug("[AutoBridge] 模拟右键 #{}: placePos={} hit={}",
                    placeCount, lastResult.placePos(), hitDesc);
        }
        return true;
    }

    /**
     * 每秒钟最多打一条「为什么没触发」。开发版专用：靠这个定位卡在哪一条校验上，
     * 比在游戏里截图 HUD 可靠得多。用户版完全不打。
     */
    private void logDenied(MinecraftClient client, ClientPlayerEntity player) {
        if (!Edition.DEV) {
            return;
        }
        if (tickCounter - lastDiagAt < DIAG_INTERVAL_TICKS) {
            return;
        }
        lastDiagAt = tickCounter;

        HitResult target = client.crosshairTarget;
        String targetDesc;
        if (target instanceof BlockHitResult blockHit && target.getType() == HitResult.Type.BLOCK) {
            targetDesc = blockHit.getBlockPos().toShortString() + "/" + blockHit.getSide().getName();
        } else {
            targetDesc = (target == null) ? "null" : target.getType().name();
        }
        String expectedDesc = (lastResult.expectedPos() == null)
                ? "-"
                : lastResult.expectedPos().toShortString();

        AutoBridgeClient.debug(
                "[AutoBridge] DENIED: {} | target={} expected={} sneak={} dir={} held={}",
                lastResult.reason(), targetDesc, expectedDesc,
                player.isSneaking(), BridgeConfig.directionMode,
                player.getMainHandStack().getItem());
    }

    private void releaseSneak(MinecraftClient client) {
        if (forcedSneak) {
            client.options.sneakKey.setPressed(false);
            forcedSneak = false;
        }
    }

    private int nextDelay() {
        int min = Math.max(0, BridgeConfig.minDelayTicks);
        int max = Math.max(min, BridgeConfig.maxDelayTicks);
        return min + RANDOM.nextInt(max - min + 1);
    }
}
