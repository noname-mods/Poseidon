package com.poseidon.core;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.playerapi.SoundActions;
import com.playerapi.config.annotations.Button;
import com.playerapi.config.annotations.ColorPicker;
import com.playerapi.config.annotations.ConfigAccordion;
import com.playerapi.config.annotations.ConfigLayout;
import com.playerapi.config.annotations.ConfigList;
import com.playerapi.config.annotations.ConfigOption;
import com.playerapi.config.annotations.Dropdown;
import com.playerapi.config.annotations.OnChange;
import com.playerapi.config.annotations.ShowIf;
import com.playerapi.config.annotations.Slider;
import com.playerapi.config.annotations.TextField;
import com.playerapi.config.annotations.Toggle;
import com.playerapi.config.theme.ConfigStyle;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigLayout({
        @ConfigLayout.Category(name = "Fishing"),
        @ConfigLayout.Category(name = "Sea Creatures"),
        @ConfigLayout.Category(name = "Hook & Water"),
        @ConfigLayout.Category(name = "Abilities"),
        @ConfigLayout.Category(name = "Triggers"),
        @ConfigLayout.Category(name = "Bait"),
        @ConfigLayout.Category(name = "Alerts"),
        @ConfigLayout.Category(name = "HUD"),
        @ConfigLayout.Category(name = "Updates"),
        @ConfigLayout.Category(name = "Developer", color = 0xFFFF5555),
})
public class FishingConfig {

    // ── Known islands — must be first; INSTANCE init calls defaultCapsByArea() ─
    public static final List<String> KNOWN_AREAS = List.of(
            "Backwater Bayou", "Crimson Isle", "Galatea",
            "Hub", "Jerry's Workshop", "The Park");

    private static final FishingConfig INSTANCE = new FishingConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir().resolve("poseidon/config.json");

    /**
     * Version history:
     *   0 — initial release (biteAlertSoundId / biteAlertVolume / biteAlertPitch flat fields)
     *   1 — flat sound fields → AlarmSound object; triggerLevels added
     *   2 — single seaCreatureCap → seaCreatureCapByArea map (per-island caps)
     *   3 — autoRecast + recastDelayMinMs/MaxMs added; dontRecast/stopBot per trigger
     *   4 — recastDecisionTicks added (was hardcoded constant)
     *   5 — despawnWarningEnabled / despawnWarningMinutes / despawnWarningSound added
     *   6 — hookStuckDetectionEnabled / hookStuckMaxDistance / hookStuckSound added
     *   7 — added updateCheckEnabled (default true)
     *   8 — seaCreatureCapByArea removed; cap standardised to SEA_CREATURE_CAP = 10 (Hypixel-wide)
     *   9 — baitHudVisible / baitLowThreshold / baitLowAlertSound / baitSwitchAlertSound added
     *  10 — rebootAlertEnabled / rebootAlertSound / fishingStatsHudVisible added
     *  11 — hookStuckAutoRecast added (separate from global autoRecast)
     *  12 — slugfishMode / slugPet added
     *  13 — goldenFishAlertEnabled / goldenFishPhrase / goldenFishTitleText / goldenFishSound added
     *  14 — hudX / hudY / hudScale added (shared HUD editor layout)
     *  15 — per-stat HUD toggles (speed / DHC / SCC / treasure)
     *  16 — log split into its own movable HUD element (logVisible + logHud layout)
     *  17 — notInWaterRecastEnabled (default true) + logBobberParticles (default false)
     */
    private static final int CURRENT_VERSION = 19;

    /** Hypixel-standardised sea creature cap — same on every island. */
    public static final int SEA_CREATURE_CAP = 10;
    private int configVersion = CURRENT_VERSION;

    // ── Detection ─────────────────────────────────────────────────────────────
    @ConfigOption(category = "Fishing", name = "Detection Radius", desc = "Radius around the bobber to look for the !!! bite signal.")
    @Slider(min = 1.0, max = 8.0, step = 0.5)
    private double detectionRadius = 4.0;

    // ── Reaction delay ────────────────────────────────────────────────────────
    @ConfigOption(category = "Fishing", name = "Reaction Delay Min (ms)", desc = "Minimum human-like delay before reeling after a bite.")
    @Slider(min = 0, max = 2000, step = 10)
    private int reactionDelayMinMs = 180;
    @ConfigOption(category = "Fishing", name = "Reaction Delay Max (ms)", desc = "Maximum human-like delay before reeling after a bite.")
    @Slider(min = 0, max = 2000, step = 10)
    private int reactionDelayMaxMs = 700;

    // ── Auto recast ───────────────────────────────────────────────────────────
    /**
     * When true (default), the bot automatically recasts after each catch.
     * Individual triggers can suppress a recast via their dontRecast flag.
     * Set to false to never auto-recast (manual casts only).
     */
    @ConfigOption(category = "Fishing", name = "Auto Recast", desc = "Automatically recast after each catch (triggers can suppress it).")
    @Toggle
    private boolean autoRecast          = true;
    @ConfigOption(category = "Fishing", name = "Recast Delay Min (ms)", desc = "Minimum delay before an auto-recast.")
    @Slider(min = 0, max = 3000, step = 50)
    @ShowIf("autoRecast")
    private int     recastDelayMinMs    = 200;
    @ConfigOption(category = "Fishing", name = "Recast Delay Max (ms)", desc = "Maximum delay before an auto-recast.")
    @Slider(min = 0, max = 3000, step = 50)
    @ShowIf("autoRecast")
    private int     recastDelayMaxMs    = 600;
    /** Base ms between each ability step (switch to slot → use → switch back) — a human-ish pace. */
    @ConfigOption(category = "Abilities", name = "Ability Action Delay (ms)", desc = "Pace between ability steps (switch → use → switch back).")
    @Slider(min = 100, max = 1000, step = 50)
    private int     abilityActionDelayMs = 400;
    /**
     * Ticks to wait after a reel-in before deciding whether to recast.
     * Gives post-catch chat time to arrive so triggers can suppress the recast.
     * Low-ping players can reduce this; high-ping players should increase it.
     * Default 10 ticks (500 ms) covers most connections up to ~300 ms ping.
     */
    @ConfigOption(category = "Fishing", name = "Recast Decision Window (ticks)", desc = "Ticks to wait after reeling before recasting (lets triggers arrive). 20 = 1s.")
    @Slider(min = 1, max = 40, step = 1)
    private int     recastDecisionTicks = 10;

    // ── Bite alert sound ──────────────────────────────────────────────────────
    @ConfigOption(category = "Triggers", name = "Bite Alert Sound", desc = "Plays on a bite (duration 0 = silent/off).")
    @ConfigAccordion(expanded = false)
    private AlarmSound biteAlertSound = AlarmSound.defaultBite();

