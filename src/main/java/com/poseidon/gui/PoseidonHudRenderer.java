package com.poseidon.gui;

import com.poseidon.core.AbilityManager;
import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingManager;
import com.poseidon.core.FishingState;
import com.poseidon.core.PoseidonLogger;
import com.poseidon.core.RebootAlertManager;
import com.playerapi.config.theme.ConfigStyle;
import com.playerapi.config.theme.Surface;
import com.playerapi.config.theme.ThemeRenderer;
import com.playerapi.hud.HudElement;
import com.playerapi.hud.HudManager;
import com.playerapi.hud.HudTransform;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;

public class PoseidonHudRenderer {

    /** Owner namespace for the shared HUD editor. */
    public static final String HUD_OWNER = "poseidon";

    private static final int BG         = 0xC0000000;
    private static final int ACCENT     = 3;
    private static final int HEADER     = 13;
    private static final int PAD        = 5;
    private static final int LINE       = 11;
    private static final int LX_OFF     = 7;
    private static final int VX_OFF     = 60;
    // Panel is drawn from local origin (0,0); its screen position + scale come from the HudTransform.
    private static final int PX         = 0;
    private static final int PY         = 0;
    private static final int PW         = 200;
    private static final int LOG_PW     = 300;
    private static final int LABEL_COL  = 0xFF666666;
    private static final int VALUE_COL  = 0xFFCCCCCC;
    private static final int LOG_BG     = 0x90000000;

    private static boolean hudVisible = false;
    /** Last computed main-panel height, cached for the editor's outline/hit-box. */
    private static int lastPanelHeight = HEADER + 1 + PAD + 3 * LINE + PAD;
    /** Last computed log-panel height, cached for the editor's outline/hit-box. */
    private static int lastLogHeight = HEADER + 1 + 5 * 9 + 4;
    /** Max log lines shown in the panel. */
    private static final int MAX_LOG = 5;

    // ── HUD style (Custom / Toned Down / Flat) — a familiar dark HUD with an ocean identity ─────
    /** Dark-navy panel with a cyan frame (Custom) + a subtle transparent variant (Toned Down). */
    private static final Surface PANEL       = Surface.nineSlice(tex("hud_panel"), 64, 11);
    private static final Surface PANEL_TONED = Surface.nineSlice(tex("hud_panel_toned"), 64, 11);
    private static final Surface HEADER_BAND = Surface.nineSlice(tex("hud_header"), 32, 8);
    private static final Identifier EMBLEM   = tex("emblem_trident");
    private static final int EMBLEM_SZ = 11, EMBLEM_TEX = 16;

    private static Identifier tex(String name) {
        return Identifier.fromNamespaceAndPath("playerapi", "textures/config/poseidon/" + name + ".png");
    }

    private static ConfigStyle style()   { return FishingConfig.getInstance().getHudStyle(); }
    private static boolean isCustom()     { return style() == ConfigStyle.CUSTOM; }
    private static boolean isTextured()   { return style() != ConfigStyle.FLAT; }

    /** Panel background in the current style: textured (Custom/Toned) or the classic colour fill. */
    private static void drawBg(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int flatColor) {
        switch (style()) {
            case CUSTOM     -> ThemeRenderer.surface(ctx, PANEL, flatColor, x, y, w, h);
            case TONED_DOWN -> ThemeRenderer.surface(ctx, PANEL_TONED, flatColor, x, y, w, h);
            case FLAT       -> fill(ctx, x, y, w, h, flatColor);
        }
    }

    /** Left accent stripe — Flat only. Textured styles show state as a header dot instead (cleaner). */
    private static void drawAccent(GuiGraphicsExtractor ctx, int x, int y, int h, int color) {
        if (isTextured()) return;
        fill(ctx, x, y, ACCENT, h, color);
    }

    /** Frame inset: content is pushed in by this many px on textured panels so text clears the frame bevel. */
    private static int fi() { return isTextured() ? 6 : 0; }

