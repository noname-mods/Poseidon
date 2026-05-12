package com.poseidon.gui;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingManager;
import com.poseidon.core.FishingState;
import com.poseidon.core.PoseidonLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

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

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        if (!hudVisible) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        FishingManager mgr = FishingManager.getInstance();
        FishingState state  = mgr.getState();
        boolean active      = mgr.isActive();
        boolean hasBobber   = client.player.fishHook != null;

        int stateCol = switch (state) {
            case IDLE    -> active ? 0xFFFFAA00 : 0xFFEE4444;
            case WAITING -> 0xFF44EE44;
            case BITING  -> 0xFFFFFF44;
            case REELING -> 0xFF44AAFF;
        };

        FishingConfig cfg    = FishingConfig.getInstance();
        boolean trackSC     = cfg.isTrackSeaCreatures();
        int trackedCount    = mgr.getTrackedCount();
        String area         = mgr.getCurrentArea();
        int scCap           = cfg.getCapForArea(area);

        // Rows: Active, State, Bobber, [Area], [SC count]
        int rows = 3 + (trackSC ? (area.isBlank() ? 1 : 2) : 0);
        int ph = HEADER + 1 + PAD + rows * LINE + PAD;

        fill(ctx, PX, PY, PW, ph, BG);
        fill(ctx, PX, PY, ACCENT, ph, stateCol);
        fill(ctx, PX + ACCENT, PY, PW - ACCENT, HEADER, 0x18FFFFFF);
        fill(ctx, PX + ACCENT, PY + HEADER, PW - ACCENT, 1, 0x30FFFFFF);

        TextRenderer tr = client.textRenderer;
        ctx.drawText(tr, "Poseidon", PX + LX_OFF, PY + 3, 0xFFAAAAAA, false);
        String stateLabel = active ? state.name() : "OFF";
        ctx.drawText(tr, stateLabel, PX + PW - tr.getWidth(stateLabel) - 5, PY + 3, stateCol, false);

        int y  = PY + HEADER + 1 + PAD;
        int lx = PX + LX_OFF;
        int vx = PX + VX_OFF;

        kv(ctx, tr, lx, vx, y, "Active", active ? "Yes" : "No",
                active ? 0xFF44EE44 : 0xFFEE4444);
        y += LINE;

        kv(ctx, tr, lx, vx, y, "State", state.name(), stateCol);
        y += LINE;

        String nearbyText  = mgr.getNearbyText();
        String bobberLabel = !hasBobber            ? "None"
                           : nearbyText.isBlank()  ? "Detected"
                           : nearbyText;
        int bobberCol = !hasBobber            ? 0xFF888888   // no bobber — grey
                      : !nearbyText.isBlank() ? 0xFFFFCC00   // countdown visible — yellow
                      :                        0xFF44EE44;  // bobber only — green
        kv(ctx, tr, lx, vx, y, "Bobber", bobberLabel, bobberCol);

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
            ctx.drawText(tr, "LOG", PX + LX_OFF, logY + 3, 0xFF555555, false);

            int ly = logY + HEADER + 1 + 2;
            for (int i = start; i < logs.size(); i++) {
                String line = logs.get(i);
                int msgStart = line.indexOf("] ");
                if (msgStart >= 0) line = line.substring(msgStart + 2);
                if (line.length() > 50) line = line.substring(0, 50) + "…";
                ctx.drawText(tr, line, PX + LX_OFF, ly, 0xFF999999, false);
                ly += 9;
            }
        }
    }

    private static void fill(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + h, color);
    }

    private static void kv(DrawContext ctx, TextRenderer tr,
                            int lx, int vx, int y,
                            String label, String value, int valueCol) {
        ctx.drawText(tr, label, lx, y, LABEL_COL, false);
        ctx.drawText(tr, value, vx, y, valueCol, false);
    }
}
