package com.poseidon.gui;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingManager;
import com.poseidon.core.FishingState;
import com.poseidon.core.PoseidonLogger;
import com.poseidon.core.RebootAlertManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

public class PoseidonHudRenderer {

    private static final int BG         = 0xC0000000;
    private static final int ACCENT     = 3;
    private static final int HEADER     = 13;
    private static final int PAD        = 5;
    private static final int LINE       = 11;
    private static final int LX_OFF     = 7;
    private static final int VX_OFF     = 60;
    private static final int PX         = 4;
    private static final int PY         = 4;
    private static final int PW         = 200;
    private static final int LOG_PW     = 300;
    private static final int LABEL_COL  = 0xFF666666;
    private static final int VALUE_COL  = 0xFFCCCCCC;
    private static final int LOG_BG     = 0x90000000;

    private static boolean hudVisible = false;

    private PoseidonHudRenderer() {}

    public static boolean isHudVisible() { return hudVisible; }
    public static void setHudVisible(boolean v) { hudVisible = v; }

    public static void render(GuiGraphicsExtractor ctx, DeltaTracker tick) {
        if (!hudVisible) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        FishingManager mgr = FishingManager.getInstance();
        FishingState state  = mgr.getState();
        boolean active      = mgr.isActive();
        boolean hasBobber   = client.player.fishing != null;
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

        // Collect stats — only show section if at least one value is known
        String statSpeed    = mgr.getStatFishingSpeed();
        String statSCC      = mgr.getStatSeaCreatureChance();
        String statDHC      = mgr.getStatDoubleHookChance();
        String statTreasure = mgr.getStatTreasureChance();
        boolean hasAnyStats = showStats &&
                (!statSpeed.isEmpty() || !statSCC.isEmpty() || !statDHC.isEmpty() || !statTreasure.isEmpty());

        // Row count: core rows + optional rows
        int rows = 3                                                      // Active, State, Bobber
                + (rebootAlert ? 1 : 0)                                   // Reboot warning
                + (slugfishMode ? 1 : 0)                                  // Slugfish timer
                + (showBait ? 1 : 0)                                      // Bait
                + (trackSC ? (area.isBlank() ? 1 : 2) : 0)               // [Area,] SC
                + (hasAnyStats ? 4 : 0);                                  // DHC, SCC, Speed, Treasure
        int ph = HEADER + 1 + PAD + rows * LINE + PAD;

        // Accent stripe: flash red when reboot is imminent
        int accentCol = rebootAlert ? 0xFFFF4444 : stateCol;

        fill(ctx, PX, PY, PW, ph, BG);
        fill(ctx, PX, PY, ACCENT, ph, accentCol);
        fill(ctx, PX + ACCENT, PY, PW - ACCENT, HEADER, 0x18FFFFFF);
        fill(ctx, PX + ACCENT, PY + HEADER, PW - ACCENT, 1, 0x30FFFFFF);

        Font tr = client.font;
        ctx.text(tr, "Poseidon", PX + LX_OFF, PY + 3, 0xFFAAAAAA, false);
        String stateLabel = active ? state.name() : "OFF";
        ctx.text(tr, stateLabel, PX + PW - tr.width(stateLabel) - 5, PY + 3, stateCol, false);

        int y  = PY + HEADER + 1 + PAD;
        int lx = PX + LX_OFF;
        int vx = PX + VX_OFF;

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

        if (hasAnyStats) {
            y += LINE;
            kv(ctx, tr, lx, vx, y, "DHC",      statDHC.isEmpty()      ? "--" : statDHC,      VALUE_COL);
            y += LINE;
            kv(ctx, tr, lx, vx, y, "SCC",      statSCC.isEmpty()      ? "--" : statSCC,      VALUE_COL);
            y += LINE;
            kv(ctx, tr, lx, vx, y, "Speed",    statSpeed.isEmpty()    ? "--" : statSpeed,    VALUE_COL);
            y += LINE;
            kv(ctx, tr, lx, vx, y, "Treasure", statTreasure.isEmpty() ? "--" : statTreasure, VALUE_COL);
        }

        // Log panel
        List<String> logs = PoseidonLogger.getInstance().getRecentLines();
        if (!logs.isEmpty()) {
            int maxLog   = 5;
            int start    = Math.max(0, logs.size() - maxLog);
            int shown    = logs.size() - start;
            int logH     = HEADER + 1 + shown * 9 + 4;
            int logY     = PY + ph + 3;

            fill(ctx, PX, logY, LOG_PW, logH, LOG_BG);
            fill(ctx, PX, logY, ACCENT, logH, 0x88888888);
            fill(ctx, PX + ACCENT, logY, LOG_PW - ACCENT, HEADER, 0x10FFFFFF);
            fill(ctx, PX + ACCENT, logY + HEADER, LOG_PW - ACCENT, 1, 0x20FFFFFF);
            ctx.text(tr, "LOG", PX + LX_OFF, logY + 3, 0xFF555555, false);

            int ly = logY + HEADER + 1 + 2;
            for (int i = start; i < logs.size(); i++) {
                String line = logs.get(i);
                int msgStart = line.indexOf("] ");
                if (msgStart >= 0) line = line.substring(msgStart + 2);
                if (line.length() > 50) line = line.substring(0, 50) + "…";
                ctx.text(tr, line, PX + LX_OFF, ly, 0xFF999999, false);
                ly += 9;
            }
        }
    }

    private static void fill(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + h, color);
    }

    private static void kv(GuiGraphicsExtractor ctx, Font tr,
                            int lx, int vx, int y,
                            String label, String value, int valueCol) {
        ctx.text(tr, label, lx, y, LABEL_COL, false);
        ctx.text(tr, value, vx, y, valueCol, false);
    }
}
