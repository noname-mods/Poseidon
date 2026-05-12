package com.poseidon.gui;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingConfig.AlarmSound;
import com.poseidon.core.FishingConfig.TriggerLevel;
import com.poseidon.core.PoseidonLogger;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class PoseidonConfigScreen {

    private PoseidonConfigScreen() {}

    public static Screen create(Screen parent) {
        FishingConfig cfg = FishingConfig.getInstance();

        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("Poseidon Configuration"))

                // ── Detection ─────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Detection"))
                        .tooltip(Text.literal("Controls how Poseidon identifies a fish bite."))

                        .option(Option.<Double>createBuilder()
                                .name(Text.literal("Detection Radius"))
                                .description(OptionDescription.of(Text.literal(
                                        "How many blocks around the bobber to scan for the !!! signal.\n" +
                                        "Increase if bites are missed; decrease to reduce false positives.")))
                                .binding(4.0, cfg::getDetectionRadius, cfg::setDetectionRadius)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(1.0, 10.0).step(0.5))
                                .build())

                        .option(LabelOption.createBuilder()
                                .line(Text.literal("§6─── Hook Stuck Detection ────────────────────────────────"))
                                .line(Text.literal("§7Detects when the hook attaches to a mob instead of landing in water."))
                                .line(Text.literal("§7Normal bobbing is vertical only — horizontal drift means it's stuck."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Hook Stuck Detection"))
                                .description(OptionDescription.of(Text.literal(
                                        "Automatically reel in and recast if the bobber drifts\n" +
                                        "horizontally after landing — a sign the hook hit a mob.\n\n" +
                                        "Detection waits 1 second after the bobber lands before\n" +
                                        "it starts measuring drift.")))
                                .binding(true, cfg::isHookStuckDetectionEnabled, cfg::setHookStuckDetectionEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Double>createBuilder()
                                .name(Text.literal("Max Drift Distance (blocks)"))
                                .description(OptionDescription.of(Text.literal(
                                        "How far the bobber must drift horizontally before\n" +
                                        "it is considered stuck. Normal water bobbing stays\n" +
                                        "well under 0.5 blocks. Default: 1.5 blocks.")))
                                .binding(1.5, cfg::getHookStuckMaxDistance, cfg::setHookStuckMaxDistance)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(0.5, 5.0).step(0.25))
                                .build())

                        .option(alarmSoundIdOption("Hook Stuck Sound",
                                "minecraft:entity.villager.no", cfg.getHookStuckSound(), cfg))
                        .option(alarmVolumeOption("Hook Stuck Volume", cfg.getHookStuckSound(), cfg))
                        .option(alarmPitchOption("Hook Stuck Pitch", cfg.getHookStuckSound(), cfg))
                        .option(alarmRepeatOption("Hook Stuck Duration", cfg.getHookStuckSound(), 2, cfg))
                        .option(alarmIntervalOption("Hook Stuck Interval", cfg.getHookStuckSound(), 10, cfg))

                        .build())

                // ── Reaction ──────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Reaction Delay"))
                        .tooltip(Text.literal("Human-like delay between detecting !!! and reeling in."))

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Min Delay (ms)"))
                                .description(OptionDescription.of(Text.literal(
                                        "Minimum milliseconds to wait after !!! before reeling in.")))
                                .binding(180, cfg::getReactionDelayMinMs, cfg::setReactionDelayMinMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(50, 2000).step(10))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Max Delay (ms)"))
                                .description(OptionDescription.of(Text.literal(
                                        "Maximum milliseconds to wait. Actual delay is random between Min and Max.")))
                                .binding(700, cfg::getReactionDelayMaxMs, cfg::setReactionDelayMaxMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(50, 3000).step(10))
                                .build())

                        .option(LabelOption.createBuilder()
                                .line(Text.literal("§6─── Auto Recast ─────────────────────────────────────────"))
                                .line(Text.literal("§7After each catch, automatically recast the fishing rod."))
                                .line(Text.literal("§7Individual triggers can suppress or override this below."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Auto Recast"))
                                .description(OptionDescription.of(Text.literal(
                                        "Automatically recast the rod after each catch.\n" +
                                        "When off, the bot waits for a manual cast. Individual\n" +
                                        "triggers can still suppress a recast even when this is on.")))
                                .binding(true, cfg::isAutoRecast, cfg::setAutoRecast)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Recast Delay Min (ms)"))
                                .description(OptionDescription.of(Text.literal(
                                        "Minimum milliseconds to wait before recasting after a catch.")))
                                .binding(200, cfg::getRecastDelayMinMs, cfg::setRecastDelayMinMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(100, 3000).step(50))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Recast Delay Max (ms)"))
                                .description(OptionDescription.of(Text.literal(
                                        "Maximum milliseconds to wait. Actual delay is random between Min and Max.")))
                                .binding(600, cfg::getRecastDelayMaxMs, cfg::setRecastDelayMaxMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(100, 5000).step(50))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Trigger Wait (ticks)"))
                                .description(OptionDescription.of(Text.literal(
                                        "Ticks to wait after reeling in before deciding to recast.\n" +
                                        "During this window, chat triggers can suppress or change the recast.\n\n" +
                                        "Low ping (< 80 ms):  5–10 ticks\n" +
                                        "Medium ping (80–200 ms):  10–15 ticks\n" +
                                        "High ping (200+ ms):  20–30 ticks\n\n" +
                                        "20 ticks = 1 second.")))
                                .binding(10, cfg::getRecastDecisionTicks, cfg::setRecastDecisionTicks)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(2, 40).step(1)
                                        .valueFormatter(v -> Text.literal(v + " ticks (" + (v * 50) + " ms)")))
                                .build())

                        .build())

                // ── Sea Creature Tracking ─────────────────────────────────────
                .category(buildSeaCreatureCategory(cfg))

                // ── Chat Triggers ─────────────────────────────────────────────
                .category(buildTriggersCategory(cfg))

                // ── Updates ───────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Updates"))
                        .tooltip(Text.literal("GitHub release update notifications."))

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Update Check"))
                                .description(OptionDescription.of(Text.literal(
                                        "On every world join, Poseidon contacts GitHub to check for a newer release.\n" +
                                        "If one is found, a single chat message is shown with the version and a link.\n" +
                                        "Nothing is downloaded automatically.\n\n" +
                                        "How it works: the check reads the tag of the latest GitHub release and\n" +
                                        "compares it against the installed version. The release title does not\n" +
                                        "matter — only the tag (e.g. v1.2.0 or 1.2.0) is used.\n\n" +
                                        "Disable this if you are offline, on a restricted network, or\n" +
                                        "simply do not want the notification.")))
                                .binding(true, cfg::isUpdateCheckEnabled, cfg::setUpdateCheckEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .build())

                // ── Developer ─────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Developer").formatted(Formatting.RED))
                        .tooltip(Text.literal("Testing options — do not use during normal operation."))

                        .option(LabelOption.createBuilder()
                                .line(Text.literal("§c⚠ These settings may break things or cause unintended behaviour."))
                                .line(Text.literal("§7Only change these if you know what you are doing."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Text.literal("Debug Mode"))
                                .description(OptionDescription.of(Text.literal(
                                        "Logs verbose detection output to poseidon/poseidon.log.")))
                                .binding(false, cfg::isDebugMode, cfg::setDebugMode)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Text.literal("Log Level"))
                                .description(OptionDescription.of(Text.literal(
                                        "DEBUG = most verbose. ERROR = errors only.")))
                                .binding(PoseidonLogger.LEVEL_WARN, cfg::getLogLevel, cfg::setLogLevel)
                                .controller(opt -> CyclingListControllerBuilder.create(opt)
                                        .values(List.of(
                                                PoseidonLogger.LEVEL_DEBUG,
                                                PoseidonLogger.LEVEL_INFO,
                                                PoseidonLogger.LEVEL_WARN,
                                                PoseidonLogger.LEVEL_ERROR))
                                        .valueFormatter(v -> switch (v) {
                                            case PoseidonLogger.LEVEL_DEBUG -> Text.literal("DEBUG");
                                            case PoseidonLogger.LEVEL_INFO  -> Text.literal("INFO");
                                            case PoseidonLogger.LEVEL_WARN  -> Text.literal("WARN");
                                            default                         -> Text.literal("ERROR");
                                        }))
                                .build())

                        .build())

                .save(cfg::save)
                .build()
                .generateScreen(parent);
    }

    // ── Sea creature tracking category ───────────────────────────────────────

    private static ConfigCategory buildSeaCreatureCategory(FishingConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Text.literal("Sea Creature Tracking"))
                .tooltip(Text.literal(
                        "Tracks sea creatures spawned by your catches and alerts when you have enough to kill."))

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Enable Tracking"))
                        .description(OptionDescription.of(Text.literal(
                                "After each reel-in, scan near the bobber for sea creature name plates\n" +
                                "(identified by the ⚓ anchor icon). Tracked creatures are shown in the\n" +
                                "HUD as SC: count / cap. Creatures are removed when they disappear.")))
                        .binding(true, cfg::isTrackSeaCreatures, cfg::setTrackSeaCreatures)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Text.literal("Scan Radius"))
                        .description(OptionDescription.of(Text.literal(
                                "Blocks around the bobber position to scan for sea creature entities\n" +
                                "after each reel-in. Increase if creatures spawn far from the bobber.")))
                        .binding(12.0, cfg::getCreatureScanRadius, cfg::setCreatureScanRadius)
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(5.0, 30.0).step(1.0))
                        .build())

                .option(LabelOption.createBuilder()
                        .line(Text.literal("§6─── Cap Alert Counts ─────────────────────────────────────"))
                        .line(Text.literal("§7Play the cap alert when this many creatures are tracked."))
                        .line(Text.literal("§7Hub is also the fallback cap for any unlisted island."))
                        .build())

                .option(capSlider("Backwater Bayou Cap", "Backwater Bayou", cfg))
                .option(capSlider("Crimson Isle Cap",    "Crimson Isle",    cfg))
                .option(capSlider("Galatea Cap",         "Galatea",         cfg))
                .option(capSlider("Hub Cap §7(default)", "Hub",             cfg))
                .option(capSlider("Jerry's Workshop Cap","Jerry's Workshop",cfg))
                .option(capSlider("The Park Cap",        "The Park",        cfg))

                .option(LabelOption.createBuilder()
                        .line(Text.literal("§6─── Cap Alert Sound ─────────────────────────────────────────"))
                        .line(Text.literal("§7Plays when the tracked creature count reaches the cap."))
                        .line(Text.literal("Sound IDs: §ehttps://misode.github.io/sounds/"))
                        .build())

                .option(alarmSoundIdOption("Cap Alert Sound",
                        "minecraft:entity.player.levelup", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmVolumeOption("Cap Alert Volume", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmPitchOption("Cap Alert Pitch", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmRepeatOption("Cap Alert Duration", cfg.getSeaCreatureCapSound(), 10, cfg))
                .option(alarmIntervalOption("Cap Alert Interval", cfg.getSeaCreatureCapSound(), 20, cfg))

                // ── Despawn warning ───────────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Text.literal("§6─── Despawn Warning ─────────────────────────────────────"))
                        .line(Text.literal("§7Sea creatures despawn after ~6 minutes. Get an alert before"))
                        .line(Text.literal("§7they disappear so you know to kill them."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Enable Despawn Warning"))
                        .description(OptionDescription.of(Text.literal(
                                "Play a sound when a tracked creature has been alive for the\n" +
                                "configured number of minutes, warning it may despawn soon.")))
                        .binding(true, cfg::isDespawnWarningEnabled, cfg::setDespawnWarningEnabled)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Warning At (minutes)"))
                        .description(OptionDescription.of(Text.literal(
                                "Fire the warning this many minutes after the creature was detected.\n" +
                                "Sea creatures despawn at 6 minutes — 5 gives you ~1 minute to act.")))
                        .binding(5, cfg::getDespawnWarningMinutes, cfg::setDespawnWarningMinutes)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 6).step(1)
                                .valueFormatter(v -> Text.literal(v + " min")))
                        .build())

                .option(alarmSoundIdOption("Despawn Warning Sound",
                        "minecraft:block.bell.use", cfg.getDespawnWarningSound(), cfg))
                .option(alarmVolumeOption("Despawn Warning Volume", cfg.getDespawnWarningSound(), cfg))
                .option(alarmPitchOption("Despawn Warning Pitch", cfg.getDespawnWarningSound(), cfg))
                .option(alarmRepeatOption("Despawn Warning Duration", cfg.getDespawnWarningSound(), 5, cfg))
                .option(alarmIntervalOption("Despawn Warning Interval", cfg.getDespawnWarningSound(), 20, cfg))

                .build();
    }

    // ── Triggers category ─────────────────────────────────────────────────────

    private static ConfigCategory buildTriggersCategory(FishingConfig cfg) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder()
                .name(Text.literal("Chat Triggers"))
                .tooltip(Text.literal(
                        "Play a sound when specific text appears in chat after reeling in.\n" +
                        "Each trigger has its own patterns and sound. First match wins."))

                .option(LabelOption.createBuilder()
                        .line(Text.literal("Triggers fire when chat contains any of the listed patterns."))
                        .line(Text.literal("Patterns are §ecomma-separated§r, case-insensitive substrings."))
                        .line(Text.literal("§7Examples: §eappeared, emerged§7 for sea creatures;"))
                        .line(Text.literal("§7          §ecatch!§7 for treasure messages."))
                        .line(Text.literal("§7Triggers are checked top to bottom — put specific ones first."))
                        .build());

        List<TriggerLevel> levels = cfg.getTriggerLevels();
        for (int i = 0; i < levels.size(); i++) {
            cat.group(buildTriggerGroup(i + 1, levels.get(i), cfg));
        }

        // ── Bite alert — at bottom, off by default ────────────────────────────
        cat.option(LabelOption.createBuilder()
                .line(Text.literal("§6─── Bite Alert Sound ────────────────────────────────────────"))
                .line(Text.literal("§7Optional sound played the moment !!! is detected on the bobber."))
                .line(Text.literal("§7Off by default — use triggers above for important catches instead."))
                .line(Text.literal("§7Set Duration above 0 to enable. Sound IDs: §ehttps://misode.github.io/sounds/"))
                .build());

        cat.option(alarmSoundIdOption("Bite Alert Sound",
                AlarmSound.defaultBite().soundId, cfg.getBiteAlertSound(), cfg));
        cat.option(alarmVolumeOption("Bite Alert Volume", cfg.getBiteAlertSound(), cfg));
        cat.option(alarmPitchOption("Bite Alert Pitch", cfg.getBiteAlertSound(), cfg));
        cat.option(alarmRepeatOption("Bite Alert Duration (0 = off)", cfg.getBiteAlertSound(), 0, cfg));
        cat.option(alarmIntervalOption("Bite Alert Interval", cfg.getBiteAlertSound(), 15, cfg));

        return cat.build();
    }

    private static OptionGroup buildTriggerGroup(int slot, TriggerLevel level, FishingConfig cfg) {
        return OptionGroup.createBuilder()
                .name(Text.literal("Trigger " + slot + (level.name.isBlank() ? "" : " — " + level.name)))
                .description(OptionDescription.of(Text.literal(
                        "Fires when chat contains any of this trigger's patterns.")))

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Enabled"))
                        .binding(true, () -> level.enabled, v -> { level.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Text.literal("Name"))
                        .description(OptionDescription.of(Text.literal(
                                "Label shown in the group header and in log output.")))
                        .binding("", () -> level.name, v -> { level.name = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Text.literal("Patterns"))
                        .description(OptionDescription.of(Text.literal(
                                "Comma-separated substrings to match in chat (case-insensitive).\n" +
                                "Example: appeared, emerged, reeled in\n" +
                                "Any one match is enough to fire the trigger.")))
                        .binding("", () -> level.patterns, v -> { level.patterns = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Text.literal("Action"))
                        .description(OptionDescription.of(Text.literal(
                                "Future: action to take when this trigger fires.\n" +
                                "Currently unused — leave blank.")))
                        .binding("", () -> level.action, v -> { level.action = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                // ── Title overlay ──────────────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Text.literal("§7— Title Overlay —"))
                        .line(Text.literal("§7Show a Minecraft title on screen when this trigger fires."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Show Title"))
                        .description(OptionDescription.of(Text.literal(
                                "Display a Minecraft title overlay when this trigger fires.\n" +
                                "Uses the Title Text below, or the trigger Name if that is blank.")))
                        .binding(false, () -> level.showTitle, v -> { level.showTitle = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Text.literal("Title Text"))
                        .description(OptionDescription.of(Text.literal(
                                "Text shown as the MC title. Supports §colour codes.\n" +
                                "Leave blank to use the trigger Name field instead.")))
                        .binding("", () -> level.titleText, v -> { level.titleText = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                // ── Recast behaviour for this trigger ─────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Text.literal("§7— Recast Behaviour —"))
                        .line(Text.literal("§7Override what happens after this trigger fires."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Don't Recast"))
                        .description(OptionDescription.of(Text.literal(
                                "When this trigger fires, suppress the automatic recast.\n" +
                                "Use this for triggers that signal a special creature you\n" +
                                "want to deal with before fishing again.")))
                        .binding(false, () -> level.dontRecast, v -> { level.dontRecast = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Stop Bot"))
                        .description(OptionDescription.of(Text.literal(
                                "When this trigger fires, deactivate the bot entirely.\n" +
                                "The HUD stays open. Re-activate with the Toggle key.")))
                        .binding(false, () -> level.stopBot, v -> { level.stopBot = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                // ── Sound for this trigger ─────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Text.literal("§7— Sound —"))
                        .line(Text.literal("Sound IDs: §ehttps://misode.github.io/sounds/"))
                        .line(Text.literal("§7Omit 'minecraft:' prefix for vanilla sounds."))
                        .build())

                .option(alarmSoundIdOption("Sound",
                        AlarmSound.defaultBite().soundId, level.sound, cfg))
                .option(alarmVolumeOption("Volume", level.sound, cfg))
                .option(alarmPitchOption("Pitch", level.sound, cfg))
                .option(alarmRepeatOption("Duration", level.sound, 5, cfg))
                .option(alarmIntervalOption("Interval", level.sound, 15, cfg))

                .build();
    }

    // ── Per-area cap slider ───────────────────────────────────────────────────

    private static Option<Integer> capSlider(String label, String area, FishingConfig cfg) {
        return Option.<Integer>createBuilder()
                .name(Text.literal(label))
                .description(OptionDescription.of(Text.literal(
                        "Play the cap alert when this many sea creatures are tracked on " + area + ".\n" +
                        "Resets automatically after you kill creatures below this threshold.")))
                .binding(10, () -> cfg.getCapForArea(area), v -> cfg.setCapForArea(area, v))
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 50).step(1))
                .build();
    }

    // ── AlarmSound option helpers — same pattern as CeresConfigScreen ─────────

    private static Option<String> alarmSoundIdOption(String name, String defaultId,
                                                      AlarmSound sound, FishingConfig cfg) {
        return Option.<String>createBuilder()
                .name(Text.literal(name))
                .description(OptionDescription.of(Text.literal(
                        "Minecraft sound ID. Omit 'minecraft:' prefix for vanilla sounds.\n" +
                        "Example: entity.player.levelup\n" +
                        "Browse: https://misode.github.io/sounds/")))
                .binding(defaultId, () -> sound.soundId, v -> { sound.soundId = v; cfg.save(); })
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static Option<Double> alarmVolumeOption(String name, AlarmSound sound, FishingConfig cfg) {
        return Option.<Double>createBuilder()
                .name(Text.literal(name))
                .description(OptionDescription.of(Text.literal("0.1 = very quiet  •  1.0 = normal  •  2.0 = loud.")))
                .binding(1.0, () -> sound.volume, v -> { sound.volume = v; cfg.save(); })
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.1, 2.0).step(0.05))
                .build();
    }

    private static Option<Double> alarmPitchOption(String name, AlarmSound sound, FishingConfig cfg) {
        return Option.<Double>createBuilder()
                .name(Text.literal(name))
                .description(OptionDescription.of(Text.literal(
                        "0.5 = low and slow  •  1.0 = normal  •  2.0 = high and fast.")))
                .binding(1.0, () -> sound.pitch, v -> { sound.pitch = v; cfg.save(); })
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.5, 2.0).step(0.05))
                .build();
    }

    private static Option<Integer> alarmRepeatOption(String name, AlarmSound sound,
                                                      int defaultSecs, FishingConfig cfg) {
        return Option.<Integer>createBuilder()
                .name(Text.literal(name))
                .description(OptionDescription.of(Text.literal(
                        "Total seconds the sound plays. 0 = play once only.")))
                .binding(defaultSecs, () -> sound.durationSeconds,
                        v -> { sound.durationSeconds = v; cfg.save(); })
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, 30).step(1)
                        .valueFormatter(v -> Text.literal(v + "s")))
                .build();
    }

    private static Option<Integer> alarmIntervalOption(String name, AlarmSound sound,
                                                        int defaultTicks, FishingConfig cfg) {
        return Option.<Integer>createBuilder()
                .name(Text.literal(name))
                .description(OptionDescription.of(Text.literal(
                        "Ticks between each repeat. 20 ticks = 1 second.")))
                .binding(defaultTicks, () -> sound.intervalTicks,
                        v -> { sound.intervalTicks = v; cfg.save(); })
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(5, 60).step(5))
                .build();
    }
}
