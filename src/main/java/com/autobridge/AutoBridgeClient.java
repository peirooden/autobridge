package com.autobridge;

import com.autobridge.config.BridgeConfig;
import com.autobridge.core.BridgeController;
import com.autobridge.util.DebugHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoBridgeClient implements ClientModInitializer {

    public static final String MOD_ID = "autobridge";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoBridge");

    public static final BridgeController CONTROLLER = new BridgeController();

    public static void debug(String format, Object... args) {
        if (Edition.DEV) {
            LOGGER.info(format, args);
        }
    }

    @Override
    public void onInitializeClient() {
        BridgeConfig.load();

        ClientTickEvents.START_CLIENT_TICK.register(CONTROLLER::tickStart);
        HudRenderCallback.EVENT.register(DebugHud::render);

        LOGGER.info("[AutoBridge] {} loaded: CLIENT-ONLY. Sneak + simulated right-click only, rotation untouched.",
                Edition.TITLE);
    }
}
