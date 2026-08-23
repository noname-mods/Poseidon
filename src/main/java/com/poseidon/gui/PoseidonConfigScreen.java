package com.poseidon.gui;

import com.poseidon.core.FishingConfig;
import com.playerapi.config.PlayerConfig;
import com.playerapi.config.theme.ConfigTheme;
import com.playerapi.config.theme.Surface;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Poseidon config screen. The screen is defined declaratively by the annotations on
 * {@link FishingConfig} and rendered by PlayerAPI's built-in config library; this class is the thin
 * factory used by ModMenu and the {@code /poseidon} command, and it supplies Poseidon's deep-ocean
 * theme. Persistence stays with {@link FishingConfig}. Chat triggers are a dynamic add/remove list
 * ({@code @ConfigList}); alarm sounds are collapsible accordions.
 */
public final class PoseidonConfigScreen {

    private PoseidonConfigScreen() {}

    public static Screen create(Screen parent) {
        FishingConfig cfg = FishingConfig.getInstance();
        return PlayerConfig.createScreen("Poseidon", cfg, cfg::save, parent, theme());
    }

    /** Poseidon's look — deep-ocean blue panels + teal/aqua accents. Textures bundled in PlayerAPI. */
    private static ConfigTheme theme() {
        ConfigTheme t = new ConfigTheme();
        t.background       = Surface.stretch(tex("background"), 512); // underwater art, opaque
        t.sidebar          = null; // translucent sidebar colour lets the art glow through
        t.selection        = Surface.nineSlice(tex("selection"), 16, 5);
        t.inputBox         = Surface.nineSlice(tex("input"), 16, 5);
        t.accordionHeader  = Surface.nineSlice(tex("accordion"), 16, 5);
        t.toggleOnTex      = Surface.nineSlice(tex("toggle_on"), 16, 7);
        t.toggleOffTex     = Surface.nineSlice(tex("toggle_off"), 16, 7);
        t.toggleKnobTex    = Surface.stretch(tex("toggle_knob"), 16);
        t.buttonTex        = Surface.nineSlice(tex("button"), 16, 5);
        t.buttonHoverTex   = Surface.nineSlice(tex("button_hover"), 16, 5);

        t.screenBg    = 0xFF071020;
        t.sidebarBg   = 0x8C0A1526;   // translucent over the art
        t.contentScrim = 0xA6071020;  // keeps light text readable over the bright background
        t.textShadow   = true;
        t.divider     = 0xFF1E3A54;
        t.catSelected = 0xFFFFFFFF;
        t.rowLine     = 0x2255C8E0;
        t.labelFg     = 0xFFE6F2F8;
        t.descFg      = 0xFF89A6BC;
        t.catFg       = 0xFFCBE2EE;
        t.subFg       = 0xFFA2C0D2;
        t.chevronFg   = 0xFF55C0E0;
        t.accFg       = 0xFFDCEEF6;
        t.widgetFg    = 0xFFFFFFFF;
        t.boxBg       = 0xFF06101F;
        t.boxFocus    = 0xFF0E2135;
        t.border      = 0xFF3FA6D8;
        t.enumArrow   = 0xFF2A6E92;
        t.enumBox     = 0xFF0E1E30;
        t.sliderTrack = 0xFF1E3A54;
        t.sliderFill  = 0xFF2E9AC8;
        t.sliderKnob  = 0xFFDCEEF6;
        t.buttonBg    = 0xFF2A7EA6;
        t.buttonHover = 0xFF3FA6D8;
        t.buttonFg    = 0xFFFFFFFF;
        t.toggleOn    = 0xFF2E9AC8;
        t.toggleOff   = 0xFF37485C;
        t.toggleKnob  = 0xFFE6F2F8;
        return t;
    }

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("playerapi", "textures/config/poseidon/" + name + ".png");
    }
}
