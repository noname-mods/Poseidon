package com.poseidon.core;

import com.playerapi.InteractionActions;
import com.playerapi.InventoryActions;
import com.playerapi.InventoryInfo;
import com.playerapi.Scheduler;
import com.poseidon.core.FishingConfig.AbilityConfig;
import com.poseidon.core.FishingConfig.AbilityMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Auto-uses hotbar "ability" items after a catch so the bot can fish for longer stretches:
 * the <b>Fire Veil</b> wand (AoE that instant-kills low-rarity sea creatures) and the
 * <b>Totem of Corruption</b> (a placed 5-minute banner that corrupts sea creatures).
 *
 * <p>Each ability has a mode: {@code CONSTANT} (re-fire whenever its cooldown has elapsed,
 * after every catch) or {@code AT_CAP} (only when the tracked sea-creature count reaches the
 * cap). Both can be enabled at once; when both are due on the same catch the <b>Totem fires
 * first, then Fire Veil</b>, and we only switch back to the rod once at the end (the extra
 * mana cost is accepted).</p>
 *
 * <p><b>The camera is never moved.</b> Fire Veil doesn't need aim; the Totem is placed
 * wherever the player is already looking — if that isn't a valid spot the placement simply
 * whiffs. Casting only happens after a catch (no bobber out) so switching slots is safe.</p>
 */
public final class AbilityManager {

    private static final AbilityManager INSTANCE = new AbilityManager();
    public static AbilityManager getInstance() { return INSTANCE; }

    private AbilityManager() {
        lastCastTick = new long[Ability.values().length];
        Arrays.fill(lastCastTick, -1L);
    }

    /**
     * The built-in abilities, in cast-priority order (Totem before Fire Veil).
     * {@code itemName} is the substring matched against the configured slot's item so a
     * mis-set slot never fires; {@code activeSeconds} is the effect duration, used only for
     * the HUD "on" indicator.
     */
    public enum Ability {
        TOTEM("Totem", "Totem of Corruption", 300),
        FIRE_VEIL("Fire Veil", "Fire Veil", 5);

        public final String label;
        public final String itemName;
        public final int    activeSeconds;

        Ability(String label, String itemName, int activeSeconds) {
            this.label = label;
            this.itemName = itemName;
            this.activeSeconds = activeSeconds;
        }
    }

    /** Cast-priority iteration order. */
    private static final Ability[] ORDER = { Ability.TOTEM, Ability.FIRE_VEIL };

    /** Last cast tick per ability (indexed by ordinal); -1 = never cast this session. */
    private final long[] lastCastTick;

    // ── Human-ish spacing between sequence steps (ticks) ───────────────────────
    private static int switchDelay() { return 3 + (int) (Math.random() * 4); } // 3–6 ticks
    private static int useDelay()    { return 2 + (int) (Math.random() * 3); } // 2–4 ticks

    private static AbilityConfig cfgFor(Ability a, FishingConfig cfg) {
        return a == Ability.TOTEM ? cfg.getTotem() : cfg.getFireVeil();
    }

    private boolean isReady(Ability a, FishingConfig cfg) {
        long last = lastCastTick[a.ordinal()];
        if (last < 0) return true;
        long cdTicks = (long) (cfgFor(a, cfg).cooldownSeconds * 20);
        return Scheduler.getCurrentTick() - last >= cdTicks;
    }

    /** True only if the configured slot actually holds this ability's item. */
    private boolean slotHasItem(Ability a, AbilityConfig ac) {
        int idx = ac.slot - 1; // 1-based config → 0-based hotbar index
        if (idx < 0 || idx > 7) return false; // slot 9 (index 8) is the menu, never usable
        String name = InventoryInfo.getHotbarSlot(idx).displayName();
        return name != null && name.toLowerCase().contains(a.itemName.toLowerCase());
    }

    /**
     * Runs any due abilities after a catch, then invokes {@code onDone} once back on the rod
     * slot. If none are due, {@code onDone} runs immediately (so the normal recast proceeds
     * with no delay). {@code atCap} = the tracked sea-creature count has reached the cap.
     */
    public void runDueAbilities(boolean atCap, Runnable onDone) {
        FishingConfig cfg = FishingConfig.getInstance();

        List<Ability> due = new ArrayList<>(2);
        for (Ability a : ORDER) {
            AbilityConfig ac = cfgFor(a, cfg);
            if (ac.mode == AbilityMode.OFF) continue;
            boolean triggered = ac.mode == AbilityMode.CONSTANT
                    || (ac.mode == AbilityMode.AT_CAP && atCap);
            if (!triggered) continue;
            if (!isReady(a, cfg)) continue;
            if (!slotHasItem(a, ac)) continue;
            due.add(a);
        }

        if (due.isEmpty()) {
            onDone.run();
            return;
        }

        // Remember the current (rod) slot so we can return to it at the end.
        final int rodSlot = InventoryInfo.getSelectedSlot();

        int t = 0;
        for (Ability a : due) {
            final int idx = cfgFor(a, cfg).slot - 1;
            final Ability fa = a;
            Scheduler.schedule(t, () -> InventoryActions.switchToSlot(idx));
            t += switchDelay();
            Scheduler.schedule(t, () -> {
                InteractionActions.useItem();
                lastCastTick[fa.ordinal()] = Scheduler.getCurrentTick();
                PoseidonLogger.getInstance().logInfo("Ability used: " + fa.label);
            });
            t += useDelay();
        }

        // Switch back to the rod once (not between abilities), then let the caller recast.
        Scheduler.schedule(t, () -> InventoryActions.switchToSlot(rodSlot));
        t += switchDelay();
        Scheduler.schedule(t, onDone);
    }

    // ── HUD state ──────────────────────────────────────────────────────────────

    /** Whether this ability is enabled (mode is not OFF) — drives whether the HUD shows it. */
    public boolean isEnabled(Ability a) {
        return cfgFor(a, FishingConfig.getInstance()).mode != AbilityMode.OFF;
    }

    /** Seconds left in the active-effect window (>0 means "on"), else 0. */
    public int activeSecondsLeft(Ability a) {
        long last = lastCastTick[a.ordinal()];
        if (last < 0) return 0;
        long leftTicks = a.activeSeconds * 20L - (Scheduler.getCurrentTick() - last);
        return leftTicks > 0 ? (int) Math.ceil(leftTicks / 20.0) : 0;
    }

    /** Seconds until the ability is ready to re-fire (>0 means cooling down), else 0. */
    public int cooldownSecondsLeft(Ability a) {
        long last = lastCastTick[a.ordinal()];
        if (last < 0) return 0;
        long cdTicks = (long) (cfgFor(a, FishingConfig.getInstance()).cooldownSeconds * 20);
        long leftTicks = cdTicks - (Scheduler.getCurrentTick() - last);
        return leftTicks > 0 ? (int) Math.ceil(leftTicks / 20.0) : 0;
    }
}