    // ── Sea creature tracking ──────────────────────────────────────────────────
    @ConfigOption(category = "Sea Creatures", name = "Track Sea Creatures", desc = "Track sea creatures that spawn after your catches.")
    @Toggle
    private boolean trackSeaCreatures  = true;
    /** Radius around the bobber to scan for new sea creature name plates. */
    @ConfigOption(category = "Sea Creatures", name = "Creature Scan Radius", desc = "Radius around the bobber to scan for new sea-creature name plates.")
    @Slider(min = 4.0, max = 24.0, step = 1.0)
    private double  creatureScanRadius = 12.0;
    @ConfigOption(category = "Sea Creatures", name = "Cap Reached Sound", desc = "Plays when the sea-creature cap is reached.")
    @ConfigAccordion(expanded = false)
    private AlarmSound seaCreatureCapSound = new AlarmSound(
            "minecraft:entity.player.levelup", 1.0, 0.8, 10, 20);
    /**
     * Debug tool: when true, the sea-creature catch registry is read from the
     * live-editable {@code config/poseidon/sea_creature_catches.json} instead of the
     * bundled default. Off for normal use. See {@link SeaCreatureCatches}.
     */
    @ConfigOption(category = "Developer", name = "Catch Registry Debug JSON", desc = "Read the sea-creature catch registry from the live-editable JSON instead of the bundled default.")
    @Toggle
    private boolean catchRegistryDebugJson = false;

    /**
     * Glow the sea creatures you caught, so they stand out from other players' mobs.
     * Client-side outline only (no box) — see the glow mixins.
     */
    @ConfigOption(category = "Sea Creatures", name = "Highlight Sea Creatures", desc = "Glow the sea creatures you caught (client-side outline).")
    @Toggle
    private boolean highlightSeaCreatures = false;
    /** Glow colour for your tracked sea creatures, as packed RGB (no alpha). */
    @ConfigOption(category = "Sea Creatures", name = "Highlight Color", desc = "Glow colour for your tracked sea creatures.")
    @ColorPicker
    @ShowIf("highlightSeaCreatures")
    private int     seaCreatureHighlightColor = 0x55FFFF;

    // ── Despawn warning ────────────────────────────────────────────────────────
    /** Fire a warning alert when a tracked creature has been alive this many minutes. */
    @ConfigOption(category = "Sea Creatures", name = "Despawn Warning", desc = "Warn when a tracked creature has been alive too long.")
    @Toggle
    private boolean    despawnWarningEnabled = true;
    @ConfigOption(category = "Sea Creatures", name = "Despawn Warning (minutes)", desc = "Minutes alive before the despawn warning fires.")
    @Slider(min = 1, max = 30, step = 1)
    @ShowIf("despawnWarningEnabled")
    private int        despawnWarningMinutes = 5;
    @ConfigOption(category = "Sea Creatures", name = "Despawn Warning Sound", desc = "Sound for the despawn warning.")
    @ConfigAccordion(expanded = false)
    private AlarmSound despawnWarningSound   = new AlarmSound(
            "minecraft:block.bell.use", 1.0, 0.8, 5, 20);

    // ── Hook-stuck detection ───────────────────────────────────────────────────
    /**
     * When true, Poseidon watches for the bobber drifting horizontally after
     * landing. A drift of more than {@code hookStuckMaxDistance} blocks indicates
     * the hook has attached to a moving mob rather than landing in water.
     * The rod is automatically reeled in and recast.
     */
    @ConfigOption(category = "Hook & Water", name = "Hook-Stuck Detection", desc = "Detect the hook sticking to a moving mob (bobber drifts horizontally).")
    @Toggle
    private boolean    hookStuckDetectionEnabled = true;
    /** Horizontal drift threshold in blocks. Normal bobbing is < 0.2 blocks. */
    @ConfigOption(category = "Hook & Water", name = "Hook-Stuck Max Drift", desc = "Horizontal drift (blocks) that counts as stuck. Normal bobbing < 0.2.")
    @Slider(min = 0.5, max = 5.0, step = 0.1)
    @ShowIf("hookStuckDetectionEnabled")
    private double     hookStuckMaxDistance      = 1.5;
    /**
     * When true, automatically reel in and recast after drift is detected,
     * independent of the global {@link #autoRecast} setting.
     * The alert sound still plays regardless of this flag.
     */
    @ConfigOption(category = "Hook & Water", name = "Hook-Stuck Auto Recast", desc = "Reel in + recast when a stuck hook is detected (independent of Auto Recast).")
    @Toggle
    @ShowIf("hookStuckDetectionEnabled")
    private boolean    hookStuckAutoRecast       = true;
    @ConfigOption(category = "Hook & Water", name = "Hook-Stuck Sound", desc = "Sound played when a stuck hook is detected.")
    @ConfigAccordion(expanded = false)
    private AlarmSound hookStuckSound            = new AlarmSound(
            "minecraft:entity.villager.no", 1.0, 1.2, 2, 10);

    // ── "Not in water" recovery ────────────────────────────────────────────────
    /**
     * When true, Poseidon watches for the blue "bobber not in water" particle burst around the bobber
     * (Hypixel's in-water/lava detection sometimes fails, so the bobber never bites) and force-recasts
     * to recover. See {@link com.poseidon.core.ParticleWatch} + the FishingManager check.
     */
    @ConfigOption(category = "Hook & Water", name = "\"Not in Water\" Recast", desc = "Force-recast when the bobber lands out of water/lava (particle burst detected).")
    @Toggle
    private boolean notInWaterRecastEnabled = true;
    /**
     * Debug: log the distinct particle type ids seen around the bobber while waiting. Used to confirm
     * exactly which particle Hypixel spawns for the "not in water" state so the trigger set is precise.
     */
    @ConfigOption(category = "Developer", name = "Log Bobber Particles", desc = "Log particle type ids seen around the bobber (to tune 'not in water' detection).")
    @Toggle
    private boolean logBobberParticles = false;

    // ── Chat triggers ─────────────────────────────────────────────────────────
    /** Ordered list of trigger levels. First match wins. */
    @ConfigList(category = "Triggers", nameField = "name", itemLabel = "Trigger",
            addLabel = "+ Add Trigger", removeLabel = "× Remove trigger")
    private List<TriggerLevel> triggerLevels = defaultTriggerLevels();

    // ── Update checker ────────────────────────────────────────────────────────
    @ConfigOption(category = "Updates", name = "Update Check", desc = "On world join, check GitHub for a newer Poseidon release.")
    @Toggle
    private boolean updateCheckEnabled = true;

    // ── Bait monitoring ──────────────────────────────────────────────────────────
    @ConfigOption(category = "HUD", name = "Bait HUD Line", desc = "Show the bait count/name line on the HUD.")
    @Toggle
    private boolean    baitHudVisible       = true;
    @ConfigOption(category = "Bait", name = "Low Bait Threshold", desc = "Alert when bait remaining drops to this count.")
    @Slider(min = 1, max = 64, step = 1)
    private int        baitLowThreshold     = 5;
    @ConfigOption(category = "Bait", name = "Low Bait Sound", desc = "Sound when bait runs low.")
    @ConfigAccordion(expanded = false)
    private AlarmSound baitLowAlertSound    = new AlarmSound(
            "minecraft:entity.experience_orb.pickup", 1.0, 0.5, 3, 20);
    @ConfigOption(category = "Bait", name = "Bait Switch Sound", desc = "Sound when the bait type changes.")
    @ConfigAccordion(expanded = false)
    private AlarmSound baitSwitchAlertSound = new AlarmSound(
            "minecraft:block.bell.use", 1.0, 0.8, 2, 20);

