package com.poseidon.core;

import com.playerapi.MovementActions;
import com.playerapi.Scheduler;
import com.playerapi.TabListInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Core state machine for Poseidon.
 *
 * Bite detection: waits for an entity bearing the red "!!!" signal to appear
 * near the bobber (the server places this entity during the catch window).
 *
 * Sea creature tracking: after each reel-in, scans for entities bearing the
 * anchor ⚓ symbol (Hypixel Skyblock sea creature name plates). Tracked
 * creatures are stored in a list; dead / despawned creatures are removed
 * every {@value #CLEANUP_PERIOD_TICKS} ticks. When the list reaches the
 * configured cap, a sound alert fires.
 *
 * All world access runs on the main MC thread (called from PoseidonMod.onTick),
 * so no threading concerns arise. The cleanup is deliberately throttled to
 * every 2 seconds to avoid per-tick entity scanning overhead.
 */
public class FishingManager {

    private static final FishingManager INSTANCE = new FishingManager();

    // ── Timing constants ──────────────────────────────────────────────────────
    /** Ticks after reel-in before scanning for new sea creatures. */
    private static final int SCAN_DELAY_TICKS     = 5;
    /** How often (in ticks) to remove creatures that have disappeared. */
    private static final int CLEANUP_PERIOD_TICKS = 40;
    /** How often (in ticks) to refresh the current area from the tab list. */
    private static final int AREA_REFRESH_TICKS   = 40;

    // ── Fishing state ─────────────────────────────────────────────────────────
    private boolean active = false;
    private FishingState state = FishingState.IDLE;

    // Saved bobber position at the moment !!! is detected (bobber may vanish by reel time).
    private double lastBobberX, lastBobberY, lastBobberZ;

    // ── Sea creature tracking ─────────────────────────────────────────────────
    private final List<TrackedSeaCreature> tracked = new ArrayList<>();
    private boolean capAlertFired = false;
    private long lastCleanupTick = 0;

    // ── Area tracking ─────────────────────────────────────────────────────────
    /** Current island as read from the Hypixel tab list "Area:" line. Empty if unknown. */
    private String currentArea = "";
    private long lastAreaRefreshTick = 0;

    // ── Recast flags — set by notifyTriggerFired(), cleared after each reel ──
    private boolean pendingSuppressRecast = false;
    private boolean pendingStopBot        = false;

    // ── Countdown display ─────────────────────────────────────────────────────
    /** Text from any entity near the bobber that isn't the !!! signal (e.g. yellow countdown). */
    private String nearbyText = "";

    // ── Hook-stuck detection ───────────────────────────────────────────────────
    /**
     * Ticks since the current bobber was first detected. Used to give the
     * bobber time to settle before checking for horizontal drift.
     */
    private int    bobberSettleTicks = 0;
    /** XZ anchor position recorded after the settle period ends. */
    private double initialBobberX   = 0;
    private double initialBobberZ   = 0;
    /** True once the stuck alert has fired for the current cast (prevents spam). */
    private boolean hookStuckFired  = false;
    /** Ticks to let the bobber settle before drift-checking begins (1 second). */
    private static final int BOBBER_SETTLE_TICKS = 20;

    private static class TrackedSeaCreature {
        final int    entityId;
        final String name;
        /** Tick at which this creature was first detected, for despawn-warning age checks. */
        final long   spawnTick;
        /** True once the approaching-despawn alert has fired for this creature. */
        boolean      despawnAlertFired = false;

        TrackedSeaCreature(int entityId, String name, long spawnTick) {
            this.entityId  = entityId;
            this.name      = name;
            this.spawnTick = spawnTick;
        }
    }

    private FishingManager() {}

    public static FishingManager getInstance() { return INSTANCE; }

    // ── Called every tick by PoseidonMod ──────────────────────────────────────

    public void tick() {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        boolean hasBobber = mc.player.fishHook != null;

        switch (state) {

            case IDLE -> {
                nearbyText = "";
                if (hasBobber) {
                    state            = FishingState.WAITING;
                    bobberSettleTicks = 0;
                    hookStuckFired   = false;
                    PoseidonLogger.getInstance().logInfo("Bobber detected — watching for !!!");
                }
            }

            case WAITING -> {
                if (!hasBobber) {
                    nearbyText = "";
                    state = FishingState.IDLE;
                    PoseidonLogger.getInstance().logInfo("Bobber lost while waiting");
                    return;
                }
                if (detectBite(mc)) {
                    nearbyText = "";
                    // Record position now — bobber will be gone by the time reel-in fires
                    lastBobberX = mc.player.fishHook.getX();
                    lastBobberY = mc.player.fishHook.getY();
                    lastBobberZ = mc.player.fishHook.getZ();
                    state = FishingState.BITING;
                    playBiteAlert();
                    scheduleReelIn();
                } else {
                    // Scan for countdown/timer entities near the bobber and show their text
                    nearbyText = scanNearbyText(mc);
                    // Check if the bobber has drifted too far (attached to a mob)
                    checkHookStuck(mc);
                }
            }

            case BITING, REELING -> {
                if (!hasBobber) {
                    nearbyText = "";
                    FishingState prev = state;
                    state = FishingState.IDLE;
                    PoseidonLogger.getInstance().logInfo("Bobber lost during " + prev.name());
                }
            }
        }

        long currentTick = Scheduler.getCurrentTick();

        // Throttled area refresh — reads "Area:" line from the tab list.
        if (currentTick - lastAreaRefreshTick >= AREA_REFRESH_TICKS) {
            lastAreaRefreshTick = currentTick;
            refreshCurrentArea();
        }

        // Throttled sea creature cleanup — runs every CLEANUP_PERIOD_TICKS ticks.
        if (FishingConfig.getInstance().isTrackSeaCreatures()
                && currentTick - lastCleanupTick >= CLEANUP_PERIOD_TICKS) {
            lastCleanupTick = currentTick;
            cleanupDeadCreatures(mc);
        }
    }

    // ── Bite detection ────────────────────────────────────────────────────────

    /**
     * Returns true when the "reel now" signal (red "!!!") is visible on an
     * entity near the bobber. Ignores the yellow countdown text that appears
     * earlier in the fishing process.
     */
    private boolean detectBite(MinecraftClient mc) {
        double bx = mc.player.fishHook.getX();
        double by = mc.player.fishHook.getY();
        double bz = mc.player.fishHook.getZ();
        double r  = FishingConfig.getInstance().getDetectionRadius();

        Box searchBox = new Box(bx - r, by - 1, bz - r, bx + r, by + r + 2, bz + r);

        for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox)) {
            if (hasReelNowSignal(entity)) {
                PoseidonLogger.getInstance().logInfo(
                        "!!! signal on " + entity.getType().getName().getString()
                        + " at " + String.format("%.1f / %.1f / %.1f",
                                entity.getX(), entity.getY(), entity.getZ()));
                return true;
            }
        }
        return false;
    }

    private boolean hasReelNowSignal(Entity entity) {
        Text name = entity.getCustomName();
        if (name != null && name.getString().contains("!!!")) return true;

        if (entity instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity td) {
            Text text = td.getText();
            if (text != null && text.getString().contains("!!!")) return true;
        }
        return false;
    }

    // ── Reel in ───────────────────────────────────────────────────────────────

    private void scheduleReelIn() {
        FishingConfig cfg = FishingConfig.getInstance();
        int minMs   = cfg.getReactionDelayMinMs();
        int maxMs   = cfg.getReactionDelayMaxMs();
        int delayMs = minMs + (int)(Math.random() * Math.max(1, maxMs - minMs));

        PoseidonLogger.getInstance().logInfo("Reeling in after " + delayMs + "ms");

        // Capture bobber position for creature scan (lastBobber* set in tick())
        final double bx = lastBobberX, by = lastBobberY, bz = lastBobberZ;

        Scheduler.scheduleMs(delayMs, () -> {
            if (state == FishingState.BITING) {
                state = FishingState.REELING;
                MovementActions.tapKey("use", 100);
                PoseidonLogger.getInstance().logInfo("Reel in sent");

                // Creature scan: bobber may vanish within a tick, so anchor scan to
                // SCAN_DELAY_TICKS after the reel (not after the 10-tick state reset).
                if (cfg.isTrackSeaCreatures()) {
                    Scheduler.schedule(SCAN_DELAY_TICKS, () -> scanForNewCreatures(bx, by, bz));
                }

                // Reset recast flags immediately — don't wait for the 10-tick bobber-check.
                // If we wait, the tick handler may change state to IDLE first (bobber despawns
                // before 10 ticks), causing the old inner callback guard to skip everything.
                pendingSuppressRecast = false;
                pendingStopBot        = false;
                int decisionTicks = cfg.getRecastDecisionTicks();
                PoseidonLogger.getInstance().logInfo(
                        "[recast] waiting " + decisionTicks + " ticks for triggers");

                Scheduler.schedule(decisionTicks, () -> {
                    PoseidonLogger.getInstance().logInfo(
                            "[recast] decision: active=" + active
                            + " stopBot=" + pendingStopBot
                            + " autoRecast=" + cfg.isAutoRecast()
                            + " suppress=" + pendingSuppressRecast
                            + " state=" + state);
                    if (!active) return;
                    if (pendingStopBot) {
                        setActive(false);
                        PoseidonLogger.getInstance().logInfo("Trigger stopped the bot.");
                        return;
                    }
                    if (!cfg.isAutoRecast() || pendingSuppressRecast) {
                        PoseidonLogger.getInstance().logInfo(
                                "Recast suppressed — waiting for manual cast.");
                        return;
                    }
                    scheduleRecast(cfg);
                });

                // Still need to reset state after the reel-in window
                Scheduler.schedule(10, () -> {
                    if (state == FishingState.REELING) state = FishingState.IDLE;
                });
            }
        });
    }

    private void playBiteAlert() {
        FishingConfig.getInstance().getBiteAlertSound().play();
    }

    /**
     * Called by PoseidonMod when a chat trigger fires during this catch.
     * Flags are checked after the recast decision window expires.
     *
     * @param dontRecast suppress auto-recast for this catch
     * @param stopBot    deactivate the bot entirely after this catch
     */
    public void notifyTriggerFired(boolean dontRecast, boolean stopBot) {
        if (dontRecast) pendingSuppressRecast = true;
        if (stopBot)    pendingStopBot        = true;
    }

    private void scheduleRecast(FishingConfig cfg) {
        int min   = cfg.getRecastDelayMinMs();
        int max   = cfg.getRecastDelayMaxMs();
        int delay = min + (int)(Math.random() * Math.max(1, max - min));
        PoseidonLogger.getInstance().logInfo("[recast] scheduling recast in " + delay + "ms");
        Scheduler.scheduleMs(delay, () -> {
            PoseidonLogger.getInstance().logInfo(
                    "[recast] fire: active=" + active + " state=" + state);
            // Allow REELING: the 10-tick state reset may not have fired yet when the
            // recast delay is very short. WAITING means a bobber already exists (e.g.
            // server auto-recast), so skip to avoid double-casting.
            if (!active || state == FishingState.WAITING || state == FishingState.BITING) {
                PoseidonLogger.getInstance().logInfo(
                        "[recast] skipped (active=" + active + " state=" + state + ")");
                return;
            }
            MovementActions.tapKey("use", 100);
            PoseidonLogger.getInstance().logInfo("Recast sent");
        });
    }

    // ── Sea creature tracking ─────────────────────────────────────────────────

    /**
     * Scans entities near the given position for untracked sea creature name
     * plates (identified by the ⚓ anchor character used by Hypixel Skyblock).
     * Any new entities found are added to the tracked list.
     */
    private void scanForNewCreatures(double bx, double by, double bz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        FishingConfig cfg = FishingConfig.getInstance();
        double r = cfg.getCreatureScanRadius();
        Box searchBox = new Box(bx - r, by - 2, bz - r, bx + r, by + r + 6, bz + r);

        // Build a fast lookup of already-tracked IDs
        java.util.Set<Integer> knownIds = new java.util.HashSet<>();
        for (TrackedSeaCreature t : tracked) knownIds.add(t.entityId);

        int newFound = 0;
        for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox)) {
            if (!knownIds.contains(entity.getId()) && isSeaCreatureDisplay(entity)) {
                String name = extractCreatureName(entity);
                tracked.add(new TrackedSeaCreature(entity.getId(), name, Scheduler.getCurrentTick()));
                knownIds.add(entity.getId());
                newFound++;
                PoseidonLogger.getInstance().logInfo(
                        "Tracking: " + name + " (id=" + entity.getId() + ") — total: " + tracked.size());
            }
        }

        if (newFound > 0) checkCapAlert();
    }

    /**
     * Returns true if this entity is a Hypixel Skyblock sea creature name plate.
     * These entities display the ⚓ anchor character (U+2693) followed by the
     * creature's name and health bar.
     */
    private boolean isSeaCreatureDisplay(Entity entity) {
        Text name = entity.getCustomName();
        if (name != null && name.getString().contains("\u2693")) return true;

        if (entity instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity td) {
            Text text = td.getText();
            if (text != null && text.getString().contains("\u2693")) return true;
        }
        return false;
    }

    /**
     * Best-effort extraction of the creature name from the display text.
     * The full format is "[LvN] ⚓ CreatureName HP/MaxHP❤".
     * Returns the full string if parsing fails.
     */
    private String extractCreatureName(Entity entity) {
        String raw = "";
        Text name = entity.getCustomName();
        if (name != null) {
            raw = name.getString();
        } else if (entity instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity td
                   && td.getText() != null) {
            raw = td.getText().getString();
        }

        // Strip the ⚓ anchor and everything after the first number following the name
        int anchor = raw.indexOf('\u2693');
        if (anchor >= 0 && anchor + 2 < raw.length()) {
            String afterAnchor = raw.substring(anchor + 2).trim();
            // Take up to the first digit (start of HP) or end of string
            int hpStart = afterAnchor.length();
            for (int i = 0; i < afterAnchor.length(); i++) {
                if (Character.isDigit(afterAnchor.charAt(i))) { hpStart = i; break; }
            }
            String candidate = afterAnchor.substring(0, hpStart).trim();
            if (!candidate.isEmpty()) return candidate;
        }
        return raw.isBlank() ? "Unknown" : raw;
    }

    /**
     * Removes creatures whose entity no longer exists in the world.
     * Runs every {@value #CLEANUP_PERIOD_TICKS} ticks — deliberately throttled
     * to avoid per-tick entity lookups.
     */
    private void cleanupDeadCreatures(MinecraftClient mc) {
        if (tracked.isEmpty()) return;

        FishingConfig cfg = FishingConfig.getInstance();
        long now = Scheduler.getCurrentTick();

        // Despawn warnings — check before removing so we can still log the creature name
        if (cfg.isDespawnWarningEnabled()) {
            long warnTicks = cfg.getDespawnWarningTicks();
            for (TrackedSeaCreature t : tracked) {
                if (!t.despawnAlertFired && (now - t.spawnTick) >= warnTicks) {
                    t.despawnAlertFired = true;
                    cfg.getDespawnWarningSound().play();
                    PoseidonLogger.getInstance().logInfo(
                            "Despawn warning: " + t.name + " is approaching its despawn timer!");
                }
            }
        }

        int before = tracked.size();
        tracked.removeIf(t -> mc.world.getEntityById(t.entityId) == null);
        int removed = before - tracked.size();

        if (removed > 0) {
            PoseidonLogger.getInstance().logInfo(
                    "Removed " + removed + " despawned creature(s). Remaining: " + tracked.size());
            if (tracked.size() < cfg.getCapForArea(currentArea)) {
                capAlertFired = false;
            }
        }
    }

    private void checkCapAlert() {
        FishingConfig cfg = FishingConfig.getInstance();
        int cap = cfg.getCapForArea(currentArea);
        if (!capAlertFired && tracked.size() >= cap) {
            capAlertFired = true;
            cfg.getSeaCreatureCapSound().play();
            PoseidonLogger.getInstance().logInfo(
                    "Sea creature cap reached! " + tracked.size() + " / " + cap
                    + (currentArea.isBlank() ? "" : " (area: " + currentArea + ")"));
        }
    }

    // ── Countdown text ────────────────────────────────────────────────────────

    /**
     * Scans entities near the bobber for any text that isn't the !!! bite signal
     * or a sea creature name plate. Returns the first match (stripped of control
     * characters beyond MC colour codes), or empty string if nothing found.
     */
    private String scanNearbyText(MinecraftClient mc) {
        if (mc.player.fishHook == null) return "";
        double bx = mc.player.fishHook.getX();
        double by = mc.player.fishHook.getY();
        double bz = mc.player.fishHook.getZ();
        double r  = FishingConfig.getInstance().getDetectionRadius();
        Box box   = new Box(bx - r, by - 1, bz - r, bx + r, by + r + 2, bz + r);

        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            String t = extractEntityText(entity);
            if (t.isBlank() || t.contains("!!!") || t.contains("\u2693")) continue;
            // Truncate to keep HUD tidy
            return t.length() > 20 ? t.substring(0, 20) + "…" : t;
        }
        return "";
    }

    private String extractEntityText(Entity entity) {
        Text name = entity.getCustomName();
        if (name != null) return name.getString();
        if (entity instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity td) {
            Text text = td.getText();
            if (text != null) return text.getString();
        }
        return "";
    }

    // ── Hook-stuck detection ───────────────────────────────────────────────────

    /**
     * Called every tick while in WAITING state (no bite detected yet).
     *
     * After a 1-second settle period the XZ anchor position is recorded.
     * If the bobber subsequently drifts more than the configured threshold
     * (default 1.5 blocks) it has almost certainly been attached to a mob —
     * normal water bobbing is purely vertical and < 0.2 blocks horizontal.
     *
     * On detection: plays the alert sound, reels in, and schedules a recast
     * if auto-recast is enabled (no trigger decision window — nothing was caught).
     */
    private void checkHookStuck(MinecraftClient mc) {
        if (mc.player.fishHook == null || hookStuckFired) return;

        bobberSettleTicks++;

        // During the settle window, keep updating the anchor to the current position
        // so we measure drift *after* landing, not during the cast arc.
        if (bobberSettleTicks <= BOBBER_SETTLE_TICKS) {
            initialBobberX = mc.player.fishHook.getX();
            initialBobberZ = mc.player.fishHook.getZ();
            return;
        }

        FishingConfig cfg = FishingConfig.getInstance();
        if (!cfg.isHookStuckDetectionEnabled()) return;

        double dx   = mc.player.fishHook.getX() - initialBobberX;
        double dz   = mc.player.fishHook.getZ() - initialBobberZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist > cfg.getHookStuckMaxDistance()) {
            hookStuckFired = true;
            PoseidonLogger.getInstance().logWarn(
                    "Hook attached to mob — drifted " + String.format("%.1f", dist)
                    + " blocks. Reeling in.");
            cfg.getHookStuckSound().play();

            // Reel in immediately (no human delay — this isn't a bite reaction)
            state = FishingState.REELING;
            MovementActions.tapKey("use", 100);

            // Reset state and recast if configured — no trigger decision window
            // since the player caught nothing.
            Scheduler.schedule(10, () -> {
                if (state == FishingState.REELING) {
                    state = FishingState.IDLE;
                    if (cfg.isAutoRecast()) scheduleRecast(cfg);
                }
            });
        }
    }

    // ── Area tracking ─────────────────────────────────────────────────────────

    private void refreshCurrentArea() {
        String line = TabListInfo.findLineContaining("Area:");
        if (line != null) {
            int idx = line.indexOf("Area:");
            currentArea = line.substring(idx + 5).trim();
        }
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    public void setActive(boolean v) {
        active = v;
        if (!v) {
            state                 = FishingState.IDLE;
            pendingSuppressRecast = false;
            pendingStopBot        = false;
            PoseidonLogger.getInstance().logInfo("Poseidon disabled");
        } else {
            PoseidonLogger.getInstance().logInfo("Poseidon enabled — watching for bobber");
        }
    }

    public void toggle() { setActive(!active); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isActive()        { return active; }
    public FishingState getState()   { return state; }
    public int getTrackedCount()     { return tracked.size(); }
    /** Current island from the tab list. Empty string if not on a known server or tab list unavailable. */
    public String getCurrentArea()   { return currentArea; }
    /** Text from any non-bite entity near the bobber (e.g. yellow countdown). Empty when nothing visible. */
    public String getNearbyText()    { return nearbyText; }

    /** True when right-click should be blocked (prevents cancelling an active cast). */
    public boolean shouldBlockRightClick() {
        return active && (state == FishingState.WAITING || state == FishingState.BITING);
    }
}