    /** Header separator (height {@code h}): a navy band + cyan underline (Custom) or the classic tint. */
    private static void drawHeader(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        int f = fi(), hx = x + ACCENT + f, hw = w - ACCENT * 2 - f * 2;
        if (isCustom()) {
            ThemeRenderer.surface(ctx, HEADER_BAND, 0x18FFFFFF, hx, y + f, hw, h - f);
            fill(ctx, hx, y + h, hw, 1, 0x9955C8E0); // cyan underline
        } else {
            fill(ctx, x + ACCENT, y, w - ACCENT, HEADER, 0x18FFFFFF);
            fill(ctx, x + ACCENT, y + HEADER, w - ACCENT, 1, 0x30FFFFFF);
        }
    }

    // ── Style-aware text (textured = light-on-navy glass; Flat = classic) ────────
    private static boolean sh()          { return isTextured(); } // shadow for readability on the glass
    private static int  labelCol()       { return isTextured() ? 0xFFA9C4D6 : LABEL_COL; } // light steel
    private static int  titleCol()       { return isTextured() ? 0xFF8FDcF2 : 0xFFAAAAAA; } // cyan
    /** Neutral value colour brightens on the glass; status colours pass through. */
    private static int  mapVal(int c)    { return (isTextured() && c == VALUE_COL) ? 0xFFF1F7FB : c; }

    /**
     * Draws the state indicator top-right: on the glass a small colour dot + neutral text (a clean
     * badge, not a loud coloured word); on Flat the classic coloured word.
     */
    private static void drawState(GuiGraphicsExtractor ctx, Font tr, int rightX, int y, String label, int stateCol) {
        int tw = tr.width(label);
        if (isTextured()) {
            int padX = 4, h = tr.lineHeight + 1, w = tw + padX * 2, x = rightX - w, by = y - 1;
            fill(ctx, x + 1, by, w - 2, h, stateCol);          // pill body
            fill(ctx, x, by + 1, 1, h - 2, stateCol);          // fake-rounded left edge
            fill(ctx, x + w - 1, by + 1, 1, h - 2, stateCol);  // right edge
            ctx.text(tr, label, x + padX, y, 0xFFFFFFFF, false);
        } else {
            ctx.text(tr, label, rightX - tw, y, stateCol, false);
        }
    }

    /** The trident identity emblem, drawn at {@code (x,y)} (caller places it at the header's left). */
    private static void drawEmblem(GuiGraphicsExtractor ctx, int x, int y) {
        ctx.blit(RenderPipelines.GUI_TEXTURED, EMBLEM, x, y,
                0f, 0f, EMBLEM_SZ, EMBLEM_SZ, EMBLEM_TEX, EMBLEM_TEX, EMBLEM_TEX, EMBLEM_TEX);
    }

    private PoseidonHudRenderer() {}

    public static boolean isHudVisible() { return hudVisible; }
    public static void setHudVisible(boolean v) { hudVisible = v; }

    /**
     * Registers the HUD panel as a movable/scalable element with the shared editor. Call once at
     * mod init (after config load). The element's transform is backed by {@link FishingConfig}, and
     * closing the editor persists it.
     */
    public static void register() {
        FishingConfig cfg = FishingConfig.getInstance();
        HudTransform transform = new HudTransform(cfg.getHudX(), cfg.getHudY(), cfg.getHudScale());
        HudManager.register(HUD_OWNER, PANEL_ELEMENT, transform);

        HudTransform logTransform = new HudTransform(cfg.getLogHudX(), cfg.getLogHudY(), cfg.getLogHudScale());
        HudManager.register(HUD_OWNER, LOG_ELEMENT, logTransform);

        HudManager.onSave(HUD_OWNER, () -> {
            HudTransform t = HudManager.transformOf(HUD_OWNER, PANEL_ELEMENT.id());
            if (t != null) cfg.setHudLayout(t.getX(), t.getY(), t.getScale());
            HudTransform lt = HudManager.transformOf(HUD_OWNER, LOG_ELEMENT.id());
            if (lt != null) cfg.setLogLayout(lt.getX(), lt.getY(), lt.getScale());
        });
    }

    /** Opens the shared HUD editor scoped to Poseidon's HUD. */
    public static void openEditor() {
        HudManager.openEditor(HUD_OWNER);
    }

