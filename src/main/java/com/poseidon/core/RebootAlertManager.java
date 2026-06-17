package com.poseidon.core;

import com.playerapi.SoundActions;

/**
 * Detects the Hypixel "scheduled reboot" server message and plays a persistent
 * alarm sound until the player changes area (indicating they warped away).
 *
 * Detection: a server (no-sender) chat message containing {@value TRIGGER_TEXT}.
 * Hypixel sends:  §c[Important] §eThis server will restart soon: §bScheduled Reboot
 * After stripping: [Important] This server will restart soon: Scheduled Reboot
 *
 * Dismissal: the alarm stops automatically when the tab-list area changes from
 * the area that was active when the reboot message arrived.
 *
 * Sound looping is driven by a tick counter inside {@link #tick()} rather than
 * the Scheduler, so it is immune to Scheduler.cancelAll() called when the bot stops.
 */
public class RebootAlertManager {

    private static final RebootAlertManager INSTANCE = new RebootAlertManager();

    private static final String TRIGGER_TEXT = "This server will restart soon";

    private boolean alertActive       = false;
    private String  areaWhenTriggered = "";
    /** Counts up each tick; sound plays when it reaches the configured interval. */
    private int ticksSinceLastPlay = Integer.MAX_VALUE; // ensures first play is immediate

    private RebootAlertManager() {}

    public static RebootAlertManager getInstance() { return INSTANCE; }

    // ── Called by PoseidonMod ─────────────────────────────────────────────────

    /**
     * Called from PlayerAPIEvents.CHAT_RECEIVED.
     * Server messages arrive with an empty sender; player messages have a name.
     */
    public void onChatReceived(String sender, String message) {
        if (alertActive) return;
        if (!FishingConfig.getInstance().isRebootAlertEnabled()) return;
        if (!sender.isEmpty()) return;                        // server messages only
        if (!message.contains(TRIGGER_TEXT)) return;

        areaWhenTriggered  = FishingManager.getInstance().getCurrentArea();
        alertActive        = true;
        ticksSinceLastPlay = Integer.MAX_VALUE;               // play on the very next tick
        PoseidonLogger.getInstance().logWarn(
                "Server reboot detected — alarm will play until you leave "
                + (areaWhenTriggered.isEmpty() ? "the current area." : areaWhenTriggered + "."));
    }

    /**
     * Called every tick from PoseidonMod.onTick().
     * Manages sound looping and watches for the player changing area.
     */
    public void tick() {
        if (!alertActive) return;

        // Allow the user to disable mid-alarm via the config screen.
        if (!FishingConfig.getInstance().isRebootAlertEnabled()) {
            cancelAlert();
            return;
        }

        // Stop once the player has warped to a different area.
        // Only act when the area is known (non-empty) — avoids false positives during
        // the brief window before the tab list populates after a warp.
        String area = FishingManager.getInstance().getCurrentArea();
        if (!area.isEmpty() && !area.equalsIgnoreCase(areaWhenTriggered)) {
            cancelAlert();
            return;
        }

        // Drive sound looping via a simple tick counter.
        FishingConfig.AlarmSound sound = FishingConfig.getInstance().getRebootAlertSound();
        int interval = Math.max(1, sound.intervalTicks);
        ticksSinceLastPlay++;
        if (ticksSinceLastPlay >= interval) {
            ticksSinceLastPlay = 0;
            SoundActions.playById(sound.soundId, (float) sound.volume, (float) sound.pitch);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void cancelAlert() {
        alertActive        = false;
        ticksSinceLastPlay = Integer.MAX_VALUE;
        PoseidonLogger.getInstance().logInfo("Reboot alarm dismissed — area changed.");
    }

    public boolean isAlertActive() { return alertActive; }
}
