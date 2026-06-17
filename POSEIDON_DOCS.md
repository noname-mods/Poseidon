# Poseidon — Design & Documentation

**Version:** 1.1.0 (config schema v13)
**Platform:** Fabric 26.1.2, Java 21
**Dependencies:** PlayerAPI, Fabric API, YACL v3, ModMenu (optional)
**Mod ID:** `poseidon`
**Entry point:** `com.poseidon.PoseidonMod`
**Config file:** `<game_dir>/config/poseidon/config.json`
**Log file:** `<game_dir>/config/poseidon/poseidon.log`

---

## AI Session Quick-Start

> **Read this first if you are a new Claude Code session working in this folder.**

### What this mod is

Poseidon is a **client-side fishing automation bot** for Hypixel Skyblock. It watches for the `!!!` bite signal entity near the fishing bobber, reacts with a configurable human-like delay, reels in, and optionally recasts. It also tracks sea creatures that spawn after catches, detects hooks stuck on mobs, and fires configurable sound/title alerts on chat patterns. It is built on top of PlayerAPI and never imports Minecraft internals for player input.

### Key dependency

| Dependency | Version field | Location |
|------------|--------------|----------|
| PlayerAPI | `playerapi_version` in `gradle.properties` | `C:\Users\willi\Documents\completeMods\PlayerAPI` |

**Build order:** If you changed PlayerAPI, run `./gradlew publishToMavenLocal` there first, then build Poseidon.

### Source layout (what lives where)

```
src/main/java/com/poseidon/
├── PoseidonMod.java                  — entry point, event wiring, keybinds, /poseidon command
├── core/
│   ├── FishingState.java             — enum: IDLE / WAITING / BITING / REELING
│   ├── FishingManager.java           — THE central singleton: full state machine + all fishing logic
│   ├── FishingConfig.java            — all persistent settings, JSON config, versioned migrations
│   └── PoseidonLogger.java           — ring-buffer logger + file output
├── gui/
│   ├── PoseidonHudRenderer.java      — HUD overlay (Active, State, Bobber, Area, SC rows + log panel)
│   └── PoseidonConfigScreen.java     — YACL config screen (5 tabs)
├── mixin/
│   └── MouseClickMixin.java          — blocks right-click while WAITING or BITING (prevents cast cancel)
└── modmenu/
    └── PoseidonModMenuPlugin.java    — ModMenu integration
```

### Config file — `<game>/config/poseidon/config.json`

Schema is versioned (`configVersion`, currently **6**). Auto-migrated on load.

**Version history at a glance:**
- v0→v1: flat `biteAlert*` fields → `biteAlertSound` object; `triggerLevels` added
- v1→v2: single `seaCreatureCap` int → `seaCreatureCapByArea` Map
- v2→v3: `autoRecast` added (migration needed — GSON defaults bool to `false`)
- v3→v4: `recastDecisionTicks` added
- v4→v5: `despawnWarningEnabled` + `despawnWarningMinutes` added
- v5→v6: `hookStuckDetectionEnabled` + `hookStuckMaxDistance` added

**Rule:** Any new boolean field that should default to `true` needs a migration step — GSON bypasses constructors and will produce `false` for missing fields.

**Adding a migration:**
1. Bump `CURRENT_VERSION` in `FishingConfig`
2. Add a `private static JsonObject migrateVn(JsonObject json)` method
3. Call it in `migrate()` with the appropriate version check

### The state machine (FishingManager)

```
IDLE → WAITING    when bobber appears (fishHook != null)
WAITING → BITING  when detectBite() finds !!! entity near bobber
WAITING → IDLE    if bobber disappears
BITING → REELING  when scheduled reel-in fires (after reaction delay)
BITING → IDLE     if bobber disappears before reel-in fires
REELING → IDLE    after 10-tick reset
```

Everything important happens in `FishingManager.tick()`, called every tick from `PoseidonMod.onTick()`. The recast decision window (`recastDecisionTicks`) is the gap between the reel-in send and the recast — it exists so chat triggers have time to arrive and set `pendingSuppressRecast` / `pendingStopBot` flags.

### PlayerAPI classes used by Poseidon

| Class | Used for |
|-------|---------|
| `PlayerAPIEvents.TICK` | Main tick loop |
| `PlayerAPIEvents.CHAT_RECEIVED` | Chat trigger matching |
| `MovementActions.tapKey("use", 100)` | Reel in + recast |
| `Scheduler.scheduleMs()` / `schedule()` | Reaction delay, recast delay, scan delay |
| `TabListInfo.findLineContaining("Area:")` | Area refresh every 40 ticks |
| `SoundActions.playByIdRepeated()` | All alarm sounds |
| `DisplayActions.showTitle()` | Title overlay on trigger fire |
| `PlayerInfo.isInWorld()` | Guard at top of onTick() |

### Common things you'll need to touch

**Adding a new detection feature:**
- Main logic goes in `FishingManager` (tick loop or a scheduled callback)
- Config fields go in `FishingConfig` with getter/setter + `save()` call
- UI goes in `PoseidonConfigScreen` — use the existing `alarmSoundIdOption` / `alarmVolumeOption` etc. helpers for sound settings
- If the new field defaults to `true`: add a migration in `FishingConfig`

**Adding a new trigger action (beyond sound/title/dontRecast/stopBot):**
- `TriggerLevel` fields are in `FishingConfig` (public inner class)
- The matching loop is in `PoseidonMod.onChatReceived()`
- Flags are consumed in `FishingManager.scheduleReelIn()` → recast decision callback
- Add the new field to the YACL trigger group builder in `PoseidonConfigScreen.buildTriggerGroup()`

**Adding a new per-island cap (new area):**
- Add the area name string to `FishingConfig.KNOWN_AREAS`
- Add a `capSlider()` call in `PoseidonConfigScreen.buildSeaCreatureCategory()`
- `KNOWN_AREAS` must be declared **before** `INSTANCE` in `FishingConfig` — same static init order rule as Ceres/BotConfig

**Changing bite detection logic:**
- `detectBite()` and `hasReelNowSignal()` in `FishingManager`
- Currently checks both `entity.getCustomName()` (armor stands) and `TextDisplayEntity.getText()` (display entities)

### Key design rules

- Poseidon never calls `mc.player` input methods directly — all input goes through `MovementActions`
- The mixin (`MouseClickMixin`) is the **only** place Minecraft's `Mouse` class is touched
- `FishingManager` and `FishingConfig` are both singletons — always access via `getInstance()`
- `AlarmSound.play()` is a no-op when `durationSeconds <= 0` — this is how the bite alert is "off by default"
- The bobber position is snapshotted at bite time (`lastBobberX/Y/Z`) because `fishHook` will be null by the time delayed scan callbacks fire

