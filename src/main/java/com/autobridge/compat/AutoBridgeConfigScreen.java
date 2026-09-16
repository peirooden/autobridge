package com.autobridge.compat;

import com.autobridge.config.BridgeConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * ModMenu 里的设置界面。
 *
 * <p>刻意只用原版 {@link ButtonWidget} 手搓，不引 Cloth Config —— 每点一下就地改
 * {@link BridgeConfig} 并立刻存盘，按钮上显示的永远是当前真实值，不存在「改了没保存」的中间状态。
 */
public class AutoBridgeConfigScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 22;

    /** 启动搭路所需的低头角度候选（俯仰角，<b>正数向下</b>）。用户实测瞄侧面约 +77°~+83°。 */
    private static final double[] LOW_HEAD_STEPS = {65.0D, 70.0D, 77.0D, 83.0D};

    /** 空闲超时的候选秒数。 */
    private static final int[] IDLE_TIMEOUT_STEPS = {1, 2, 3, 4, 5};

    /** ModMenu 会把上一级界面传进来，点「完成」要还回去。 */
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

        addDrawableChild(ButtonWidget.builder(lowHeadText(), button -> {
            int idx = 0;
            for (int i = 0; i < LOW_HEAD_STEPS.length; i++) {
                if (Math.abs(LOW_HEAD_STEPS[i] - BridgeConfig.lowHeadPitch) < 1.0E-6D) {
                    idx = i;
                    break;
                }
            }
            BridgeConfig.lowHeadPitch = LOW_HEAD_STEPS[(idx + 1) % LOW_HEAD_STEPS.length];
            BridgeConfig.save();
            button.setMessage(lowHeadText());
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
        this.renderBackground(context);
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

    // ------------------------------------------------------------------
    // 按钮文案：每次都从 BridgeConfig 现取，所以永远显示当前真值
    // ------------------------------------------------------------------

    private static Text autoModeText() {
        return Text.literal("自动搭路模式：" + (BridgeConfig.autoMode ? "开" : "关"));
    }

    private static Text lowHeadText() {
        return Text.literal(String.format(Locale.ROOT, "启动低头角度：+%.0f°（正数=低头）", BridgeConfig.lowHeadPitch));
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
