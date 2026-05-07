package com.poseidon.modmenu;

import com.poseidon.gui.PoseidonConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class PoseidonModMenuPlugin implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PoseidonConfigScreen::create;
    }
}