    // -- Reboot alert ------------------------------------------------------
    @ConfigOption(category = "Alerts", name = "Reboot Alert", desc = "Play an alarm on the Hypixel server-reboot warning.")
    @Toggle
    private boolean    rebootAlertEnabled = true;
    @ConfigOption(category = "Alerts", name = "Reboot Alert Sound", desc = "Sound looped on the reboot warning.")
    @ConfigAccordion(expanded = false)
    private AlarmSound rebootAlertSound   = new AlarmSound(
            "minecraft:block.bell.use", 1.0, 1.0, 300, 40);

    /** Live-HUD style: Custom textured / Toned-down transparent / Flat (classic). */
    @ConfigOption(category = "HUD", name = "HUD Style", desc = "Live HUD look: Custom (ocean panel), Toned (transparent), or Flat (classic).")
    @Dropdown
    private ConfigStyle hudStyle = ConfigStyle.CUSTOM;

    // -- Fishing stats HUD ------------------------------------------------
    /** Master toggle for the whole stats section. */
    @ConfigOption(category = "HUD", name = "Fishing Stats", desc = "Show the fishing-stats section on the HUD.")
    @Toggle
    private boolean fishingStatsHudVisible = true;
    /** Per-line toggles. A line shows only when its toggle is on AND the stat appears in the tab list. */
    @ConfigOption(category = "HUD", name = "Stat: Speed", desc = "Show the Speed stat line (if present in tab).")
    @Toggle @ShowIf("fishingStatsHudVisible")
    private boolean statSpeedHudVisible    = true;
    @ConfigOption(category = "HUD", name = "Stat: DHC", desc = "Show the Double-Hook Chance line (if present in tab).")
    @Toggle @ShowIf("fishingStatsHudVisible")
    private boolean statDhcHudVisible      = true;
    @ConfigOption(category = "HUD", name = "Stat: SCC", desc = "Show the Sea-Creature Chance line (if present in tab).")
    @Toggle @ShowIf("fishingStatsHudVisible")
    private boolean statSccHudVisible      = true;
    @ConfigOption(category = "HUD", name = "Stat: Treasure", desc = "Show the Treasure Chance line (if present in tab).")
    @Toggle @ShowIf("fishingStatsHudVisible")
    private boolean statTreasureHudVisible = true;

    // ── HUD layout (position + scale, edited via the shared HUD editor) ─────────
    /** Top-left screen position + uniform scale of the Poseidon HUD panel. */
    private float hudX     = 4f;
    private float hudY     = 4f;
    private float hudScale = 1.0f;

    // ── Log panel (its own movable/scalable HUD element) ────────────────────────
    /** Whether the log panel is shown at all. */
    @ConfigOption(category = "HUD", name = "Show Log", desc = "Show the scrolling log panel.")
    @Toggle
    private boolean logVisible  = true;
    /** Opens the shared HUD editor to move/scale the panels. */
    @ConfigOption(category = "HUD", name = "Edit HUD Position", desc = "Move/scale the HUD + log panels.")
    @Button(text = "Open HUD editor")
    public transient final Runnable editHudAction =
            () -> com.poseidon.gui.PoseidonHudRenderer.openEditor();
    private float   logHudX     = 4f;
    private float   logHudY     = 140f;
    private float   logHudScale = 1.0f;

    // ── Slugfish mode ─────────────────────────────────────────────────────────
    /**
     * When true, the bot ignores all reel-in signals until the slugfish timer
     * has elapsed since the cast (21 s normally, 11 s with the Slug Pet).
     * Should only be enabled while actively farming the Slugfish trophy fish.
     */
    @ConfigOption(category = "Fishing", name = "Slugfish Mode", desc = "Ignore reel signals until the slugfish timer elapses (only while farming Slugfish).")
    @Toggle
    private boolean slugfishMode = false;
    /**
     * Halves the slugfish timer to 11 s (assumes a level-100 Slug Pet is
     * equipped; Poseidon does not verify the pet for you).
     */
    @ConfigOption(category = "Fishing", name = "Slug Pet", desc = "Halve the slugfish timer to 11s (assumes a level-100 Slug Pet).")
    @Toggle
    @ShowIf("slugfishMode")
    private boolean slugPet = false;

    // ── Golden Fish alert ───────────────────────────────────────────────────────
    /**
     * When true, Poseidon watches chat for the Golden Fish message. On a match it
     * shows a golden title card, stops the bot, and reels in any active cast so
     * the player can manually catch the Golden Fish. The player re-enables the bot
     * afterwards to resume normal fishing. Default OFF — when disabled this feature
     * does nothing.
     *
     * <p>Unlike the configurable chat triggers, this is checked outside the
     * post-reel catch window because the Golden Fish announcement can arrive at
     * any point while fishing, not just right after a reel-in.</p>
     */
    @ConfigOption(category = "Triggers", name = "Golden Fish Alert", desc = "Watch chat for the Golden Fish; on match show a title, stop the bot, and reel in.")
    @Toggle
    private boolean goldenFishAlertEnabled = false;
    /**
     * Comma-separated substrings identifying the Golden Fish chat message
     * (case-insensitive; any one matching fires the alert). Default matches the
     * distinctive middle of Hypixel's announcement —
     * "You spot a Golden Fish surface from beneath the lava/waves!" — so it covers
     * both the lava and water variants without risking false fires on other chat.
     */
    @ConfigOption(category = "Triggers", name = "Golden Fish Phrase", desc = "Comma-separated chat substrings that identify the Golden Fish message.")
    @TextField
    @ShowIf("goldenFishAlertEnabled")
    private String  goldenFishPhrase = "spot a Golden Fish surface";
    /** Title overlay text shown when the alert fires. Supports § / & colour codes. */
    @ConfigOption(category = "Triggers", name = "Golden Fish Title", desc = "Title overlay text shown on a Golden Fish match (supports § / & colours).")
    @TextField
    @ShowIf("goldenFishAlertEnabled")
    private String  goldenFishTitleText = "§6§lGOLDEN FISH";
    /** Alert sound played when the Golden Fish is detected. */
    @ConfigOption(category = "Triggers", name = "Golden Fish Sound", desc = "Sound played when the Golden Fish is detected.")
    @ConfigAccordion(expanded = false)
    private AlarmSound goldenFishSound = new AlarmSound(
            "minecraft:entity.player.levelup", 1.0, 1.2, 4, 20);

    // ── Fishing abilities (Fire Veil / Totem of Corruption) ────────────────────

    /** When and whether an ability item is auto-used after a catch. */
    public enum AbilityMode { OFF, CONSTANT, AT_CAP }

