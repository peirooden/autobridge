package com.autobridge.core;

import com.autobridge.config.BridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class BridgeValidator {

    private BridgeValidator() {
    }

    public record Result(boolean ok, String reason, BlockPos placePos, BlockPos expectedPos) {

        public static Result fail(String reason, BlockPos expected) {
            return new Result(false, reason, null, expected);
        }

        public static Result pass(BlockPos placePos, BlockPos expected) {
            return new Result(true, "OK", placePos, expected);
        }
    }

    public static Hand bridgingHand(ClientPlayerEntity player) {
        if (player.getMainHandStack().getItem() instanceof BlockItem) {
            return Hand.MAIN_HAND;
        }
        if (player.getOffHandStack().getItem() instanceof BlockItem) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    public static boolean isHoldingBlock(ClientPlayerEntity player) {
        return bridgingHand(player) != null;
    }

    public static String heldDescription(ClientPlayerEntity player) {
        Hand hand = bridgingHand(player);
        if (hand == null) {
            return "空";
        }
        return player.getStackInHand(hand).getItem() + (hand == Hand.OFF_HAND ? "(副手)" : "");
    }

    public static Result validate(MinecraftClient client) {
        return validate(client, null);
    }

    public static Result validate(MinecraftClient client, BlockPos referenceBlock) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Result.fail("无玩家或世界", null);
        }
        if (client.currentScreen != null) {
            return Result.fail("有界面打开", null);
        }
        if (player.isSpectator()) {
            return Result.fail("旁观者模式", null);
        }

        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() != HitResult.Type.BLOCK || !(target instanceof BlockHitResult hit)) {
            return Result.fail("准星未命中方块", null);
        }

        Hand hand = bridgingHand(player);
        if (hand == null) {
            return Result.fail("主手和副手都不是方块物品", null);
        }
        ItemStack stack = player.getStackInHand(hand);

        ItemPlacementContext placeContext = new ItemPlacementContext(player, hand, stack, hit);
        if (!placeContext.canPlace()) {
            return Result.fail("原版 canPlace 判定不可放置", null);
        }
        BlockPos placePos = placeContext.getBlockPos();

        BlockPos footPos = (referenceBlock != null) ? referenceBlock : findFootBlock(player, client.world);
        if (footPos == null) {
            return Result.fail("脚下没有支撑方块（在飞或游泳？）", null);
        }
        BlockPos expected = expectedPos(player, footPos);

        Box blockBox = new Box(
                placePos.getX(), placePos.getY(), placePos.getZ(),
                placePos.getX() + 1.0, placePos.getY() + 1.0, placePos.getZ() + 1.0);
        if (player.getBoundingBox().intersects(blockBox)) {
            return Result.fail("放置位与玩家碰撞箱重叠", expected);
        }

        double dist = player.getEyePos().distanceTo(hit.getPos());
        double reach = player.getAttributeValue(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE);
        if (dist > reach) {
            return Result.fail(String.format("超出交互距离 %.2f > %.2f", dist, reach), expected);
        }

        if (BridgeConfig.bridgeMode != BridgeConfig.BridgeMode.GOD_BRIDGE && !player.isSneaking()) {
            return Result.fail("未潜行（右键会先触发方块交互，放置不成立）", expected);
        }

        if (hit.getSide().getAxis() == Direction.Axis.Y) {
            return Result.fail("命中的是方块顶面/底面（搭路必须瞄侧面），面="
                    + hit.getSide().getName(), expected);
        }

        return checkEdgeSneak(player, placePos, footPos);
    }

    private static Result checkEdgeSneak(ClientPlayerEntity player, BlockPos placePos, BlockPos footPos) {
        BlockPos expected = expectedPos(player, footPos);

        if (placePos.getY() != footPos.getY()) {
            return Result.fail("放置位不在脚下层 (Y=" + placePos.getY() + "，需要 " + footPos.getY() + ")", expected);
        }

        int dx = placePos.getX() - footPos.getX();
        int dz = placePos.getZ() - footPos.getZ();
        int manhattan = Math.abs(dx) + Math.abs(dz);
        if (manhattan < 1 || manhattan > 2) {
            return Result.fail("放置位与脚下方块水平距离不合法 (曼哈顿=" + manhattan + ")", expected);
        }

        String dirError = checkDirection(player, dx, dz);
        if (dirError != null) {
            return Result.fail(dirError, expected);
        }

        return Result.pass(placePos, expected);
    }

    private static String shortPos(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    public static BlockPos findFootBlock(ClientPlayerEntity player, World world) {
        Box box = player.getBoundingBox();
        int y = MathHelper.floor(box.minY - 0.08D);
        int minX = MathHelper.floor(box.minX + 1.0E-4D);
        int maxX = MathHelper.floor(box.maxX - 1.0E-4D);
        int minZ = MathHelper.floor(box.minZ + 1.0E-4D);
        int maxZ = MathHelper.floor(box.maxZ - 1.0E-4D);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cursor.set(x, y, z);
                if (!world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty()) {
                    return cursor.toImmutable();
                }
            }
        }
        return null;
    }

    public static boolean isOnBlockEdge(ClientPlayerEntity player, World world) {
        if (findFootBlock(player, world) == null) {
            return false;
        }

        int y = MathHelper.floor(player.getBoundingBox().minY - 0.08D);
        double m = Math.max(0.0D, BridgeConfig.edgeMargin);
        double cx = player.getX();
        double cz = player.getZ();
        int bx = MathHelper.floor(cx);
        int bz = MathHelper.floor(cz);

        if (isEmptyAt(world, bx, y, bz)) {
            return true;
        }

        return (cx - bx < m && isEmptyAt(world, bx - 1, y, bz))
                || ((bx + 1) - cx < m && isEmptyAt(world, bx + 1, y, bz))
                || (cz - bz < m && isEmptyAt(world, bx, y, bz - 1))
                || ((bz + 1) - cz < m && isEmptyAt(world, bx, y, bz + 1));
    }

    private static boolean isEmptyAt(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    public static BlockPos expectedPos(ClientPlayerEntity player, BlockPos footPos) {
        if (footPos == null) {
            return null;
        }
        return switch (BridgeConfig.directionMode) {
            case BEHIND_VIEW -> footPos.offset(player.getHorizontalFacing().getOpposite());
        };
    }

    public static BlockPos expectedPos(ClientPlayerEntity player, World world) {
        return expectedPos(player, findFootBlock(player, world));
    }

    private static String checkDirection(ClientPlayerEntity player, int dx, int dz) {
        Vec3d look = player.getRotationVec(1.0F);
        double dot = dx * look.x + dz * look.z;
        return switch (BridgeConfig.directionMode) {
            case BEHIND_VIEW -> dot < 0.0D ? null : "放置位不在视角反方向（应在身后）";
        };
    }
}
