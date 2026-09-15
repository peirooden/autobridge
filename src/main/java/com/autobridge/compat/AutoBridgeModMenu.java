package com.autobridge.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成入口。
 *
 * <p>只有玩家装了 ModMenu，Fabric Loader 才会来取这个入口点；没装的话这个类根本不会被加载，
 * 所以本模组对 ModMenu 不构成硬依赖（{@code fabric.mod.json} 里也只写在 {@code suggests}）。
 */
public class AutoBridgeModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AutoBridgeConfigScreen::new;
    }
}