    /**
     * Auto-use config for one hotbar ability item. {@code slot} is the 1-based hotbar
     * slot (1–8; slot 9 / index 8 is the SkyBlock menu and is never used). {@code cooldownSeconds}
     * is the minimum gap between auto-uses — tune it below the real ability duration to
     * re-fire early for zero downtime.
     */
    public static class AbilityConfig {
        @ConfigOption(name = "Mode", desc = "OFF, CONSTANT (every catch), or AT_CAP (only at sea-creature cap).")
        @Dropdown
        public AbilityMode mode           = AbilityMode.OFF;
        @ConfigOption(name = "Hotbar Slot", desc = "1-based hotbar slot of the ability item (1–8).")
        @Slider(min = 1, max = 8, step = 1)
        public int         slot           = 1;   // 1-based, clamped to 1..8
        @ConfigOption(name = "Cooldown (s)", desc = "Minimum seconds between auto-uses (tune below the real duration for no downtime).")
        @Slider(min = 1, max = 600, step = 1)
        public double      cooldownSeconds = 6.0;
    }

    @ConfigOption(category = "Abilities", name = "Fire Veil", desc = "Auto-use config for the Fire Veil ability item.")
    @ConfigAccordion(expanded = false)
    private AbilityConfig fireVeil = defaultAbility(1, 6.0);
    @ConfigOption(category = "Abilities", name = "Totem of Corruption", desc = "Auto-use config for the Totem of Corruption.")
    @ConfigAccordion(expanded = false)
    private AbilityConfig totem    = defaultAbility(2, 300.0);

    private static AbilityConfig defaultAbility(int slot, double cooldownSeconds) {
        AbilityConfig a = new AbilityConfig();
        a.slot = slot;
        a.cooldownSeconds = cooldownSeconds;
        return a;
    }

    /** Repairs a loaded ability config (GSON may leave fields null/zero on old configs). */
    private static AbilityConfig sanitizeAbility(AbilityConfig a, int defaultSlot, double defaultCd) {
        if (a == null) return defaultAbility(defaultSlot, defaultCd);
        if (a.mode == null) a.mode = AbilityMode.OFF;
        if (a.slot < 1 || a.slot > 8) a.slot = defaultSlot;
        if (a.cooldownSeconds <= 0) a.cooldownSeconds = defaultCd;
        return a;
    }

    // ── Developer ─────────────────────────────────────────────────────────────
    @ConfigOption(category = "Developer", name = "Debug Mode", desc = "Extra diagnostics.")
    @Toggle
    private boolean debugMode = false;
    @ConfigOption(category = "Developer", name = "Log Level", desc = "How much detail the bot logs (console + file).")
    @Dropdown
    @OnChange("onLogLevelChanged")
    private LogLevel logLevel = LogLevel.WARN;

    /**
     * Log verbosity, shown as a labelled dropdown. Each constant maps to the matching {@link PoseidonLogger}
     * level, so selecting a name always sets the correct level (the old int slider's labels were reversed
     * relative to the logger's constants). Ordered least→most verbose.
     */
    public enum LogLevel {
        ERROR("Error", PoseidonLogger.LEVEL_ERROR),
        WARN ("Warn",  PoseidonLogger.LEVEL_WARN),
        INFO ("Info",  PoseidonLogger.LEVEL_INFO),
        DEBUG("Debug", PoseidonLogger.LEVEL_DEBUG);
        public final String label;
        public final int level;
        LogLevel(String label, int level) { this.label = label; this.level = level; }
        @Override public String toString() { return label; }
        /** Map a raw {@link PoseidonLogger} level int back to the enum (defaults to WARN if none match). */
        public static LogLevel fromLevel(int lv) {
            for (LogLevel e : values()) if (e.level == lv) return e;
            return WARN;
        }
    }

    private FishingConfig() {}

    public static FishingConfig getInstance() { return INSTANCE; }

    /** Chosen live-HUD style (never null). */
    public ConfigStyle getHudStyle() { return hudStyle == null ? ConfigStyle.CUSTOM : hudStyle; }


    /** @OnChange hook: the library edits the field directly, so re-apply the level to the logger. */
    private void onLogLevelChanged() {
        PoseidonLogger.getInstance().setLogLevel(logLevel.level);
    }

    // ── AlarmSound ────────────────────────────────────────────────────────────

    /**
     * Configuration for a single alarm sound event.
     * Sound IDs use the format "namespace:sound.id".
     * Browse all vanilla sounds at: https://misode.github.io/sounds/
     */
    public static class AlarmSound {
        @ConfigOption(name = "Sound ID", desc = "e.g. minecraft:entity.player.levelup")
        @TextField
        public String soundId;
        @ConfigOption(name = "Volume", desc = "0.1 – 2.0")
        @Slider(min = 0.1, max = 2.0, step = 0.05)
        public double volume;
        @ConfigOption(name = "Pitch", desc = "0.5 – 2.0 (lower = deeper)")
        @Slider(min = 0.5, max = 2.0, step = 0.05)
        public double pitch;
        @ConfigOption(name = "Duration (s)", desc = "Total seconds the alarm plays (0 = off/once).")
        @Slider(min = 0, max = 300, step = 5)
        public int    durationSeconds;
        @ConfigOption(name = "Interval (ticks)", desc = "Ticks between plays.")
        @Slider(min = 5, max = 60, step = 5)
        public int    intervalTicks;

        public AlarmSound() {}

        public AlarmSound(String soundId, double volume, double pitch,
                          int durationSeconds, int intervalTicks) {
            this.soundId         = soundId;
            this.volume          = volume;
            this.pitch           = pitch;
            this.durationSeconds = durationSeconds;
            this.intervalTicks   = intervalTicks;
        }

        public static AlarmSound defaultBite() {
            // durationSeconds = 0 → play() returns early → silent by default.
            // Users enable this via the Triggers → Bite Alert section in config.
            return new AlarmSound("minecraft:entity.experience_orb.pickup", 1.0, 1.5, 0, 15);
        }

        public static AlarmSound defaultAlert() {
            return new AlarmSound("minecraft:entity.player.levelup", 1.0, 1.0, 8, 20);
        }

        public void mergeFrom(AlarmSound src, AlarmSound defaults) {
            soundId         = (src.soundId != null && !src.soundId.isBlank()) ? src.soundId : defaults.soundId;
            volume          = src.volume > 0          ? src.volume          : defaults.volume;
            pitch           = src.pitch > 0           ? src.pitch           : defaults.pitch;
            durationSeconds = src.durationSeconds > 0 ? src.durationSeconds : defaults.durationSeconds;
            intervalTicks   = src.intervalTicks > 0   ? src.intervalTicks   : defaults.intervalTicks;
        }

        public void play() {
            if (soundId == null || soundId.isBlank() || durationSeconds <= 0) return;
            int times = Math.max(1, (durationSeconds * 20) / Math.max(1, intervalTicks));
            SoundActions.playByIdRepeated(soundId, (float) volume, (float) pitch, times, intervalTicks);
        }
    }

    // ── TriggerLevel ──────────────────────────────────────────────────────────

