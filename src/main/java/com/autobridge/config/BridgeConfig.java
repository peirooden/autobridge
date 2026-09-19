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

public final class BridgeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoBridge");
    private static final String FILE_NAME = "autobridge.properties";

    private BridgeConfig() {
    }

    public enum DirectionMode {
        BEHIND_VIEW
    }

    public enum BridgeMode {

        SNEAK_BRIDGE("蹲搭"),

        GOD_BRIDGE("神桥");

        private final String displayName;

        BridgeMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static boolean autoMode = true;

    public static DirectionMode directionMode = DirectionMode.BEHIND_VIEW;

    public static BridgeMode bridgeMode = BridgeMode.SNEAK_BRIDGE;

    public static double edgeMargin = 0.03D;

    public static final double[] EDGE_MARGIN_STEPS = {0.03D, 0.08D, 0.15D, 0.22D, 0.30D};

    public static boolean forceSneak = true;

    public static boolean debugHud = Edition.DEV;

    public static double lowHeadPitch = 60.0D;

    public static int idleTimeoutTicks = 60;

    public static int minDelayTicks = 0;

    public static int maxDelayTicks = 1;

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
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

        save();
    }

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

    public static void cycleDirection() {
        DirectionMode[] all = DirectionMode.values();
        directionMode = all[(directionMode.ordinal() + 1) % all.length];
        save();
    }

    public static void cycleBridgeMode() {
        BridgeMode[] all = BridgeMode.values();
        bridgeMode = all[(bridgeMode.ordinal() + 1) % all.length];
        save();
    }

    public static String directionName() {
        return switch (directionMode) {
            case BEHIND_VIEW -> "视角反方向(身后)";
        };
    }
}
