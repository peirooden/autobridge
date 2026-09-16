package com.autobridge.core;

import com.autobridge.config.BridgeConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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

/**
 * 判断「玩家当前这一刻的准星」是否构成一次合法的搭路放置。
 *
 * <p>核心思想：<b>不自己算几何、不自己造 hitVec</b>。
 * 我们直接读取原版每帧算出来的 {@code client.crosshairTarget}，并用原版的
 * {@link ItemPlacementContext} 反推「服务端会把这个方块放在哪」。
 * 这样客户端认为合法的东西，和服务端校验的东西是同一套逻辑，容差为零。
 */
public final class BridgeValidator {

    private BridgeValidator() {
    }

    /** 校验结果。{@code ok=false} 时 {@code reason} 说明被哪一条挡下。 */
    public record Result(boolean ok, String reason, BlockPos placePos, BlockPos expectedPos) {

        public static Result fail(String reason, BlockPos expected) {
            return new Result(false, reason, null, expected);
        }

        public static Result pass(BlockPos placePos, BlockPos expected) {
            return new Result(true, "OK", placePos, expected);
        }
    }

    /** 便利重载：基准方块由本方法自己去找（边缘搭路用这个）。 */
    public static Result validate(MinecraftClient client) {
        return validate(client, null);
    }

    /**
     * 完整校验。
     *
     * @param referenceBlock 基准方块 —— 一切几何判定都以它为准。
     *                       传 {@code null} 表示「自己找玩家此刻踩着的方块」。
     *                       需要以「某个已经不在脚下的方块」为基准时，显式传进来即可
     *                       （玩家离地后 {@link #findFootBlock} 会返回 null）。
     */
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