    /**
     * A named trigger that fires when a chat message matches any of its patterns.
     * Triggers are checked in list order — first match wins.
     *
     * {@code patterns} is stored as a comma-separated string in the config
     * (displayed as such in YACL) but accessed as a List internally.
     */
    public static class TriggerLevel {
        @ConfigOption(name = "Name", desc = "Display name for this trigger (shown as the accordion header).")
        @TextField
        public String  name     = "";
        @ConfigOption(name = "Enabled", desc = "Whether this trigger is active.")
        @Toggle
        public boolean enabled  = true;
        /** Comma-separated substrings to watch for in chat (case-insensitive). */
        @ConfigOption(name = "Patterns", desc = "Comma-separated chat substrings to watch for (case-insensitive; any match fires).")
        @TextField
        public String  patterns = "";
        @ConfigOption(name = "Sound", desc = "Sound played when this trigger fires.")
        @ConfigAccordion(expanded = false)
        public AlarmSound sound = AlarmSound.defaultBite();
        /** Reserved for future actions (e.g. recast, stop, alert). */
        public String action    = "";
        /** When true, suppress the auto-recast after this trigger fires. */
        @ConfigOption(name = "Don't Recast", desc = "Suppress the auto-recast after this trigger fires.")
        @Toggle
        public boolean dontRecast = false;
        /** When true, deactivate the bot (HUD stays open) after this trigger fires. */
        @ConfigOption(name = "Stop Bot", desc = "Deactivate the bot after this trigger fires (HUD stays open).")
        @Toggle
        public boolean stopBot    = false;
        /** Show a Minecraft title overlay when this trigger fires. */
        @ConfigOption(name = "Show Title", desc = "Show a Minecraft title overlay when this trigger fires.")
        @Toggle
        public boolean showTitle = false;
        /**
         * Text displayed as the MC title when {@code showTitle} is true.
         * Leave blank to use the trigger's Name field instead.
         */
        @ConfigOption(name = "Title Text", desc = "Title text when Show Title is on (blank = use the Name).")
        @TextField
        @ShowIf("showTitle")
        public String  titleText = "";

        public TriggerLevel() {}

        public TriggerLevel(String name, boolean enabled, String patterns, AlarmSound sound) {
            this.name     = name;
            this.enabled  = enabled;
            this.patterns = patterns;
            this.sound    = sound;
        }

        /**
         * Returns true if the given chat text matches any pattern in this trigger.
         */
        public boolean matches(String chatText) {
            if (!enabled || patterns == null || patterns.isBlank()) return false;
            String lower = chatText.toLowerCase();
            for (String pat : patterns.split(",")) {
                String p = pat.trim().toLowerCase();
                if (!p.isEmpty() && lower.contains(p)) return true;
            }
            return false;
        }
    }