    /** The Poseidon HUD panel as a single editor element (main panel + trailing log, coupled for now). */
    private static final HudElement PANEL_ELEMENT = new HudElement() {
        @Override public String id() { return "hud"; }
        @Override public String displayName() { return "Poseidon HUD"; }
        @Override public boolean isEnabled() { return true; } // always editable; live draw is gated by hudVisible
        @Override public int width()  { return PW; }
        @Override public int height() { return lastPanelHeight; }
        @Override public void render(GuiGraphicsExtractor ctx, boolean preview) { drawPanel(ctx); }
        @Override public void resetTransform(HudTransform t) { t.moveTo(4f, 4f); t.setScale(1f); } // default: top-left
    };

    /** The log panel as its own movable element — hidden when the config toggle is off. */
    private static final HudElement LOG_ELEMENT = new HudElement() {
        @Override public String id() { return "log"; }
        @Override public String displayName() { return "Poseidon Log"; }
        @Override public boolean isEnabled() { return FishingConfig.getInstance().isLogVisible(); }
        @Override public int width()  { return LOG_PW; }
        @Override public int height() { return lastLogHeight; }
        @Override public void render(GuiGraphicsExtractor ctx, boolean preview) { drawLog(ctx, preview); }
        @Override public void resetTransform(HudTransform t) { t.moveTo(4f, 140f); t.setScale(1f); }
    };

    /** HUD render callback (registered with HudElementRegistry). Live draw respects the toggle. */
    public static void render(GuiGraphicsExtractor ctx, DeltaTracker tick) {
        if (!hudVisible) return;
        if (Minecraft.getInstance().player == null) return;
        HudManager.render(HUD_OWNER, ctx);
    }