---

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [Architecture Overview](#2-architecture-overview)
3. [Package Structure](#3-package-structure)
4. [FishingState — State Enum](#4-fishingstate--state-enum)
5. [FishingManager — Core State Machine](#5-fishingmanager--core-state-machine)
6. [FishingConfig — Configuration](#6-fishingconfig--configuration)
7. [PoseidonHudRenderer — HUD Overlay](#7-poseidonhudrenderer--hud-overlay)
8. [PoseidonConfigScreen — YACL Config UI](#8-poseidonconfigscreen--yacl-config-ui)
9. [MouseClickMixin — Right-click Guard](#9-mouseclickmixin--right-click-guard)
10. [PoseidonLogger — Logging](#10-poseidonlogger--logging)
11. [PoseidonMod — Entry Point & Event Wiring](#11-poseidonmod--entry-point--event-wiring)
12. [Chat Triggers System](#12-chat-triggers-system)
13. [Sea Creature Tracking System](#13-sea-creature-tracking-system)
14. [Hook Stuck Detection](#14-hook-stuck-detection)
15. [AlarmSound Data Class](#15-alarmsound-data-class)
16. [Config Schema & Migration History](#16-config-schema--migration-history)
17. [Keybinds & Commands](#17-keybinds--commands)
18. [HUD Reference](#18-hud-reference)
19. [Configuration Reference (All Settings)](#19-configuration-reference-all-settings)
20. [Timing Reference](#20-timing-reference)
21. [Design Decisions & Notes](#21-design-decisions--notes)

---

## 1. Purpose and Scope

Poseidon is a client-side fishing automation mod designed for Hypixel Skyblock. It automates the full fishing loop:

1. Cast the rod (manual or auto-recast)
2. Watch for the server's "reel now" signal (`!!!` entity near the bobber)
3. React with a human-like random delay and reel in
4. Optionally recast after a configurable delay
5. Optionally track sea creatures that spawn after each catch
6. Fire sound alerts and title overlays on configured chat patterns

Poseidon depends on PlayerAPI for all Minecraft interaction and scheduling. It never calls Minecraft APIs directly for player input — everything goes through `MovementActions`, `Scheduler`, `TabListInfo`, `SoundActions`, `DisplayActions`, and `PlayerAPIEvents`.

---

## 2. Architecture Overview

```
PoseidonMod (entry point)
├── registers keybinds (Y, H, no-default)
├── registers /poseidon command
├── subscribes to PlayerAPIEvents.TICK → onTick()
├── subscribes to PlayerAPIEvents.CHAT_RECEIVED → onChatReceived()
└── registers HudRenderCallback → PoseidonHudRenderer.render()

onTick() each game tick:
├── opens config screen if openConfigNextTick flag set
├── FishingManager.tick() — core state machine
└── handleKeybinds() — polls keybind presses

onChatReceived(sender, message):
├── isFromServer() gate — rejects player chat (signed sender, or Hypixel
│   "[rank] Name: …" / "Guild > …" shape). All alerts below are server-only.
├── RebootAlertManager.onChatReceived()
├── Golden Fish: if enabled + bot active + phrase matches →
│   golden title, sound, reel in, stop bot (returns; bypasses catch window)
└── catch triggers: within catch window + isCatchMessage() →
    check FishingConfig.getTriggerLevels()
    └── first match: plays sound, shows title, calls FishingManager.notifyTriggerFired()

FishingManager.tick() (only when active):
├── IDLE state:     waits for bobber to appear → transitions to WAITING
├── WAITING state:  runs detectBite() every tick
│   ├── bite found → saves bobber position, → BITING, schedules reel-in
│   └── no bite:  updates nearbyText, runs checkHookStuck()
├── BITING state:   waits for scheduled reel-in; reacts to bobber loss
└── REELING state:  waits for bobber to disappear
    ├── Scheduler fires (delayed): sends "use" key, scans for creatures
    └── Scheduler fires (10 ticks): resets to IDLE, triggers recast decision
```

**Threading model:** Everything runs on the main Minecraft client thread. `PlayerAPIEvents.TICK` fires on the client tick. `Scheduler` callbacks also execute on the client thread. No background threads are used other than the file I/O in `PoseidonLogger`.

---

## 3. Package Structure

```
com.poseidon
├── PoseidonMod.java              — ClientModInitializer, event wiring, keybinds, commands
├── core/
│   ├── FishingState.java         — enum: IDLE, WAITING, BITING, REELING
│   ├── FishingManager.java       — singleton state machine, all fishing logic
│   ├── FishingConfig.java        — singleton config, persistence, migrations
│   └── PoseidonLogger.java       — singleton logger, in-memory ring buffer + file output
├── gui/
│   ├── PoseidonHudRenderer.java  — HUD overlay drawn each frame
│   └── PoseidonConfigScreen.java — YACL config screen builder
├── mixin/
│   └── MouseClickMixin.java      — blocks right-click during active cast
└── modmenu/
    └── PoseidonModMenuPlugin.java — ModMenu integration
```

---

## 4. FishingState — State Enum

`com.poseidon.core.FishingState`

The four states of the fishing loop:

| State | Meaning |
|-------|---------|
| `IDLE` | Bot inactive, or active but no rod has been cast yet. No bobber present. |
| `WAITING` | Bobber is in the water. Scanning every tick for the `!!!` bite signal entity. Also watching for nearby countdown text and hook drift. |
| `BITING` | The `!!!` signal was detected. The reaction delay timer is counting down via `Scheduler`. Reel-in has not been sent yet. |
| `REELING` | The `tapKey("use", 100)` command has been sent to reel in. Waiting for the bobber entity to disappear, which signals the catch is complete. |

**Transitions:**
```
IDLE → WAITING     when hasBobber becomes true
WAITING → BITING   when detectBite() returns true
WAITING → IDLE     if bobber disappears while watching
BITING → REELING   when scheduled reel-in fires
BITING → IDLE      if bobber disappears before reel-in fires
REELING → IDLE     after 10-tick reset (or if bobber disappears)
```

---

## 5. FishingManager — Core State Machine

`com.poseidon.core.FishingManager` — singleton, accessed via `FishingManager.getInstance()`

### Activation

```java
FishingManager.getInstance().setActive(true);   // start watching
FishingManager.getInstance().setActive(false);  // stop, reset to IDLE
FishingManager.getInstance().toggle();          // flip active state
```

`setActive(false)` also clears both `pendingSuppressRecast` and `pendingStopBot` flags and resets state to IDLE.

### tick() — Called every game tick by PoseidonMod

The method is a no-op if `!active`. It runs the state machine switch, then:
- Refreshes the current area from the tab list every 40 ticks (2 seconds)
- Runs sea creature cleanup every 40 ticks if tracking is enabled

### Bite Detection

`detectBite(MinecraftClient mc)` scans a box around the bobber's current position:
- Box dimensions: `±detectionRadius` in XZ, `-1` to `+detectionRadius+2` in Y
- Checks every entity in that box for `hasReelNowSignal(entity)`:
  - Checks `entity.getCustomName().getString().contains("!!!")`
  - Also checks `TextDisplayEntity.getText().getString().contains("!!!")` for modern display entities

The `!!!` signal is placed by the Hypixel server as an armor stand (older system) or text display entity (newer system) near the bobber during the catch window. It typically appears for only a few ticks.

### Reaction Delay & Reel-in

Once a bite is detected, the bobber position is snapshotted (`lastBobberX/Y/Z`) because the bobber may have despawned by the time the reel-in fires.

`scheduleReelIn()` picks a random delay between `reactionDelayMinMs` and `reactionDelayMaxMs` and schedules via `Scheduler.scheduleMs()`. When the callback fires:

1. Transitions to `REELING`
2. `MovementActions.tapKey("use", 100)` — right-click held for 100ms to reel in
3. If sea creature tracking is on: schedules `scanForNewCreatures()` at `SCAN_DELAY_TICKS` (5 ticks)
4. Clears recast flags (`pendingSuppressRecast`, `pendingStopBot`)
5. Logs the recast decision window start
6. Schedules the **recast decision** at `recastDecisionTicks` (default 10 ticks)
7. Schedules a **state reset** at 10 ticks (`REELING → IDLE`)

### Recast Decision Window

After reeling in, Poseidon waits `recastDecisionTicks` before deciding whether to recast. This window exists so that post-catch chat messages have time to arrive and fire their triggers (which can set `pendingSuppressRecast` or `pendingStopBot`).

Decision logic (in priority order):
1. If `!active` → abort
2. If `pendingStopBot` → call `setActive(false)` → done
3. If `!autoRecast || pendingSuppressRecast` → do not recast (wait for manual cast)
4. Otherwise → `scheduleRecast(cfg)` with random delay between `recastDelayMinMs` and `recastDelayMaxMs`

`scheduleRecast()` skips the recast if the state has returned to `WAITING` (server already recast for us) or `BITING` (another bite started before recast fired).

### notifyTriggerFired(boolean dontRecast, boolean stopBot)

Called from `PoseidonMod.onChatReceived()` when a trigger matches during a catch. Sets the recast flags that the decision window checks. The flags are reset at the start of each reel-in.

### Nearby Text

While in `WAITING` state, `scanNearbyText()` scans the same detection box for any entity text that:
- Is not blank
- Does not contain `!!!` (the bite signal)
- Does not contain `⚓` (a sea creature name plate)

The first match (truncated to 20 characters) is stored as `nearbyText` and shown in the HUD Bobber row. This captures the yellow countdown timers that Hypixel displays before a bite window opens.

### Public Getters

| Method | Returns |
|--------|---------|
| `isActive()` | Whether the bot is currently enabled |
| `getState()` | Current `FishingState` |
| `getTrackedCount()` | Number of currently tracked sea creatures |
| `getCurrentArea()` | Area string from tab list, or `""` |
| `getNearbyText()` | Countdown/nearby entity text, or `""` |
| `shouldBlockRightClick()` | True when right-click should be suppressed (WAITING or BITING state) |

---

## 6. FishingConfig — Configuration

`com.poseidon.core.FishingConfig` — singleton, accessed via `FishingConfig.getInstance()`

Config is stored as JSON at `config/poseidon/config.json`. Loaded once on `onInitializeClient()`. Every setter immediately calls `save()`.

### Known Areas

```java
FishingConfig.KNOWN_AREAS = List.of(
    "Backwater Bayou", "Crimson Isle", "Galatea",
    "Hub", "Jerry's Workshop", "The Park"
)
```

These are the Hypixel Skyblock islands that have per-area sea creature caps in the config. Hub is the fallback cap for any island not in this list.

### Config Groups

**Detection**
- `detectionRadius` — radius around bobber for `!!!` scan (default `4.0`)
- `hookStuckDetectionEnabled` — whether to check for bobber drift (default `true`)
- `hookStuckMaxDistance` — horizontal drift threshold in blocks (default `1.5`)
- `hookStuckSound` — played when hook stuck is detected

**Reaction / Recast**
- `reactionDelayMinMs` / `reactionDelayMaxMs` — random window for reel-in reaction
- `autoRecast` — whether to automatically recast after each catch (default `true`)
- `recastDelayMinMs` / `recastDelayMaxMs` — random window for recast
- `recastDecisionTicks` — how long to wait for chat triggers before deciding to recast

**Sea Creature Tracking**
- `trackSeaCreatures` — master toggle (default `true`)
- `creatureScanRadius` — scan box radius after each reel-in (default `12.0`)
- `seaCreatureCapByArea` — per-island cap map, all default `10`
- `seaCreatureCapSound` — played when cap is reached
- `despawnWarningEnabled` — whether to warn about creature age (default `true`)
- `despawnWarningMinutes` — age threshold for the warning (default `5`)
- `despawnWarningSound` — played when a creature approaches despawn

**Triggers**
- `triggerLevels` — list of 5 `TriggerLevel` objects (all disabled by default)
- `biteAlertSound` — played when `!!!` is first detected (off by default, `durationSeconds=0`)

**Developer**
- `debugMode` — verbose logging flag
- `logLevel` — minimum log level (`LEVEL_WARN` = 2 by default)

### getCapForArea(String area)

Returns the cap for the given area string. Falls back to the Hub cap for any unlisted area. Falls back to `10` if Hub itself has no entry. Minimum returned value is `1`.

### getDespawnWarningTicks()

Converts `despawnWarningMinutes` to game ticks: `minutes × 60 × 20`.

---

## 7. PoseidonHudRenderer — HUD Overlay

`com.poseidon.gui.PoseidonHudRenderer`

**Visibility state:** Stored as a static boolean `hudVisible`, default `false`. Controlled by the H keybind. Closing the HUD while the bot is active automatically calls `setActive(false)`.

**Render method:** `render(DrawContext ctx, RenderTickCounter tick)` — registered via `HudRenderCallback.EVENT`. Draws nothing if `!hudVisible` or player is null.

### Panel Layout

The main panel is always at screen position `(4, 4)`, width 200 pixels. Height is dynamic based on which rows appear.

**Header:** "Poseidon" label left-aligned; state label right-aligned; colored accent bar on the left edge.

**Rows displayed (in order):**

| Row | Label | Value | Color Logic |
|-----|-------|-------|-------------|
| 1 | `Active` | Yes / No | Green if active, red if not |
| 2 | `State` | State name | Matches accent bar color |
| 3 | `Bobber` | None / Detected / \<countdown text\> | Grey=none, Green=detected, Yellow=countdown visible |
| 4 | `Area` | Island name | Grey — only shown if tracking enabled AND area is known |
| 5 | `SC` | `count / cap` | Grey=0, Orange=>0, Red=at or over cap — only shown if tracking enabled |

**Accent bar (left edge) color by state:**

| State | Color |
|-------|-------|
| IDLE (active=false) | Red `0xFFEE4444` |
| IDLE (active=true) | Orange `0xFFFFAA00` |
| WAITING | Green `0xFF44EE44` |
| BITING | Yellow `0xFFFFFF44` |
| REELING | Blue `0xFF44AAFF` |

The state label in the header also uses the same color.

### Log Panel

Appears directly below the main panel when `PoseidonLogger` has entries. Shows the last 5 log lines (truncated to 50 characters each). The `[HH:mm:ss] [LEVEL] ` prefix is stripped, showing only the message.

---

## 8. PoseidonConfigScreen — YACL Config UI

`com.poseidon.gui.PoseidonConfigScreen`

Opened via `/poseidon` command, the `Open Config` keybind (no default), or ModMenu. Has five tabs:

### Tab 1 — Detection

Controls how Poseidon identifies a bite and handles stuck hooks.

| Setting | Type | Default | Range |
|---------|------|---------|-------|
| Armor Stand Radius | double slider | 4.0 | 1.0 – 10.0, step 0.5 |
| Hook Stuck Detection | boolean | true | — |
| Max Drift Distance (blocks) | double slider | 1.5 | 0.5 – 5.0, step 0.25 |
| Hook Stuck Sound | string | `minecraft:entity.villager.no` | sound ID |
| Hook Stuck Volume | double slider | 1.0 | 0.1 – 2.0, step 0.05 |
| Hook Stuck Pitch | double slider | 1.2 | 0.5 – 2.0, step 0.05 |
| Hook Stuck Duration | int slider | 2s | 0 – 30s |
| Hook Stuck Interval | int slider | 10 ticks | 5 – 60 ticks |

### Tab 2 — Reaction Delay

Controls the human reaction delay and auto-recast behaviour.

| Setting | Type | Default | Range |
|---------|------|---------|-------|
| Min Delay (ms) | int slider | 180 | 50 – 2000, step 10 |
| Max Delay (ms) | int slider | 700 | 50 – 3000, step 10 |
| Auto Recast | boolean | true | — |
| Recast Delay Min (ms) | int slider | 200 | 100 – 3000, step 50 |
| Recast Delay Max (ms) | int slider | 600 | 100 – 5000, step 50 |
| Trigger Wait (ticks) | int slider | 10 | 2 – 40, step 1 |

The Trigger Wait slider uses a custom formatter: `"N ticks (N×50 ms)"`.

Ping guidance shown in the description:
- Low ping (<80 ms): 5–10 ticks
- Medium ping (80–200 ms): 10–15 ticks
- High ping (200+ ms): 20–30 ticks

### Tab 3 — Sea Creature Tracking

| Setting | Type | Default | Range |
|---------|------|---------|-------|
| Enable Tracking | boolean | true | — |
| Scan Radius | double slider | 12.0 | 5.0 – 30.0, step 1.0 |
| Backwater Bayou Cap | int slider | 10 | 1 – 50 |
| Crimson Isle Cap | int slider | 10 | 1 – 50 |
| Galatea Cap | int slider | 10 | 1 – 50 |
| Hub Cap (default) | int slider | 10 | 1 – 50 |
| Jerry's Workshop Cap | int slider | 10 | 1 – 50 |
| The Park Cap | int slider | 10 | 1 – 50 |
| Cap Alert Sound | string | `minecraft:entity.player.levelup` | sound ID |
| Cap Alert Volume | double slider | 1.0 | 0.1 – 2.0 |
| Cap Alert Pitch | double slider | 0.8 | 0.5 – 2.0 |
| Cap Alert Duration | int slider | 10s | 0 – 30s |
| Cap Alert Interval | int slider | 20 ticks | 5 – 60 ticks |
| Enable Despawn Warning | boolean | true | — |
| Warning At (minutes) | int slider | 5 | 1 – 6, formatter: "N min" |
| Despawn Warning Sound | string | `minecraft:block.bell.use` | sound ID |
| Despawn Warning Volume | double slider | 1.0 | 0.1 – 2.0 |
| Despawn Warning Pitch | double slider | 0.8 | 0.5 – 2.0 |
| Despawn Warning Duration | int slider | 5s | 0 – 30s |
| Despawn Warning Interval | int slider | 20 ticks | 5 – 60 ticks |

Note: Hub Cap is labelled "Hub Cap §7(default)" — Hub is the fallback cap for any island not in the list.

### Tab 4 — Chat Triggers

Contains a header label explaining trigger syntax, then five `OptionGroup` blocks (one per trigger slot), followed by the Bite Alert sound at the bottom.

**Each trigger group has:**

| Setting | Type | Default |
|---------|------|---------|
| Enabled | boolean | false |
| Name | string | `""` |
| Patterns | string | `""` (comma-separated substrings) |
| Action | string | `""` (unused, reserved) |
| Show Title | boolean | false |
| Title Text | string | `""` (falls back to Name if blank) |
| Don't Recast | boolean | false |
| Stop Bot | boolean | false |
| Sound (5 fields) | AlarmSound | defaultBite defaults |

**Bite Alert** (at bottom, after all triggers):
| Setting | Default |
|---------|---------|
| Bite Alert Sound | `minecraft:entity.experience_orb.pickup` |
| Volume | 1.0 |
| Pitch | 1.5 |
| Duration | 0s (off by default — set above 0 to enable) |
| Interval | 15 ticks |

The bite alert is silent by default (`durationSeconds=0`). The `AlarmSound.play()` method returns early if duration is 0.

> The live config screen also has **Bait**, **Stats & Reboot Alert**, and **Updates** tabs (added in 1.1.0) between the ones documented above; their options map directly to the corresponding `FishingConfig` getters/setters.

### Golden Fish Tab

Optional alert (off by default). Built by `buildGoldenFishCategory()`.

| Setting | Type | Default |
|---------|------|---------|
| Golden Fish Alert | boolean | false |
| Trigger Phrase | string | `spot a Golden Fish surface` (comma-separated, case-insensitive) |
| Title Text | string | `§6§lGOLDEN FISH` (supports `§`/`&` codes) |
| Alert Sound (5 fields) | AlarmSound | `entity.player.levelup`, 4s duration |

When the phrase matches a server message while the bot is active, Poseidon shows the title, plays the sound, reels in any active cast, and stops the bot (`FishingManager.handleGoldenFish()`). This is checked in `onChatReceived()` ahead of the catch-window/catch-message gates, since the announcement can arrive at any time while fishing.

### Tab 5 — Developer

| Setting | Type | Default |
|---------|------|---------|
| Debug Mode | boolean | false |
| Log Level | cycling list (DEBUG/INFO/WARN/ERROR) | WARN |

---

## 9. MouseClickMixin — Right-click Guard

`com.poseidon.mixin.MouseClickMixin`

```java
@Mixin(Mouse.class)
public class MouseClickMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void poseidon$blockRightClickWhenFishing(
        long window, MouseInput mouseInput, int action, CallbackInfo ci)
```

**What it does:** Injects at the very start of `Mouse.onMouseButton()` and cancels the event (preventing the right-click from reaching the game) when all of the following are true:
- The mouse action is `GLFW_PRESS` (not release or repeat)
- The button is `GLFW_MOUSE_BUTTON_RIGHT`
- `FishingManager.shouldBlockRightClick()` returns `true`

**When `shouldBlockRightClick()` is true:** The bot is active AND the state is `WAITING` or `BITING`.

**Why this is needed:** When fishing in Minecraft, right-clicking the rod while a bobber is in the water reels it in (the same action used for the intentional reel-in). Without this guard, any accidental right-click during the `WAITING` period would cancel the active cast, leaving the bot stuck. The mixin prevents that at the lowest possible level — before the input reaches `ClientPlayerInteractionManager`.

This mixin is intentionally narrow. It does **not** block right-click in `REELING` state (the reel-in has already been sent) or `IDLE` state (no cast is active).

---

## 10. PoseidonLogger — Logging

`com.poseidon.core.PoseidonLogger` — singleton, accessed via `PoseidonLogger.getInstance()`

### Log Levels

| Constant | Value | Usage |
|----------|-------|-------|
| `LEVEL_DEBUG` | 0 | Verbose detection output |
| `LEVEL_INFO` | 1 | Normal operational events |
| `LEVEL_WARN` | 2 | Unexpected situations (default) |
| `LEVEL_ERROR` | 3 | Failures and exceptions |

Default level is `LEVEL_INFO` on construction, then overridden to `LEVEL_WARN` when config loads.

### Output

- **In-memory ring buffer:** Last 50 lines kept in a `ArrayDeque<String>`, accessed via `getRecentLines()`. Used by `PoseidonHudRenderer` for the log panel. Thread-safe via `synchronized`.
- **File output:** Appended to `config/poseidon/poseidon.log` on every log call. File is opened once at construction in APPEND mode. Auto-flush enabled.

### Line Format

```
[HH:mm:ss] [LEVEL] message
```

Example: `[14:32:07] [INFO] Bobber detected — watching for !!!`

---

## 11. PoseidonMod — Entry Point & Event Wiring

`com.poseidon.PoseidonMod` — implements `ClientModInitializer`

### Initialization Sequence (`onInitializeClient`)

1. Log "Poseidon initialising..."
2. `FishingConfig.getInstance().load()` — load config from disk (or create defaults)
3. `registerKeybinds()` — register Y, H, and the no-default config key
4. `registerCommands()` — register `/poseidon` and the ALLOW_COMMAND fallback
5. `HudRenderCallback.EVENT.register(PoseidonHudRenderer::render)`
6. `PlayerAPIEvents.TICK.register(this::onTick)`
7. `PlayerAPIEvents.CHAT_RECEIVED.register(this::onChatReceived)`
8. Log "Poseidon ready."

### `/poseidon` Command

Registered via Fabric's `ClientCommandRegistrationCallback`. Sets `openConfigNextTick = true`, which causes the config screen to open on the next tick. The tick delay avoids a race condition with the chat screen closing.

A fallback is also registered via `ClientSendMessageEvents.ALLOW_COMMAND` for servers that override the client command tree. If the command `poseidon` arrives via this path, it sets the flag and returns `false` (block the message from being sent to the server).

### onTick()

Runs every game tick:
1. Guard: `if (!PlayerInfo.isInWorld()) return`
2. If `openConfigNextTick`: clear flag, open `PoseidonConfigScreen`
3. `FishingManager.getInstance().tick()`
4. `handleKeybinds()`

### handleKeybinds()

Polls three keybinds for presses:

**Y (Toggle Fishing):**
- If active: `setActive(false)`
- If inactive AND HUD visible: `setActive(true)`
- If inactive AND HUD not visible: log warning ("Open the HUD first (H) before starting")
- This enforces the workflow: open HUD → toggle on, rather than allowing an invisible active state.

**H (Toggle HUD):**
- Flips `PoseidonHudRenderer.hudVisible`
- If HUD was just closed AND bot is active: calls `setActive(false)` and logs "HUD closed — fishing stopped"

**Config key (no default):**
- Opens `PoseidonConfigScreen` immediately

### onChatReceived(String sender, String message)

Iterates `FishingConfig.getTriggerLevels()` in order. For the first trigger that matches (`level.matches(message)`):
1. Logs the match
2. `level.sound.play()`
3. If `level.showTitle`: calls `showTitle(level)` (see below)
4. `FishingManager.getInstance().notifyTriggerFired(level.dontRecast, level.stopBot)`
5. Break — only the first matching trigger fires

**showTitle(TriggerLevel level):**
Uses `DisplayActions.showTitle(text, "")` from PlayerAPI. The title text is:
- `level.titleText` if not blank
- Otherwise `level.name`
- Otherwise the hardcoded fallback `"Trigger Fired"`

`DisplayActions` is a PlayerAPI class that wraps Minecraft's `InGameHud.setTitle()` / `setSubtitle()` to display the big MC title overlay.

---

## 12. Chat Triggers System

### Overview

Poseidon has 5 configurable trigger slots. Each trigger watches incoming chat messages for keyword matches. When a match is found, it can:

- Play an alarm sound
- Show a Minecraft title overlay
- Suppress the automatic recast (so you can deal with whatever caught your attention)
- Stop the bot entirely (HUD stays open)

Triggers are checked in order from slot 1 to slot 5. The first match wins — later triggers do not fire for the same message.

### TriggerLevel Fields

```java
public String  name        // display label (shown in config group header and logs)
public boolean enabled     // whether this trigger is active
public String  patterns    // comma-separated match substrings, case-insensitive
public AlarmSound sound    // sound to play on match
public String  action      // unused (reserved for future use, leave blank)
public boolean dontRecast  // suppress auto-recast after this catch
public boolean stopBot     // deactivate the bot after this catch
public boolean showTitle   // show a MC title overlay
public String  titleText   // title text (uses `name` if blank)
```

### Pattern Matching

`TriggerLevel.matches(String chatText)`:
- Returns `false` if `!enabled` or patterns is blank
- Splits patterns on commas, trims each part, lowercases both
- Returns `true` if any non-empty pattern is found as a substring of the chat text
- Case-insensitive substring match, not regex

Example patterns for Hypixel Skyblock sea creature events: `"appeared, emerged"`.

### Interaction with Recast Decision Window

Chat messages arrive asynchronously relative to the reel-in. `recastDecisionTicks` is the window between the reel-in action and the recast decision. If a trigger fires within this window, its `dontRecast`/`stopBot` flags will be seen by the decision callback.

If a trigger fires **after** the decision window closes, the flags are ignored. Increasing `recastDecisionTicks` (or the associated millisecond equivalent) allows more time for high-latency servers.

---

## 13. Sea Creature Tracking System

### Overview

After each reel-in, Poseidon scans for entities near the bobber position bearing the ⚓ anchor character (U+2693). This is the prefix Hypixel uses on sea creature name plates. New creatures are added to a `List<TrackedSeaCreature>` with their entity ID, name, and spawn tick.

### Detection Mechanism

`isSeaCreatureDisplay(Entity entity)`:
- Checks `entity.getCustomName().getString().contains("⚓")` for armor stands / mob name plates
- Checks `TextDisplayEntity.getText().getString().contains("⚓")` for text display entities

`extractCreatureName(Entity entity)`:
- Reads raw entity text
- Finds the `⚓` character, takes everything after it (trimmed)
- Strips from the first digit onward (start of the HP value)
- Returns the resulting name, or `"Unknown"` if parsing fails
- Full format Hypixel uses: `"[LvN] ⚓ CreatureName HP/MaxHP❤"`

### Scan Timing

The scan runs `SCAN_DELAY_TICKS = 5` ticks after the reel-in. This delay is intentional: the bobber entity typically despawns within 1–2 ticks of the `use` key press. Anchoring the scan to the saved `lastBobberX/Y/Z` position rather than the current bobber position (which may be gone) ensures the scan always runs at the right place.

### Cleanup

`cleanupDeadCreatures(MinecraftClient mc)` runs every `CLEANUP_PERIOD_TICKS = 40` ticks (2 seconds):
1. **Despawn warnings**: If `despawnWarningEnabled`, checks each tracked creature's age. If `(currentTick - spawnTick) >= despawnWarningTicks`, and the alert hasn't fired yet, plays `despawnWarningSound` and logs a warning.
2. **Dead removal**: `tracked.removeIf(t -> mc.world.getEntityById(t.entityId) == null)` — removes any creature whose entity is no longer in the world.
3. **Cap reset**: If the tracked count falls below the cap after removal, resets `capAlertFired = false` so the alert can fire again next time.

### Cap Alert

`checkCapAlert()` is called after each scan finds new creatures. If `tracked.size() >= cap` and `!capAlertFired`, plays `seaCreatureCapSound` and sets `capAlertFired = true`. The cap used is `cfg.getCapForArea(currentArea)`.

### TrackedSeaCreature (inner class)

```java
final int     entityId         // MC entity ID for alive-check
final String  name             // extracted display name
final long    spawnTick        // Scheduler.getCurrentTick() at detection time
boolean       despawnAlertFired // prevents duplicate despawn warnings
```

---

## 14. Hook Stuck Detection

### Problem

When casting near mobs, the fishing hook can attach to the mob instead of landing in water. The bobber entity exists and is valid, so the normal state machine would wait indefinitely for a bite that will never come. The hook moves with the mob, never triggering `!!!`.

### Detection Mechanism

After the bobber lands, a 1-second settle period (`BOBBER_SETTLE_TICKS = 20`) runs. During this window, `initialBobberX/Z` is continuously updated to the current bobber XZ position (so we measure from the final resting place, not the arc peak).

Once the settle period ends, every tick:
1. Compute horizontal displacement: `dx = bobber.X - initialBobberX`, `dz = bobber.Z - initialBobberZ`
2. Compute Euclidean distance: `dist = sqrt(dx² + dz²)`
3. If `dist > hookStuckMaxDistance` (default 1.5):
   - Set `hookStuckFired = true` (prevents alert spam for the same cast)
   - Play `hookStuckSound`
   - Transition to `REELING`, call `MovementActions.tapKey("use", 100)`
   - Schedule state reset after 10 ticks, then schedule recast if `autoRecast`

### Why Horizontal Only?

Normal fishing bobbers bob vertically as they float on water. The vertical amplitude is typically < 0.5 blocks. Horizontal drift essentially never occurs for a bobber floating in water. By checking only XZ displacement, the system avoids false positives from the normal bobbing animation.

### Configuration

- `hookStuckDetectionEnabled` (bool, default true) — master toggle
- `hookStuckMaxDistance` (double, default 1.5 blocks) — drift threshold, range 0.5–5.0
- `hookStuckSound` (`AlarmSound`, default: `minecraft:entity.villager.no`)

---

## 15. AlarmSound Data Class

`FishingConfig.AlarmSound` — public static inner class of `FishingConfig`

Represents a single repeating sound alert. Used for all sounds throughout Poseidon.

### Fields

| Field | Type | Meaning |
|-------|------|---------|
| `soundId` | String | Minecraft sound ID (`"namespace:sound.path"`) |
| `volume` | double | 0.1 (quiet) – 2.0 (loud), 1.0 = normal |
| `pitch` | double | 0.5 (slow/deep) – 2.0 (fast/high), 1.0 = normal |
| `durationSeconds` | int | Total seconds the alarm plays; 0 = play once, silent if 0 for bite alert |
| `intervalTicks` | int | Ticks between each repeat (20 ticks = 1 second) |

### Default Instances

```java
AlarmSound.defaultBite()
// soundId=minecraft:entity.experience_orb.pickup, vol=1.0, pitch=1.5, dur=0, interval=15
// Note: dur=0 means silent by default. Set duration > 0 to enable.

AlarmSound.defaultAlert()
// soundId=minecraft:entity.player.levelup, vol=1.0, pitch=1.0, dur=8, interval=20
```

### play()

```java
public void play() {
    if (soundId == null || soundId.isBlank() || durationSeconds <= 0) return;
    int times = Math.max(1, (durationSeconds * 20) / Math.max(1, intervalTicks));
    SoundActions.playByIdRepeated(soundId, (float) volume, (float) pitch, times, intervalTicks);
}
```

Calculates repeat count from duration and interval. Returns early if silent (duration ≤ 0).

### mergeFrom(AlarmSound src, AlarmSound defaults)

Used during config load to merge file values over defaults while protecting against zeroed/null fields:
- Uses `src.soundId` if non-blank, else `defaults.soundId`
- Uses `src.volume/pitch` if > 0, else defaults
- Uses `src.durationSeconds/intervalTicks` if > 0, else defaults

This means a config file cannot set volume to exactly 0.0 (it would fall back to the default). This is an intentional safeguard against silent-by-mistake configs.

---

## 16. Config Schema & Migration History

The config file includes a `configVersion` integer. On load, the `migrate()` method chains through all versions in order, bringing old configs forward automatically.

### Migration Chain

```
v0 → v1: flat biteAlert* fields → biteAlertSound AlarmSound object; triggerLevels added
v1 → v2: single seaCreatureCap int → seaCreatureCapByArea Map (all areas get the old cap value)
v2 → v3: autoRecast added (GSON defaults bool to false; migration injects true for old configs)
v3 → v4: recastDecisionTicks added (was hardcoded 40 in old code, default now 10)
v4 → v5: despawnWarningEnabled, despawnWarningMinutes added
v5 → v6: hookStuckDetectionEnabled, hookStuckMaxDistance added
```

**GSON boolean default caveat:** GSON creates objects by bypassing constructors. Any new boolean field added to `FishingConfig` that should default to `true` must be injected in the migration step, not just added as a Java field initializer, because GSON will produce `false` for a missing field. Poseidon handles this correctly via migrations (v2→v3 for `autoRecast`, v4→v5 for `despawnWarningEnabled`, v5→v6 for `hookStuckDetectionEnabled`).

### KNOWN_AREAS Static Init Order

`FishingConfig.KNOWN_AREAS` is declared as the first static field specifically because the `INSTANCE = new FishingConfig()` line (which comes just after) triggers the constructor, and `defaultCapsByArea()` iterates `KNOWN_AREAS`. If `INSTANCE` were declared first, `KNOWN_AREAS` would be `null` during the constructor call. The field order is load-bearing.

---

## 17. Keybinds & Commands

### Keybinds

All registered under the `Poseidon Controls` category in Minecraft's keybind settings.

| Key | Default | Action |
|-----|---------|--------|
| Y | Y | **Toggle Fishing** — starts the bot (if HUD is open) or stops it |
| H | H | **Toggle HUD** — shows/hides the HUD overlay; also stops the bot if HUD is closed while active |
| (none) | Unbound | **Open Config** — opens the YACL config screen directly |

**Start workflow:** The Y key will refuse to start the bot if the HUD is not visible. The intended flow is: H to open HUD, then Y to start fishing.

### Commands

| Command | Effect |
|---------|--------|
| `/poseidon` | Opens the YACL configuration screen |

The command uses a tick-delay trick (`openConfigNextTick`) to avoid a race where the config screen tries to open while the chat screen is still closing.

A fallback via `ClientSendMessageEvents.ALLOW_COMMAND` handles the case where a server overrides the client command tree and intercepts `/poseidon` before Fabric's `ClientCommandManager` can handle it.

---

## 18. HUD Reference

### When is it shown?

Only when `PoseidonHudRenderer.hudVisible == true`. Default is `false` on launch. H key toggles it.

### Main Panel

Position: top-left `(4, 4)`.
Width: 200 pixels.
Height: dynamic — header (13 px) + divider (1 px) + padding (5 px) + rows × 11 px + bottom padding (5 px).

```
┌── Poseidon ─────────── [STATE] ──┐  ← colored accent bar + header
│                                   │  ← divider line
│  Active      Yes / No             │
│  State       IDLE / WAITING / ... │
│  Bobber      None / Detected / .. │
│  Area        Hub             (opt)│
│  SC          4 / 10          (opt)│
└───────────────────────────────────┘
```

Area row only appears if sea creature tracking is enabled AND `currentArea` is not blank.
SC row only appears if sea creature tracking is enabled.

### Log Panel

Appears immediately below the main panel (3 px gap). Shows last 5 log lines. Format: message text only (prefix stripped). Max 50 characters per line, truncated with `…`.

### State → Color Mapping

| Bot Active | FishingState | Color | Header Label |
|------------|-------------|-------|--------------|
| false | IDLE | Red `0xFFEE4444` | OFF |
| true | IDLE | Orange `0xFFFFAA00` | IDLE |
| true | WAITING | Green `0xFF44EE44` | WAITING |
| true | BITING | Yellow `0xFFFFFF44` | BITING |
| true | REELING | Blue `0xFF44AAFF` | REELING |

When active=false, the header label shows `"OFF"` rather than `"IDLE"`.

### Bobber Row Color

| Condition | Color | Value |
|-----------|-------|-------|
| No bobber | Grey `0xFF888888` | `"None"` |
| Bobber present, no nearby text | Green `0xFF44EE44` | `"Detected"` |
| Bobber present, nearby text found | Yellow `0xFFFFCC00` | The countdown text (≤20 chars) |

### SC Row Color

| Condition | Color |
|-----------|-------|
| At or over cap | Red `0xFFFF4444` |
| Tracked > 0 | Orange `0xFFFFAA00` |
| Tracked = 0 | Grey `0xFF888888` |

---

## 19. Configuration Reference (All Settings)

All settings live in `config/poseidon/config.json`. Keys match the Java field names.

### Detection

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `detectionRadius` | double | 4.0 | Scan box half-size around bobber for `!!!` signal |
| `hookStuckDetectionEnabled` | bool | true | Enable horizontal drift check |
| `hookStuckMaxDistance` | double | 1.5 | Drift threshold in blocks |
| `hookStuckSound` | AlarmSound | villager.no, 1.0, 1.2, 2s, 10t | — |

### Reaction & Recast

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `reactionDelayMinMs` | int | 180 | Min reaction delay in ms |
| `reactionDelayMaxMs` | int | 700 | Max reaction delay in ms |
| `autoRecast` | bool | true | Auto-recast after each catch |
| `recastDelayMinMs` | int | 200 | Min recast delay in ms |
| `recastDelayMaxMs` | int | 600 | Max recast delay in ms |
| `recastDecisionTicks` | int | 10 | Ticks to wait for triggers before recast decision |

### Sea Creature Tracking

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `trackSeaCreatures` | bool | true | Master toggle |
| `creatureScanRadius` | double | 12.0 | Scan radius after each reel-in |
| `seaCreatureCapByArea` | Map\<String,Integer\> | all 10 | Per-island cap; Hub is fallback |
| `seaCreatureCapSound` | AlarmSound | levelup, 1.0, 0.8, 10s, 20t | — |
| `despawnWarningEnabled` | bool | true | — |
| `despawnWarningMinutes` | int | 5 | Age threshold for despawn warning |
| `despawnWarningSound` | AlarmSound | bell.use, 1.0, 0.8, 5s, 20t | — |

### Triggers

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `triggerLevels` | List\<TriggerLevel\> | 5 empty/disabled | — |
| `biteAlertSound` | AlarmSound | orb.pickup, 1.0, 1.5, 0s, 15t | Duration 0 = disabled |

### Developer

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `debugMode` | bool | false | — |
| `logLevel` | int | 2 (WARN) | 0=DEBUG, 1=INFO, 2=WARN, 3=ERROR |
| `configVersion` | int | 6 | Do not edit manually |

---

## 20. Timing Reference

| Constant | Value | Location | Purpose |
|----------|-------|----------|---------|
| `SCAN_DELAY_TICKS` | 5 | FishingManager | Delay after reel-in before scanning for new sea creatures |
| `CLEANUP_PERIOD_TICKS` | 40 | FishingManager | How often to check for despawned creatures (2 seconds) |
| `AREA_REFRESH_TICKS` | 40 | FishingManager | How often to re-read "Area:" from tab list (2 seconds) |
| `BOBBER_SETTLE_TICKS` | 20 | FishingManager | Settle window before hook stuck drift check starts (1 second) |
| State reset delay | 10 ticks | FishingManager | After reel-in fires, time before REELING→IDLE transition |
| `recastDecisionTicks` | 10 (configurable) | FishingConfig | Wait for chat triggers before recast decision |
| `reactionDelayMinMs` | 180 (configurable) | FishingConfig | Min human reaction delay |
| `reactionDelayMaxMs` | 700 (configurable) | FishingConfig | Max human reaction delay |
| `recastDelayMinMs` | 200 (configurable) | FishingConfig | Min recast delay |
| `recastDelayMaxMs` | 600 (configurable) | FishingConfig | Max recast delay |
| `despawnWarningMinutes` | 5 (configurable) | FishingConfig | Age at which despawn warning fires |

20 ticks = 1 second. 1 tick ≈ 50 ms.

---

## 21. Design Decisions & Notes

### Why a Settle Period for Hook Stuck Detection?

During the cast arc, the bobber travels through the air before landing. Its XZ position changes dramatically during this arc. Without the settle period, every cast would immediately trigger a hook stuck alert because the bobber just flew from the player position to the water. The 1-second settle window (20 ticks) waits for the bobber to reach its final resting position in the water before measuring drift.

### Why Save the Bobber Position Before Reeling In?

The bobber entity (`fishHook`) can despawn within 1–2 ticks of the reel-in action being sent. By the time the `Scheduler.schedule(SCAN_DELAY_TICKS, ...)` callback fires, `mc.player.fishHook` is almost certainly null. The bobber XZ/Y coordinates are therefore captured at the moment the `!!!` signal is detected, and those saved coordinates are passed as parameters to the delayed sea creature scan.

### Why Use `ALLOW_COMMAND` as a Fallback?

On servers like Hypixel, the server pushes a custom command tree to the client that may not include `/poseidon`. In that case, Fabric's `ClientCommandManager` would not intercept `/poseidon` before it is sent to the server. The `ALLOW_COMMAND` event fires before the message is sent over the network, catching this case. The handler returns `false` (blocking the send) and sets the flag.

### Why the Bobber-in-REELING Guard?

`scheduleRecast()` skips if `state == FishingState.WAITING || state == FishingState.BITING`. The `WAITING` check handles the case where the server automatically recasts the rod (some Hypixel modes do this) — if a bobber has already appeared by the time the recast fires, there is no need to cast again. The `BITING` check handles an edge case where a new bite signal appears extremely quickly.

### Why Only 5 Trigger Slots?

Trigger slots are serialized as a fixed-length list in the config JSON and the YACL UI generates one group per slot. A fixed count of 5 was chosen because Hypixel Skyblock fishing typically needs only 2–3 practical triggers (e.g., "sea creature appeared", "rare creature appeared", "stop on special drop"). Five slots gives margin without making the config screen unwieldy. The list is not hard-limited internally — `getTriggerLevels()` returns whatever the list contains — but the UI and defaults always produce exactly 5.

### Why is `shouldBlockRightClick()` Only True for WAITING and BITING?

In `IDLE` state: no cast is active, so right-click should work normally (it casts the rod).
In `REELING` state: the reel-in has already been sent, so the right-click guard would block a second reel-in. The 10-tick window between the reel-in send and the IDLE reset is short enough that this is not a problem in practice. If the guard were active during `REELING`, a player who clicked to reel in manually after the bot sent the key would be blocked from doing so.

### DisplayActions (PlayerAPI)

Poseidon uses `DisplayActions.showTitle(title, subtitle)` from PlayerAPI. This class was added to PlayerAPI to wrap Minecraft's `InGameHud` title display functionality. It lets Poseidon show a large on-screen MC title when a chat trigger fires, without importing Minecraft's `InGameHud` directly.

### Area as Fallback Key

The `seaCreatureCapByArea` map uses the area string exactly as read from the Hypixel tab list "Area:" line. The map falls back to the Hub cap for any unrecognised string. This means if Hypixel changes an island's display name, the affected island silently falls back to Hub's cap rather than crashing. Hub itself falls back to hardcoded `10` if not in the map.

### Thread Safety

`PoseidonLogger.recentLines` is synchronized because `getRecentLines()` is called from the render thread while `log()` is called from the game thread. All other state (`FishingManager`, `FishingConfig`) is only touched on the main client thread (via `TICK` event and `Scheduler` callbacks), so no synchronization is needed there.