    private static List<TriggerLevel> defaultTriggerLevels() {
        // Triggers are now a dynamic add/remove list — seed one empty, disabled starter.
        List<TriggerLevel> list = new ArrayList<>();
        TriggerLevel t = new TriggerLevel();
        t.enabled  = false;
        t.name     = "";
        t.patterns = "";
        t.sound    = AlarmSound.defaultBite();
        list.add(t);
        return list;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(CONFIG_FILE)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null) {
                json = migrate(json);
                FishingConfig loaded = GSON.fromJson(json, FishingConfig.class);
                if (loaded != null) {
                    this.configVersion      = CURRENT_VERSION;
                    this.detectionRadius    = loaded.detectionRadius > 0 ? loaded.detectionRadius : 4.0;
                    this.reactionDelayMinMs = loaded.reactionDelayMinMs > 0 ? loaded.reactionDelayMinMs : 180;
                    this.reactionDelayMaxMs = loaded.reactionDelayMaxMs > 0 ? loaded.reactionDelayMaxMs : 700;
                    this.autoRecast         = loaded.autoRecast;
                    this.abilityActionDelayMs = loaded.abilityActionDelayMs > 0 ? loaded.abilityActionDelayMs : 400;
                    this.recastDelayMinMs   = loaded.recastDelayMinMs  > 0 ? loaded.recastDelayMinMs  : 500;
                    this.recastDelayMaxMs   = loaded.recastDelayMaxMs  > 0 ? loaded.recastDelayMaxMs  : 1500;
                    this.debugMode          = loaded.debugMode;
                    this.logLevel           = loaded.logLevel != null ? loaded.logLevel : LogLevel.WARN;
                    PoseidonLogger.getInstance().setLogLevel(this.logLevel.level);

                    if (loaded.biteAlertSound != null)
                        this.biteAlertSound.mergeFrom(loaded.biteAlertSound, AlarmSound.defaultBite());

                    this.trackSeaCreatures  = loaded.trackSeaCreatures;
                    this.creatureScanRadius = loaded.creatureScanRadius > 0 ? loaded.creatureScanRadius : 12.0;
                    this.catchRegistryDebugJson = loaded.catchRegistryDebugJson;
                    this.highlightSeaCreatures = loaded.highlightSeaCreatures;
                    this.seaCreatureHighlightColor = loaded.seaCreatureHighlightColor != 0
                            ? loaded.seaCreatureHighlightColor : 0x55FFFF;
                    this.fireVeil = sanitizeAbility(loaded.fireVeil, 1, 6.0);
                    this.totem    = sanitizeAbility(loaded.totem, 2, 300.0);

                    if (loaded.seaCreatureCapSound != null)
                        this.seaCreatureCapSound.mergeFrom(loaded.seaCreatureCapSound,
                                new AlarmSound("minecraft:entity.player.levelup", 1.0, 0.8, 10, 20));

                    if (loaded.triggerLevels != null && !loaded.triggerLevels.isEmpty())
                        this.triggerLevels = loaded.triggerLevels;

                    this.recastDecisionTicks    = loaded.recastDecisionTicks > 0 ? loaded.recastDecisionTicks : 10;
                    this.despawnWarningEnabled  = loaded.despawnWarningEnabled;
                    this.despawnWarningMinutes  = loaded.despawnWarningMinutes > 0 ? loaded.despawnWarningMinutes : 5;
                    if (loaded.despawnWarningSound != null)
                        this.despawnWarningSound.mergeFrom(loaded.despawnWarningSound,
                                new AlarmSound("minecraft:block.bell.use", 1.0, 0.8, 5, 20));

                    this.hookStuckDetectionEnabled = loaded.hookStuckDetectionEnabled;
                    this.hookStuckMaxDistance      = loaded.hookStuckMaxDistance > 0 ? loaded.hookStuckMaxDistance : 1.5;
                    this.hookStuckAutoRecast       = loaded.hookStuckAutoRecast;
                    if (loaded.hookStuckSound != null)
                        this.hookStuckSound.mergeFrom(loaded.hookStuckSound,
                                new AlarmSound("minecraft:entity.villager.no", 1.0, 1.2, 2, 10));

                    this.notInWaterRecastEnabled = loaded.notInWaterRecastEnabled;
                    this.logBobberParticles      = loaded.logBobberParticles;

                    this.updateCheckEnabled = loaded.updateCheckEnabled;

                    this.baitHudVisible   = loaded.baitHudVisible;
                    this.baitLowThreshold = loaded.baitLowThreshold > 0 ? loaded.baitLowThreshold : 5;
                    if (loaded.baitLowAlertSound != null)
                        this.baitLowAlertSound.mergeFrom(loaded.baitLowAlertSound,
                                new AlarmSound("minecraft:entity.experience_orb.pickup", 1.0, 0.5, 3, 20));
                    if (loaded.baitSwitchAlertSound != null)
                        this.baitSwitchAlertSound.mergeFrom(loaded.baitSwitchAlertSound,
                                new AlarmSound("minecraft:block.bell.use", 1.0, 0.8, 2, 20));
                    this.rebootAlertEnabled = loaded.rebootAlertEnabled;
                    if (loaded.rebootAlertSound != null)
                        this.rebootAlertSound.mergeFrom(loaded.rebootAlertSound,
                                new AlarmSound("minecraft:block.bell.use", 1.0, 1.0, 300, 40));
                    this.hudStyle               = loaded.hudStyle != null ? loaded.hudStyle : ConfigStyle.CUSTOM;
                    this.fishingStatsHudVisible = loaded.fishingStatsHudVisible;
                    this.statSpeedHudVisible    = loaded.statSpeedHudVisible;
                    this.statDhcHudVisible      = loaded.statDhcHudVisible;
                    this.statSccHudVisible      = loaded.statSccHudVisible;
                    this.statTreasureHudVisible = loaded.statTreasureHudVisible;
                    this.hudX     = loaded.hudX;
                    this.hudY     = loaded.hudY;
                    this.hudScale = loaded.hudScale > 0 ? loaded.hudScale : 1.0f;
                    this.logVisible  = loaded.logVisible;
                    this.logHudX     = loaded.logHudX;
                    this.logHudY     = loaded.logHudY;
                    this.logHudScale = loaded.logHudScale > 0 ? loaded.logHudScale : 1.0f;
                    this.slugfishMode           = loaded.slugfishMode;
                    this.slugPet                = loaded.slugPet;

                    this.goldenFishAlertEnabled = loaded.goldenFishAlertEnabled;
                    if (loaded.goldenFishPhrase != null && !loaded.goldenFishPhrase.isBlank())
                        this.goldenFishPhrase = loaded.goldenFishPhrase;
                    if (loaded.goldenFishTitleText != null && !loaded.goldenFishTitleText.isBlank())
                        this.goldenFishTitleText = loaded.goldenFishTitleText;
                    if (loaded.goldenFishSound != null)
                        this.goldenFishSound.mergeFrom(loaded.goldenFishSound,
                                new AlarmSound("minecraft:entity.player.levelup", 1.0, 1.2, 4, 20));
                }
            }
        } catch (Exception e) {
            PoseidonLogger.getInstance().logError("FishingConfig: Failed to load: " + e.getMessage());
        }
        save();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            PoseidonLogger.getInstance().logError("FishingConfig: Failed to save: " + e.getMessage());
        }
    }

    // ── Migration ─────────────────────────────────────────────────────────────

    private static JsonObject migrate(JsonObject json) {
        int version = json.has("configVersion") ? json.get("configVersion").getAsInt() : 0;
        if (version < 1) json = migrateV0toV1(json);
        if (version < 2) json = migrateV1toV2(json);
        if (version < 3) json = migrateV2toV3(json);
        if (version < 4) json = migrateV3toV4(json);
        if (version < 5) json = migrateV4toV5(json);
        if (version < 6) json = migrateV5toV6(json);
        if (version < 7) json = migrateV6toV7(json);
        if (version < 8) json = migrateV7toV8(json);
        if (version < 9) json = migrateV8toV9(json);
        if (version < 10) json = migrateV9toV10(json);
        if (version < 11) json = migrateV10toV11(json);
        if (version < 12) json = migrateV11toV12(json);
        if (version < 13) json = migrateV12toV13(json);
        if (version < 14) json = migrateV13toV14(json);
        if (version < 15) json = migrateV14toV15(json);
        if (version < 16) json = migrateV15toV16(json);
        if (version < 17) json = migrateV16toV17(json);
        if (version < 18) json = migrateV17toV18(json);
        if (version < 19) json = migrateV18toV19(json);
        json.addProperty("configVersion", CURRENT_VERSION);
        return json;
    }

    private static JsonObject migrateV17toV18(JsonObject json) {
        if (!json.has("abilityActionDelayMs")) json.addProperty("abilityActionDelayMs", 400);
        return json;
    }

    /**
     * v18 → v19: {@code logLevel} changed from an int slider (whose labels were reversed relative to
     * PoseidonLogger's constants) to a {@code LogLevel} enum. Map the old numeric value through the logger
     * constants to the enum name so it deserializes correctly; anything that doesn't map falls back to WARN.
     */
    private static JsonObject migrateV18toV19(JsonObject json) {
        if (json.has("logLevel") && json.get("logLevel").isJsonPrimitive()
                && json.get("logLevel").getAsJsonPrimitive().isNumber()) {
            json.addProperty("logLevel", LogLevel.fromLevel(json.get("logLevel").getAsInt()).name());
        }
        return json;
    }

    /** v16 -> v17: "not in water" recovery. notInWaterRecastEnabled defaults true (inject so GSON
     *  doesn't leave it false); logBobberParticles defaults false (GSON handles that). */
    private static JsonObject migrateV16toV17(JsonObject json) {
        if (!json.has("notInWaterRecastEnabled")) json.addProperty("notInWaterRecastEnabled", true);
        return json;
    }

    /** v15 -> v16: log split into its own HUD element. Inject visible + a default position/scale so the
     *  log doesn't land at 0,0 with GSON's 0.0 scale (invisible). */
    private static JsonObject migrateV15toV16(JsonObject json) {
        if (!json.has("logVisible"))  json.addProperty("logVisible", true);
        if (!json.has("logHudX"))     json.addProperty("logHudX", 4.0f);
        if (!json.has("logHudY"))     json.addProperty("logHudY", 140.0f);
        if (!json.has("logHudScale")) json.addProperty("logHudScale", 1.0f);
        return json;
    }

    /** v14 -> v15: per-stat HUD toggles added (default true — inject so GSON doesn't leave them false). */
    private static JsonObject migrateV14toV15(JsonObject json) {
        if (!json.has("statSpeedHudVisible"))    json.addProperty("statSpeedHudVisible", true);
        if (!json.has("statDhcHudVisible"))      json.addProperty("statDhcHudVisible", true);
        if (!json.has("statSccHudVisible"))      json.addProperty("statSccHudVisible", true);
        if (!json.has("statTreasureHudVisible")) json.addProperty("statTreasureHudVisible", true);
        return json;
    }

    /** v13 -> v14: HUD layout fields added. Inject the current default position/scale so an
     *  upgraded config keeps the panel where it always was (top-left, 1.0x) — and, critically,
     *  so hudScale is never left as GSON's 0.0 (which would render the panel invisibly small). */
    private static JsonObject migrateV13toV14(JsonObject json) {
        if (!json.has("hudX"))     json.addProperty("hudX", 4.0f);
        if (!json.has("hudY"))     json.addProperty("hudY", 4.0f);
        if (!json.has("hudScale")) json.addProperty("hudScale", 1.0f);
        return json;
    }

    /** v12 -> v13: Golden Fish alert fields added. goldenFishAlertEnabled defaults
     *  false (GSON handles that); inject the phrase/title defaults for clarity so
     *  an upgraded config shows the intended values rather than empty strings. */
    private static JsonObject migrateV12toV13(JsonObject json) {
        if (!json.has("goldenFishAlertEnabled")) json.addProperty("goldenFishAlertEnabled", false);
        if (!json.has("goldenFishPhrase"))       json.addProperty("goldenFishPhrase", "spot a Golden Fish surface");
        if (!json.has("goldenFishTitleText"))    json.addProperty("goldenFishTitleText", "§6§lGOLDEN FISH");
        return json;
    }

    /** v3 → v4: recastDecisionTicks added (was a hardcoded constant of 40, now 10). */
    private static JsonObject migrateV3toV4(JsonObject json) {
        if (!json.has("recastDecisionTicks")) json.addProperty("recastDecisionTicks", 10);
        return json;
    }

    /** v4 → v5: despawn warning fields added. */
    private static JsonObject migrateV4toV5(JsonObject json) {
        if (!json.has("despawnWarningEnabled")) json.addProperty("despawnWarningEnabled", true);
        if (!json.has("despawnWarningMinutes"))  json.addProperty("despawnWarningMinutes", 5);
        return json;
    }

    /** v5 → v6: hook-stuck detection fields added. */
    private static JsonObject migrateV5toV6(JsonObject json) {
        if (!json.has("hookStuckDetectionEnabled")) json.addProperty("hookStuckDetectionEnabled", true);
        if (!json.has("hookStuckMaxDistance"))       json.addProperty("hookStuckMaxDistance", 1.5);
        return json;
    }

    /** v6 → v7: updateCheckEnabled added (default true). */
    private static JsonObject migrateV6toV7(JsonObject json) {
        if (!json.has("updateCheckEnabled")) json.addProperty("updateCheckEnabled", true);
        return json;
    }

    /** v7 → v8: seaCreatureCapByArea removed; cap is now the Hypixel-standard 5 on all islands. */
    private static JsonObject migrateV7toV8(JsonObject json) {
        json.remove("seaCreatureCapByArea");
        return json;
    }

    /** v8 -> v9: bait monitoring fields added (GSON boolean defaults false, inject true for baitHudVisible). */
    private static JsonObject migrateV8toV9(JsonObject json) {
        if (!json.has("baitHudVisible"))   json.addProperty("baitHudVisible", true);
        if (!json.has("baitLowThreshold")) json.addProperty("baitLowThreshold", 5);
        return json;
    }

    /** v9 -> v10: rebootAlertEnabled + fishingStatsHudVisible added (GSON boolean defaults false, inject true). */
    private static JsonObject migrateV9toV10(JsonObject json) {
        if (!json.has("rebootAlertEnabled"))    json.addProperty("rebootAlertEnabled", true);
        if (!json.has("fishingStatsHudVisible")) json.addProperty("fishingStatsHudVisible", true);
        return json;
    }

    /** v10 -> v11: hookStuckAutoRecast added (GSON boolean defaults false, inject true). */
    private static JsonObject migrateV10toV11(JsonObject json) {
        if (!json.has("hookStuckAutoRecast")) json.addProperty("hookStuckAutoRecast", true);
        return json;
    }

    /** v11 -> v12: slugfishMode / slugPet added (both default false — GSON handles this correctly,
     *  but the migration is here for documentation and version tracking). */
    private static JsonObject migrateV11toV12(JsonObject json) {
        // No data transformation needed; GSON already defaults missing booleans to false.
        return json;
    }

    /** v1 → v2: single seaCreatureCap int → seaCreatureCapByArea map. */
    private static JsonObject migrateV1toV2(JsonObject json) {
        if (!json.has("seaCreatureCapByArea")) {
            int oldCap = json.has("seaCreatureCap") ? json.get("seaCreatureCap").getAsInt() : 10;
            com.google.gson.JsonObject caps = new com.google.gson.JsonObject();
            for (String area : KNOWN_AREAS) caps.addProperty(area, oldCap);
            json.add("seaCreatureCapByArea", caps);
        }
        json.remove("seaCreatureCap");
        return json;
    }

    /** v2 → v3: autoRecast added (GSON defaults boolean to false, so patch to true for old configs). */
    private static JsonObject migrateV2toV3(JsonObject json) {
        if (!json.has("autoRecast")) json.addProperty("autoRecast", true);
        return json;
    }

    /** v0 → v1: flat biteAlert* fields → biteAlertSound object; triggerLevels added (defaults). */
    private static JsonObject migrateV0toV1(JsonObject json) {
        if (!json.has("biteAlertSound")) {
            JsonObject sound = new JsonObject();
            sound.addProperty("soundId",
                    json.has("biteAlertSoundId") ? json.get("biteAlertSoundId").getAsString()
                                                 : "minecraft:entity.experience_orb.pickup");
            sound.addProperty("volume",
                    json.has("biteAlertVolume") ? json.get("biteAlertVolume").getAsDouble() : 1.0);
            sound.addProperty("pitch",
                    json.has("biteAlertPitch") ? json.get("biteAlertPitch").getAsDouble() : 1.5);
            sound.addProperty("durationSeconds", 5);
            sound.addProperty("intervalTicks", 15);
            json.add("biteAlertSound", sound);
        }
        json.remove("biteAlertSoundId");
        json.remove("biteAlertVolume");
        json.remove("biteAlertPitch");
        return json;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public double getDetectionRadius() { return detectionRadius; }
    public void setDetectionRadius(double v) { detectionRadius = v; save(); }

    public int getReactionDelayMinMs() { return reactionDelayMinMs; }
    public void setReactionDelayMinMs(int v) { reactionDelayMinMs = v; save(); }

    public int getReactionDelayMaxMs() { return reactionDelayMaxMs; }
    public void setReactionDelayMaxMs(int v) { reactionDelayMaxMs = v; save(); }

    public boolean isAutoRecast() { return autoRecast; }
    public void setAutoRecast(boolean v) { autoRecast = v; save(); }

    public int getAbilityActionDelayMs() { return abilityActionDelayMs; }
    public void setAbilityActionDelayMs(int v) { abilityActionDelayMs = v; save(); }

    public int getRecastDelayMinMs() { return recastDelayMinMs; }
    public void setRecastDelayMinMs(int v) { recastDelayMinMs = v; save(); }

    public int getRecastDelayMaxMs() { return recastDelayMaxMs; }
    public void setRecastDelayMaxMs(int v) { recastDelayMaxMs = v; save(); }

    public int getRecastDecisionTicks() { return recastDecisionTicks; }
    public void setRecastDecisionTicks(int v) { recastDecisionTicks = v; save(); }

    public AlarmSound getBiteAlertSound() { return biteAlertSound; }

    public List<TriggerLevel> getTriggerLevels() { return triggerLevels; }

    public boolean isTrackSeaCreatures() { return trackSeaCreatures; }
    public void setTrackSeaCreatures(boolean v) { trackSeaCreatures = v; save(); }

    public double getCreatureScanRadius() { return creatureScanRadius; }
    public void setCreatureScanRadius(double v) { creatureScanRadius = v; save(); }

    public boolean isCatchRegistryDebugJson() { return catchRegistryDebugJson; }
    public void setCatchRegistryDebugJson(boolean v) { catchRegistryDebugJson = v; save(); }

    public boolean isHighlightSeaCreatures() { return highlightSeaCreatures; }
    public void setHighlightSeaCreatures(boolean v) { highlightSeaCreatures = v; save(); }

    public int  getSeaCreatureHighlightColor() { return seaCreatureHighlightColor; }
    public void setSeaCreatureHighlightColor(int v) { seaCreatureHighlightColor = v; save(); }

    public AbilityConfig getFireVeil() { return fireVeil; }
    public AbilityConfig getTotem()    { return totem; }

    public AlarmSound getSeaCreatureCapSound() { return seaCreatureCapSound; }

    public boolean isDespawnWarningEnabled() { return despawnWarningEnabled; }
    public void setDespawnWarningEnabled(boolean v) { despawnWarningEnabled = v; save(); }

    public int getDespawnWarningMinutes() { return despawnWarningMinutes; }
    public void setDespawnWarningMinutes(int v) { despawnWarningMinutes = v; save(); }

    /** Converts the configured warning minutes to game ticks (20 ticks per second). */
    public long getDespawnWarningTicks() { return (long) despawnWarningMinutes * 60 * 20; }

    public AlarmSound getDespawnWarningSound() { return despawnWarningSound; }

    public boolean isHookStuckDetectionEnabled() { return hookStuckDetectionEnabled; }
    public void setHookStuckDetectionEnabled(boolean v) { hookStuckDetectionEnabled = v; save(); }

    public double getHookStuckMaxDistance() { return hookStuckMaxDistance; }
    public void setHookStuckMaxDistance(double v) { hookStuckMaxDistance = v; save(); }

    public boolean isHookStuckAutoRecast() { return hookStuckAutoRecast; }
    public void setHookStuckAutoRecast(boolean v) { hookStuckAutoRecast = v; save(); }

    public AlarmSound getHookStuckSound() { return hookStuckSound; }

    public boolean isNotInWaterRecastEnabled() { return notInWaterRecastEnabled; }
    public void setNotInWaterRecastEnabled(boolean v) { notInWaterRecastEnabled = v; save(); }

    public boolean isLogBobberParticles() { return logBobberParticles; }
    public void setLogBobberParticles(boolean v) { logBobberParticles = v; save(); }

    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }
    public void setUpdateCheckEnabled(boolean v) { updateCheckEnabled = v; save(); }

    public boolean isBaitHudVisible() { return baitHudVisible; }
    public void setBaitHudVisible(boolean v) { baitHudVisible = v; save(); }

    public int getBaitLowThreshold() { return baitLowThreshold; }
    public void setBaitLowThreshold(int v) { baitLowThreshold = v; save(); }

    public AlarmSound getBaitLowAlertSound()    { return baitLowAlertSound; }
    public AlarmSound getBaitSwitchAlertSound() { return baitSwitchAlertSound; }

    public boolean isRebootAlertEnabled()   { return rebootAlertEnabled; }
    public void setRebootAlertEnabled(boolean v) { rebootAlertEnabled = v; save(); }
    public AlarmSound getRebootAlertSound() { return rebootAlertSound; }

    public boolean isFishingStatsHudVisible()  { return fishingStatsHudVisible; }
    public void setFishingStatsHudVisible(boolean v) { fishingStatsHudVisible = v; save(); }

    public boolean isStatSpeedHudVisible()    { return statSpeedHudVisible; }
    public void setStatSpeedHudVisible(boolean v) { statSpeedHudVisible = v; save(); }
    public boolean isStatDhcHudVisible()      { return statDhcHudVisible; }
    public void setStatDhcHudVisible(boolean v) { statDhcHudVisible = v; save(); }
    public boolean isStatSccHudVisible()      { return statSccHudVisible; }
    public void setStatSccHudVisible(boolean v) { statSccHudVisible = v; save(); }
    public boolean isStatTreasureHudVisible() { return statTreasureHudVisible; }
    public void setStatTreasureHudVisible(boolean v) { statTreasureHudVisible = v; save(); }

    public float getHudX()     { return hudX; }
    public float getHudY()     { return hudY; }
    public float getHudScale() { return hudScale; }

    /** Persists the HUD panel's position + scale in one write (called when the editor closes). */
    public void setHudLayout(float x, float y, float scale) {
        this.hudX = x;
        this.hudY = y;
        this.hudScale = scale > 0 ? scale : 1.0f;
        save();
    }

    public boolean isLogVisible() { return logVisible; }
    public void setLogVisible(boolean v) { logVisible = v; save(); }

    public float getLogHudX()     { return logHudX; }
    public float getLogHudY()     { return logHudY; }
    public float getLogHudScale() { return logHudScale; }

    /** Persists the log panel's position + scale (called when the editor closes). */
    public void setLogLayout(float x, float y, float scale) {
        this.logHudX = x;
        this.logHudY = y;
        this.logHudScale = scale > 0 ? scale : 1.0f;
        save();
    }

    public boolean isSlugfishMode() { return slugfishMode; }
    public void setSlugfishMode(boolean v) { slugfishMode = v; save(); }

    public boolean isSlugPet() { return slugPet; }
    public void setSlugPet(boolean v) { slugPet = v; save(); }

    public boolean isGoldenFishAlertEnabled() { return goldenFishAlertEnabled; }
    public void setGoldenFishAlertEnabled(boolean v) { goldenFishAlertEnabled = v; save(); }

    public String getGoldenFishPhrase() { return goldenFishPhrase; }
    public void setGoldenFishPhrase(String v) { goldenFishPhrase = v; save(); }

    public String getGoldenFishTitleText() { return goldenFishTitleText; }
    public void setGoldenFishTitleText(String v) { goldenFishTitleText = v; save(); }

    public AlarmSound getGoldenFishSound() { return goldenFishSound; }

    /**
     * Returns true if the given chat text matches the configured Golden Fish
     * phrase. Mirrors {@link TriggerLevel#matches}: comma-separated, case-
     * insensitive substring match where any one term matching counts.
     */
    public boolean matchesGoldenFishPhrase(String chatText) {
        if (!goldenFishAlertEnabled || goldenFishPhrase == null || goldenFishPhrase.isBlank()) {
            return false;
        }
        String lower = chatText.toLowerCase();
        for (String pat : goldenFishPhrase.split(",")) {
            String p = pat.trim().toLowerCase();
            if (!p.isEmpty() && lower.contains(p)) return true;
        }
        return false;
    }

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean v) { debugMode = v; save(); }

    public int getLogLevel() { return logLevel.level; }
    public void setLogLevel(int v) {
        logLevel = LogLevel.fromLevel(v);
        PoseidonLogger.getInstance().setLogLevel(logLevel.level);
        save();
    }
}
