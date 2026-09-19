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

public class BridgeController {

    private static final Random RANDOM = new Random();

    private static final int DIAG_INTERVAL_TICKS = 20;

    private static final int IDLE_SCAN_RADIUS = 1;

    private boolean forcedSneak = false;

    private boolean forcedUse = false;

    private int delayTicks = 0;

    private boolean playerOwnUse = false;

    private BridgeValidator.Result lastResult = null;
    private boolean active = false;

    private int lastPlaceAt = -1;

    private int firstPlacedAt = -1;

    private int lastPlacedAt = -1;

    private String pressAimDesc = "-";

    private int tickCounter = 0;

    private int lastDiagAt = -DIAG_INTERVAL_TICKS;

    private int lastIdleDiagAt = -DIAG_INTERVAL_TICKS;

    private boolean idleSneaking = false;
    private boolean idleOnEdge = false;
    private boolean idleHeadDown = false;
    private boolean idleHolding = false;
    private boolean idleFootSolid = false;

    private int ticksSinceEdge = EDGE_MEMORY_TICKS;

    private static final int EDGE_MEMORY_TICKS = 5;

    private static final int SNEAK_EDGE_MEMORY_TICKS = 1;

    private boolean bridging = false;

    private int idleTicks = 0;

    private int confirmedPlaceCount = 0;

    private BlockPos pendingPlacePos = null;

    private final Map<BlockPos, Boolean> layerSnapshot = new HashMap<>();

    public BridgeValidator.Result getLastResult() {
        return lastResult;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isClampActive() {
        return active && BridgeConfig.bridgeMode == BridgeConfig.BridgeMode.GOD_BRIDGE;
    }

    public boolean isForcedSneak() {
        return forcedSneak;
    }

    public double getAverageBlocksPerSecond() {
        int span = tickCounter - firstPlacedAt;
        if (firstPlacedAt < 0 || confirmedPlaceCount <= 0 || span <= 0) {
            return 0.0D;
        }
        return confirmedPlaceCount * 20.0D / span;
    }

    public int getLastPlaceAt() {
        return lastPlaceAt;
    }

    public int getTickCounter() {
        return tickCounter;
    }

    public boolean isBridging() {
        return bridging;
    }

    public int getIdleTicks() {
        return idleTicks;
    }

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

        boolean pressedAtStart = client.options.useKey.isPressed();
        boolean wePressedLastTick = forcedUse;
        if (wePressedLastTick) {
            client.options.useKey.setPressed(false);
            forcedUse = false;
        }
        playerOwnUse = pressedAtStart && !wePressedLastTick;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            endBridging(client);
            return;
        }

        if (client.currentScreen != null) {
            releaseSneak(client);
            active = false;
            lastResult = null;
            return;
        }

