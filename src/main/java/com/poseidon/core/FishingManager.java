package com.poseidon.core;

import com.playerapi.InventoryInfo;
import com.playerapi.MovementActions;
import com.playerapi.types.ItemSnapshot;
import com.playerapi.Scheduler;
import com.playerapi.TabListInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Core state machine for Poseidon.
 *
 * Bite detection: waits for an entity bearing the red "!!!" signal to appear
 * near the bobber (the server places this entity during the catch window).
 *
 * Sea creature tracking: after each reel-in, scans for entities bearing the
 * anchor ⚓ (water) or trident ♆ (lava) symbol (Hypixel Skyblock sea creature
 * name plates). Tracked
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
    /**
     * If the bot stays in IDLE this many ticks after a recast without detecting
     * a bobber, the watchdog fires and schedules a fresh recast.
     * Covers the case where the bobber appears briefly then vanishes (failed cast)
     * leaving the state machine with nothing to retry.
     */
    private static final int IDLE_TIMEOUT_TICKS   = 100;
    /** Slugfish normal-mode delay: 21 s (1 s safety margin over the 20 s requirement). */
    private static final int SLUGFISH_NORMAL_TICKS = 420;
    /** Slugfish Slug-Pet delay: 11 s (1 s safety margin over the 10 s halved requirement). */
    private static final int SLUGFISH_PET_TICKS    = 220;
    /** Minimum ticks the bot waits after a GUI closes before reacting to bites. */
    private static final int GUI_RESUME_MIN_TICKS  = 15;  // ~0.75 s
    /** Maximum ticks the bot waits after a GUI closes before reacting to bites. */
    private static final int GUI_RESUME_MAX_TICKS  = 50;  // ~2.5 s
    /**
     * How long after a reel-in the bot will process chat triggers (ticks).
     * Catch messages from Hypixel arrive within ~1–2 s; 200 ticks (10 s) gives
     * plenty of headroom while still blocking unrelated chat that arrives at
     * any other time.
     */
    private static final int CATCH_WINDOW_TICKS    = 200; // 10 s

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

    // ── Fishing stats (tab list) ──────────────────────────────────────────────
    private String statFishingSpeed      = "";
    private String statSeaCreatureChance = "";
    private String statDoubleHookChance  = "";
    private String statTreasureChance    = "";

    // ── Recast flags — set by notifyTriggerFired(), cleared after each reel ──
    private boolean pendingSuppressRecast = false;
    private boolean pendingStopBot        = false;
    /**
     * Tick at which the most recent recast tapKey was sent, or -1 when no recast
     * is pending. Used by the idle watchdog to detect a failed/dropped recast and
     * retry automatically.
     */
    private long lastRecastTick = -1;
    /**
     * Tick at which the bobber was first detected this cast (IDLE → WAITING).
     * Used by slugfish mode to measure elapsed time since the cast.
     * -1 when no bobber is out or the bot is inactive.
     */
    private long castTick = -1;
    /**
     * Tick at which the most recent reel-in tapKey was sent, or -1 when the bot
     * hasn't reeled in yet this session.  Used by {@link #isInCatchWindow()} to
     * gate chat-trigger processing so that only messages arriving shortly after
     * a reel-in are considered (blocks unrelated chat matching a trigger keyword).
     */
    private long lastReelTick = -1;

    // ── GUI-close lock ────────────────────────────────────────────────────────
    /** True while a GUI screen is open; used to detect the open→closed transition. */
    private boolean guiWasOpen = false;
    /**
     * Tick at which the GUI-close lock expires, or -1 when no lock is active.
     * After closing a GUI the bot waits a random [GUI_RESUME_MIN, GUI_RESUME_MAX]
     * tick window before reacting to bites — instant reactions look suspicious.
     */
    private long guiLockUntilTick = -1;

    // ── Countdown display ─────────────────────────────────────────────────────
    /** Text from any entity near the bobber that isn't the !!! signal (e.g. yellow countdown). */
    private String nearbyText = "";

    // ── Bait monitoring ──────────────────────────────────────────────────────────
    private String  currentBaitName  = "";
    private int     currentBaitCount = 0;
    /** Last observed bait name — used to detect a bait switch while active. */
    private String  lastBaitName     = "";
    /** True after the low-bait alert fires; resets when count rises above threshold. */
    private boolean lowBaitAlertFired = false;

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
        int    entityId;
        final String name;
        /** Tick at which this creature was first detected, for despawn-warning age checks. */
        final long   spawnTick;
        /** True once the approaching-despawn alert has fired for this creature. */
        boolean      despawnAlertFired = false;
        /** Last known XZ position — kept fresh by cleanupDeadCreatures while entity is visible.
         *  Used to detect nameplate entity ID refreshes (same creature, new display entity). */
        double lastX, lastZ;

        TrackedSeaCreature(int entityId, String name, long spawnTick, double x, double z) {
            this.entityId  = entityId;
            this.name      = name;
            this.spawnTick = spawnTick;
            this.lastX     = x;
            this.lastZ     = z;
        }
    }

    private FishingManager() {}

    public static FishingManager getInstance() { return INSTANCE; }

    // ── Called every tick by PoseidonMod ──────────────────────────────────────

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!active) return;

        boolean hasBobber = mc.player.fishing != null;
        long currentTick  = Scheduler.getCurrentTick();

        // ── GUI-close lock ────────────────────────────────────────────────────
        // Track open→closed transitions and arm a random delay so the bot never
        // reacts to a bite the instant a GUI is dismissed.
        boolean guiOpen = mc.screen != null;
        if (guiOpen) {
            guiWasOpen = true;
        } else if (guiWasOpen) {
            // GUI just closed — arm the lock
            guiWasOpen = false;
            int lockTicks = GUI_RESUME_MIN_TICKS
                    + (int)(Math.random() * (GUI_RESUME_MAX_TICKS - GUI_RESUME_MIN_TICKS));
            guiLockUntilTick = currentTick + lockTicks;
            PoseidonLogger.getInstance().logInfo(
                    "[gui] closed — bot resumes in " + lockTicks + " ticks");
        }
        // guiLocked = true while a GUI is open OR during the post-close delay
        boolean guiLocked = guiOpen || (guiLockUntilTick >= 0 && currentTick < guiLockUntilTick);

        switch (state) {

            case IDLE -> {
                nearbyText = "";
                if (hasBobber) {
                    state             = FishingState.WAITING;
                    bobberSettleTicks = 0;
                    hookStuckFired    = false;
                    lastRecastTick    = -1; // bobber landed — watchdog no longer needed
                    castTick          = currentTick; // start slugfish / general cast timer
                    // Bait is consumed on cast — read it now so alerts fire at the right moment
                    tickBait();
                    PoseidonLogger.getInstance().logInfo("Bobber detected — watching for !!!");
                } else if (lastRecastTick >= 0
                        && currentTick - lastRecastTick > IDLE_TIMEOUT_TICKS) {
                    // Watchdog: recast was sent but no bobber appeared within the timeout.
                    // This happens when the cast fails (e.g. server lag, missed use packet)
                    // or the bobber surfaced and sank before the IDLE case ran.
                    PoseidonLogger.getInstance().logWarn(
                            "No bobber detected after recast — retrying cast");
                    lastRecastTick = -1; // clear before scheduleRecast sets it again
                    scheduleRecast(FishingConfig.getInstance());
                }
            }

            case WAITING -> {
                if (!hasBobber) {
                    nearbyText = "";
                    state    = FishingState.IDLE;
                    castTick = -1;
                    PoseidonLogger.getInstance().logInfo("Bobber lost while waiting");
                    return;
                }
                if (detectBite(mc)) {
                    if (guiLocked) {
                        // GUI is open or the post-close delay hasn't elapsed yet —
                        // stay WAITING and retry on the next tick.
                        return;
                    }
                    // Slugfish mode: suppress reel-in until enough time has passed
                    // since the cast. Slugfish only bite after ≥20 s (10 s with Slug Pet).
                    FishingConfig slugCfg = FishingConfig.getInstance();
                    if (slugCfg.isSlugfishMode() && castTick >= 0) {
                        long required = slugCfg.isSlugPet()
                                ? SLUGFISH_PET_TICKS : SLUGFISH_NORMAL_TICKS;
                        if (currentTick - castTick < required) {
                            return; // timer not elapsed — ignore this bite
                        }
                    }
                    nearbyText = "";
                    // Record position now — bobber will be gone by the time reel-in fires
                    lastBobberX = mc.player.fishing.getX();
                    lastBobberY = mc.player.fishing.getY();
                    lastBobberZ = mc.player.fishing.getZ();
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
    private boolean detectBite(Minecraft mc) {
        double bx = mc.player.fishing.getX();
        double by = mc.player.fishing.getY();
        double bz = mc.player.fishing.getZ();
        double r  = FishingConfig.getInstance().getDetectionRadius();

        AABB searchBox = new AABB(bx - r, by - 1, bz - r, bx + r, by + r + 2, bz + r);

        for (Entity entity : mc.level.getEntities(mc.player, searchBox)) {
            if (hasReelNowSignal(entity)) {
                PoseidonLogger.getInstance().logInfo(
                        "!!! signal on " + entity.getType().getDescription().getString()
                        + " at " + String.format("%.1f / %.1f / %.1f",
                                entity.getX(), entity.getY(), entity.getZ()));
                return true;
            }
        }
        return false;
    }

    private boolean hasReelNowSignal(Entity entity) {
        Component name = entity.getCustomName();
        if (name != null && name.getString().contains("!!!")) return true;

        if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td) {
            Component text = td.getText();
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
                // Normal path — bobber was still present when the reaction delay elapsed.
                state = FishingState.REELING;
                MovementActions.tapKey("use", 100);
                lastReelTick = Scheduler.getCurrentTick(); // open the catch-message window
                PoseidonLogger.getInstance().logInfo("Reel in sent");

                // Creature scan: bobber may vanish within a tick, so anchor scan to
                // SCAN_DELAY_TICKS after the reel (not after the 10-tick state reset).
                if (cfg.isTrackSeaCreatures()) {
                    Scheduler.schedule(SCAN_DELAY_TICKS, () -> scanForNewCreatures(bx, by, bz));
                }

                // Reset state after the reel-in window.
                Scheduler.schedule(10, () -> {
                    if (state == FishingState.REELING) state = FishingState.IDLE;
                });

            } else if (state == FishingState.IDLE) {
                // The tick handler set state = IDLE because the bobber vanished before
                // the reaction delay elapsed (server removed it while we were waiting).
                // We can't reel in anything, but we must still schedule a recast so the
                // bot doesn't stall forever waiting for a bobber that will never arrive.
                PoseidonLogger.getInstance().logInfo(
                        "[reel] bobber lost during reaction delay — skipping tapKey, recast still scheduled");

            } else {
                // WAITING or any other unexpected state — abort entirely.
                return;
            }

            // ── Common tail: schedule the trigger-decision window then recast ──────
            // Runs for both the BITING (normal) and IDLE (pre-vanished bobber) paths.
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
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            String held = mc.player.getMainHandItem().getHoverName().getString();
            if (!held.toLowerCase().contains("rod")) {
                PoseidonLogger.getInstance().logInfo(
                        "[recast] skipped — not holding a rod (" + held + ")");
                return;
            }
            MovementActions.tapKey("use", 100);
            lastRecastTick = Scheduler.getCurrentTick(); // arm the idle watchdog
            tickBait(); // bait consumed on cast — read after each recast too
            PoseidonLogger.getInstance().logInfo("Recast sent");
        });
    }

    // ── Sea creature tracking ─────────────────────────────────────────────────

    /**
     * Scans entities near the given position for an untracked sea creature name
     * plate (identified by the anchor character used by Hypixel Skyblock).
     * At most one creature is added per reel-in to avoid picking up other
     * players' nearby sea creatures. In the future this will be tightened
     * further by matching against the specific creature type from the catch
     * chat message.
     */
    private void scanForNewCreatures(double bx, double by, double bz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        FishingConfig cfg = FishingConfig.getInstance();
        double r = cfg.getCreatureScanRadius();
        AABB searchBox = new AABB(bx - r, by - 2, bz - r, bx + r, by + r + 6, bz + r);

        // Build a fast lookup of already-tracked entity IDs.
        java.util.Set<Integer> knownIds = new java.util.HashSet<>();
        for (TrackedSeaCreature t : tracked) knownIds.add(t.entityId);

        for (Entity entity : mc.level.getEntities(mc.player, searchBox)) {
            if (!knownIds.contains(entity.getId()) && isSeaCreatureDisplay(entity)) {
                String name = extractCreatureName(entity);

                // Hypixel sometimes refreshes a sea creature's nameplate display entity
                // (e.g. when its HP bar updates), giving it a new entity ID while the
                // creature itself hasn't moved. Detect this by checking if any tracked
                // creature with the same name is already within 3 blocks of this entity.
                // If so, update the stored ID in-place rather than counting it as a new catch.
                boolean isRefreshedNameplate = false;
                if (!name.isEmpty()) {
                    for (TrackedSeaCreature t : tracked) {
                        if (t.name.equals(name)) {
                            double dx = entity.getX() - t.lastX;
                            double dz = entity.getZ() - t.lastZ;
                            if (dx * dx + dz * dz < 9.0) { // within 3 blocks
                                t.entityId = entity.getId();
                                isRefreshedNameplate = true;
                                break;
                            }
                        }
                    }
                }
                if (isRefreshedNameplate) continue;

                tracked.add(new TrackedSeaCreature(entity.getId(), name, Scheduler.getCurrentTick(),
                        entity.getX(), entity.getZ()));
                PoseidonLogger.getInstance().logInfo(
                        "Tracking: " + name + " (id=" + entity.getId() + ") -- total: " + tracked.size());
                checkCapAlert();
                // Only track one creature per reel-in to avoid adding other
                // players' sea creatures that happen to be in scan range.
                return;
            }
        }
    }

    /**
     * Returns true if this entity is a Hypixel Skyblock sea creature name plate.
     * Water sea creatures use the ⚓ anchor character (U+2693).
     * Lava sea creatures use the ♆ trident character (U+2646).
     * Note: some non-sea-creature mobs may also carry these symbols. Proper
     * discrimination will be added in a future update using the catch chat message.
     */
    private boolean isSeaCreatureDisplay(Entity entity) {
        Component name = entity.getCustomName();
        if (name != null) {
            String s = name.getString();
            if (s.contains("⚓") || s.contains("♆")) return true;
        }

        if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td) {
            Component text = td.getText();
            if (text != null) {
                String s = text.getString();
                if (s.contains("⚓") || s.contains("♆")) return true;
            }
        }
        return false;
    }

    /**
     * Best-effort extraction of the creature name from the display text.
     * The full format is "[LvN] ⚓ CreatureName HP/MaxHP❤" (water)
     * or "[LvN] ♆ CreatureName HP/MaxHP❤" (lava).
     * Returns the full string if parsing fails.
     */
    private String extractCreatureName(Entity entity) {
        String raw = "";
        Component name = entity.getCustomName();
        if (name != null) {
            raw = name.getString();
        } else if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td
                   && td.getText() != null) {
            raw = td.getText().getString();
        }

        // Find whichever type symbol is present and strip it + everything after the HP
        int symbol = raw.indexOf('⚓');
        if (symbol < 0) symbol = raw.indexOf('♆');
        if (symbol >= 0 && symbol + 2 < raw.length()) {
            String afterSymbol = raw.substring(symbol + 2).trim();
            // Take up to the first digit (start of HP) or end of string
            int hpStart = afterSymbol.length();
            for (int i = 0; i < afterSymbol.length(); i++) {
                if (Character.isDigit(afterSymbol.charAt(i))) { hpStart = i; break; }
            }
            String candidate = afterSymbol.substring(0, hpStart).trim();
            if (!candidate.isEmpty()) return candidate;
        }
        return raw.isBlank() ? "Unknown" : raw;
    }

    /**
     * Removes creatures whose entity no longer exists in the world.
     * Runs every {@value #CLEANUP_PERIOD_TICKS} ticks — deliberately throttled
     * to avoid per-tick entity lookups.
     */
    private void cleanupDeadCreatures(Minecraft mc) {
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
        tracked.removeIf(t -> {
            Entity e = mc.level.getEntity(t.entityId);
            if (e != null) {
                t.lastX = e.getX(); // keep position fresh while creature is visible
                t.lastZ = e.getZ();
                return false;
            }
            return true;
        });
        int removed = before - tracked.size();

        if (removed > 0) {
            PoseidonLogger.getInstance().logInfo(
                    "Removed " + removed + " despawned creature(s). Remaining: " + tracked.size());
            if (tracked.size() < FishingConfig.SEA_CREATURE_CAP) {
                capAlertFired = false;
            }
        }
    }

    private void checkCapAlert() {
        FishingConfig cfg = FishingConfig.getInstance();
        int cap = FishingConfig.SEA_CREATURE_CAP;
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
     * Scans entities near the bobber for a fishing countdown timer.
     * Only text that looks like a timer (plain digits, or M:SS format) is
     * returned — other nearby entity text (e.g. other players' name plates,
     * decorative entities) is ignored.
     * Returns the timer string, or empty string if none found.
     */
    private String scanNearbyText(Minecraft mc) {
        if (mc.player.fishing == null) return "";
        double bx = mc.player.fishing.getX();
        double by = mc.player.fishing.getY();
        double bz = mc.player.fishing.getZ();
        double r  = FishingConfig.getInstance().getDetectionRadius();
        AABB box  = new AABB(bx - r, by - 1, bz - r, bx + r, by + r + 2, bz + r);

        for (Entity entity : mc.level.getEntities(mc.player, box)) {
            String t = extractEntityText(entity).trim();
            if (looksLikeTimer(t)) return t;
        }
        return "";
    }

    /**
     * Returns true if the text looks like a Hypixel fishing countdown timer.
     * The timer is always a decimal value (e.g. "0.5", "1.3", "1.0").
     * Rejects anything else so we don't show unrelated nearby entity text.
     */
    private static boolean looksLikeTimer(String text) {
        return !text.isBlank() && text.matches("\\d+\\.\\d+");
    }

    private String extractEntityText(Entity entity) {
        Component name = entity.getCustomName();
        if (name != null) return name.getString();
        if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td) {
            Component text = td.getText();
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
    private void checkHookStuck(Minecraft mc) {
        if (mc.player.fishing == null || hookStuckFired) return;

        bobberSettleTicks++;

        // During the settle window, keep updating the anchor to the current position
        // so we measure drift *after* landing, not during the cast arc.
        if (bobberSettleTicks <= BOBBER_SETTLE_TICKS) {
            initialBobberX = mc.player.fishing.getX();
            initialBobberZ = mc.player.fishing.getZ();
            return;
        }

        FishingConfig cfg = FishingConfig.getInstance();
        if (!cfg.isHookStuckDetectionEnabled()) return;

        double dx   = mc.player.fishing.getX() - initialBobberX;
        double dz   = mc.player.fishing.getZ() - initialBobberZ;
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

            // Reset state and recast if the drift-specific auto-recast is on.
            // This is intentionally separate from the global autoRecast flag so
            // the bot can recover from drift even when manual-cast mode is active.
            // No trigger decision window — the player caught nothing.
            Scheduler.schedule(10, () -> {
                if (state == FishingState.REELING) {
                    state = FishingState.IDLE;
                    if (cfg.isHookStuckAutoRecast()) scheduleRecast(cfg);
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
        statFishingSpeed      = parseTabStat("Fishing Speed:");
        statSeaCreatureChance = parseTabStat("Sea Creature Chance:");
        statDoubleHookChance  = parseTabStat("Double Hook Chance:");
        statTreasureChance    = parseTabStat("Treasure Chance:");
    }

    private static String parseTabStat(String prefix) {
        String line = TabListInfo.findLineContaining(prefix);
        if (line == null) return "";
        int idx = line.indexOf(prefix);
        return line.substring(idx + prefix.length()).trim();
    }

    // ── Bait monitoring ──────────────────────────────────────────────────────────

    /**
     * Reads the bait from hotbar slot 8 and fires low-bait / bait-switch alerts.
     * Called only at cast time (IDLE→WAITING transition and after each auto-recast),
     * not every tick, so alerts fire exactly when bait is consumed.
     *
     * Bait is identified by "bait" appearing anywhere in the display name (case-insensitive).
     * Count is read from the "Bait Remaining: <n>" lore line (the Fishing Bag value),
     * falling back to stack count if that line isn't present.
     */
    private void tickBait() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(8);
        String newName  = "";
        int    newCount = 0;

        if (!stack.isEmpty()) {
            String dn = stack.getHoverName().getString();
            if (dn.toLowerCase().contains("bait")) {
                newName = dn;
                // Parse "Bait Remaining: <n>" from lore lines
                net.minecraft.world.item.component.ItemLore lore =
                        stack.get(net.minecraft.core.component.DataComponents.LORE);
                if (lore != null) {
                    for (net.minecraft.network.chat.Component loreLine : lore.lines()) {
                        String raw = loreLine.getString();
                        int idx = raw.indexOf("Bait Remaining:");
                        if (idx >= 0) {
                            String after = raw.substring(idx + 15).trim();
                            try {
                                newCount = Integer.parseInt(after.replaceAll("[^0-9]", ""));
                            } catch (NumberFormatException ignored) {}
                            break;
                        }
                    }
                }
                // Fall back to stack count if lore parsing found nothing
                if (newCount == 0) newCount = stack.getCount();
            }
        }

        if (active) {
            FishingConfig cfg = FishingConfig.getInstance();

            // Bait-switch alert — fires when a known bait is replaced by a different one
            if (!lastBaitName.isEmpty() && !newName.equals(lastBaitName)) {
                cfg.getBaitSwitchAlertSound().play();
                PoseidonLogger.getInstance().logWarn(
                        "Bait switched: " + lastBaitName + " -> "
                        + (newName.isEmpty() ? "none" : newName));
            }

            // Low-bait alert — fires once when count drops to/below the threshold;
            // resets when restocked above it.
            if (!newName.isEmpty()) {
                int threshold = cfg.getBaitLowThreshold();
                if (!lowBaitAlertFired && newCount <= threshold) {
                    lowBaitAlertFired = true;
                    cfg.getBaitLowAlertSound().play();
                    PoseidonLogger.getInstance().logWarn(
                            "Low bait: " + newCount + " " + newName + " remaining");
                } else if (newCount > threshold) {
                    lowBaitAlertFired = false;
                }
            }
        }

        currentBaitName  = newName;
        currentBaitCount = newCount;
        lastBaitName     = newName;
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    public void setActive(boolean v) {
        active = v;
        if (!v) {
            state                 = FishingState.IDLE;
            pendingSuppressRecast = false;
            pendingStopBot        = false;
            lastRecastTick        = -1; // disarm the idle watchdog
            lastReelTick          = -1; // close the catch-message window
            castTick              = -1; // reset slugfish cast timer
            guiWasOpen            = false;
            guiLockUntilTick      = -1; // disarm any pending GUI lock
            // Reset bait baseline so re-enabling never compares against a stale session
            lastBaitName     = "";
            lowBaitAlertFired = false;
            PoseidonLogger.getInstance().logInfo("Poseidon disabled");
        } else {
            // Blank the baseline so the first tick after enable never fires a switch alert
            lastBaitName     = "";
            lowBaitAlertFired = false;
            PoseidonLogger.getInstance().logInfo("Poseidon enabled — watching for bobber");
        }
    }

    public void toggle() { setActive(!active); }

    /**
     * Handles a Golden Fish detection: reels in any active cast (so the line is
     * clear and the player isn't mid-cast) and stops the bot, handing control to
     * the player to catch the Golden Fish manually. The player re-enables the bot
     * afterwards to resume.
     *
     * <p>Only reels in when a bobber is actually out — pressing "use" with no
     * bobber would <em>cast</em> rather than uncast.</p>
     */
    public void handleGoldenFish() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.fishing != null) {
            MovementActions.tapKey("use", 100); // reel in / uncast — clear the line
            PoseidonLogger.getInstance().logInfo("[goldenfish] uncasting before handing control to player");
        }
        setActive(false);
        PoseidonLogger.getInstance().logInfo(
                "[goldenfish] bot stopped — catch the Golden Fish, then re-enable to resume");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public boolean isActive()        { return active; }
    public FishingState getState()   { return state; }
    public int getTrackedCount()     { return tracked.size(); }
    /** Current island from the tab list. Empty string if not on a known server or tab list unavailable. */
    public String getCurrentArea()   { return currentArea; }
    public String getStatFishingSpeed()      { return statFishingSpeed; }
    public String getStatSeaCreatureChance() { return statSeaCreatureChance; }
    public String getStatDoubleHookChance()  { return statDoubleHookChance; }
    public String getStatTreasureChance()    { return statTreasureChance; }
    /** Name of the bait in hotbar slot 8, or empty string if no bait is present. */
    public String getBaitName()  { return currentBaitName; }
    /** Stack count of the bait in hotbar slot 8. 0 when no bait is present. */
    public int getBaitCount()    { return currentBaitCount; }

    /** Text from any non-bite entity near the bobber (e.g. yellow countdown). Empty when nothing visible. */
    public String getNearbyText()    { return nearbyText; }

    /**
     * Returns the remaining ticks before the slugfish timer has elapsed for the
     * current cast, or {@link Long#MIN_VALUE} when slugfish mode is off or no
     * bobber is out.  A value ≤ 0 means the timer has elapsed (slugfish catchable).
     */
    public long getSlugfishRemainingTicks() {
        if (!FishingConfig.getInstance().isSlugfishMode() || castTick < 0) return Long.MIN_VALUE;
        long required = FishingConfig.getInstance().isSlugPet()
                ? SLUGFISH_PET_TICKS : SLUGFISH_NORMAL_TICKS;
        return required - (Scheduler.getCurrentTick() - castTick);
    }

    /**
     * Returns true if a chat trigger should be evaluated against the given message.
     * The window opens when the reel-in tapKey is sent and closes after
     * {@value #CATCH_WINDOW_TICKS} ticks, ensuring only messages that arrive
     * shortly after an actual reel-in are considered.
     */
    public boolean isInCatchWindow() {
        return lastReelTick >= 0
                && Scheduler.getCurrentTick() - lastReelTick <= CATCH_WINDOW_TICKS;
    }

    /**
     * True when right-click should be blocked to prevent the player from
     * accidentally cancelling an active cast.
     *
     * <p>The block is lifted whenever a GUI screen is open — you can't cancel a
     * cast through a menu anyway, and blocking right-click inside a GUI prevents
     * normal item interactions (e.g. picking up items, using containers).
     */
    public boolean shouldBlockRightClick() {
        if (!active) return false;
        if (state != FishingState.WAITING && state != FishingState.BITING) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null; // never block inside a GUI
    }
}
