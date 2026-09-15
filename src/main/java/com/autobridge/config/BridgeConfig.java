package com.autobridge.config;

import com.autobridge.Edition;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 全局配置。任何改动都会立刻落盘到 {@code config/autobridge.properties}，
 * 重启后不再回到默认值。
 *
 * <p>设计红线（用户拍板）：
 * <ul>
 *   <li>模组只做「潜行 + 模拟右键」，移动由玩家自己走；</li>
 *   <li>绝不修改玩家视角（rotation），瞄准完全交给玩家；</li>
 *   <li>不自己构造放置包，一切走原版 {@code MinecraftClient#doItemUse} 路径。</li>
 * </ul>
 */
public final class BridgeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoBridge");
    private static final String FILE_NAME = "autobridge.properties";

    private BridgeConfig() {
    }

    /**
     * 放置位相对玩家视线的方向。目前<b>固定为视角反方向（身后）</b>——
     * 「身前」和「任意」两种模式已按用户要求删除。
     *
     * <p>枚举留着当扩展点；改方向只走 ModMenu 设置界面，游戏内不再有对应按键。
     */
    public enum DirectionMode {
        /** 视角反方向（身后）一格 —— 用户确认的搭路方向。 */
        BEHIND_VIEW
    }

    /**
     * 搭路方式。要加新方式就往这里加一项，并在 {@code BridgeValidator} 的几何分派里补一个分支。
     */
    public enum BridgeMode {

        /**
         * 自动搭路 —— <b>唯一的方式</b>：
         *
         * <pre>
         *   站在方块边缘    → 模组自动潜行（这样右键=放置而不是交互）
         *   准星瞄到合法位置 → 模拟右键放置（走原版路径）
         *   人走过去        → 又站到新的边缘 → 循环
         * </pre>
         *
         * <p>潜行由模组接管；玩家一按跳跃就立刻松开潜行让开
         * （原版潜行会屏蔽跳跃，玩家想跳下去时必须不挡路）。
         *
         * <p>「空中/跳跃时补一格」那套已经整个删掉了 —— 详见对应的 lesson 记忆。
         * 新增方式时：在这里加一项，再在 {@code BridgeValidator} 的几何分派、
         * 以及 {@code BridgeController.tickStart} 的分派里各加一个 {@code case}。
         */
        AUTO_BRIDGE("自动搭路");

        private final String displayName;

        BridgeMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * AUTO 模式：不需要按键。玩家站到方块边缘就自动潜行并尝试放置，离开边缘立刻松开潜行。
     * 由 toggle 键（右 Alt）切换；false = 只有按住 hold 键（V）才生效。
     */
    public static boolean autoMode = true;

    /** 放置位方向。默认 ANY：先保证能触发，方向由 B 键在现场试。 */
    /** 当前方向模式。固定视角反方向；要改只在 ModMenu 设置界面里改，游戏内没有对应按键。 */
    public static DirectionMode directionMode = DirectionMode.BEHIND_VIEW;

    /**
     * 当前搭路方式。目前只有 AUTO_BRIDGE 一项 —— 边缘潜行与空中补格是同一个机制，
     * 不是两种可以切换的东西，所以既没有 M 键也没有配置界面里的切换按钮。
     * 枚举本身留着当扩展点：以后真要加第二种方式，在这里加一项 + 两处 case 即可。
     */
    public static BridgeMode bridgeMode = BridgeMode.AUTO_BRIDGE;

    /**
     * 「站在边缘」的判定余量（格）：玩家中心到方块外沿不足这个距离，才算站到了边缘。
     *
     * <p>数值越小越极限 —— 0.30 相当于碰撞箱一贴边就触发，0.03 则要几乎半个身子悬空。
     * 用户实测 0.03 稳定，定为默认。用 N 键在现场轮换。
     */
    public static double edgeMargin = 0.03D;

    /** edgeMargin 的候选档位，N 键依次轮换。 */
    public static final double[] EDGE_MARGIN_STEPS = {0.03D, 0.08D, 0.15D, 0.22D, 0.30D};

    /**
     * 判定「危险边缘」时往下探多少格：如果边缘外侧在这个深度内能找到落脚方块，
     * 就认为掉下去无所谓（比如平地旁边一个只矮一格的小坑，走回来就行），不触发潜行。
     *
     * <p>默认 3 —— 原版掉落 3 格以内不掉血，所以 3 格内有底就不算危险。
     * 只有悬崖和虚空这种「探到底都没东西接着」的地方才值得为它潜行。
     */
    public static int edgeDropDepth = 3;

    /**
     * 「落脚面」的最小面积（同一层 3×3 里至少要有几格实心）。
     *
     * <p>孤立的单格方块、一格宽的窄梁都接不住人 —— 人从旁边擦过去会继续往下掉，
     * 所以它们不算落脚点。默认 5：像样的地面（3×3 铺满 = 9）和浅坑底（9）都过线，
     * 孤立方块（1）和窄梁（3）过不了。
     */
    public static int landingArea = 5;

    /**
     * 危险判定时沿悬空方向往外看几格：连续这么多格都空，就认定是「大片虚空」（悬空桥、悬崖），
     * 而不是平地上一个小坑。
     *
     * <p>最小 2 —— 只看 1 格的话，桥下恰好有个孤立方块、或者桥搭得矮（下方就是地面），
     * 都会被误判成「掉下去没事」而漏掉潜行。
     */
    public static int edgeLookAhead = 2;

    /**
     * 玩家朝悬空方向的速度达到这个值（格/tick）时，判定再多看一格。
     *
     * <p>因为潜行要 1 tick 才同步到服务端，这段时间里玩家还在往虚空走 —— 速度越快，
     * 这段空窗走得越远，越需要提前发现。参考值：走路约 0.22、疾跑约 0.28、潜行约 0.07。
     */
    public static double fastApproachSpeed = 0.25D;

    /** 是否由模组强制潜行。搭路必需（潜行时右键才会跳过方块交互直接放置）。 */
    public static boolean forceSneak = true;

    /** 是否显示调试 HUD。开发版默认开，用户版默认关（在 ModMenu 里仍可手动打开）。 */
    public static boolean debugHud = Edition.DEV;

    /** 触发前的最小随机延迟（tick）。 */
    public static int minDelayTicks = 0;

    /** 触发前的最大随机延迟（tick）。 */
    public static int maxDelayTicks = 1;

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** 配置文件路径：{@code .minecraft/config/autobridge.properties}。 */
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 读回上次的设置。文件不存在、读不出来、或者值非法，都静默保持当前默认值。 */
    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            // 首次运行就把默认值落盘，这样玩家能直接打开文件手改，而不是要先去游戏里按一下按键
            LOGGER.info("[AutoBridge] no config yet, writing defaults to {}", path);
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[AutoBridge] failed to read config, using defaults: {}", e.toString());
            return;
        }

        autoMode = readBool(props, "autoMode", autoMode);
        forceSneak = readBool(props, "forceSneak", forceSneak);
        debugHud = readBool(props, "debugHud", debugHud);

        try {
            edgeMargin = Double.parseDouble(props.getProperty("edgeMargin", String.valueOf(edgeMargin)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad edgeMargin in config, keeping {}", edgeMargin);
        }

        try {
            edgeDropDepth = Integer.parseInt(props.getProperty("edgeDropDepth", String.valueOf(edgeDropDepth)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad edgeDropDepth in config, keeping {}", edgeDropDepth);
        }

        try {
            landingArea = Integer.parseInt(props.getProperty("landingArea", String.valueOf(landingArea)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad landingArea in config, keeping {}", landingArea);
        }

        try {
            edgeLookAhead = Integer.parseInt(props.getProperty("edgeLookAhead", String.valueOf(edgeLookAhead)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad edgeLookAhead in config, keeping {}", edgeLookAhead);
        }

        try {
            fastApproachSpeed = Double.parseDouble(
                    props.getProperty("fastApproachSpeed", String.valueOf(fastApproachSpeed)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad fastApproachSpeed in config, keeping {}", fastApproachSpeed);
        }

        String dir = props.getProperty("directionMode");
        if (dir != null) {
            try {
                directionMode = DirectionMode.valueOf(dir);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[AutoBridge] bad directionMode in config, keeping {}", directionMode);
            }
        }

        String mode = props.getProperty("bridgeMode");
        if (mode != null) {
            try {
                bridgeMode = BridgeMode.valueOf(mode);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[AutoBridge] bad bridgeMode in config, keeping {}", bridgeMode);
            }
        }

        LOGGER.info("[AutoBridge] config loaded: auto={} mode={} direction={} hud={} | edgeMargin={} dropDepth={} landingArea={} lookAhead={} fastApproach={}",
                autoMode, bridgeMode, directionMode, debugHud,
                edgeMargin, edgeDropDepth, landingArea, edgeLookAhead, fastApproachSpeed);

        // 回写一次：老版本存下的文件会缺掉后来新加的键，这样能自动补齐
        save();
    }

    /** 把当前设置写回磁盘。按键改完立刻调用，代价只有几毫秒。 */
    public static void save() {
        Properties props = new Properties();
        props.setProperty("autoMode", String.valueOf(autoMode));
        props.setProperty("forceSneak", String.valueOf(forceSneak));
        props.setProperty("debugHud", String.valueOf(debugHud));
        props.setProperty("edgeMargin", String.valueOf(edgeMargin));
        props.setProperty("edgeDropDepth", String.valueOf(edgeDropDepth));
        props.setProperty("landingArea", String.valueOf(landingArea));
        props.setProperty("edgeLookAhead", String.valueOf(edgeLookAhead));
        props.setProperty("fastApproachSpeed", String.valueOf(fastApproachSpeed));
        props.setProperty("directionMode", directionMode.name());
        props.setProperty("bridgeMode", bridgeMode.name());
        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "AutoBridge - client-side bridge helper");
        } catch (IOException e) {
            LOGGER.warn("[AutoBridge] failed to write config: {}", e.toString());
        }
    }

    private static boolean readBool(Properties props, String key, boolean fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }

    // ------------------------------------------------------------------
    // 现场轮换
    // ------------------------------------------------------------------

    /** 依次轮换边缘判定余量，并立刻存盘。 */
    public static void cycleEdgeMargin() {
        int idx = 0;
        for (int i = 0; i < EDGE_MARGIN_STEPS.length; i++) {
            if (Math.abs(EDGE_MARGIN_STEPS[i] - edgeMargin) < 1.0E-6D) {
                idx = i;
                break;
            }
        }
        edgeMargin = EDGE_MARGIN_STEPS[(idx + 1) % EDGE_MARGIN_STEPS.length];
        save();
    }

    /** 依次轮换方向模式，并立刻存盘。只在 ModMenu 设置界面里用；只剩一项时点了不会变。 */
    public static void cycleDirection() {
        DirectionMode[] all = DirectionMode.values();
        directionMode = all[(directionMode.ordinal() + 1) % all.length];
        save();
    }

    /** 当前方向模式的中文名，给 HUD 和设置界面用。 */
    public static String directionName() {
        return switch (directionMode) {
            case BEHIND_VIEW -> "视角反方向(身后)";
        };
    }
}
