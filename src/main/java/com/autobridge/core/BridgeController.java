package com.autobridge.core;

import com.autobridge.AutoBridgeClient;
import com.autobridge.Edition;
import com.autobridge.config.BridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 主状态机。每个客户端 tick 跑一次，负责：启动检测、维护潜行、校验准星、模拟右键。
 *
 * <p>为什么用 {@code options.useKey.setPressed(true)} 而不是直接发放置包：
 * <ol>
 *   <li>完全走原版 {@code handleInputEvents() -> doItemUse()} 流程，
 *       sequence 递增、raycast 校验、交互距离检查全部由原版完成；</li>
 *   <li>原版自带 {@code itemUseCooldown}（约 4 tick / 5 CPS），
 *       等于自动把速度限制在真人手速区间，不会出现"超原版"的放置频率。</li>
 * </ol>
 *
 * <h2>两个阶段</h2>
 * <ul>
 *   <li><b>待机</b>：程序完全不碰潜行键和右键。玩家自己蹲下 + 站在方块边缘 + 低头，
 *       并在自己脚下一格（{@code player.getBlockPos().down()}）放下一个方块 ——
 *       这一格「从空变实」就是启动信号。</li>
 *   <li><b>接管</b>：程序强制潜行 + 模拟右键。每成功放置一次就把计时器归零，
 *       连续 {@link BridgeConfig#idleTimeoutTicks} tick 没有成功放置就自动回到待机。</li>
 * </ul>
 */
public class BridgeController {

    private static final Random RANDOM = new Random();

    /** 诊断日志限流：最多每 20 tick（约 1 秒）打一条拒绝原因，避免刷屏。 */
    private static final int DIAG_INTERVAL_TICKS = 20;

    /** 待机检测时扫描玩家周围多大范围（格）。搭路放置位只会在旁边一格。 */
    private static final int IDLE_SCAN_RADIUS = 1;

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

    /** 累计模拟右键次数（发起次数，不等于成功次数），给 HUD 和诊断日志用。 */
    private int placeCount = 0;

    /** 最近一次模拟右键的 tick 计数。 */
    private int lastPlaceAt = -1;
    private int tickCounter = 0;

    /** 上一次打诊断日志的 tick。 */
    private int lastDiagAt = -DIAG_INTERVAL_TICKS;

    /** 上一次打「待机诊断」的 tick。 */
    private int lastIdleDiagAt = -DIAG_INTERVAL_TICKS;

    // 待机条件快照（HUD 直接显示用，省得去翻日志）
    private boolean idleSneaking = false;
    private boolean idleOnEdge = false;
    private boolean idleHeadDown = false;
    private boolean idleHolding = false;
    private boolean idleFootSolid = false;

    /**
     * 距上一次「站在危险边缘」过了多少 tick。
     *
     * <p><b>为什么需要这个记忆</b>：{@code isOnBlockEdge} 的定义是「玩家所在那格是空气、
     * 人靠旁边的方块撑着」。所以玩家一旦往那一格放进方块，那格就变实心了，
     * {@code onEdge} <b>必然在同一 tick 翻成 false</b> —— 这两个条件在时序上是互斥的。
     *
     * <p>因此启动判定不能用「现在是否站在边缘」，而要用「刚刚是否站过边缘」：
     * 放方块前那一 tick 的 true 才是有效信号。
     */
    private int ticksSinceEdge = EDGE_MEMORY_TICKS;

    /**
     * <b>启动检测</b>用的宽限 tick 数。
     *
     * <p>需要比较大：玩家往脚下一格放方块，到那一格在客户端世界里变成实心，
     * 中间可能隔几 tick。而方块一落地，实时的 onEdge 就假了 —— 所以要能把
     * 「放之前那一 tick 站过边缘」这个信号留住。
     */
    private static final int EDGE_MEMORY_TICKS = 5;

    /**
     * <b>潜行判定</b>用的宽限 tick 数，必须比上面那个小得多。
     *
     * <p>这里踩过一次坑：一开始两处共用 {@code EDGE_MEMORY_TICKS=5}，结果用户反馈
     * 「太早触发了，感觉还没走到边缘就按住了」。原因是搭路的节奏是「放一格 → 后退一格」，
     * 后退后立刻又探出到新边缘、{@code ticksSinceEdge} 又归零，于是 5 tick 宽限被不断刷新，
     * 潜行几乎变成了常按 —— 而原版潜行会把移速乘 0.3，人像在泥里走。
     *
     * <p>给 1 tick 只是为了防抖（潜行状态本身要 1 tick 才同步），不能更长。
     */
    private static final int SNEAK_EDGE_MEMORY_TICKS = 1;

    // ------------------------------------------------------------------
    // 启动 / 超时
    // ------------------------------------------------------------------

    /** 是否已经通过「自己蹲 + 低头 + 在脚下一格放一块」启动了接管。 */
    private boolean bridging = false;

    /** 距上一次「世界状态确认过的成功放置」过了多少 tick。 */
    private int idleTicks = 0;

    /** 世界状态确认成功的放置次数（区别于 placeCount 的"发起次数"）。 */
    private int confirmedPlaceCount = 0;

    /** 上一 tick 发起的那次放置的目标位置，下一 tick 拿它去世界验收。 */
    private BlockPos pendingPlacePos = null;

    /**
     * 上一 tick 玩家周围「Y = 脚格 - 1」那一层的实心快照。
     *
     * <p>用绝对坐标存，所以玩家重心微微前后移动、跨过了格子边界也不会漏掉信号。
     */
    private final Map<BlockPos, Boolean> layerSnapshot = new HashMap<>();

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

    /** 是否已经启动接管（HUD 用）。 */
    public boolean isBridging() {
        return bridging;
    }

    /** 距上次成功放置过了多少 tick（HUD 用来显示倒计时）。 */
    public int getIdleTicks() {
        return idleTicks;
    }

    /** 世界状态确认过的成功放置次数（HUD 用）。 */
    public int getConfirmedPlaceCount() {
        return confirmedPlaceCount;
    }

    public boolean isIdleSneaking() {
        return idleSneaking;
    }

    public boolean isIdleOnEdge() {
        return idleOnEdge;
    }

    public boolean isIdleHeadDown() {
        return idleHeadDown;
    }

    public boolean isIdleHolding() {
        return idleHolding;
    }

    public boolean isIdleFootSolid() {
        return idleFootSolid;
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
            endBridging(client);
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
     * 自动搭路。分两个阶段：待机（检测启动信号）和接管（边缘潜行 + 模拟右键）。
     *
     * <p>接管期间的「active」要求三件事同时成立：<b>正在边缘</b> + 手上拿着方块 + 没按跳跃。
     * <ul>
     *   <li><b>必须在边缘</b> —— 用户明确要求「在方块边缘自动潜行，而<b>不是一直潜行</b>」。
     *       一直潜行有两个实际害处：原版潜行会把移动速度乘 0.3（人像在泥里走），
     *       而且离开边缘后本就没必要潜行。所以潜行走「在边缘就按、离开就松」的节奏。</li>
     *   <li>手上要拿方块 —— 空手走到任何悬崖边都被强制潜行的话，正常跑图会被烦死；</li>
     *   <li>没按跳跃 —— 原版在潜行状态下会屏蔽跳跃，玩家想跳下去时必须让开。</li>
     * </ul>
     *
     * <p><b>注意</b>：「是否在边缘」用的是 {@link #ticksSinceEdge} 记忆（刚站过边缘），
     * 不是实时值 —— 方块一落地那格就变实心，实时的 onEdge 当场就假了。
     *
     * <p>接管状态本身（bridging）不因为离开边缘而结束，只由
     * {@link BridgeConfig#idleTimeoutTicks} 连续无成功放置来收尾 —— 这样搭路中间
     * 偶尔走岔一下不会整个断掉。
     */
    private void tickAutoBridge(MinecraftClient client, ClientPlayerEntity player) {
        // 总开关关掉 = 整个模组停用，连待机检测都不做
        if (!BridgeConfig.autoMode) {
            endBridging(client);
            return;
        }

        // 每 tick 先验收上一 tick 发起的放置：用世界状态，不用"我按了几次右键"
        idleTicks++;
        confirmPendingPlacement(client);

        // 「最近是否站过边缘」必须在任何分支之前更新 —— 待机检测和接管潜行都要用它。
        // 不能用实时值：方块落进脚下那格的同一 tick，onEdge 就已经变成 false 了。
        boolean onEdgeNow = BridgeValidator.isOnBlockEdge(player, client.world);
        ticksSinceEdge = onEdgeNow ? 0 : Math.min(ticksSinceEdge + 1, EDGE_MEMORY_TICKS + 1);

        // ---------- 待机：检测启动信号 ----------
        if (!bridging) {
            tickIdle(client, player);
            if (!bridging) {
                active = false;
                lastResult = null;
                releaseSneak(client);
                return;
            }
        }

        // ---------- 接管 ----------
        // 潜行判定用「小宽限」：只在真的贴着边缘时按，离开就松。
        // 不能用启动检测那个 5 tick 的大宽限 —— 那会让潜行在连续搭路时几乎常按。
        boolean onEdge = ticksSinceEdge <= SNEAK_EDGE_MEMORY_TICKS;
        boolean holdingBlock = player.getMainHandStack().getItem() instanceof BlockItem;
        boolean wantsToJump = client.options.jumpKey.isPressed();
        active = onEdge && holdingBlock && !wantsToJump;

        if (active && BridgeConfig.forceSneak) {
            if (!client.options.sneakKey.isPressed()) {
                client.options.sneakKey.setPressed(true);
                forcedSneak = true;
            }
        } else {
            // 离开边缘 / 空手 / 想跳跃 -> 立刻松开潜行，别把玩家钉死在 0.3 倍速
            releaseSneak(client);
        }

        if (!active) {
            lastResult = null;
            delayTicks = 0;
            checkTimeout(client);
            return;
        }

        // 玩家自己按着右键 -> 让原版去放
        if (playerOwnUse) {
            lastResult = null;
            delayTicks = 0;
            checkTimeout(client);
            return;
        }

        lastResult = BridgeValidator.validate(client);
        if (!lastResult.ok()) {
            delayTicks = 0;
            logDenied(client, player);
            checkTimeout(client);
            return;
        }

        fireUse(client);
        checkTimeout(client);
    }

    /**
     * 待机阶段的启动检测。
     *
     * <p>五个条件全部满足才启动：
     * <ol>
     *   <li>{@code player.isSneaking()} —— 玩家<b>自己</b>蹲着
     *       （待机时程序不碰潜行键，所以这个状态只可能来自玩家）；</li>
     *   <li><b>刚站过边缘</b>（{@link #ticksSinceEdge}，由调用方 {@code tickAutoBridge} 更新）；</li>
     *   <li>低头（俯仰角超过 {@link BridgeConfig#lowHeadPitch}）；</li>
     *   <li>手上拿着方块；</li>
     *   <li><b>{@code foot.down()} 那一格「从空变实」</b> —— 玩家刚在脚下一格放了方块。</li>
     * </ol>
     *
     * <p>第 5 条是核心信号。玩家站在方块边缘时浮点坐标会探出到相邻那格，
     * 所以 {@code player.getBlockPos().down()} 指向的是旁边那格空气，
     * 它被放上方块的那一刻就是「我要开始搭路」的宣言。
     *
     * <p>第 2 条为什么用「刚站过」而不是「正站在」：见 {@link #ticksSinceEdge} 的注释。
     */
    private void tickIdle(MinecraftClient client, ClientPlayerEntity player) {
        BlockPos foot = player.getBlockPos();
        int layerY = foot.getY() - 1;
        BlockPos watch = new BlockPos(foot.getX(), layerY, foot.getZ());

        // 注意：ticksSinceEdge 由 tickAutoBridge 统一更新，这里不要重复更新（否则一 tick 加两次）
        // 先拿上一 tick 的快照比对……
        boolean solidNow = !client.world.getBlockState(watch).isAir();
        Boolean solidPrev = layerSnapshot.get(watch);
        boolean justPlaced = solidPrev != null && !solidPrev && solidNow;

        // ……再重建快照（顺序不能反，否则永远比不出变化）
        layerSnapshot.clear();
        for (int dx = -IDLE_SCAN_RADIUS; dx <= IDLE_SCAN_RADIUS; dx++) {
            for (int dz = -IDLE_SCAN_RADIUS; dz <= IDLE_SCAN_RADIUS; dz++) {
                BlockPos p = new BlockPos(foot.getX() + dx, layerY, foot.getZ() + dz);
                layerSnapshot.put(p, !client.world.getBlockState(p).isAir());
            }
        }

        if (!justPlaced) {
            updateIdleFlags(client, player, solidNow);
            logIdle(client, player, watch, solidNow);
            return;
        }

        // 方块出现了，再看其余条件
        boolean sneaking = player.isSneaking();
        // 「刚站过边缘」而不是「现在站在边缘」—— 见 ticksSinceEdge 的注释
        boolean onEdge = ticksSinceEdge <= EDGE_MEMORY_TICKS;
        boolean headDown = player.getPitch() > BridgeConfig.lowHeadPitch;
        boolean holdingBlock = player.getMainHandStack().getItem() instanceof BlockItem;

        updateIdleFlags(client, player, solidNow);

        if (sneaking && onEdge && headDown && holdingBlock) {
            bridging = true;
            idleTicks = 0;
            AutoBridgeClient.debug(
                    "[AutoBridge] 搭路启动: watch={} foot={} pitch={} edgeAgo={}tick",
                    watch.toShortString(), foot.toShortString(),
                    String.format(Locale.ROOT, "%.1f", player.getPitch()), ticksSinceEdge);
        } else {
            // 检测到「脚下一格出现方块」了，但其它条件没满足 —— 这条最值得看
            AutoBridgeClient.debug(
                    "[AutoBridge] IDLE 检测到方块但未启动 | watch={} 空->实 | sneak={} edgeAgo={}tick pitch={} (>{}?) held={}",
                    watch.toShortString(), sneaking, ticksSinceEdge,
                    String.format(Locale.ROOT, "%.1f", player.getPitch()),
                    BridgeConfig.lowHeadPitch, holdingBlock);
        }
    }

    /** 把五个待机条件的当前值记下来，给 HUD 显示（不用等看日志）。 */
    private void updateIdleFlags(MinecraftClient client, ClientPlayerEntity player, boolean footSolid) {
        idleSneaking = player.isSneaking();
        // HUD 上显示「刚站过边缘」，与启动判定保持一致
        idleOnEdge = ticksSinceEdge <= EDGE_MEMORY_TICKS;
        idleHeadDown = player.getPitch() > BridgeConfig.lowHeadPitch;
        idleHolding = player.getMainHandStack().getItem() instanceof BlockItem;
        idleFootSolid = footSolid;
    }

    /**
     * 待机态的限流诊断。开发版专用。
     *
     * <p>没有这个的话，「怎么弄都不触发」是完全查不出来的 —— 因为待机失败时状态机
     * 只是静默返回。这里把 5 个条件的实时值全打出来，一眼就能看出卡在哪一条。
     */
    private void logIdle(MinecraftClient client, ClientPlayerEntity player, BlockPos watch, boolean solidNow) {
        if (!Edition.DEV) {
            return;
        }
        if (tickCounter - lastIdleDiagAt < DIAG_INTERVAL_TICKS) {
            return;
        }
        lastIdleDiagAt = tickCounter;

        boolean sneaking = player.isSneaking();
        boolean onEdge = BridgeValidator.isOnBlockEdge(player, client.world);
        boolean headDown = player.getPitch() > BridgeConfig.lowHeadPitch;
        boolean holdingBlock = player.getMainHandStack().getItem() instanceof BlockItem;

        AutoBridgeClient.debug(
                "[AutoBridge] IDLE | watch={} solid={} | sneak={} onEdge={} pitch={} (>{}?) held={} | foot={}",
                watch.toShortString(), solidNow,
                sneaking, onEdge,
                String.format(Locale.ROOT, "%.1f", player.getPitch()), BridgeConfig.lowHeadPitch,
                holdingBlock, player.getBlockPos().toShortString());
    }

    /**
     * 验收上一 tick 发起的那次放置。
     *
     * <p>原版的「按下右键」只是一次<b>请求</b> —— 服务端可能拒绝、也可能还卡在
     * {@code itemUseCooldown} 里。所以只有方块真的出现在目标位置，才算一次成功放置、
     * 才把空闲计时器归零。这是"你方块1没放怎么就放方块2了"那次的教训。
     */
    private void confirmPendingPlacement(MinecraftClient client) {
        if (pendingPlacePos == null || client.world == null) {
            return;
        }
        if (!client.world.getBlockState(pendingPlacePos).isAir()) {
            idleTicks = 0;
            confirmedPlaceCount++;
        }
        pendingPlacePos = null;
    }

    /** 空闲太久（连续没有成功放置）就回到待机。 */
    private void checkTimeout(MinecraftClient client) {
        if (bridging && idleTicks >= BridgeConfig.idleTimeoutTicks) {
            AutoBridgeClient.debug("[AutoBridge] 搭路结束：连续 {} tick 没有成功放置（阈值 {}）",
                    idleTicks, BridgeConfig.idleTimeoutTicks);
            endBridging(client);
        }
    }

    /** 回到待机：松开潜行、清掉所有接管期状态。 */
    private void endBridging(MinecraftClient client) {
        bridging = false;
        idleTicks = 0;
        pendingPlacePos = null;
        layerSnapshot.clear();
        active = false;
        lastResult = null;
        delayTicks = 0;
        releaseSneak(client);
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

        // 记下目标位置，下一 tick 去世界验收它到底放成没有
        pendingPlacePos = (lastResult == null) ? null : lastResult.placePos();

        // 开发版：把「放到了哪、瞄的是什么面」打出来。
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
                "[AutoBridge] DENIED: {} | target={} expected={} sneak={} dir={} held={} idle={}/{}",
                lastResult.reason(), targetDesc, expectedDesc,
                player.isSneaking(), BridgeConfig.directionMode,
                player.getMainHandStack().getItem(),
                idleTicks, BridgeConfig.idleTimeoutTicks);
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
