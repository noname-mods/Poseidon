package com.poseidon.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.playerapi.SoundActions;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     */
    private static final int CURRENT_VERSION = 7;
    private int configVersion = CURRENT_VERSION;

    // ── Detection ─────────────────────────────────────────────────────────────
    private double detectionRadius = 4.0;

    // ── Reaction delay ────────────────────────────────────────────────────────
    private int reactionDelayMinMs = 180;
    private int reactionDelayMaxMs = 700;

    // ── Auto recast ───────────────────────────────────────────────────────────
    /**
     * When true (default), the bot automatically recasts after each catch.
     * Individual triggers can suppress a recast via their dontRecast flag.
     * Set to false to never auto-recast (manual casts only).
     */
    private boolean autoRecast          = true;
    private int     recastDelayMinMs    = 200;
    private int     recastDelayMaxMs    = 600;
    /**
     * Ticks to wait after a reel-in before deciding whether to recast.
     * Gives post-catch chat time to arrive so triggers can suppress the recast.
     * Low-ping players can reduce this; high-ping players should increase it.
     * Default 10 ticks (500 ms) covers most connections up to ~300 ms ping.
     */
    private int     recastDecisionTicks = 10;

    // ── Bite alert sound ──────────────────────────────────────────────────────
    private AlarmSound biteAlertSound = AlarmSound.defaultBite();

    // ── Sea creature tracking ──────────────────────────────────────────────────
    private boolean trackSeaCreatures  = true;
    /** Radius around the bobber to scan for new sea creature name plates. */
    private double  creatureScanRadius = 12.0;
    /**
     * Per-island cap values. Hub is used as the fallback for any unlisted area.
     * Keys match the exact area strings from the Hypixel tab list.
     */
    private Map<String, Integer> seaCreatureCapByArea = defaultCapsByArea();
    private AlarmSound seaCreatureCapSound = new AlarmSound(
            "minecraft:entity.player.levelup", 1.0, 0.8, 10, 20);

    // ── Despawn warning ────────────────────────────────────────────────────────
    /** Fire a warning alert when a tracked creature has been alive this many minutes. */
    private boolean    despawnWarningEnabled = true;
    private int        despawnWarningMinutes = 5;
    private AlarmSound despawnWarningSound   = new AlarmSound(
            "minecraft:block.bell.use", 1.0, 0.8, 5, 20);

    // ── Hook-stuck detection ───────────────────────────────────────────────────
    /**
     * When true, Poseidon watches for the bobber drifting horizontally after
     * landing. A drift of more than {@code hookStuckMaxDistance} blocks indicates
     * the hook has attached to a moving mob rather than landing in water.
     * The rod is automatically reeled in and recast.
     */
    private boolean    hookStuckDetectionEnabled = true;
    /** Horizontal drift threshold in blocks. Normal bobbing is < 0.2 blocks. */
    private double     hookStuckMaxDistance      = 1.5;
    private AlarmSound hookStuckSound            = new AlarmSound(
            "minecraft:entity.villager.no", 1.0, 1.2, 2, 10);

    private static Map<String, Integer> defaultCapsByArea() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String area : KNOWN_AREAS) m.put(area, 10);
        return m;
    }

    // ── Chat triggers ─────────────────────────────────────────────────────────
    /** Ordered list of trigger levels. First match wins. */
    private List<TriggerLevel> triggerLevels = defaultTriggerLevels();

    // ── Update checker ────────────────────────────────────────────────────────
    private boolean updateCheckEnabled = true;

    // ── Developer ─────────────────────────────────────────────────────────────
    private boolean debugMode = false;
    private int logLevel = PoseidonLogger.LEVEL_WARN;

    private FishingConfig() {}

    public static FishingConfig getInstance() { return INSTANCE; }

    // ── AlarmSound ────────────────────────────────────────────────────────────

    /**
     * Configuration for a single alarm sound event.
     * Sound IDs use the format "namespace:sound.id".
     * Browse all vanilla sounds at: https://misode.github.io/sounds/
     */
    public static class AlarmSound {
        public String soundId;
        public double volume;
        public double pitch;
        public int    durationSeconds;
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
        public String  name     = "";
        public boolean enabled  = true;
        /** Comma-separated substrings to watch for in chat (case-insensitive). */
        public String  patterns = "";
        public AlarmSound sound = AlarmSound.defaultBite();
        /** Reserved for future actions (e.g. recast, stop, alert). */
        public String action    = "";
        /** When true, suppress the auto-recast after this trigger fires. */
        public boolean dontRecast = false;
        /** When true, deactivate the bot (HUD stays open) after this trigger fires. */
        public boolean stopBot    = false;
        /** Show a Minecraft title overlay when this trigger fires. */
        public boolean showTitle = false;
        /**
         * Text displayed as the MC title when {@code showTitle} is true.
         * Leave blank to use the trigger's Name field instead.
         */
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
        List<TriggerLevel> list = new ArrayList<>();
        // All 5 slots start empty and disabled — user configures them in-game
        for (int i = 1; i <= 5; i++) {
            TriggerLevel t = new TriggerLevel();
            t.enabled  = false;
            t.name     = "";
            t.patterns = "";
            t.sound    = AlarmSound.defaultBite();
            list.add(t);
        }
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
                    this.recastDelayMinMs   = loaded.recastDelayMinMs  > 0 ? loaded.recastDelayMinMs  : 500;
                    this.recastDelayMaxMs   = loaded.recastDelayMaxMs  > 0 ? loaded.recastDelayMaxMs  : 1500;
                    this.debugMode          = loaded.debugMode;
                    this.logLevel           = loaded.logLevel;
                    PoseidonLogger.getInstance().setLogLevel(this.logLevel);

                    if (loaded.biteAlertSound != null)
                        this.biteAlertSound.mergeFrom(loaded.biteAlertSound, AlarmSound.defaultBite());

                    this.trackSeaCreatures  = loaded.trackSeaCreatures;
                    this.creatureScanRadius = loaded.creatureScanRadius > 0 ? loaded.creatureScanRadius : 12.0;

                    if (loaded.seaCreatureCapByArea != null && !loaded.seaCreatureCapByArea.isEmpty())
                        this.seaCreatureCapByArea = loaded.seaCreatureCapByArea;

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
                    if (loaded.hookStuckSound != null)
                        this.hookStuckSound.mergeFrom(loaded.hookStuckSound,
                                new AlarmSound("minecraft:entity.villager.no", 1.0, 1.2, 2, 10));

                    this.updateCheckEnabled = loaded.updateCheckEnabled;
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
        json.addProperty("configVersion", CURRENT_VERSION);
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

    /**
     * Returns the cap for the given area string (as read from the tab list).
     * Falls back to the Hub cap for any unknown area. Hub itself falls back to 10.
     */
    public int getCapForArea(String area) {
        if (area == null || area.isBlank()) return getHubCap();
        Integer cap = seaCreatureCapByArea.get(area);
        return cap != null ? Math.max(1, cap) : getHubCap();
    }

    private int getHubCap() {
        Integer cap = seaCreatureCapByArea.get("Hub");
        return cap != null ? Math.max(1, cap) : 10;
    }

    public void setCapForArea(String area, int v) {
        seaCreatureCapByArea.put(area, Math.max(1, v));
        save();
    }

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

    public AlarmSound getHookStuckSound() { return hookStuckSound; }

    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }
    public void setUpdateCheckEnabled(boolean v) { updateCheckEnabled = v; save(); }

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean v) { debugMode = v; save(); }

    public int getLogLevel() { return logLevel; }
    public void setLogLevel(int v) {
        logLevel = v;
        PoseidonLogger.getInstance().setLogLevel(v);
        save();
    }
}