        // ---------- ① 准星必须命中方块 ----------
        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() != HitResult.Type.BLOCK || !(target instanceof BlockHitResult hit)) {
            return Result.fail("准星未命中方块", null);
        }

        // ---------- ② 主手必须是可放置的方块物品 ----------
        ItemStack stack = player.getStackInHand(Hand.MAIN_HAND);
        if (!(stack.getItem() instanceof BlockItem)) {
            return Result.fail("主手不是方块物品", null);
        }

        // ---------- ③ 问原版：你会把它放在哪 ----------
        ItemPlacementContext placeContext = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
        if (!placeContext.canPlace()) {
            return Result.fail("原版 canPlace 判定不可放置", null);
        }
        BlockPos placePos = placeContext.getBlockPos();

        // ---------- ④ 基准方块 ----------
        // 注意：不能用 player.getBlockPos().down()。站在方块边缘时脚部坐标取整会落到
        // 隔壁的空气柱上，down() 拿到空气，后面所有几何判定就全错位了。
        BlockPos footPos = (referenceBlock != null) ? referenceBlock : findFootBlock(player, client.world);
        if (footPos == null) {
            return Result.fail("脚下没有支撑方块（在飞或游泳？）", null);
        }
        BlockPos expected = expectedPos(player, footPos);

        // ---------- ⑤ 不能卡住玩家自己 ----------
        // 注意这条其实比原版严：原版 canPlace 不检查实体碰撞
        // （World.canPlace 传进去的 entity 就是 null）。留着它是因为玩家站着不动，
        // 放置位还能和自己重叠，就说明站位真的有问题。
        Box blockBox = new Box(
                placePos.getX(), placePos.getY(), placePos.getZ(),
                placePos.getX() + 1.0, placePos.getY() + 1.0, placePos.getZ() + 1.0);
        if (player.getBoundingBox().intersects(blockBox)) {
            return Result.fail("放置位与玩家碰撞箱重叠", expected);
        }

        // ---------- ⑨ 交互距离（原版 raycast 已保证，这里做双保险） ----------
        double dist = player.getEyePos().distanceTo(hit.getPos());
        double reach = client.interactionManager == null ? 4.5D : client.interactionManager.getReachDistance();
        if (dist > reach) {
            return Result.fail(String.format("超出交互距离 %.2f > %.2f", dist, reach), expected);
        }

        // ---------- ⑨ 必须处于潜行 ----------
        // 原版潜行会让 shouldCancelInteraction() 返回 true，
        // 右键才不会去开箱子/按按钮，而是直接进入方块放置流程。
        if (!player.isSneaking()) {
            return Result.fail("未潜行（右键会先触发方块交互，放置不成立）", expected);
        }

        // ---------- ⑩ 必须瞄在方块的侧面上（不能是顶面 / 底面） ----------
        // 这是搭路与「叠高」的分界线：
        //   瞄侧面 → 方块放到该侧面外侧（横向延伸 = 搭路）
        //   瞄顶面 → 方块放到命中方块的上方（纵向叠高，不是搭路）
        //   瞄底面 → 往下方垫
        // 用户明确要求：低头瞄到顶面时不许放。
        if (hit.getSide().getAxis() == Direction.Axis.Y) {
            return Result.fail("命中的是方块顶面/底面（搭路必须瞄侧面），面="
                    + hit.getSide().getName(), expected);
        }

        // ---------- ⑪ 几何判定 ----------
        return checkEdgeSneak(player, placePos, footPos);
    }

    /**
     * 边缘搭路的几何：放置位必须与基准方块<b>同层</b>、水平距离 1~2 格、且方向符合配置。
     */
    private static Result checkEdgeSneak(ClientPlayerEntity player, BlockPos placePos, BlockPos footPos) {
        BlockPos expected = expectedPos(player, footPos);

        // 必须与脚下方块同层（排除往头上/脚下叠）
        if (placePos.getY() != footPos.getY()) {
            return Result.fail("放置位不在脚下层 (Y=" + placePos.getY() + "，需要 " + footPos.getY() + ")", expected);
        }

        // 水平距离必须落在 1~2 格。
        // 放宽到 2 格，是因为"点相邻方块的侧面、把方块放到它外侧"在实战里也很常见。
        // 距离 0 表示要放在自己脚下，直接排除。
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

    /**
     * 找玩家真正踩着的那个方块：拿碰撞箱底面覆盖到的所有水平格去探。
     *
     * @return 支撑方块；悬空/飞行时为 {@code null}
     */
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

    /**
     * 玩家是否站在方块边缘 —— 两件事：
     * <ol>
     *   <li>脚下确实踩在方块上（排除跳跃/坠落途中）；</li>
     *   <li>某个方向已经悬空，且玩家中心离那条边界不足 {@link BridgeConfig#edgeMargin}。</li>
     * </ol>
     *
     * <p>少了 ①，人在坠落途中脚下全是空气，会被判成站在边缘。
     *
     * <h2>为什么不再判断「这里危不危险」</h2>
     * <p>0.2.0 之前这里还有第三层判定：往下探 {@code edgeDropDepth} 格找落脚面、沿悬空方向
     * 看 {@code edgeLookAhead} 格是不是大片虚空、落脚面够不够 {@code landingArea} 大 ——
     * 目的是把「真悬崖」和「平地旁的浅坑」区分开，免得模组在人家正常走路时乱潜行。
     *
     * <p><b>现在启动权在玩家手里</b>（要自己蹲 + 低头 + 放一格才启动），
     * 等于他已经声明了「我在搭路」。那就<b>只要在边缘就该潜行</b>，
     * 不需要再论证这个边缘值不值得 —— 于是这一整套判定连同
     * {@code isRiskyGap} / {@code lookAheadSpan} / {@code hasLandingBelow} / {@code landingAreaAt}
     * 全部删除（用户要求：「既然咱们已经设置了这么复杂自动搭路开启前置，之前的那个深度和水平检测可以去掉了」）。
     */
    public static boolean isOnBlockEdge(ClientPlayerEntity player, World world) {
        // ① 先要求真的踩在方块上：人一离地就出局
        if (findFootBlock(player, world) == null) {
            return false;
        }

        int y = MathHelper.floor(player.getBoundingBox().minY - 0.08D);
        double m = Math.max(0.0D, BridgeConfig.edgeMargin);
        double cx = player.getX();
        double cz = player.getZ();
        int bx = MathHelper.floor(cx);
        int bz = MathHelper.floor(cz);

        // ② 中心格本身已经空了（但碰撞箱还压着旁边的方块）—— 这已经是最极限的边缘
        if (isEmptyAt(world, bx, y, bz)) {
            return true;
        }

        // ③ 贴着某条边界，且那一边的相邻格是空的
        return (cx - bx < m && isEmptyAt(world, bx - 1, y, bz))
                || ((bx + 1) - cx < m && isEmptyAt(world, bx + 1, y, bz))
                || (cz - bz < m && isEmptyAt(world, bx, y, bz - 1))
                || ((bz + 1) - cz < m && isEmptyAt(world, bx, y, bz + 1));
    }

    private static boolean isEmptyAt(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /** 按配置算出「期望的放置位」；没踩到方块时返回 null。 */
    public static BlockPos expectedPos(ClientPlayerEntity player, BlockPos footPos) {
        if (footPos == null) {
            return null;
        }
        return switch (BridgeConfig.directionMode) {
            case BEHIND_VIEW -> footPos.offset(player.getHorizontalFacing().getOpposite());
        };
    }

    /** 给调试 HUD 用的便利重载。 */
    public static BlockPos expectedPos(ClientPlayerEntity player, World world) {
        return expectedPos(player, findFootBlock(player, world));
    }

    /** 用真实视线向量做方向判定，而不是只靠轴向取整，斜着看也能用。 */
    private static String checkDirection(ClientPlayerEntity player, int dx, int dz) {
        Vec3d look = player.getRotationVec(1.0F);
        double dot = dx * look.x + dz * look.z;
        return switch (BridgeConfig.directionMode) {
            case BEHIND_VIEW -> dot < 0.0D ? null : "放置位不在视角反方向（应在身后）";
        };
    }
}
