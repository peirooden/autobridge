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
    private static final int GAP = 24;

    /** fastApproachSpeed 的候选档位（格/tick），点一下轮换一档。 */
    private static final double[] FAST_APPROACH_STEPS = {0.15D, 0.20D, 0.25D, 0.30D, 0.40D};

    /** ModMenu 会把上一级界面传进来，点「完成」要还回去。 */
    private final Screen parent;

    public AutoBridgeConfigScreen(Screen parent) {
        super(Text.literal("AutoBridge 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = this.height / 4;

        addDrawableChild(ButtonWidget.builder(autoModeText(), button -> {
            BridgeConfig.autoMode = !BridgeConfig.autoMode;
            BridgeConfig.save();
            button.setMessage(autoModeText());
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

        addDrawableChild(ButtonWidget.builder(dropDepthText(), button -> {
            BridgeConfig.edgeDropDepth = BridgeConfig.edgeDropDepth >= 5 ? 1 : BridgeConfig.edgeDropDepth + 1;
            BridgeConfig.save();
            button.setMessage(dropDepthText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(landingAreaText(), button -> {
            BridgeConfig.landingArea = BridgeConfig.landingArea >= 9 ? 1 : BridgeConfig.landingArea + 1;
            BridgeConfig.save();
            button.setMessage(landingAreaText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(lookAheadText(), button -> {
            BridgeConfig.edgeLookAhead = BridgeConfig.edgeLookAhead >= 4 ? 2 : BridgeConfig.edgeLookAhead + 1;
            BridgeConfig.save();
            button.setMessage(lookAheadText());
        }).dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        y += GAP;

        addDrawableChild(ButtonWidget.builder(fastApproachText(), button -> {
            int idx = 0;
            for (int i = 0; i < FAST_APPROACH_STEPS.length; i++) {
                if (Math.abs(FAST_APPROACH_STEPS[i] - BridgeConfig.fastApproachSpeed) < 1.0E-6D) {
                    idx = i;
                    break;
                }
            }
            BridgeConfig.fastApproachSpeed = FAST_APPROACH_STEPS[(idx + 1) % FAST_APPROACH_STEPS.length];
            BridgeConfig.save();
            button.setMessage(fastApproachText());
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
                this.width / 2, this.height / 4 - 26, 0xFFFFFF);
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

    private static Text directionText() {
        return Text.literal("放置方向：" + BridgeConfig.directionName());
    }

    private static Text edgeMarginText() {
        return Text.literal(String.format(Locale.ROOT, "边缘判定阈值：%.2f 格", BridgeConfig.edgeMargin));
    }

    private static Text dropDepthText() {
        return Text.literal("危险判定深度：" + BridgeConfig.edgeDropDepth + " 格");
    }

    private static Text lookAheadText() {
        return Text.literal("水平延伸跨度：" + BridgeConfig.edgeLookAhead + " 格");
    }

    private static Text landingAreaText() {
        return Text.literal("落脚面最小面积：" + BridgeConfig.landingArea + " / 9 格");
    }

    private static Text fastApproachText() {
        return Text.literal(String.format(Locale.ROOT, "加速判定阈值：%.2f 格/刻", BridgeConfig.fastApproachSpeed));
    }

    private static Text hudText() {
        return Text.literal("调试 HUD：" + (BridgeConfig.debugHud ? "开" : "关"));
    }
}