        switch (BridgeConfig.bridgeMode) {
            case SNEAK_BRIDGE, GOD_BRIDGE -> tickAutoBridge(client, player);
        }
    }

    private void tickAutoBridge(MinecraftClient client, ClientPlayerEntity player) {
        if (!BridgeConfig.autoMode) {
            endBridging(client);
            return;
        }

        idleTicks++;
        confirmPendingPlacement(client);

        boolean onEdgeNow = BridgeValidator.isOnBlockEdge(player, client.world);
        ticksSinceEdge = onEdgeNow ? 0 : Math.min(ticksSinceEdge + 1, EDGE_MEMORY_TICKS + 1);

        if (!bridging) {
            tickIdle(client, player);
            if (!bridging) {
                active = false;
                lastResult = null;
                releaseSneak(client);
                return;
            }
        }

        boolean holdingBlock = BridgeValidator.isHoldingBlock(player);
        boolean wantsToJump = client.options.jumpKey.isPressed();
        boolean onEdge = ticksSinceEdge <= SNEAK_EDGE_MEMORY_TICKS;
        boolean godBridge = BridgeConfig.bridgeMode == BridgeConfig.BridgeMode.GOD_BRIDGE;
        active = godBridge
                ? (holdingBlock && !wantsToJump)
                : (onEdge && holdingBlock && !wantsToJump);

        if (godBridge) {
            releaseSneak(client);
        } else if (active && BridgeConfig.forceSneak) {
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
            checkTimeout(client);
            return;
        }

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

    private void tickIdle(MinecraftClient client, ClientPlayerEntity player) {
        BlockPos foot = player.getBlockPos();
        int layerY = foot.getY() - 1;
        BlockPos watch = new BlockPos(foot.getX(), layerY, foot.getZ());

        boolean justPlaced = false;
        BlockPos placedAt = null;
        for (int dx = -IDLE_SCAN_RADIUS; dx <= IDLE_SCAN_RADIUS; dx++) {
            for (int dz = -IDLE_SCAN_RADIUS; dz <= IDLE_SCAN_RADIUS; dz++) {
                BlockPos p = new BlockPos(foot.getX() + dx, layerY, foot.getZ() + dz);
                boolean solidHere = !client.world.getBlockState(p).isAir();
                Boolean prevHere = layerSnapshot.get(p);
                if (prevHere != null && !prevHere && solidHere) {
                    justPlaced = true;
                    placedAt = p;
                }
            }
        }

        boolean solidNow = !client.world.getBlockState(watch).isAir();

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

        boolean sneaking = player.isSneaking();
        boolean onEdge = ticksSinceEdge <= EDGE_MEMORY_TICKS;
        boolean headDown = player.getPitch() > BridgeConfig.lowHeadPitch;
        boolean holdingBlock = BridgeValidator.isHoldingBlock(player);

        updateIdleFlags(client, player, solidNow);

        if (sneaking && onEdge && headDown && holdingBlock) {
            bridging = true;
            idleTicks = 0;
            confirmedPlaceCount = 0;
            firstPlacedAt = -1;
            lastPlacedAt = -1;
            AutoBridgeClient.debug(
                    "[AutoBridge] 搭路启动: 放置格={} （中心格正下方={}） foot={} pitch={} edgeAgo={}tick",
                    placedAt, watch.toShortString(), foot.toShortString(),
                    String.format(Locale.ROOT, "%.1f", player.getPitch()), ticksSinceEdge);
        } else {
            AutoBridgeClient.debug(
                    "[AutoBridge] IDLE 检测到方块但未启动 | 放置格={} 空->实 中心格正下方={} | sneak={} edgeAgo={}tick pitch={} (>{}?) held={}",
                    placedAt, watch.toShortString(), sneaking, ticksSinceEdge,
                    String.format(Locale.ROOT, "%.1f", player.getPitch()),
                    BridgeConfig.lowHeadPitch, holdingBlock);
        }
    }

    private void updateIdleFlags(MinecraftClient client, ClientPlayerEntity player, boolean footSolid) {
        idleSneaking = player.isSneaking();
        idleOnEdge = ticksSinceEdge <= EDGE_MEMORY_TICKS;
        idleHeadDown = player.getPitch() > BridgeConfig.lowHeadPitch;
        idleHolding = BridgeValidator.isHoldingBlock(player);
        idleFootSolid = footSolid;
    }

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
        boolean holdingBlock = BridgeValidator.isHoldingBlock(player);

        String aim = "-";
        HitResult target = client.crosshairTarget;
        if (target instanceof BlockHitResult bh) {
            aim = bh.getBlockPos().toShortString() + "/" + bh.getSide().asString()
                    + " => put=" + bh.getBlockPos().offset(bh.getSide()).toShortString();
        }

        AutoBridgeClient.debug(
                "[AutoBridge] IDLE | watch={} solid={} | sneak={} onEdge={} pitch={} (>{}?) held={} | foot={} | aim={} | layer={}",
                watch.toShortString(), solidNow,
                sneaking, onEdge,
                String.format(Locale.ROOT, "%.1f", player.getPitch()), BridgeConfig.lowHeadPitch,
                holdingBlock, player.getBlockPos().toShortString(),
                aim, layerPattern(client, player.getBlockPos()));
    }

    private String layerPattern(MinecraftClient client, BlockPos foot) {
        int layerY = foot.getY() - 1;
        StringBuilder sb = new StringBuilder();
        for (int dz = -IDLE_SCAN_RADIUS; dz <= IDLE_SCAN_RADIUS; dz++) {
            if (dz > -IDLE_SCAN_RADIUS) {
                sb.append('/');
            }
            for (int dx = -IDLE_SCAN_RADIUS; dx <= IDLE_SCAN_RADIUS; dx++) {
                BlockPos p = new BlockPos(foot.getX() + dx, layerY, foot.getZ() + dz);
                sb.append(client.world.getBlockState(p).isAir() ? '.' : 'X');
            }
        }
        return sb.toString();
    }

    private void confirmPendingPlacement(MinecraftClient client) {
        if (pendingPlacePos == null || client.world == null) {
            return;
        }
        if (!client.world.getBlockState(pendingPlacePos).isAir()) {
            idleTicks = 0;
            confirmedPlaceCount++;
            logPlaced(client, pendingPlacePos);
        }
        pendingPlacePos = null;
    }

    private void logPlaced(MinecraftClient client, BlockPos placedPos) {
        int gap = (lastPlacedAt < 0) ? 0 : (tickCounter - lastPlacedAt);
        if (firstPlacedAt < 0) {
            firstPlacedAt = tickCounter;
        }
        lastPlacedAt = tickCounter;

        if (!Edition.DEV) {
            return;
        }
        if (confirmedPlaceCount <= 3 || confirmedPlaceCount % 10 == 0 || gap >= 10) {
            HitResult target = client.crosshairTarget;
            String hitDesc = (target instanceof BlockHitResult bh)
                    ? bh.getBlockPos().toShortString() + "/" + bh.getSide().asString()
                    : "-";
            AutoBridgeClient.debug("[AutoBridge] 放成 #{}: placePos={} hit={} {} 距上一块 {} tick",
                    confirmedPlaceCount, placedPos, hitDesc, pressAimDesc, gap);
        }
    }

    private void checkTimeout(MinecraftClient client) {
        if (bridging && idleTicks >= BridgeConfig.idleTimeoutTicks) {
            AutoBridgeClient.debug("[AutoBridge] 搭路结束：连续 {} tick 没有成功放置（阈值 {}）",
                    idleTicks, BridgeConfig.idleTimeoutTicks);
            endBridging(client);
        }
    }

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

    private boolean fireUse(MinecraftClient client) {
        if (BridgeConfig.bridgeMode != BridgeConfig.BridgeMode.GOD_BRIDGE) {
            if (delayTicks > 0) {
                delayTicks--;
                return false;
            }
            delayTicks = nextDelay();
        }

        client.options.useKey.setPressed(true);
        forcedUse = true;

        pressAimDesc = aimDesc(client, lastResult);

        lastPlaceAt = tickCounter;
        pendingPlacePos = (lastResult == null) ? null : lastResult.placePos();
        return true;
    }

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
            targetDesc = blockHit.getBlockPos().toShortString() + "/" + blockHit.getSide().asString();
        } else {
            targetDesc = (target == null) ? "null" : target.getType().name();
        }
        String expectedDesc = (lastResult.expectedPos() == null)
                ? "-"
                : lastResult.expectedPos().toShortString();

        AutoBridgeClient.debug(
                "[AutoBridge] DENIED: {} | target={} expected={} {} sneak={} dir={} held={} idle={}/{}",
                lastResult.reason(), targetDesc, expectedDesc,
                aimDesc(client, lastResult),
                player.isSneaking(), BridgeConfig.directionMode,
                BridgeValidator.heldDescription(player),
                idleTicks, BridgeConfig.idleTimeoutTicks);
    }

    private String aimDesc(MinecraftClient client, BridgeValidator.Result result) {
        ClientPlayerEntity player = client.player;
        if (player == null || result == null || result.expectedPos() == null) {
            return "pitch=- δ=- δtan=-";
        }
        BlockPos foot = BridgeValidator.findFootBlock(player, player.getEntityWorld());
        double pitch = player.getPitch();
        double delta = overhang(player, foot, result.expectedPos());
        double deltaTan = Double.isNaN(delta) ? Double.NaN : delta * Math.tan(Math.toRadians(pitch));
        return String.format("pitch=%.1f δ=%.3f δtan=%.2f", pitch, delta, deltaTan);
    }

    private static double overhang(ClientPlayerEntity player, BlockPos foot, BlockPos expected) {
        if (foot == null) {
            return Double.NaN;
        }
        if (expected.getX() != foot.getX()) {
            int boundary = Math.max(foot.getX(), expected.getX());
            return (expected.getX() > foot.getX())
                    ? player.getX() - boundary
                    : boundary - player.getX();
        }
        if (expected.getZ() != foot.getZ()) {
            int boundary = Math.max(foot.getZ(), expected.getZ());
            return (expected.getZ() > foot.getZ())
                    ? player.getZ() - boundary
                    : boundary - player.getZ();
        }
        return Double.NaN;
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
