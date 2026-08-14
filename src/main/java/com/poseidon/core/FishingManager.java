package com.poseidon.core;

import com.playerapi.InventoryInfo;
import com.playerapi.MovementActions;
import com.playerapi.types.ItemSnapshot;
import com.playerapi.Scheduler;
import com.playerapi.TabListInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core state machine for Poseidon.
 *
 * Bite detection: waits for an entity bearing the red "!!!" signal to appear
 * near the bobber (the server places this entity during the catch window).
 *
 * Sea creature tracking: after each reel-in, scans for entities bearing the
 * resource pack's water (Aquatic) or lava (Magmatic) type glyph (Hypixel Skyblock
 * sea creature name plates). Tracked
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
    /** Ticks after the catch line is identified before binding its name plate. */
    private static final int SCAN_DELAY_TICKS     = 5;
    /** Ticks between matched-catch scan retries (see {@link #scanForCatchWithRetry}). */
    private static final int SCAN_RETRY_INTERVAL  = 5;
    /** Max matched-catch scan attempts (~SCAN_RETRY_INTERVAL × this ticks of coverage). */
    private static final int SCAN_MAX_ATTEMPTS    = 6;
    /**
     * Ticks after a reel-in before the proximity <em>fallback</em> scan runs. Long enough for the
     * catch chat line to arrive and bind precisely; the fallback is skipped if it did.
     */
    private static final int FALLBACK_SCAN_DELAY_TICKS = 30;
    /** How often (in ticks) to remove creatures that have disappeared. */
    private static final int CLEANUP_PERIOD_TICKS = 40;
    /**
     * Hard cap on how long a creature may stay tracked (SkyHanni's {@code DESPAWN_TIME}, 6 min).
     * A safety net only: normally a creature is dropped the moment its mob entity leaves the world.
     * This catches the rare case where a mob is never seen to despawn (e.g. it unloaded far away).
     */
    private static final long DESPAWN_MAX_TICKS = 6 * 60 * 20; // 6 minutes
    /** How often (in ticks) to refresh the current area from the tab list. */
    private static final int AREA_REFRESH_TICKS   = 40;
    /**
     * If the bot stays in IDLE this many ticks after a recast without detecting
     * a bobber, the watchdog fires and schedules a fresh recast.
     * Covers the case where the bobber appears briefly then vanishes (failed cast)
     * leaving the state machine with nothing to retry.
     */
    private static final int IDLE_TIMEOUT_TICKS   = 100;
    /** Ticks a bobber may be missing during WAITING before it counts as lost (server-stutter grace). */
    private static final int BOBBER_LOST_GRACE_TICKS = 6;
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

    // ── "Not in water" particle detection ──────────────────────────────────────
    /**
     * Particle type ids that signal the "bobber not in water" state — confirmed by live capture to be the
     * blue {@code minecraft:dust} (coloured redstone dust) burst Hypixel spawns. Deliberately excludes the
     * normal in-water fishing particles (bubble / splash / fishing wake), which appear during healthy
     * fishing and would cause false recasts.
     */
    private static final java.util.Set<String> NOT_IN_WATER_PARTICLES = java.util.Set.of(
            "minecraft:dust");
    /** Radius around the bobber to count particles in. */
    private static final double NOT_IN_WATER_RADIUS = 1.6;
    /** Look back over this many ticks of recorded particles (~1s). */
    private static final int NOT_IN_WATER_WINDOW_TICKS = 20;
    /** This many matching particles near the bobber within the window → treat as "not in water". */
    private static final int NOT_IN_WATER_MIN_HITS = 8;
    /** Wait this long after the bobber lands before checking, so the cast's own splash is ignored. */
    private static final int NOT_IN_WATER_SETTLE_TICKS = 30;
    /** Minimum gap between forced not-in-water recasts (~3s), so we never spam. */
    private static final int NOT_IN_WATER_COOLDOWN_TICKS = 60;

    // ── Fishing state ─────────────────────────────────────────────────────────
    private boolean active = false;
    private FishingState state = FishingState.IDLE;
    /**
     * "First real cast" gate for the no-bobber recovery watchdog. The bot never bootstraps a cast from
     * cold — it waits until you manually cast once (a real bobber appears) before it will auto-recast a
     * lost/failed bobber. Switching off the rod resets this, so re-equipping waits for a real cast again.
     */
    private boolean seenRealCast = false;

    // Saved bobber position at the moment !!! is detected (bobber may vanish by reel time).
    private double lastBobberX, lastBobberY, lastBobberZ;

    // ── Sea creature tracking ─────────────────────────────────────────────────
    private final List<TrackedSeaCreature> tracked = new ArrayList<>();
    private boolean capAlertFired = false;
    /**
     * Chat-confirmed catch state. On each reel a fresh window opens; the first catch
     * chat line that arrives ({@link #handleCatchMessage}) identifies the creature and
     * schedules the bind, then latches {@code caughtThisWindow} so later messages in the
     * same window can't double-add. Bobber position is snapshotted for the bind scan.
     */
    private boolean caughtThisWindow = false;
    /** True once a DOUBLE HOOK! line was seen this window — used even if the creature is unknown. */
    private boolean pendingDoubleHook = false;
    private double  catchBobberX, catchBobberY, catchBobberZ;
    private long lastCleanupTick = 0;

    /** Last tick a "not in water" forced recast fired (cooldown gate). */
    private long lastNotInWaterRecastTick = Long.MIN_VALUE / 2;
    /** Throttle for the particle-type debug log. */
    private long lastParticleLogTick = Long.MIN_VALUE / 2;

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
     * Tick from which we've been IDLE with no bobber, or -1 when we have a bobber. Drives the
     * recovery watchdog: if it stays set past {@link #IDLE_TIMEOUT_TICKS}, the cast is assumed to
     * have failed (or never happened) and we recast — <em>regardless of how we reached IDLE</em>.
     * This replaces the old recast-only watchdog, which could be left disarmed after a bobber was
     * lost from WAITING, leaving the bot stuck in IDLE forever.
     */
    private long idleNoBobberSince = -1;
    /**
     * Consecutive ticks the bobber has been missing while WAITING. A brief server stutter can null
     * {@code player.fishing} for a tick or two; we tolerate {@link #BOBBER_LOST_GRACE_TICKS} of that
     * before treating the bobber as genuinely lost, so a flicker doesn't bounce WAITING→IDLE.
     */
    private int bobberMissingTicks = 0;
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

    /**
     * One tracked sea creature, keyed on the <b>mob's</b> base entity id — the SkyHanni model
     * (see {@code SeaCreatureDetectionApi}). We deliberately do <em>not</em> key on the floating
     * name plate: Hypixel re-creates the plate entity on every HP change, so a plate-keyed tracker
     * double-counts refreshes and mistakes plate churn for death. The mob entity id, by contrast,
     * is stable for the creature's whole life, so "mob entity gone" is a reliable death signal.
     */
    private static class TrackedSeaCreature {
        /** Stable key: the base mob entity id (resolved from under the name plate at bind time). */
        final int    mobId;
        final String name;
        /** Tick at which this creature was first detected, for despawn-warning + max-age checks. */
        final long   spawnTick;
        /** True when the catch chat line correlated this creature to our own catch (vs a passive
         *  proximity add of someone else's creature). Kept for diagnostics/parity with SkyHanni. */
        final boolean isOwn;
        /** True once the approaching-despawn alert has fired for this creature. */
        boolean      despawnAlertFired = false;
        /** Last known XZ position — kept fresh by cleanupDeadCreatures while the mob is loaded. */
        double lastX, lastZ;

        TrackedSeaCreature(int mobId, String name, long spawnTick, boolean isOwn, double x, double z) {
            this.mobId     = mobId;
            this.name      = name;
            this.spawnTick = spawnTick;
            this.isOwn     = isOwn;
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

        // Keep the glow set in sync even while the bot is idle — creatures you already caught
        // should stay highlighted after you stop the bot to kill them.
        updateGlow();

        // Only record particles while the bot is running and the not-in-water feature (or its debug) is on.
        FishingConfig pcfg = FishingConfig.getInstance();
        ParticleWatch.getInstance().setActive(
                active && (pcfg.isNotInWaterRecastEnabled() || pcfg.isLogBobberParticles()));

        if (!active) return;

        // Recover a desynced bobber: on Hypixel the hook entity sometimes spawns without the
        // client ever linking it to player.fishing, which would wedge the bot in IDLE forever
        // (a cast rod but hasBobber == false). If our link is missing, find the FishingHook we
        // actually own in the world and re-link it, so all detection below works normally.
        if (mc.player.fishing == null) {
            FishingHook own = findOwnBobber(mc);
            if (own != null) {
                mc.player.fishing = own;
                PoseidonLogger.getInstance().logInfo(
                        "[cast] re-linked desynced bobber (player.fishing was null)");
            }
        }

        boolean hasBobber = mc.player.fishing != null;
        long currentTick  = Scheduler.getCurrentTick();

        // "First real cast" gate: a real bobber marks that fishing has genuinely started (the first one can
        // only be a manual cast, since the watchdog won't auto-cast until this is set). Switching off the
        // rod clears it, so the bot again waits for a real cast when you come back to the rod.
        if (!com.playerapi.PlayerInfo.getHeldItem().skyblockId().contains("ROD")) {
            seenRealCast = false;
        } else if (hasBobber) {
            seenRealCast = true;
        }

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
                    bobberMissingTicks = 0;
                    hookStuckFired    = false;
                    idleNoBobberSince = -1; // bobber landed — recovery watchdog no longer needed
                    castTick          = currentTick; // start slugfish / general cast timer
                    // Bait is consumed on cast — read it now so alerts fire at the right moment
                    tickBait();
                    PoseidonLogger.getInstance().logInfo("Bobber detected — watching for !!!");
                } else if (!isReadyToCast(mc)) {
                    // In a GUI/menu or not holding a rod — the bot can't meaningfully cast, so don't
                    // attempt it (and don't spam the log every 5s). Hold the watchdog paused so it
                    // starts a fresh window once you're back on the rod / out of the menu.
                    idleNoBobberSince = currentTick;
                } else if (!seenRealCast) {
                    // Active + on the rod, but you haven't cast yet this rod session — never bootstrap a
                    // cast from cold. Hold the watchdog paused until a real (manual) cast starts fishing.
                    idleNoBobberSince = currentTick;
                } else {
                    // No bobber while IDLE, and fishing was really underway. Time it from the first such
                    // tick, and once we've been stuck past the timeout, recast — this catches a
                    // failed/dropped cast AND a bobber that was lost from WAITING.
                    if (idleNoBobberSince < 0) idleNoBobberSince = currentTick;
                    else if (currentTick - idleNoBobberSince > IDLE_TIMEOUT_TICKS) {
                        PoseidonLogger.getInstance().logWarn(
                                "No bobber for " + IDLE_TIMEOUT_TICKS + " ticks — recasting");
                        idleNoBobberSince = currentTick; // reset so a failed retry waits another cycle
                        scheduleRecast(FishingConfig.getInstance());
                    }
                }
            }

            case WAITING -> {
                if (!hasBobber) {
                    // Tolerate a brief flicker (server stutter can null the hook for a tick or two)
                    // before deciding the bobber is genuinely gone.
                    if (++bobberMissingTicks <= BOBBER_LOST_GRACE_TICKS) return;
                    bobberMissingTicks = 0;
                    nearbyText = "";
                    state    = FishingState.IDLE;
                    castTick = -1;
                    idleNoBobberSince = currentTick; // arm recovery from the moment we drop to IDLE
                    PoseidonLogger.getInstance().logInfo("Bobber lost while waiting");
                    return;
                }
                bobberMissingTicks = 0; // have the bobber — reset the flicker counter
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
                    // Check for the blue "bobber not in water" particle burst → force a recast
                    checkNotInWater(mc, currentTick);
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

    /**
     * Finds the FishingHook the local player actually owns, by entity search — used to repair a
     * missing {@code player.fishing} link. Returns {@code null} if no such hook exists (a genuine
     * "no bobber" state, e.g. a failed cast — left for the recast watchdog).
     */
    private static FishingHook findOwnBobber(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        double r = 40.0; // beyond practical cast range
        AABB box = new AABB(mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
                            mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r);
        var hooks = mc.level.getEntitiesOfClass(FishingHook.class, box,
                h -> h.getPlayerOwner() == mc.player);
        return hooks.isEmpty() ? null : hooks.get(0);
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
            boolean reeledThisCast; // true only when an actual reel-in happened (a catch)
            if (state == FishingState.BITING) {
                // Normal path — bobber was still present when the reaction delay elapsed.
                state = FishingState.REELING;
                MovementActions.tapKey("use", 100);
                lastReelTick = Scheduler.getCurrentTick(); // open the catch-message window
                PoseidonLogger.getInstance().logInfo("Reel in sent");

                // Open a fresh chat-confirmed catch window. The actual sea-creature bind
                // now happens when the catch chat line arrives (handleCatchMessage), which
                // tells us exactly which creature (and whether it was a Double Hook) rather
                // than guessing by proximity. Snapshot the bobber for that later scan, since
                // it may vanish within a tick.
                if (cfg.isTrackSeaCreatures()) {
                    caughtThisWindow  = false;
                    pendingDoubleHook = false;
                    catchBobberX = bx; catchBobberY = by; catchBobberZ = bz;

                    // Fallback: if no known catch line identified the creature in time (an unlisted
                    // creature, or no line at all), fall back to the old proximity scan so it's
                    // still tracked. Skipped when the chat line already bound precisely.
                    Scheduler.schedule(FALLBACK_SCAN_DELAY_TICKS, () -> {
                        if (caughtThisWindow) return;
                        caughtThisWindow = true;
                        PoseidonLogger.getInstance().logInfo(
                                "[sc] no catch line matched — proximity fallback scan");
                        scanForNewCreatures(bx, by, bz, null, pendingDoubleHook ? 2 : 1);
                    });
                }

                // Reset state after the reel-in window.
                Scheduler.schedule(10, () -> {
                    if (state == FishingState.REELING) state = FishingState.IDLE;
                });
                reeledThisCast = true;

            } else if (state == FishingState.IDLE) {
                // The tick handler set state = IDLE because the bobber vanished before
                // the reaction delay elapsed (server removed it while we were waiting).
                // We can't reel in anything, but we must still schedule a recast so the
                // bot doesn't stall forever waiting for a bobber that will never arrive.
                PoseidonLogger.getInstance().logInfo(
                        "[reel] bobber lost during reaction delay — skipping tapKey, recast still scheduled");
                reeledThisCast = false;

            } else {
                // WAITING or any other unexpected state — abort entirely.
                return;
            }

            // ── Common tail: schedule the trigger-decision window then recast ──────
            // Runs for both the BITING (normal) and IDLE (pre-vanished bobber) paths.
            pendingSuppressRecast = false;
            pendingStopBot        = false;
            final boolean reeled  = reeledThisCast; // gate abilities to real catches only
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
                // Auto-use fishing abilities (Fire Veil / Totem) between reel and recast — but
                // only after a real catch, not when the bobber vanished before the reel. Then
                // recast once back on the rod slot. No abilities due → recast immediately.
                if (reeled) {
                    if (AbilityManager.getInstance().hasAtCapAbilityEnabled()) {
                        // The just-caught creature is added by the delayed scan, so tracked.size() is one
                        // short right now. Wait a few ticks (past the first scan) for the count to settle so
                        // the at-cap ability fires on the catch that actually reaches the cap — before the
                        // next cast — instead of a cycle late.
                        Scheduler.schedule(8, () -> {
                            boolean atCap = tracked.size() >= FishingConfig.SEA_CREATURE_CAP;
                            AbilityManager.getInstance().runDueAbilities(atCap, () -> scheduleRecast(cfg));
                        });
                    } else {
                        // No at-cap ability → the count doesn't matter; run any constant abilities now.
                        AbilityManager.getInstance().runDueAbilities(false, () -> scheduleRecast(cfg));
                    }
                } else {
                    scheduleRecast(cfg);
                }
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

    /**
     * True when the bot can actually cast right now: no screen (GUI/menu) is open and the player is
     * holding a fishing rod. Used to pause the IDLE recast watchdog so it doesn't attempt — or log —
     * a cast every {@link #IDLE_TIMEOUT_TICKS} ticks while you're in a menu or off the rod.
     */
    private static boolean isReadyToCast(Minecraft mc) {
        if (mc.screen != null) return false;
        if (mc.player == null) return false;
        // Identify the rod by its stable SkyBlock id (contains "ROD"), not the renamed display name.
        return com.playerapi.PlayerInfo.getHeldItem().skyblockId().contains("ROD");
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
            if (!com.playerapi.PlayerInfo.getHeldItem().skyblockId().contains("ROD")) {
                String held = mc.player.getMainHandItem().getHoverName().getString();
                PoseidonLogger.getInstance().logInfo(
                        "[recast] skipped — not holding a rod (" + held + ")");
                return;
            }
            MovementActions.tapKey("use", 100);
            // Anchor the recovery watchdog to this cast: it gets a full IDLE_TIMEOUT window to
            // produce a bobber before we retry.
            idleNoBobberSince = Scheduler.getCurrentTick();
            tickBait(); // bait consumed on cast — read after each recast too
            PoseidonLogger.getInstance().logInfo("Recast sent");
        });
    }

    // ── "Not in water" recovery ────────────────────────────────────────────────

    /**
     * Detects the blue "bobber not in water" particle burst Hypixel spawns when its in/out-of-water (or
     * lava) detection fails on a cast — the bobber sits there emitting particles but will never bite. When
     * a cluster of the {@link #NOT_IN_WATER_PARTICLES} appears right at the bobber (after it has settled,
     * so the cast's own splash is ignored), we reel in and recast to recover. On by default; disabled via
     * {@code notInWaterRecastEnabled}.
     */
    private void checkNotInWater(Minecraft mc, long now) {
        FishingConfig cfg = FishingConfig.getInstance();
        if (mc.player.fishing == null) return;
        // Give the bobber a moment to settle after landing (castTick = the tick it was detected).
        if (castTick < 0 || now - castTick < NOT_IN_WATER_SETTLE_TICKS) return;

        double bx = mc.player.fishing.getX();
        double by = mc.player.fishing.getY();
        double bz = mc.player.fishing.getZ();
        long since = now - NOT_IN_WATER_WINDOW_TICKS;

        // Debug: surface exactly which particle ids are appearing at the bobber so the trigger set can be
        // confirmed/extended. Logged at WARN so it's visible without raising the log level.
        if (cfg.isLogBobberParticles() && now - lastParticleLogTick >= 20) {
            java.util.Set<String> types =
                    ParticleWatch.getInstance().typesNear(bx, by, bz, NOT_IN_WATER_RADIUS, since);
            if (!types.isEmpty()) {
                lastParticleLogTick = now;
                PoseidonLogger.getInstance().logWarn("[water] particles at bobber: " + types);
            }
        }

        if (!cfg.isNotInWaterRecastEnabled()) return;
        if (now - lastNotInWaterRecastTick < NOT_IN_WATER_COOLDOWN_TICKS) return;

        int hits = ParticleWatch.getInstance()
                .countNear(bx, by, bz, NOT_IN_WATER_RADIUS, since, NOT_IN_WATER_PARTICLES);
        if (hits >= NOT_IN_WATER_MIN_HITS) {
            lastNotInWaterRecastTick = now;
            PoseidonLogger.getInstance().logWarn("[water] bobber not in water (" + hits
                    + " blue particles) — reeling in and recasting");
            // Reel in the stuck bobber, drop to IDLE, then recast (scheduleRecast casts only when IDLE).
            MovementActions.tapKey("use", 100);
            state = FishingState.IDLE;
            castTick = -1;
            idleNoBobberSince = now; // reset the recovery watchdog so it doesn't also fire
            scheduleRecast(cfg);
        }
    }

    // ── Sea creature tracking ─────────────────────────────────────────────────

    /**
     * Binds newly-spawned sea creature name plate(s) near the bobber to the tracked list.
     *
     * <p>Driven by {@link #handleCatchMessage}: when {@code targetName} is non-null the catch
     * chat line identified exactly what was caught, so only plates whose name matches are
     * eligible — this is the precise, chat-confirmed path. When {@code targetName} is null the
     * line was unknown (a creature we have no line for, or a plain fish/item catch), so it
     * falls back to the old proximity behaviour: any untracked sea-creature plate. Either way
     * at most {@code count} are added (2 for a Double Hook, 1 otherwise), nearest to the bobber
     * first, so a plain fish catch — which spawns no plate — adds nothing.</p>
     */
    private int scanForNewCreatures(double bx, double by, double bz, String targetName, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        FishingConfig cfg = FishingConfig.getInstance();
        double r = cfg.getCreatureScanRadius();
        AABB searchBox = new AABB(bx - r, by - 2, bz - r, bx + r, by + r + 6, bz + r);

        // Fast lookup of already-tracked MOB entity ids (the stable key). A plate refresh never
        // adds a duplicate, because the mob under it is already tracked.
        java.util.Set<Integer> knownMobs = new java.util.HashSet<>();
        for (TrackedSeaCreature t : tracked) knownMobs.add(t.mobId);

        // Collect eligible plates near the bobber, each paired with the mob resolved beneath it.
        // The plate only identifies the creature (name + sea-creature marker); the MOB is what we
        // track — so a plate whose mob can't be resolved yet is skipped (the retry re-tries once
        // the mob entity has loaded).
        record Candidate(Entity mob, String name, double d2) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Entity entity : mc.level.getEntities(mc.player, searchBox)) {
            if (!isSeaCreatureDisplay(entity)) continue;
            if (targetName != null && !labelMatches(entity, targetName)) continue;
            Entity mob = findMobUnderLabel(mc, entity);
            if (mob == null) continue;                    // can't track a plate without its mob
            if (knownMobs.contains(mob.getId())) continue; // already tracked (or a plate refresh)
            double dx = mob.getX() - bx, dz = mob.getZ() - bz;
            candidates.add(new Candidate(mob, extractCreatureName(entity), dx * dx + dz * dz));
        }

        // Nearest to the bobber first, so a Double Hook binds the two closest matching creatures.
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::d2));

        int added = 0;
        for (Candidate c : candidates) {
            if (added >= count) break;
            if (!knownMobs.add(c.mob().getId())) continue; // guard against two plates over one mob
            tracked.add(new TrackedSeaCreature(c.mob().getId(), c.name(),
                    Scheduler.getCurrentTick(), targetName != null, c.mob().getX(), c.mob().getZ()));
            PoseidonLogger.getInstance().logInfo(
                    "Tracking: " + c.name() + " (mob=" + c.mob().getId() + ") -- total: " + tracked.size());
            checkCapAlert();
            added++;
        }
        return added;
    }

    /**
     * Binds a chat-matched catch, retrying the scan a few times if the plate hasn't spawned yet. A matched
     * catch has <b>no</b> proximity fallback (the fallback is disabled once {@code caughtThisWindow}
     * latches), so a single shot would silently miss when catches come in fast and the nameplate entity
     * lags a few ticks behind the chat line — exactly the "two catches within a second, count ends up off"
     * case. We re-scan every {@link #SCAN_RETRY_INTERVAL} ticks until the target(s) bind or attempts run
     * out; the already-tracked mob ids and the remaining count keep it from ever over-adding.
     */
    private void scanForCatchWithRetry(double bx, double by, double bz, String target,
                                       int remaining, int attemptsLeft) {
        remaining -= scanForNewCreatures(bx, by, bz, target, remaining);
        if (remaining <= 0 || attemptsLeft <= 1) return;
        final int rem = remaining;
        Scheduler.schedule(SCAN_RETRY_INTERVAL, () ->
                scanForCatchWithRetry(bx, by, bz, target, rem, attemptsLeft - 1));
    }

    /** How many entity ids back from a name plate to look for its mob (equipment/extra stands can sit between). */
    private static final int ID_ADJACENCY_SPAN = 4;

    /**
     * Resolves the sea creature mob beneath a name plate — the entity we key tracking on.
     *
     * <p><b>Primary: entity-id adjacency</b> — SkyHanni's method ({@code MobUtils.getNextEntity}).
     * Hypixel spawns a mob and its floating name plate consecutively, so the plate's entity id is the
     * mob's id plus a small offset; walking back from the plate id to the first valid mob gives a
     * solid 1:1 plate→mob link that <b>does not depend on position</b>. This is the key fix for the
     * stacked case — player, bobber and several creatures all on the same spot — where a nearest-below
     * search collapses every plate onto the one closest mob and silently under-counts. A loose distance
     * sanity check rejects a coincidental id-neighbour that isn't really this plate's mob.</p>
     *
     * <p><b>Fallback: nearest below</b> — the old spatial search, used only when adjacency finds
     * nothing (an unusual spawn ordering). Returns {@code null} if neither resolves a mob.</p>
     */
    private static Entity findMobUnderLabel(Minecraft mc, Entity plate) {
        if (mc.level == null) return null;
        Vec3 labelPos = plate.position();

        // Primary: id adjacency (plate id = mob id + small offset ⇒ mob id = plate id - k).
        int pid = plate.getId();
        for (int k = 1; k <= ID_ADJACENCY_SPAN; k++) {
            Entity e = mc.level.getEntity(pid - k);
            if (isTrackableMob(e) && nearLabel(e, labelPos)) return e;
        }

        // Fallback: nearest living, non-plate entity 0–6 blocks below the label.
        final double r = 4.0;
        AABB box = new AABB(labelPos.x - r, labelPos.y - 7, labelPos.z - r,
                            labelPos.x + r, labelPos.y + 1, labelPos.z + r);
        Entity closest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(mc.player, box)) {
            if (!isTrackableMob(e)) continue;
            double dy = labelPos.y - e.getY(); // positive = label above the mob
            if (dy < 0.0 || dy > 6.0) continue;
            double dx = labelPos.x - e.getX(), dz = labelPos.z - e.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < r * r && d2 < best) { best = d2; closest = e; }
        }
        return closest;
    }

    /**
     * A living entity trackable as a sea creature: not a name plate (armour stand / display) and not
     * a <em>real</em> player. Crucially we do <b>not</b> exclude player entities wholesale — several
     * sea creatures (e.g. Banshee) are rendered with a player model, so they arrive as Player entities.
     * SkyHanni's {@code isSkyBlockMob} keeps those and rejects only real players, told apart by UUID
     * version: Mojang accounts get random v4 UUIDs; Hypixel's mob-players get non-v4 ones.
     */
    private static boolean isTrackableMob(Entity e) {
        return e instanceof LivingEntity
                && !(e instanceof ArmorStand)
                && !(e instanceof net.minecraft.world.entity.Display)
                && !isRealPlayer(e);
    }

    /** True for a genuine player account (random v4 UUID) — as opposed to a Hypixel player-model mob/NPC. */
    private static boolean isRealPlayer(Entity e) {
        return e instanceof net.minecraft.world.entity.player.Player
                && e.getUUID().version() == 4;
    }

    /** Loose check that a candidate mob really sits under a label — rejects false id-neighbours. */
    private static boolean nearLabel(Entity mob, Vec3 labelPos) {
        double dx = labelPos.x - mob.getX(), dz = labelPos.z - mob.getZ(), dy = labelPos.y - mob.getY();
        return dx * dx + dz * dz <= 9.0 && dy >= -1.0 && dy <= 8.0; // ≤3 blocks horiz, plate above mob
    }

    // ── Sea creature glow (read by the glow mixins on the render thread) ───────

    /** Mob entity ID → packed RGB glow colour. Replaced atomically; never mutated after publish. */
    private volatile Map<Integer, Integer> glowingMobs = Map.of();

    /** Rebuilt every tick from the tracked list so it follows config + tracking changes. */
    private void updateGlow() {
        FishingConfig cfg = FishingConfig.getInstance();
        if (!cfg.isHighlightSeaCreatures() || tracked.isEmpty()) {
            if (!glowingMobs.isEmpty()) glowingMobs = Map.of();
            return;
        }
        int color = cfg.getSeaCreatureHighlightColor();
        Map<Integer, Integer> map = new HashMap<>();
        for (TrackedSeaCreature t : tracked) map.put(t.mobId, color);
        glowingMobs = map;
    }

    public boolean isSeaCreatureGlowing(int entityId) { return glowingMobs.containsKey(entityId); }
    public int getSeaCreatureGlowColor(int entityId) { return glowingMobs.getOrDefault(entityId, 0xFFFFFF); }

    /** Raw label text of a name-plate entity (custom name or text display); "" if it has none. */
    private static String rawLabelText(Entity entity) {
        Component name = entity.getCustomName();
        if (name != null) return name.getString();
        if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td
                && td.getText() != null) {
            return td.getText().getString();
        }
        return "";
    }

    /**
     * True if this plate's label <em>contains</em> the caught creature's name. The label is never
     * exactly the name — it carries the level prefix, the type glyph, HP and formatting codes — so
     * this compares on the normalized text (codes + pack glyphs stripped, lower-cased) rather than
     * requiring an exact match.
     */
    private static boolean labelMatches(Entity entity, String targetName) {
        String norm = SeaCreatureCatches.normalized(rawLabelText(entity));
        return !norm.isEmpty()
                && norm.contains(targetName.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Handles a server chat message that arrived inside the post-reel catch window. Identifies
     * the sea creature from the catch line via {@link SeaCreatureCatches} and schedules the
     * bind. Only the first catch message per reel is acted on (later messages — rare-drop
     * announcements, etc. — are ignored). A matched line binds that exact creature; an unknown
     * line falls back to the positional scan.
     *
     * <p><b>Double Hook.</b> Hypixel sends the double hook as its <em>own</em> line —
     * {@code "It's a Double Hook!"} (formatted) — <em>before</em> the catch line, not merged into it.
     * {@link SeaCreatureCatches#normalize} strips that formatting and the line contains the
     * {@code "double hook!"} key, so it sets {@link #pendingDoubleHook} here; the flag persists through
     * the (unchanged) window until the following creature line arrives and binds two.</p>
     */
    public void handleCatchMessage(String message) {
        if (!FishingConfig.getInstance().isTrackSeaCreatures()) return;
        if (caughtThisWindow) return; // already bound this reel

        SeaCreatureCatches.CatchResult r = SeaCreatureCatches.getInstance().identify(message);
        if (r.doubleHook() && !pendingDoubleHook) {
            pendingDoubleHook = true; // remember even if the creature is unknown / on its own line
            PoseidonLogger.getInstance().logInfo(
                    "[sc] Double Hook detected — the next catch this reel counts x2");
        }

        if (!r.matched()) {
            // Not a known catch line (or the standalone Double Hook line). Leave the window open — the
            // real creature line may still be coming, and the proximity fallback covers unlisted ones.
            PoseidonLogger.getInstance().logDebug(
                    "[sc] no creature bound from: \"" + SeaCreatureCatches.normalized(message) + "\"");
            return;
        }

        caughtThisWindow = true;
        final int count = pendingDoubleHook ? 2 : 1;
        PoseidonLogger.getInstance().logInfo("[sc] catch line matched: " + r.creature()
                + (count > 1 ? " x2 (Double Hook)" : ""));

        // Delay so the freshly-spawned nameplate entity exists before we scan.
        final String target = r.creature();
        final double bx = catchBobberX, by = catchBobberY, bz = catchBobberZ;
        Scheduler.schedule(SCAN_DELAY_TICKS, () ->
                scanForCatchWithRetry(bx, by, bz, target, count, SCAN_MAX_ATTEMPTS));
    }

    /**
     * Characters that mark a Hypixel sea-creature name plate. Each entry is matched as
     * a substring of the plate text; the matched marker is also where the creature name
     * begins (everything after it, up to the HP digits, is the name).
     *
     * <p>These are the mandatory resource pack's custom Private-Use-Area glyphs:
     * water = Aquatic (U+E072), lava = Magmatic (U+E07D). The pre-pack ⚓/♆ symbols
     * were dropped once the pack went mandatory.</p>
     */
    private static final String[] SEA_CREATURE_MARKERS = {
            String.valueOf((char) 0xE072), // Aquatic  — water sea creature
            String.valueOf((char) 0xE07D), // Magmatic — lava sea creature
    };

    /** Returns the first sea-creature marker found in {@code s}, or {@code null} if none. */
    private static String matchedMarker(String s) {
        if (s == null) return null;
        for (String m : SEA_CREATURE_MARKERS) {
            if (!m.isEmpty() && s.contains(m)) return m;
        }
        return null;
    }

    /**
     * Returns true if this entity is a Hypixel Skyblock sea creature name plate —
     * i.e. its plate text contains one of {@link #SEA_CREATURE_MARKERS}. Which specific
     * creature was caught is discriminated separately via the catch chat line (see
     * {@link SeaCreatureCatches} and {@link #handleCatchMessage}); this only decides
     * whether an entity is a sea-creature plate at all.
     */
    private boolean isSeaCreatureDisplay(Entity entity) {
        Component name = entity.getCustomName();
        if (name != null && matchedMarker(name.getString()) != null) return true;

        if (entity instanceof net.minecraft.world.entity.Display.TextDisplay td) {
            Component text = td.getText();
            if (text != null && matchedMarker(text.getString()) != null) return true;
        }
        return false;
    }

    /**
     * Best-effort extraction of the creature name from the display text.
     * The full format is "[LvN] &lt;marker&gt; CreatureName HP/MaxHP❤", where
     * &lt;marker&gt; is one of {@link #SEA_CREATURE_MARKERS}.
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

        // Strip everything up to and including the type marker, then take up to the HP.
        String marker = matchedMarker(raw);
        if (marker != null) {
            int idx = raw.indexOf(marker);
            // marker.length() handles multi-char (surrogate-pair) PUA glyphs correctly;
            // trim() removes the space that follows the marker.
            String afterSymbol = raw.substring(idx + marker.length()).trim();
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
            // Death signal: the MOB entity has left the world. Because we key on the mob's stable id
            // (not the churning name plate), this fires exactly once, when the creature actually dies
            // or despawns — no plate-refresh false positives. The max-age is only a far-back safety
            // net for a mob that unloaded without a visible despawn.
            Entity e = mc.level.getEntity(t.mobId);
            if (e != null) {
                t.lastX = e.getX(); // keep position fresh while the mob is loaded
                t.lastZ = e.getZ();
                return (now - t.spawnTick) >= DESPAWN_MAX_TICKS;
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
     * Bait is identified by "BAIT" appearing in the item's SkyBlock id (stable across renames).
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
            // Identify bait by its stable SkyBlock id (contains "BAIT"); count logic is unchanged.
            if (com.playerapi.types.ItemSnapshot.from(stack).skyblockId().contains("BAIT")) {
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
        seenRealCast = false; // always wait for a real cast after (de)activating — never bootstrap a cast
        if (!v) {
            state                 = FishingState.IDLE;
            pendingSuppressRecast = false;
            pendingStopBot        = false;
            idleNoBobberSince     = -1; // disarm the recovery watchdog
            bobberMissingTicks    = 0;
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
