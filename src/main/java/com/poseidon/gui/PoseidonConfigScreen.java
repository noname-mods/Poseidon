package com.poseidon.gui;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingConfig.AlarmSound;
import com.poseidon.core.FishingConfig.TriggerLevel;
import com.poseidon.core.PoseidonLogger;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

public class PoseidonConfigScreen {

    private PoseidonConfigScreen() {}

    public static Screen create(Screen parent) {
        FishingConfig cfg = FishingConfig.getInstance();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Poseidon Configuration"))

                // ── Detection ─────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Detection"))
                        .tooltip(Component.literal("Controls how Poseidon identifies a fish bite."))

                        .option(Option.<Double>createBuilder()
                                .name(Component.literal("Detection Radius"))
                                .description(OptionDescription.of(Component.literal(
                                        "How many blocks around the bobber to scan for the !!! signal.\n" +
                                        "Increase if bites are missed; decrease to reduce false positives.")))
                                .binding(4.0, cfg::getDetectionRadius, cfg::setDetectionRadius)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(1.0, 10.0).step(0.5))
                                .build())

                        .option(LabelOption.createBuilder()
                                .line(Component.literal("§6─── Hook Stuck Detection ────────────────────────────────"))
                                .line(Component.literal("§7Detects when the hook attaches to a mob instead of landing in water."))
                                .line(Component.literal("§7Normal bobbing is vertical only — horizontal drift means it's stuck."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Hook Stuck Detection"))
                                .description(OptionDescription.of(Component.literal(
                                        "Automatically reel in and recast if the bobber drifts\n" +
                                        "horizontally after landing — a sign the hook hit a mob.\n\n" +
                                        "Detection waits 1 second after the bobber lands before\n" +
                                        "it starts measuring drift.")))
                                .binding(true, cfg::isHookStuckDetectionEnabled, cfg::setHookStuckDetectionEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Double>createBuilder()
                                .name(Component.literal("Max Drift Distance (blocks)"))
                                .description(OptionDescription.of(Component.literal(
                                        "How far the bobber must drift horizontally before\n" +
                                        "it is considered stuck. Normal water bobbing stays\n" +
                                        "well under 0.5 blocks. Default: 1.5 blocks.")))
                                .binding(1.5, cfg::getHookStuckMaxDistance, cfg::setHookStuckMaxDistance)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(0.5, 5.0).step(0.25))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Auto Recast on Drift"))
                                .description(OptionDescription.of(Component.literal(
                                        "When drift is detected, automatically reel in and recast.\n\n" +
                                        "The alert sound always plays regardless of this setting.\n\n" +
                                        "This is independent of the global Auto Recast toggle —\n" +
                                        "enabling this lets the bot recover from a stuck hook even\n" +
                                        "when you are fishing in manual-cast mode.")))
                                .binding(true, cfg::isHookStuckAutoRecast, cfg::setHookStuckAutoRecast)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(alarmSoundIdOption("Hook Stuck Sound",
                                "minecraft:entity.villager.no", cfg.getHookStuckSound(), cfg))
                        .option(alarmVolumeOption("Hook Stuck Volume", cfg.getHookStuckSound(), cfg))
                        .option(alarmPitchOption("Hook Stuck Pitch", cfg.getHookStuckSound(), cfg))
                        .option(alarmRepeatOption("Hook Stuck Duration", cfg.getHookStuckSound(), 2, cfg))
                        .option(alarmIntervalOption("Hook Stuck Interval", cfg.getHookStuckSound(), 10, cfg))

                        .option(LabelOption.createBuilder()
                                .line(Component.literal("§6─── Slugfish Mode ───────────────────────────────────────"))
                                .line(Component.literal("§7Delays reel-ins until the slugfish timer has elapsed."))
                                .line(Component.literal("§7The HUD shows a live countdown and turns green when ready."))
                                .line(Component.literal("§c⚠ Only enable this while actively going for the Slugfish trophy fish."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Slugfish Mode"))
                                .description(OptionDescription.of(Component.literal(
                                        "Ignores all reel-in signals until 21 seconds have passed\n" +
                                        "after the cast. Slugfish only bite after ≥20 s so this\n" +
                                        "prevents accidentally catching normal creatures.\n\n" +
                                        "The HUD shows a countdown while waiting and 'READY' in\n" +
                                        "green once the timer has elapsed.\n\n" +
                                        "§c⚠ Only enable while going for the Slugfish trophy fish.\n" +
                                        "§cLeave this off during normal fishing — it will block all\n" +
                                        "§ccatches until the timer elapses on every cast.")))
                                .binding(false, cfg::isSlugfishMode, cfg::setSlugfishMode)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("With Slug Pet"))
                                .description(OptionDescription.of(Component.literal(
                                        "A max-level Slug Pet halves the required wait time,\n" +
                                        "reducing it from 21 s to 11 s.\n\n" +
                                        "§e⚠ Assumes a level-100 Slug Pet is equipped.\n" +
                                        "§ePoseidon does not check which pet you have — if the\n" +
                                        "§ewrong pet is active the timer will be too short and\n" +
                                        "§eyou may reel in before a Slugfish can bite.")))
                                .binding(false, cfg::isSlugPet, cfg::setSlugPet)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .build())

                // ── Reaction ──────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Reaction Delay"))
                        .tooltip(Component.literal("Human-like delay between detecting !!! and reeling in."))

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Min Delay (ms)"))
                                .description(OptionDescription.of(Component.literal(
                                        "Minimum milliseconds to wait after !!! before reeling in.")))
                                .binding(180, cfg::getReactionDelayMinMs, cfg::setReactionDelayMinMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(50, 2000).step(10))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Max Delay (ms)"))
                                .description(OptionDescription.of(Component.literal(
                                        "Maximum milliseconds to wait. Actual delay is random between Min and Max.")))
                                .binding(700, cfg::getReactionDelayMaxMs, cfg::setReactionDelayMaxMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(50, 3000).step(10))
                                .build())

                        .option(LabelOption.createBuilder()
                                .line(Component.literal("§6─── Auto Recast ─────────────────────────────────────────"))
                                .line(Component.literal("§7After each catch, automatically recast the fishing rod."))
                                .line(Component.literal("§7Individual triggers can suppress or override this below."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Auto Recast"))
                                .description(OptionDescription.of(Component.literal(
                                        "Automatically recast the rod after each catch.\n" +
                                        "When off, the bot waits for a manual cast. Individual\n" +
                                        "triggers can still suppress a recast even when this is on.")))
                                .binding(true, cfg::isAutoRecast, cfg::setAutoRecast)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Recast Delay Min (ms)"))
                                .description(OptionDescription.of(Component.literal(
                                        "Minimum milliseconds to wait before recasting after a catch.")))
                                .binding(200, cfg::getRecastDelayMinMs, cfg::setRecastDelayMinMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(100, 3000).step(50))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Recast Delay Max (ms)"))
                                .description(OptionDescription.of(Component.literal(
                                        "Maximum milliseconds to wait. Actual delay is random between Min and Max.")))
                                .binding(600, cfg::getRecastDelayMaxMs, cfg::setRecastDelayMaxMs)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(100, 5000).step(50))
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Trigger Wait (ticks)"))
                                .description(OptionDescription.of(Component.literal(
                                        "Ticks to wait after reeling in before deciding to recast.\n" +
                                        "During this window, chat triggers can suppress or change the recast.\n\n" +
                                        "Low ping (< 80 ms):  5–10 ticks\n" +
                                        "Medium ping (80–200 ms):  10–15 ticks\n" +
                                        "High ping (200+ ms):  20–30 ticks\n\n" +
                                        "20 ticks = 1 second.")))
                                .binding(10, cfg::getRecastDecisionTicks, cfg::setRecastDecisionTicks)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(2, 40).step(1)
                                        .valueFormatter(v -> Component.literal(v + " ticks (" + (v * 50) + " ms)")))
                                .build())

                        .build())

                // ── Sea Creature Tracking ─────────────────────────────────────
                .category(buildSeaCreatureCategory(cfg))

                // ── Bait ──────────────────────────────────────────────────────
                .category(buildBaitCategory(cfg))

                // ── Stats & Reboot Alert ───────────────────────────────────────
                .category(buildStatsRebootCategory(cfg))

                // ── Chat Triggers ─────────────────────────────────────────────
                .category(buildTriggersCategory(cfg))

                // ── Golden Fish ───────────────────────────────────────────────
                .category(buildGoldenFishCategory(cfg))

                // ── Updates ───────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Updates"))
                        .tooltip(Component.literal("GitHub release update notifications."))

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Update Check"))
                                .description(OptionDescription.of(Component.literal(
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
                        .name(Component.literal("Developer").withStyle(ChatFormatting.RED))
                        .tooltip(Component.literal("Testing options — do not use during normal operation."))

                        .option(LabelOption.createBuilder()
                                .line(Component.literal("§c⚠ These settings may break things or cause unintended behaviour."))
                                .line(Component.literal("§7Only change these if you know what you are doing."))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Debug Mode"))
                                .description(OptionDescription.of(Component.literal(
                                        "Logs verbose detection output to poseidon/poseidon.log.")))
                                .binding(false, cfg::isDebugMode, cfg::setDebugMode)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Integer>createBuilder()
                                .name(Component.literal("Log Level"))
                                .description(OptionDescription.of(Component.literal(
                                        "DEBUG = most verbose. ERROR = errors only.")))
                                .binding(PoseidonLogger.LEVEL_WARN, cfg::getLogLevel, cfg::setLogLevel)
                                .controller(opt -> CyclingListControllerBuilder.create(opt)
                                        .values(List.of(
                                                PoseidonLogger.LEVEL_DEBUG,
                                                PoseidonLogger.LEVEL_INFO,
                                                PoseidonLogger.LEVEL_WARN,
                                                PoseidonLogger.LEVEL_ERROR))
                                        .valueFormatter(v -> switch (v) {
                                            case PoseidonLogger.LEVEL_DEBUG -> Component.literal("DEBUG");
                                            case PoseidonLogger.LEVEL_INFO  -> Component.literal("INFO");
                                            case PoseidonLogger.LEVEL_WARN  -> Component.literal("WARN");
                                            default                         -> Component.literal("ERROR");
                                        }))
                                .build())

                        .build())

                .save(cfg::save)
                .build()
                .generateScreen(parent);
    }

    // ── Golden Fish category ─────────────────────────────────────────────────

    private static ConfigCategory buildGoldenFishCategory(FishingConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Golden Fish"))
                .tooltip(Component.literal(
                        "Optional alert for the Golden Fish. When the matching chat\n" +
                        "message appears, the bot stops, reels in, and hands control\n" +
                        "to you so you can catch it manually."))

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Golden Fish Alert ───────────────────────────────────"))
                        .line(Component.literal("§7When the Golden Fish message appears, Poseidon shows a golden"))
                        .line(Component.literal("§7title, plays a sound, reels in, and turns the bot off so you can"))
                        .line(Component.literal("§7catch it yourself. Re-enable the bot afterwards to resume."))
                        .line(Component.literal("§8Disabled by default — does nothing until you turn it on."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Golden Fish Alert"))
                        .description(OptionDescription.of(Component.literal(
                                "Watch chat for the Golden Fish message. On a match:\n" +
                                "  • golden title card + alert sound\n" +
                                "  • reel in any active cast\n" +
                                "  • stop the bot (HUD stays open)\n\n" +
                                "You then catch the Golden Fish manually and re-enable\n" +
                                "the bot to continue. Off by default.")))
                        .binding(false, cfg::isGoldenFishAlertEnabled, cfg::setGoldenFishAlertEnabled)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Trigger Phrase"))
                        .description(OptionDescription.of(Component.literal(
                                "Comma-separated text to watch for (case-insensitive).\n" +
                                "Any one term matching fires the alert.\n\n" +
                                "Default: spot a Golden Fish surface\n" +
                                "  → matches \"You spot a Golden Fish surface from\n" +
                                "    beneath the lava/waves!\" (both lava and water).\n\n" +
                                "Unlike the Chat Triggers tab, this is checked any time\n" +
                                "while fishing — not just right after a reel-in — because\n" +
                                "the Golden Fish announcement can arrive at any moment.")))
                        .binding("spot a Golden Fish surface", cfg::getGoldenFishPhrase, cfg::setGoldenFishPhrase)
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Title Text"))
                        .description(OptionDescription.of(Component.literal(
                                "Text shown as the on-screen title when the alert fires.\n" +
                                "Supports § and & colour/style codes.\n\n" +
                                "Default: §6§lGOLDEN FISH")))
                        .binding("§6§lGOLDEN FISH", cfg::getGoldenFishTitleText, cfg::setGoldenFishTitleText)
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(alarmSoundIdOption("Alert Sound",
                        "minecraft:entity.player.levelup", cfg.getGoldenFishSound(), cfg))
                .option(alarmVolumeOption("Alert Volume", cfg.getGoldenFishSound(), cfg))
                .option(alarmPitchOption("Alert Pitch", cfg.getGoldenFishSound(), cfg))
                .option(alarmRepeatOption("Alert Duration", cfg.getGoldenFishSound(), 4, cfg))
                .option(alarmIntervalOption("Alert Interval", cfg.getGoldenFishSound(), 20, cfg))

                .build();
    }

    // ── Sea creature tracking category ───────────────────────────────────────

    private static ConfigCategory buildSeaCreatureCategory(FishingConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Sea Creature Tracking"))
                .tooltip(Component.literal(
                        "Tracks sea creatures spawned by your catches and alerts when you have enough to kill."))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enable Tracking"))
                        .description(OptionDescription.of(Component.literal(
                                "After each reel-in, scan near the bobber for sea creature name plates\n" +
                                "(identified by the ⚓ anchor icon). Tracked creatures are shown in the\n" +
                                "HUD as SC: count / cap. Creatures are removed when they disappear.")))
                        .binding(true, cfg::isTrackSeaCreatures, cfg::setTrackSeaCreatures)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Scan Radius"))
                        .description(OptionDescription.of(Component.literal(
                                "Blocks around the bobber position to scan for sea creature entities\n" +
                                "after each reel-in. Increase if creatures spawn far from the bobber.")))
                        .binding(12.0, cfg::getCreatureScanRadius, cfg::setCreatureScanRadius)
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(5.0, 30.0).step(1.0))
                        .build())

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Cap Alert Sound ─────────────────────────────────────────"))
                        .line(Component.literal("§7Plays when the tracked creature count reaches §e" + FishingConfig.SEA_CREATURE_CAP + "§7 (Hypixel cap)."))
                        .line(Component.literal("Sound IDs: §ehttps://misode.github.io/sounds/"))
                        .build())

                .option(alarmSoundIdOption("Cap Alert Sound",
                        "minecraft:entity.player.levelup", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmVolumeOption("Cap Alert Volume", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmPitchOption("Cap Alert Pitch", cfg.getSeaCreatureCapSound(), cfg))
                .option(alarmRepeatOption("Cap Alert Duration", cfg.getSeaCreatureCapSound(), 10, cfg))
                .option(alarmIntervalOption("Cap Alert Interval", cfg.getSeaCreatureCapSound(), 20, cfg))

                // ── Despawn warning ───────────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Despawn Warning ─────────────────────────────────────"))
                        .line(Component.literal("§7Sea creatures despawn after ~6 minutes. Get an alert before"))
                        .line(Component.literal("§7they disappear so you know to kill them."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enable Despawn Warning"))
                        .description(OptionDescription.of(Component.literal(
                                "Play a sound when a tracked creature has been alive for the\n" +
                                "configured number of minutes, warning it may despawn soon.")))
                        .binding(true, cfg::isDespawnWarningEnabled, cfg::setDespawnWarningEnabled)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Warning At (minutes)"))
                        .description(OptionDescription.of(Component.literal(
                                "Fire the warning this many minutes after the creature was detected.\n" +
                                "Sea creatures despawn at 6 minutes — 5 gives you ~1 minute to act.")))
                        .binding(5, cfg::getDespawnWarningMinutes, cfg::setDespawnWarningMinutes)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 6).step(1)
                                .valueFormatter(v -> Component.literal(v + " min")))
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
                .name(Component.literal("Chat Triggers"))
                .tooltip(Component.literal(
                        "Play a sound when specific text appears in chat after reeling in.\n" +
                        "Each trigger has its own patterns and sound. First match wins."))

                .option(LabelOption.createBuilder()
                        .line(Component.literal("Triggers fire when chat contains any of the listed patterns."))
                        .line(Component.literal("Patterns are §ecomma-separated§r, case-insensitive substrings."))
                        .line(Component.literal("§7Examples: §eappeared, emerged§7 for sea creatures;"))
                        .line(Component.literal("§7          §ecatch!§7 for treasure messages."))
                        .line(Component.literal("§7Triggers are checked top to bottom — put specific ones first."))
                        .build());

        List<TriggerLevel> levels = cfg.getTriggerLevels();
        for (int i = 0; i < levels.size(); i++) {
            cat.group(buildTriggerGroup(i + 1, levels.get(i), cfg));
        }

        // ── Bite alert — at bottom, off by default ────────────────────────────
        cat.option(LabelOption.createBuilder()
                .line(Component.literal("§6─── Bite Alert Sound ────────────────────────────────────────"))
                .line(Component.literal("§7Optional sound played the moment !!! is detected on the bobber."))
                .line(Component.literal("§7Off by default — use triggers above for important catches instead."))
                .line(Component.literal("§7Set Duration above 0 to enable. Sound IDs: §ehttps://misode.github.io/sounds/"))
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
                .name(Component.literal("Trigger " + slot + (level.name.isBlank() ? "" : " — " + level.name)))
                .description(OptionDescription.of(Component.literal(
                        "Fires when chat contains any of this trigger's patterns.")))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enabled"))
                        .binding(true, () -> level.enabled, v -> { level.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Name"))
                        .description(OptionDescription.of(Component.literal(
                                "Label shown in the group header and in log output.")))
                        .binding("", () -> level.name, v -> { level.name = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Patterns"))
                        .description(OptionDescription.of(Component.literal(
                                "Comma-separated substrings to match in chat (case-insensitive).\n" +
                                "Example: appeared, emerged, reeled in\n" +
                                "Any one match is enough to fire the trigger.")))
                        .binding("", () -> level.patterns, v -> { level.patterns = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Action"))
                        .description(OptionDescription.of(Component.literal(
                                "Future: action to take when this trigger fires.\n" +
                                "Currently unused — leave blank.")))
                        .binding("", () -> level.action, v -> { level.action = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                // ── Title overlay ──────────────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Component.literal("§7— Title Overlay —"))
                        .line(Component.literal("§7Show a Minecraft title on screen when this trigger fires."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Show Title"))
                        .description(OptionDescription.of(Component.literal(
                                "Display a Minecraft title overlay when this trigger fires.\n" +
                                "Uses the Title Text below, or the trigger Name if that is blank.")))
                        .binding(false, () -> level.showTitle, v -> { level.showTitle = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Title Text"))
                        .description(OptionDescription.of(Component.literal(
                                "Text shown as the MC title.\n" +
                                "Leave blank to use the trigger Name field instead.\n\n" +
                                "Colour/style codes: use & or § followed by a code.\n" +
                                "  &4 dark red   &c red      &6 gold    &e yellow\n" +
                                "  &2 dark green &a green    &b aqua    &9 blue\n" +
                                "  &d light purple &5 purple &f white   &7 gray\n" +
                                "  &l bold   &o italic   &n underline   &r reset\n\n" +
                                "Example: &dOld Leather Boot  or  &6&lRARE CATCH")))
                        .binding("", () -> level.titleText, v -> { level.titleText = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                // ── Recast behaviour for this trigger ─────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Component.literal("§7— Recast Behaviour —"))
                        .line(Component.literal("§7Override what happens after this trigger fires."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Don't Recast"))
                        .description(OptionDescription.of(Component.literal(
                                "When this trigger fires, suppress the automatic recast.\n" +
                                "Use this for triggers that signal a special creature you\n" +
                                "want to deal with before fishing again.")))
                        .binding(false, () -> level.dontRecast, v -> { level.dontRecast = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Stop Bot"))
                        .description(OptionDescription.of(Component.literal(
                                "When this trigger fires, deactivate the bot entirely.\n" +
                                "The HUD stays open. Re-activate with the Toggle key.")))
                        .binding(false, () -> level.stopBot, v -> { level.stopBot = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                // ── Sound for this trigger ─────────────────────────────────────
                .option(LabelOption.createBuilder()
                        .line(Component.literal("§7— Sound —"))
                        .line(Component.literal("Sound IDs: §ehttps://misode.github.io/sounds/"))
                        .line(Component.literal("§7Omit 'minecraft:' prefix for vanilla sounds."))
                        .build())

                .option(alarmSoundIdOption("Sound",
                        AlarmSound.defaultBite().soundId, level.sound, cfg))
                .option(alarmVolumeOption("Volume", level.sound, cfg))
                .option(alarmPitchOption("Pitch", level.sound, cfg))
                .option(alarmRepeatOption("Duration", level.sound, 5, cfg))
                .option(alarmIntervalOption("Interval", level.sound, 15, cfg))

                .build();
    }

    // ── Stats & Reboot Alert category ────────────────────────────────────────

    private static ConfigCategory buildStatsRebootCategory(FishingConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Stats & Alerts"))
                .tooltip(Component.literal(
                        "Fishing stats HUD display and server reboot alarm."))

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Fishing Stats HUD ──────────────────────────────────"))
                        .line(Component.literal("§7Shows Double Hook Chance, Sea Creature Chance, Fishing Speed,"))
                        .line(Component.literal("§7and Treasure Chance — read from the Hypixel tab list."))
                        .line(Component.literal("§7Stats only appear while connected to Hypixel Skyblock."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Show Fishing Stats in HUD"))
                        .description(OptionDescription.of(Component.literal(
                                "Display your current fishing stats (DHC, SCC, Speed, Treasure)\n" +
                                "in the Poseidon HUD panel. Values are pulled from the tab list\n" +
                                "every 2 seconds and only shown when at least one stat is available.")))
                        .binding(true, cfg::isFishingStatsHudVisible, cfg::setFishingStatsHudVisible)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Reboot Alert ───────────────────────────────────────"))
                        .line(Component.literal("§7Plays a looping alarm when Hypixel announces a server reboot."))
                        .line(Component.literal("§7The alarm stops automatically once you warp to a different area."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Reboot Alert"))
                        .description(OptionDescription.of(Component.literal(
                                "Play a looping alarm when a Hypixel server reboot message is received.\n\n" +
                                "Detection: looks for \"This server will restart soon\" in server chat\n" +
                                "(the message has no player sender).\n\n" +
                                "Dismissal: the alarm stops automatically when you warp away from\n" +
                                "the area you were in when the reboot was announced.")))
                        .binding(true, cfg::isRebootAlertEnabled, cfg::setRebootAlertEnabled)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(alarmSoundIdOption("Reboot Sound",
                        "minecraft:block.bell.use", cfg.getRebootAlertSound(), cfg))
                .option(alarmVolumeOption("Reboot Volume",   cfg.getRebootAlertSound(), cfg))
                .option(alarmPitchOption("Reboot Pitch",     cfg.getRebootAlertSound(), cfg))
                .option(alarmRepeatOption("Reboot Duration", cfg.getRebootAlertSound(), 300, cfg))
                .option(alarmIntervalOption("Reboot Interval", cfg.getRebootAlertSound(), 40, cfg))

                .build();
    }

    // ── Bait category ────────────────────────────────────────────────────────

    private static ConfigCategory buildBaitCategory(FishingConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Component.literal("Bait"))
                .tooltip(Component.literal(
                        "Monitors the bait in your last hotbar slot and alerts when bait is low or switches."))

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§eNote: Bait is only tracked from your Fishing Bag (last hotbar slot)."))
                        .line(Component.literal("§7Bait sitting in your regular inventory is not counted."))
                        .line(Component.literal("§7If bait is in your Fishing Bag it will appear in that slot automatically;"))
                        .line(Component.literal("§7keeping bait in your inventory instead is unnecessary — use the Fishing Bag."))
                        .line(Component.literal("§7Checks are skipped entirely when you are not holding a rod."))
                        .build())

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Show Bait in HUD"))
                        .description(OptionDescription.of(Component.literal(
                                "Show the current bait type and count in the HUD.\n" +
                                "Displays the name and stack size of the item in the last hotbar slot\n" +
                                "if it contains 'bait'. Shows 'No Bait' in red otherwise.")))
                        .binding(true, cfg::isBaitHudVisible, cfg::setBaitHudVisible)
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Low Bait Alert ──────────────────────────────────────"))
                        .line(Component.literal("§7Plays an alert when the bait stack drops to the threshold."))
                        .line(Component.literal("§7Resets when you restock above the threshold."))
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Low Bait Threshold"))
                        .description(OptionDescription.of(Component.literal(
                                "Fire the low-bait alert when the bait count drops to or below this value.\n" +
                                "The alert resets automatically when you restock above this threshold.")))
                        .binding(5, cfg::getBaitLowThreshold, cfg::setBaitLowThreshold)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 64).step(1))
                        .build())

                .option(alarmSoundIdOption("Low Bait Sound",
                        "minecraft:entity.experience_orb.pickup", cfg.getBaitLowAlertSound(), cfg))
                .option(alarmVolumeOption("Low Bait Volume",    cfg.getBaitLowAlertSound(), cfg))
                .option(alarmPitchOption("Low Bait Pitch",      cfg.getBaitLowAlertSound(), cfg))
                .option(alarmRepeatOption("Low Bait Duration",  cfg.getBaitLowAlertSound(), 3,  cfg))
                .option(alarmIntervalOption("Low Bait Interval",cfg.getBaitLowAlertSound(), 20, cfg))

                .option(LabelOption.createBuilder()
                        .line(Component.literal("§6─── Bait Switch Alert ──────────────────────────────────"))
                        .line(Component.literal("§7Plays when the bait type changes while the bot is active."))
                        .line(Component.literal("§7Useful for detecting when bait runs out and a different item takes its slot."))
                        .build())

                .option(alarmSoundIdOption("Bait Switch Sound",
                        "minecraft:block.bell.use", cfg.getBaitSwitchAlertSound(), cfg))
                .option(alarmVolumeOption("Bait Switch Volume",    cfg.getBaitSwitchAlertSound(), cfg))
                .option(alarmPitchOption("Bait Switch Pitch",      cfg.getBaitSwitchAlertSound(), cfg))
                .option(alarmRepeatOption("Bait Switch Duration",  cfg.getBaitSwitchAlertSound(), 2,  cfg))
                .option(alarmIntervalOption("Bait Switch Interval",cfg.getBaitSwitchAlertSound(), 20, cfg))

                .build();
    }
    // ── AlarmSound option helpers — same pattern as CeresConfigScreen ─────────

    private static Option<String> alarmSoundIdOption(String name, String defaultId,
                                                      AlarmSound sound, FishingConfig cfg) {
        return Option.<String>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(
                        "Minecraft sound ID. Omit 'minecraft:' prefix for vanilla sounds.\n" +
                        "Example: entity.player.levelup\n" +
                        "Browse: https://misode.github.io/sounds/")))
                .binding(defaultId, () -> sound.soundId, v -> { sound.soundId = v; cfg.save(); })
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static Option<Double> alarmVolumeOption(String name, AlarmSound sound, FishingConfig cfg) {
        return Option.<Double>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal("0.1 = very quiet  •  1.0 = normal  •  2.0 = loud.")))
                .binding(1.0, () -> sound.volume, v -> { sound.volume = v; cfg.save(); })
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.1, 2.0).step(0.05))
                .build();
    }

    private static Option<Double> alarmPitchOption(String name, AlarmSound sound, FishingConfig cfg) {
        return Option.<Double>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(
                        "0.5 = low and slow  •  1.0 = normal  •  2.0 = high and fast.")))
                .binding(1.0, () -> sound.pitch, v -> { sound.pitch = v; cfg.save(); })
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.5, 2.0).step(0.05))
                .build();
    }

    private static Option<Integer> alarmRepeatOption(String name, AlarmSound sound,
                                                      int defaultSecs, FishingConfig cfg) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(
                        "Total seconds the sound plays. 0 = play once only.")))
                .binding(defaultSecs, () -> sound.durationSeconds,
                        v -> { sound.durationSeconds = v; cfg.save(); })
                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                        .range(0, 30).step(1)
                        .valueFormatter(v -> Component.literal(v + "s")))
                .build();
    }

    private static Option<Integer> alarmIntervalOption(String name, AlarmSound sound,
                                                        int defaultTicks, FishingConfig cfg) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .description(OptionDescription.of(Component.literal(
                        "Ticks between each repeat. 20 ticks = 1 second.")))
                .binding(defaultTicks, () -> sound.intervalTicks,
                        v -> { sound.intervalTicks = v; cfg.save(); })
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(5, 60).step(5))
                .build();
    }
}