    /** Draws the panel from local origin (0,0). Safe when no player is present (editor from menu). */
    private static void drawPanel(GuiGraphicsExtractor ctx) {
        Minecraft client = Minecraft.getInstance();

        FishingManager mgr = FishingManager.getInstance();
        FishingState state  = mgr.getState();
        boolean active      = mgr.isActive();
        boolean hasBobber   = client.player != null && client.player.fishing != null;
        boolean rebootAlert = RebootAlertManager.getInstance().isAlertActive();

        int stateCol = switch (state) {
            case IDLE    -> active ? 0xFFFFAA00 : 0xFFEE4444;
            case WAITING -> 0xFF44EE44;
            case BITING  -> 0xFFFFFF44;
            case REELING -> 0xFF44AAFF;
        };

        FishingConfig cfg    = FishingConfig.getInstance();
        boolean trackSC      = cfg.isTrackSeaCreatures();
        int trackedCount     = mgr.getTrackedCount();
        String area          = mgr.getCurrentArea();
        int scCap            = FishingConfig.SEA_CREATURE_CAP;
        boolean showBait     = cfg.isBaitHudVisible();
        boolean showStats    = cfg.isFishingStatsHudVisible();
        boolean slugfishMode = cfg.isSlugfishMode();
        long    slugRemain   = mgr.getSlugfishRemainingTicks(); // Long.MIN_VALUE = mode off / no bobber

        // Stat lines: each shows only when its own toggle is on AND the stat appears in the tab list.
        // (Absent-from-tab hides it regardless of the toggle; toggle-off hides it regardless of presence.)
        String statSpeed    = mgr.getStatFishingSpeed();
        String statSCC      = mgr.getStatSeaCreatureChance();
        String statDHC      = mgr.getStatDoubleHookChance();
        String statTreasure = mgr.getStatTreasureChance();
        boolean showDHC      = showStats && cfg.isStatDhcHudVisible()      && !statDHC.isEmpty();
        boolean showSCC      = showStats && cfg.isStatSccHudVisible()      && !statSCC.isEmpty();
        boolean showSpeed    = showStats && cfg.isStatSpeedHudVisible()    && !statSpeed.isEmpty();
        boolean showTreasure = showStats && cfg.isStatTreasureHudVisible() && !statTreasure.isEmpty();
        int statRows = (showDHC ? 1 : 0) + (showSCC ? 1 : 0) + (showSpeed ? 1 : 0) + (showTreasure ? 1 : 0);

        AbilityManager abil = AbilityManager.getInstance();
        boolean showVeil  = abil.isEnabled(AbilityManager.Ability.FIRE_VEIL);
        boolean showTotem = abil.isEnabled(AbilityManager.Ability.TOTEM);
        int abilityRows   = (showVeil ? 1 : 0) + (showTotem ? 1 : 0);

        // Row count: core rows + optional rows
        int rows = 3                                                      // Active, State, Bobber
                + (rebootAlert ? 1 : 0)                                   // Reboot warning
                + (slugfishMode ? 1 : 0)                                  // Slugfish timer
                + (showBait ? 1 : 0)                                      // Bait
                + (trackSC ? (area.isBlank() ? 1 : 2) : 0)               // [Area,] SC
                + abilityRows                                             // Fire Veil / Totem
                + statRows;                                               // DHC/SCC/Speed/Treasure (individually)
        int fi = fi();
        int hdr = HEADER + fi;                              // taller header on textured (room below the frame)
        int ph = hdr + 1 + PAD + rows * LINE + PAD + fi;    // + bottom-frame room
        lastPanelHeight = ph; // cache for the editor's outline/hit-box

        // Accent stripe: flash red when reboot is imminent
        int accentCol = rebootAlert ? 0xFFFF4444 : stateCol;

        drawBg(ctx, PX, PY, PW, ph, BG);
        drawAccent(ctx, PX, PY, ph, accentCol);
        drawHeader(ctx, PX, PY, PW, hdr);

        Font tr = client.font;
        int hy = PY + fi + 3;
        int titleX = PX + LX_OFF + fi;
        if (isCustom()) {
            drawEmblem(ctx, PX + ACCENT + fi + 3, PY + fi + 1);
            titleX += EMBLEM_SZ + 3; // make room for the trident
        }
        ctx.text(tr, "Poseidon", titleX, hy, titleCol(), sh());
        String stateLabel = active ? state.name() : "OFF";
        drawState(ctx, tr, PX + PW - 6 - fi, hy, stateLabel, rebootAlert ? 0xFFFF5555 : stateCol);

        int y  = PY + hdr + 1 + PAD;
        int lx = PX + LX_OFF + fi;
        int vx = PX + VX_OFF + fi;

        // Reboot warning — shown first so it's impossible to miss
        if (rebootAlert) {
            kv(ctx, tr, lx, vx, y, "! Reboot", "SOON", 0xFFFF4444);
            y += LINE;
        }

        kv(ctx, tr, lx, vx, y, "Active", active ? "Yes" : "No",
                active ? 0xFF44EE44 : 0xFFEE4444);
        y += LINE;

        kv(ctx, tr, lx, vx, y, "State", state.name(), stateCol);
        y += LINE;

        String nearbyText  = mgr.getNearbyText();
        String bobberLabel = !hasBobber            ? "None"
                           : nearbyText.isBlank()  ? "Detected"
                           : nearbyText;
        int bobberCol = !hasBobber            ? 0xFF888888
                      : !nearbyText.isBlank() ? 0xFFFFCC00
                      :                        0xFF44EE44;
        kv(ctx, tr, lx, vx, y, "Bobber", bobberLabel, bobberCol);

        if (slugfishMode) {
            y += LINE;
            String slugVal;
            int    slugCol;
            if (slugRemain == Long.MIN_VALUE) {
                // Mode on but no bobber out yet
                slugVal = "--";
                slugCol = 0xFF888888;
            } else if (slugRemain <= 0) {
                // Timer elapsed — slugfish can bite
                slugVal = "READY";
                slugCol = 0xFF44EE44;
            } else {
                // Counting down — show whole seconds remaining (ceil)
                int secs = (int)((slugRemain + 19) / 20);
                slugVal = secs + "s";
                slugCol = 0xFFFFAA00;
            }
            kv(ctx, tr, lx, vx, y, "Slug", slugVal, slugCol);
        }

        if (showBait) {
            y += LINE;
            String baitName = mgr.getBaitName();
            boolean hasBait = !baitName.isEmpty();
            String baitVal  = hasBait
                    ? (baitName.length() > 18 ? baitName.substring(0, 18) + ".." : baitName)
                      + " (" + mgr.getBaitCount() + ")"
                    : "No Bait";
            int baitCol = hasBait ? VALUE_COL : 0xFFEE4444;
            kv(ctx, tr, lx, vx, y, "Bait", baitVal, baitCol);
        }

        if (trackSC) {
            if (!area.isBlank()) {
                y += LINE;
                kv(ctx, tr, lx, vx, y, "Area", area, 0xFF888888);
            }
            y += LINE;
            boolean atCap = trackedCount >= scCap;
            int scCol = atCap ? 0xFFFF4444 : (trackedCount > 0 ? 0xFFFFAA00 : 0xFF888888);
            kv(ctx, tr, lx, vx, y, "SC", trackedCount + " / " + scCap, scCol);
        }

        if (showVeil) {
            y += LINE;
            abilityRow(ctx, tr, lx, vx, y, abil, AbilityManager.Ability.FIRE_VEIL);
        }
        if (showTotem) {
            y += LINE;
            abilityRow(ctx, tr, lx, vx, y, abil, AbilityManager.Ability.TOTEM);
        }

        if (showDHC)      { y += LINE; kv(ctx, tr, lx, vx, y, "DHC",      statDHC,      VALUE_COL); }
        if (showSCC)      { y += LINE; kv(ctx, tr, lx, vx, y, "SCC",      statSCC,      VALUE_COL); }
        if (showSpeed)    { y += LINE; kv(ctx, tr, lx, vx, y, "Speed",    statSpeed,    VALUE_COL); }
        if (showTreasure) { y += LINE; kv(ctx, tr, lx, vx, y, "Treasure", statTreasure, VALUE_COL); }
    }

