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
     * 搭路方式（0.3.0 起两项，只在 ModMenu 设置界面里切换）。
     *
     * <p>两种方式<b>只在「接管期间潜不潜行」这一点上分叉</b>：启动条件、校验链、模拟右键、
     * 世界状态验收、超时退出全部共用。
     */
    public enum BridgeMode {

        /**
         * 蹲搭（原普通版）：贴着边缘时由模组强制潜行。
         *
         * <pre>
         *   站在方块边缘    → 模组自动潜行（右键=放置而不是交互；移速 ×0.3，放置跟得上）
         *   准星瞄到合法位置 → 模拟右键放置（走原版路径）
         *   人走过去        → 又站到新的边缘 → 循环
         * </pre>
         *
         * <p>潜行由模组接管；玩家一按跳跃就立刻松开潜行让开
         * （原版潜行会屏蔽跳跃，玩家想跳下去时必须不挡路）。
         */
        SNEAK_BRIDGE("蹲搭"),

        /**
         * 神桥（原特殊版）：接管期间<b>完全不潜行</b>。
         *
         * <p>没有潜行就没有原版的边缘保护，改由 {@code PlayerEntityClipMixin} 注入
         * {@code clipAtLedge} 打开原版钳制，把人钉在边缘上；放置走原版按住右键
         * （{@code itemUseCooldown} 决定的 5 次/秒），跟得上行走的 4.317 格/秒。
         * 潜行键一次都不碰 —— 玩家自己按着就按着、松着就松着。
         */
        GOD_BRIDGE("神桥");

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
     * 当前搭路方式，默认「蹲搭」—— 与 0.2.0 的行为完全一致。
     *
     * <p>只能在 ModMenu 设置界面里切换（游戏内没有对应按键）。
     */
    public static BridgeMode bridgeMode = BridgeMode.SNEAK_BRIDGE;

    /**
     * 「站在边缘」的判定余量（格）：玩家中心到方块外沿不足这个距离，才算站到了边缘。
     *
     * <p>数值越小越极限 —— 0.30 相当于碰撞箱一贴边就触发，0.03 则要几乎半个身子悬空。
     * 用户实测 0.03 稳定，定为默认。用 N 键在现场轮换。
     */
    public static double edgeMargin = 0.03D;

    /** edgeMargin 的候选档位，N 键依次轮换。 */
    public static final double[] EDGE_MARGIN_STEPS = {0.03D, 0.08D, 0.15D, 0.22D, 0.30D};

    // 注意：0.2.0 删掉了 edgeDropDepth / landingArea / edgeLookAhead / fastApproachSpeed 四项。
    // 它们原本用来把「真悬崖」和「平地浅坑」区分开，免得自动检测乱潜行；
    // 现在启动权在玩家手里（要自己蹲+低头+放一格），只要在边缘就该潜行，这套判定失去意义。
    // 旧配置文件里残留的这几个键会被直接忽略，下次 save() 时不再写回。

    /** 是否由模组强制潜行。搭路必需（潜行时右键才会跳过方块交互直接放置）。 */
    public static boolean forceSneak = true;

    /** 是否显示调试 HUD。开发版默认开，用户版默认关（在 ModMenu 里仍可手动打开）。 */
    public static boolean debugHud = Edition.DEV;

    /**
     * 启动搭路所需的低头程度（俯仰角，<b>正数表示向下看</b>）。
     *
     * <p>⚠️ Minecraft 的 {@code Entity#getPitch()} 约定：<b>+90 是垂直向下看脚底，
     * -90 才是抬头看天</b>。所以低头是<b>正</b>值，别写反。
     *
     * <p><b>它只是「启动闸门」，不是「可用瞄准带」</b>：方块到底能不能放成，由校验链的几何
     * （必须真的瞄到脚下方块侧面）把关 —— 所以闸门放宽不会放出歪方块，只是让"还在调角度"
     * 时也可能先接管。
     *
     * <p>正面站着瞄侧面约 +79°~+84°（潜行 +77°~+83°）；但<b>斜着站在方块角落（对角线边缘）
     * 时水平距离更长，可以浅很多</b>。2026-09-17 实测：用户在那个姿势下自然瞄的是
     * <b>61°~65°</b>（他说 60° 左右已经算极限）。所以下限定 <b>60</b> —— 再浅就不像在看脚底了。
     */
    public static double lowHeadPitch = 60.0D;

    /**
     * 搭路启动后，连续这么多 tick 没有「成功放置」就自动结束、回到待机。
     *
     * <p>60 tick = 3 秒。计时以<b>世界状态确认过的真实放置</b>为准，
     * 不是「模拟了几次右键」—— 右键发出去了但被服务端拒绝的不算数。
     */
    public static int idleTimeoutTicks = 60;

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
            lowHeadPitch = Double.parseDouble(props.getProperty("lowHeadPitch", String.valueOf(lowHeadPitch)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad lowHeadPitch in config, keeping {}", lowHeadPitch);
        }

        try {
            idleTimeoutTicks = Integer.parseInt(
                    props.getProperty("idleTimeoutTicks", String.valueOf(idleTimeoutTicks)));
        } catch (NumberFormatException e) {
            LOGGER.warn("[AutoBridge] bad idleTimeoutTicks in config, keeping {}", idleTimeoutTicks);
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

        LOGGER.info("[AutoBridge] config loaded: auto={} mode={} direction={} hud={} | edgeMargin={} lowHeadPitch={} idleTimeout={}tick",
                autoMode, bridgeMode, directionMode, debugHud,
                edgeMargin, lowHeadPitch, idleTimeoutTicks);

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
        props.setProperty("lowHeadPitch", String.valueOf(lowHeadPitch));
        props.setProperty("idleTimeoutTicks", String.valueOf(idleTimeoutTicks));
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

    /** 依次轮换搭路方式（蹲搭 ↔ 神桥），并立刻存盘。只在 ModMenu 设置界面里改。 */
    public static void cycleBridgeMode() {
        BridgeMode[] all = BridgeMode.values();
        bridgeMode = all[(bridgeMode.ordinal() + 1) % all.length];
        save();
    }

    /** 当前方向模式的中文名，给 HUD 和设置界面用。 */
    public static String directionName() {
        return switch (directionMode) {
            case BEHIND_VIEW -> "视角反方向(身后)";
        };
    }
}
