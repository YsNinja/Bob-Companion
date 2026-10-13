package com.quackcraft.quackingly.client;

import com.quackcraft.quackingly.client.screen.BobConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu integration; Bob still loads when Mod Menu is not installed. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BobConfigScreen::new;
    }
}
