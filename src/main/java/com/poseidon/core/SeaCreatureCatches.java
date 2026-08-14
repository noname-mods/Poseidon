package com.poseidon.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry that maps a Hypixel sea-creature <em>catch chat line</em> to the creature it
 * refers to, so Poseidon can identify exactly what was caught instead of guessing by
 * proximity. Also detects the {@code DOUBLE HOOK!} prefix (two of the same creature).
 *
 * <h2>Source</h2>
 * <ul>
 *   <li><b>Default:</b> the bundled resource {@code assets/poseidon/sea_creature_catches.json}
 *       (ships in the jar; authoritative).</li>
 *   <li><b>Debug override:</b> when {@link #setDebugEnabled(boolean) enabled}, reads from
 *       {@code config/poseidon/sea_creature_catches.json} instead (seeded from the bundled
 *       file on first enable), so lines can be live-edited without recompiling.</li>
 * </ul>
 *
 * <p>Both files are keyed by creature name → catch line. This class inverts that into a
 * normalized {@code line → creature} lookup. Normalization strips legacy colour codes, the
 * resource-pack catch icon (U+E025), collapses whitespace and lower-cases, so minor
 * formatting differences don't break a match.</p>
 */
public final class SeaCreatureCatches {

    private static final SeaCreatureCatches INSTANCE = new SeaCreatureCatches();
    public static SeaCreatureCatches getInstance() { return INSTANCE; }

    /** Bundled default, shipped in the jar. */
    private static final String RESOURCE = "/assets/poseidon/sea_creature_catches.json";
    /** Live-editable override, used only while debug mode is on. */
    private static final Path OVERRIDE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("poseidon/sea_creature_catches.json");

    /** Normalized form of the Hypixel double-hook prefix. */
    private static final String DOUBLE_HOOK_KEY = "double hook!";

    /** One registry entry: the normalized catch line and the creature it identifies. */
    private record Entry(String line, String creature) {}

    /**
     * Registry entries sorted <b>longest line first</b>. Matching is {@code contains}, not equals —
     * the real chat message only <em>contains</em> the catch line and may carry colour codes, the
     * catch icon, mob-type glyphs and other text around it. Longest-first ensures a more specific
     * line wins over any shorter line that happens to be a substring of it.
     */
    private volatile List<Entry> entries = List.of();

    /** When true, read from the config-dir override instead of the bundled resource. */
    private boolean debugJson = false;

    private SeaCreatureCatches() {}

    /**
     * Result of {@link #identify(String)}. {@code matched} is true when the line resolved to
     * a known creature; {@code doubleHook} is true when the line carried the DOUBLE HOOK!
     * prefix (independent of whether the creature was matched).
     */
    public record CatchResult(String creature, boolean doubleHook, boolean matched) {}

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Called once on startup with the persisted debug flag. */
    public void init(boolean debugEnabled) {
        this.debugJson = debugEnabled;
        if (debugEnabled) ensureOverrideSeeded();
        reload();
    }

    /** Toggle the debug override on/off (seeds the file on first enable) and reload. */
    public void setDebugEnabled(boolean enabled) {
        this.debugJson = enabled;
        if (enabled) ensureOverrideSeeded();
        reload();
    }

    public boolean isDebugEnabled() { return debugJson; }

    /** Re-read from the active source (override file if debug on and present, else bundled). */
    public void reload() {
        String json = (debugJson && Files.exists(OVERRIDE_PATH))
                ? readFile(OVERRIDE_PATH)
                : readResource();
        entries = buildIndex(json);
    }

    /** Overwrite the override file with the bundled baseline (discards live edits), then reload. */
    public void regenerateFromSource() {
        writeFile(OVERRIDE_PATH, readResource());
        reload();
    }

    private void ensureOverrideSeeded() {
        if (!Files.exists(OVERRIDE_PATH)) writeFile(OVERRIDE_PATH, readResource());
    }

    public static Path overridePath() { return OVERRIDE_PATH; }

    /** Number of catch lines currently loaded. */
    public int size() { return entries.size(); }

    // ── Identify ───────────────────────────────────────────────────────────────

    /**
     * Identifies a catch chat message. The message only has to <b>contain</b> a known catch line —
     * surrounding text, colour codes and pack glyphs are tolerated — and {@code DOUBLE HOOK!} is
     * likewise detected anywhere in it. Returns a result whose {@code creature} is {@code null}
     * when no known sea-creature line is present.
     */
    public CatchResult identify(String rawMessage) {
        String norm = normalize(rawMessage);
        if (norm.isEmpty()) return new CatchResult(null, false, false);

        boolean doubleHook = norm.contains(DOUBLE_HOOK_KEY);
        for (Entry e : entries) {
            if (norm.contains(e.line())) {
                return new CatchResult(e.creature(), doubleHook, true);
            }
        }
        return new CatchResult(null, doubleHook, false);
    }

    /** The normalized form of a message — exposed so callers can log exactly what was compared. */
    public static String normalized(String raw) { return normalize(raw); }

    // ── Normalization ──────────────────────────────────────────────────────────

    /**
     * Strips legacy colour codes and <em>every</em> private-use glyph (the catch icon, mob-type
     * symbols, and any other pack icon), collapses whitespace and lower-cases — so matching is
     * unaffected by formatting or icons anywhere in the message.
     */
    private static String normalize(String s) {
        if (s == null) return "";
        String out = s.replaceAll("(?i)[§&][0-9A-FK-OR]", "");   // §X / &X legacy codes
        out = out.replaceAll("[\\uE000-\\uF8FF]", "");           // any resource-pack glyph
        return out.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    // ── Parsing / IO ───────────────────────────────────────────────────────────

    private static List<Entry> buildIndex(String json) {
        List<Entry> list = new ArrayList<>();
        if (json == null) return list;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            Map<String, String> seen = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String creature = e.getKey();
                if (creature.startsWith("_")) continue; // comment / metadata keys
                JsonElement v = e.getValue();
                if (v == null || !v.isJsonPrimitive()) continue;
                String line = v.getAsString();
                if (line == null || line.isBlank()) continue; // unknown line yet → fallback
                String key = normalize(line);
                if (key.isEmpty()) continue;

                String prev = seen.put(key, creature);
                if (prev != null && !prev.equals(creature)) {
                    PoseidonLogger.getInstance().logWarn("Catch registry: identical line for '"
                            + prev + "' and '" + creature + "' — keeping '" + creature + "'.");
                    list.removeIf(en -> en.line().equals(key));
                }
                list.add(new Entry(key, creature));
            }
        } catch (Exception ex) {
            PoseidonLogger.getInstance().logError(
                    "Failed to parse sea-creature catch registry: " + ex.getMessage());
        }
        // Longest first — a more specific line must win a contains match over any shorter line
        // that is a substring of it.
        list.sort((a, b) -> Integer.compare(b.line().length(), a.line().length()));
        return list;
    }

    private static String readResource() {
        try (InputStream in = SeaCreatureCatches.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                PoseidonLogger.getInstance().logError("Bundled sea-creature registry missing: " + RESOURCE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            PoseidonLogger.getInstance().logError(
                    "Failed to read bundled sea-creature registry: " + ex.getMessage());
            return null;
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            PoseidonLogger.getInstance().logError("Failed to read override registry: " + ex.getMessage());
            return null;
        }
    }

    private static void writeFile(Path path, String content) {
        if (content == null) return;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            PoseidonLogger.getInstance().logError("Failed to write override registry: " + ex.getMessage());
        }
    }
}