    /** The log panel, drawn from local origin (0,0) — now its own movable element. */
    private static void drawLog(GuiGraphicsExtractor ctx, boolean preview) {
        Font tr = Minecraft.getInstance().font;
        List<String> logs = PoseidonLogger.getInstance().getRecentLines();

        // In the editor with no history, show sample lines so the element stays visible/positionable.
        List<String> sample = (preview && logs.isEmpty())
                ? List.of("[info] Cast", "[info] Bobber detected", "[recast] Recast sent")
                : null;
        List<String> src = sample != null ? sample : logs;
        if (src.isEmpty()) { lastLogHeight = 0; return; }

        int start = Math.max(0, src.size() - MAX_LOG);
        int shown = src.size() - start;
        int fi = fi();
        int hdr = HEADER + fi;
        int logH  = hdr + 1 + shown * 9 + 4 + fi;
        lastLogHeight = logH;

        drawBg(ctx, 0, 0, LOG_PW, logH, LOG_BG);
        drawAccent(ctx, 0, 0, logH, 0x88888888);
        drawHeader(ctx, 0, 0, LOG_PW, hdr);
        ctx.text(tr, "LOG", LX_OFF + fi + 9, fi + 3, isTextured() ? titleCol() : 0xFF555555, sh());

        int ly = hdr + 1 + 2;
        for (int i = start; i < src.size(); i++) {
            String line = src.get(i);
            int msgStart = line.indexOf("] ");
            if (msgStart >= 0) line = line.substring(msgStart + 2);
            if (line.length() > 50) line = line.substring(0, 50) + "…";
            ctx.text(tr, line, LX_OFF + fi, ly, isTextured() ? 0xFFB9CEDC : 0xFF999999, sh());
            ly += 9;
        }
    }

    private static void fill(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + h, color);
    }

    private static void kv(GuiGraphicsExtractor ctx, Font tr,
                            int lx, int vx, int y,
                            String label, String value, int valueCol) {
        ctx.text(tr, label, lx, y, labelCol(), sh());
        ctx.text(tr, value, vx, y, mapVal(valueCol), sh());
    }

    /** Renders one ability status row: green "ON Ns" while active, else cooldown seconds or "Ready". */
    private static void abilityRow(GuiGraphicsExtractor ctx, Font tr, int lx, int vx, int y,
                                   AbilityManager abil, AbilityManager.Ability a) {
        int activeLeft = abil.activeSecondsLeft(a);
        String val;
        int col;
        if (activeLeft > 0) {
            val = "ON " + activeLeft + "s";
            col = 0xFF44EE44;
        } else {
            int cd = abil.cooldownSecondsLeft(a);
            if (cd > 0) { val = cd + "s"; col = 0xFFFFAA00; }
            else        { val = "Ready";  col = 0xFF888888; }
        }
        kv(ctx, tr, lx, vx, y, a.label, val, col);
    }
}
