package com.autobridge;

import com.autobridge.config.BridgeConfig;
import com.autobridge.core.BridgeController;
import com.autobridge.util.DebugHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯客户端入口。fabric.mod.json 里 {@code "environment": "client"}，
 * 因此这个类永远不会在服务端被加载。
 *
 * <p>本模组只做两件事：模拟潜行键、模拟鼠标右键。
 * 玩家的移动与视角（rotation）完全不受影响。
 *
 * <p><b>没有任何游戏内按键绑定</b> —— 所有设置（自动模式开关、边缘阈值、方向、
 * 危险判定深度等）都只从 ModMenu 的设置界面改。这样既不会和玩家自己的键位打架，
 * 也不会在「按键绑定」菜单里多出一整个分类。
 */
public class AutoBridgeClient implements ClientModInitializer {

    public static final String MOD_ID = "autobridge";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoBridge");

    public static final BridgeController CONTROLLER = new BridgeController();

    /**
     * 只在开发版输出的诊断日志。用户版控制台保持干净，不刷 DENIED 之类的排查信息。
     *
     * <p>调试时请构建开发版：{@code .\gradlew build -Pedition=dev}。
     */
    public static void debug(String format, Object... args) {
        if (Edition.DEV) {
            LOGGER.info(format, args);
        }
    }

    @Override
    public void onInitializeClient() {
        // 先把上次存下来的设置读回来（自动模式开关、方向、边缘阈值都会保留）
        BridgeConfig.load();

        ClientTickEvents.START_CLIENT_TICK.register(CONTROLLER::tickStart);
        HudRenderCallback.EVENT.register(DebugHud::render);

        LOGGER.info("[AutoBridge] {} loaded: CLIENT-ONLY. Sneak + simulated right-click only, rotation untouched.",
                Edition.TITLE);
    }
}
