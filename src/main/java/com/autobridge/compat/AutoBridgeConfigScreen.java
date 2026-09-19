package com.autobridge.compat;

import com.autobridge.config.BridgeConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public class AutoBridgeConfigScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 22;

    private static final int[] IDLE_TIMEOUT_STEPS = {1, 2, 3, 4, 5};

    private final Screen parent;

    public AutoBridgeConfigScreen(Screen parent) {
        super(Text.literal("AutoBridge 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = this.height / 8;

        addDrawableChild(ButtonWidget.builder(autoModeText(), button -> {
            BridgeConfig.autoMode = !BridgeConfig.autoMode;
            BridgeConfig.save();
            button.setMessage(autoModeText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(bridgeModeText(), button -> {
            BridgeConfig.cycleBridgeMode();
            button.setMessage(bridgeModeText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(idleTimeoutText(), button -> {
            int idx = 0;
            int current = Math.round(BridgeConfig.idleTimeoutTicks / 20.0F);
            for (int i = 0; i < IDLE_TIMEOUT_STEPS.length; i++) {
                if (IDLE_TIMEOUT_STEPS[i] == current) {
                    idx = i;
                    break;
                }
            }
            int seconds = IDLE_TIMEOUT_STEPS[(idx + 1) % IDLE_TIMEOUT_STEPS.length];
            BridgeConfig.idleTimeoutTicks = seconds * 20;
            BridgeConfig.save();
            button.setMessage(idleTimeoutText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(directionText(), button -> {
            BridgeConfig.cycleDirection();
            button.setMessage(directionText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(edgeMarginText(), button -> {
            BridgeConfig.cycleEdgeMargin();
            button.setMessage(edgeMarginText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(hudText(), button -> {
            BridgeConfig.debugHud = !BridgeConfig.debugHud;
            BridgeConfig.save();
            button.setMessage(hudText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP + 10;

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> this.close())
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.height / 8 - 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private static Text autoModeText() {
        return Text.literal("自动搭路模式：" + (BridgeConfig.autoMode ? "开" : "关"));
    }

    private static Text bridgeModeText() {
        return Text.literal("搭路方式：" + BridgeConfig.bridgeMode.displayName());
    }

    private static Text idleTimeoutText() {
        return Text.literal(String.format(Locale.ROOT, "搭路超时：%.1f 秒无放置结束",
                BridgeConfig.idleTimeoutTicks / 20.0F));
    }

    private static Text directionText() {
        return Text.literal("放置方向：" + BridgeConfig.directionName());
    }

    private static Text edgeMarginText() {
        return Text.literal(String.format(Locale.ROOT, "边缘判定阈值：%.2f 格（越小越严）", BridgeConfig.edgeMargin));
    }

    private static Text hudText() {
        return Text.literal("调试 HUD：" + (BridgeConfig.debugHud ? "开" : "关"));
    }
}
