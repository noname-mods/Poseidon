# Poseidon

A client-side Fabric mod that automates rod fishing on Hypixel Skyblock — cast, wait for the bite, reel in, and recast, all with configurable human-like timing.  
Named after the Greek god of the sea.

**GitHub:** <https://github.com/noname-mods/Poseidon>

> **Requires [PlayerAPI](https://github.com/noname-mods/PlayerAPI) and [YetAnotherConfigLib](https://modrinth.com/mod/yacl) to run.**  
> [ModMenu](https://modrinth.com/mod/modmenu) is optional — it adds a settings button to the mod list.

---

## Features

### Smart Bite Detection
Poseidon scans nearby entities for the `!!!` signal the server places when your bobber gets a bite. When detected it reels in after a configurable, randomised human reaction delay so the timing never looks mechanical.

### Auto-Recast
After every catch the rod is automatically recast. A configurable decision window gives server chat time to arrive before committing, so chat triggers that suppress or stop the recast always fire in time. Recast delay is independently randomisable (min/max ms) and can be disabled entirely for manual-cast mode.

### Chat Triggers
Define up to 5 keyword triggers that match incoming catch messages. Each trigger can:
- Play a custom sound
- Show a colour-formatted title on screen (`&d` / `§d` codes fully supported)
- Suppress the next auto-recast
- Stop the bot entirely (HUD stays open)

Triggers only fire within 10 seconds of a reel-in and are filtered to Hypixel catch-related messages (`§a` catch lines, `§e` double hook announcements, `⛃` treasure messages), so unrelated chat never causes a false trigger.

**Server-message gate.** Every chat alert in Poseidon — chat triggers, the reboot alert, and the Golden Fish alert — reacts only to messages from the server, never to player chat. A player typing a trigger phrase (or the Golden Fish line) in chat can't drive the bot. Signed player messages are rejected by their sender, and Hypixel-style reformatted player chat (`[rank] Name: …`, `Guild > …`, DMs) is rejected by shape.

### Bait Monitoring
Reads the bait from your Fishing Bag (last hotbar slot) at cast time:
- **HUD row** shows the current bait type and count
- **Low-bait alert** fires a sound when the count drops to or below a configurable threshold
- **Bait-switch alert** fires when the bait type changes mid-session

### Fishing Stats HUD
Pulls Double Hook Chance, Sea Creature Chance, Fishing Speed, and Treasure Chance from the Hypixel tab list and displays them live in the HUD panel.

### Server Reboot Alert
Detects Hypixel's scheduled-reboot server message and plays a looping alarm until you warp to a different area. The HUD accent bar turns red and a warning row appears at the top of the panel so you can't miss it.

### Golden Fish Alert *(optional, off by default)*
Watches chat for the Golden Fish surface message. When it appears, Poseidon shows a golden title card, plays an alert sound, reels in any active cast, and stops the bot — handing control to you so you can catch the Golden Fish manually. Re-enable the bot afterwards to resume. The trigger phrase, title text, and sound are all configurable in the Golden Fish config tab.

### Sea Creature Tracking
After each reel-in, Poseidon scans the area for `⚓` nameplate entities. Tracked creatures are shown in the HUD and you get an alert when you hit the Hypixel cap of 10. A second alert fires when a creature approaches its despawn timer (~6 minutes) so you know to kill it before it vanishes. Nameplate entity refreshes (same creature, new ID) are detected by position and handled correctly — no false double-counts.

### Bobber Drift Detection
If the hook attaches to a moving entity, the bobber drifts away from where it landed. Poseidon detects drift beyond a configurable horizontal threshold (after a 1-second settle period), plays an alert, reels in, and optionally recasts — keeping the bot from stalling on a moving target. Auto-recast on drift is a separate toggle from the global auto-recast setting.

### Slugfish Mode
Delays all reel-ins for 21 seconds after each cast (11 seconds with a max-level Slug Pet equipped). Slugfish only bite after ≥ 20 seconds, so this prevents accidentally catching regular creatures while targeting the Slugfish trophy fish. The HUD shows a live countdown and turns green when the timer has elapsed.  
⚠ *Only enable while actively farming the Slugfish trophy fish.*

### GUI-Close Lock
After the player closes any GUI screen, the bot waits a random 0.75 – 2.5 second delay before reacting to bite signals. Instant reactions right after closing a menu look suspicious — this eliminates that pattern entirely.

### Live HUD
An in-game overlay (default key: **H**) shows:
- Bot on/off and current state
- Bobber status — None / Detected / live countdown text
- Bait name and count
- Slugfish countdown when in Slugfish mode
- Sea creature count vs. cap, and current area
- Fishing stats (DHC, SCC, Speed, Treasure)
- Server reboot warning when detected
- Last 5 log lines

### Fully Configurable
All timings, thresholds, sounds, and toggles are exposed through a YACL config screen. Open it from ModMenu, the `/poseidon` command, or a keybind. No file editing required.

---

## Controls

All keybinds are rebindable in **Options → Controls → Poseidon**.

| Action | Default Key |
|---|---|
| Toggle Fishing | Y |
| Toggle HUD | H |
| Open Config | *(unbound)* |

Type `/poseidon` in chat to open the config screen directly.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 26.1.2
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Install [PlayerAPI](https://github.com/noname-mods/PlayerAPI)
4. Install [YetAnotherConfigLib](https://modrinth.com/mod/yacl)
5. Install [ModMenu](https://modrinth.com/mod/modmenu) *(optional)*
6. Drop `poseidon-*.jar` into your `mods` folder

---

## Compatibility

| Minecraft | Fabric Loader | Java |
|---|---|---|
| 26.1.2 | ≥ 0.19.2 | 21 |

---

## Minecraft Version Support

This mod targets **one Minecraft version at a time.** When it updates to a new Minecraft version, **previous versions receive zero further support** — no backports, no bug fixes, and a release is never published with support for multiple Minecraft versions at once.

- Want the newest features? You must be on the mod's currently supported Minecraft version.
- Want to stay on an older Minecraft version? Stay on that version's last release — it won't be updated.

The in-game update checker is Minecraft-version aware: if the latest release targets a different Minecraft version than you're running, it tells you so instead of prompting you to install an incompatible build.

---

## For Developers

# Poseidon — Design & Documentation

**Version:** 1.1.0 (config schema v13)  
**Platform:** Fabric 26.1.2, Java 21  
**Dependencies:** PlayerAPI, Fabric API, YACL v3, ModMenu (optional)  
**Mod ID:** `poseidon`  
**Entry point:** `com.poseidon.PoseidonMod`  
**Config file:** `<game_dir>/config/poseidon/config.json`  
**Log file:** `<game_dir>/config/poseidon/poseidon.log`

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

Poseidon depends on PlayerAPI for all Minecraft interaction and scheduling. It never calls Minecraft APIs directly for player input — everything goes through `MovementActions`, `Scheduler`, `TabListInfo`, `SoundActions`, `PlayerAPIEvents`.

---

## 2. Architecture Overview

```
PoseidonMod (entry point)
├── registers keybinds (Y, H, no-default)
├── registers /poseidon command
├── subscribes to PlayerAPIEvents.TICK → onTick()
├── subscribes to PlayerAPIEvents.CHAT_RECEIVED → onChatReceived()
├── subscribes to PlayerAPIEvents.WORLD_JOIN → onWorldJoin()
└── registers HudRenderCallback → PoseidonHudRenderer.render()

onTick() each game tick:
├── opens config screen if openConfigNextTick flag set
├── FishingManager.tick() — core state machine
├── RebootAlertManager.tick() — drives reboot alarm loop
└── handleKeybinds() — polls keybind presses

onChatReceived(sender, message):
├── RebootAlertManager.onChatReceived() — always runs
├── Guard: isInCatchWindow() — only within 10s of a reel-in
├── Guard: isCatchMessage() — §a, §e, or ⛃-prefixed only
└── iterates FishingConfig.getTriggerLevels(), first match fires

FishingManager.tick() (only when active):
├── GUI-close lock: tracks open→closed transitions, arms random delay
├── IDLE state:    waits for bobber; idle watchdog retries failed recasts
├── WAITING state: runs detectBite() every tick
│   ├── bite found + guiLocked → skip (retry next tick)
│   ├── bite found + slugfish timer not elapsed → skip (retry next tick)
│   ├── bite found → saves bobber pos → BITING, schedules reel-in
│   └── no bite: updates nearbyText, runs checkHookStuck()
├── BITING state:  waits for scheduled reel-in; reacts to bobber loss
└── REELING state: waits for bobber to disappear
```

**Threading model:** Everything runs on the main Minecraft client thread. `PlayerAPIEvents.TICK` fires on the client tick. `Scheduler` callbacks also execute on the client thread. The update checker uses a daemon thread for HTTP. `PoseidonLogger` file I/O also runs on a background thread (append mode).

---

## 3. Package Structure

```
com.poseidon
├── PoseidonMod.java              — ClientModInitializer, event wiring, keybinds, commands
├── core/
│   ├── FishingState.java         — enum: IDLE, WAITING, BITING, REELING
│   ├── FishingManager.java       — singleton state machine, all fishing logic
│   ├── FishingConfig.java        — singleton config, persistence, migrations
│   ├── RebootAlertManager.java   — detects Hypixel reboot messages, drives alarm loop
│   └── PoseidonLogger.java       — singleton logger, in-memory ring buffer + file output
├── gui/
│   ├── PoseidonHudRenderer.java  — HUD overlay drawn each frame
│   └── PoseidonConfigScreen.java — YACL config screen builder
├── mixin/
│   └── MouseClickMixin.java      — blocks right-click during active cast (not in GUIs)
└── modmenu/
    └── PoseidonModMenuPlugin.java — ModMenu integration
```

---

## 4. FishingState — State Enum

`com.poseidon.core.FishingState`

| State | Meaning |
|-------|---------|
| `IDLE` | Bot active but no rod cast yet. No bobber present. |
| `WAITING` | Bobber in water. Scanning every tick for the `!!!` bite signal. Also watching for nearby countdown text and hook drift. |
| `BITING` | The `!!!` signal was detected. Reaction delay counting down via `Scheduler`. |
| `REELING` | The `tapKey("use", 100)` command has been sent. Waiting for the bobber entity to disappear. |

**Transitions:**
```
IDLE    → WAITING   when hasBobber becomes true
WAITING → BITING    when detectBite() returns true (and all guards pass)
WAITING → IDLE      if bobber disappears while watching
BITING  → REELING   when scheduled reel-in fires (BITING path) — or recast scheduled directly (IDLE path)
BITING  → IDLE      if bobber disappears before reel-in fires
REELING → IDLE      after 10-tick reset (or if bobber disappears)
```

---

## 5. FishingManager — Core State Machine

`com.poseidon.core.FishingManager` — singleton, `FishingManager.getInstance()`

### Activation

```java
FishingManager.getInstance().setActive(true);   // start watching
FishingManager.getInstance().setActive(false);  // stop, reset to IDLE
FishingManager.getInstance().toggle();          // flip active state
```

`setActive(false)` resets all flags: state → IDLE, `pendingSuppressRecast`, `pendingStopBot`, `lastRecastTick`, `lastReelTick`, `castTick`, `guiWasOpen`, `guiLockUntilTick`, `lastBaitName`, `lowBaitAlertFired`.

### GUI-Close Lock

Every tick, `tick()` checks whether `mc.currentScreen` transitioned from non-null → null. On that transition a random lock duration is chosen (`GUI_RESUME_MIN_TICKS`=15 to `GUI_RESUME_MAX_TICKS`=50 ticks, ~0.75–2.5 s) and `guiLockUntilTick` is set. The boolean `guiLocked` is `true` while a screen is open OR within the post-close delay. The bite-detection guard checks `guiLocked` and returns early if true.

### Bite Detection

`detectBite(MinecraftClient mc)` scans a box `±detectionRadius` in XZ, `-1` to `+detectionRadius+2` in Y around the bobber. Checks each entity for `hasReelNowSignal()`:
- `entity.getCustomName().getString().contains("!!!")`
- `TextDisplayEntity.getText().getString().contains("!!!")` for display entities

### Reel-in & Recast Decision

`scheduleReelIn()` picks a random delay and calls `Scheduler.scheduleMs()`. The lambda handles two paths:
- **BITING** (normal): transitions to REELING, sends `tapKey("use", 100)`, schedules creature scan and 10-tick state reset.
- **IDLE** (bobber pre-vanished during delay): skips the `tapKey`, logs the skip, falls through to the common recast-decision tail.
- **Other state**: returns immediately.

Both BITING and IDLE paths then schedule the recast decision after `recastDecisionTicks`. The decision checks `pendingStopBot`, `pendingSuppressRecast`, and `cfg.isAutoRecast()` in priority order.

### Idle Watchdog

`lastRecastTick` is stamped when `scheduleRecast` fires its `tapKey`. If the bot stays in IDLE more than `IDLE_TIMEOUT_TICKS` (100) ticks without detecting a bobber, the watchdog logs a warning and calls `scheduleRecast()` again. `lastRecastTick` is cleared when a bobber is detected (IDLE→WAITING) or on `setActive(false)`.

### Slugfish Mode

`castTick` is stamped when the bobber is first detected (IDLE→WAITING). In the WAITING bite-detection path, if `cfg.isSlugfishMode()` is true and `currentTick - castTick < required` (420 ticks normal / 220 ticks with Slug Pet), the bite is silently ignored and the method returns. `castTick` is cleared on bobber loss and `setActive(false)`.

### Catch-Window Gating

`lastReelTick` is stamped when the reel-in `tapKey` fires. `isInCatchWindow()` returns true for `CATCH_WINDOW_TICKS` (200 ticks / 10 s) after that stamp. Used by `PoseidonMod.onChatReceived()` to gate trigger evaluation.

### Public Getters

| Method | Returns |
|--------|---------|
| `isActive()` | Whether the bot is currently enabled |
| `getState()` | Current `FishingState` |
| `getTrackedCount()` | Number of currently tracked sea creatures |
| `getCurrentArea()` | Area string from tab list, or `""` |
| `getNearbyText()` | Countdown/nearby entity text, or `""` |
| `getBaitName()` | Current bait display name, or `""` |
| `getBaitCount()` | Bait count from lore / stack size, or `0` |
| `getStatFishingSpeed()` | Fishing Speed from tab list, or `""` |
| `getStatSeaCreatureChance()` | SCC from tab list, or `""` |
| `getStatDoubleHookChance()` | DHC from tab list, or `""` |
| `getStatTreasureChance()` | Treasure Chance from tab list, or `""` |
| `getSlugfishRemainingTicks()` | Ticks until slugfish timer elapses (≤0 = ready, `MIN_VALUE` = mode off / no bobber) |
| `isInCatchWindow()` | True within 10 s of the last reel-in |
| `shouldBlockRightClick()` | True in WAITING/BITING state with no GUI open |

---

## 6. FishingConfig — Configuration

`com.poseidon.core.FishingConfig` — singleton, `FishingConfig.getInstance()`

Config stored as JSON at `config/poseidon/config.json`. Loaded once on init. Every setter calls `save()` immediately.

### Config Groups

**Detection**
- `detectionRadius` — scan box half-size for `!!!` (default `4.0`)
- `hookStuckDetectionEnabled` (default `true`)
- `hookStuckMaxDistance` — drift threshold in blocks (default `1.5`)
- `hookStuckAutoRecast` — recast after drift, independent of global auto-recast (default `true`)
- `hookStuckSound`
- `slugfishMode` — suppress reel-ins until timer elapses (default `false`)
- `slugPet` — halves the slugfish timer (default `false`)

**Reaction / Recast**
- `reactionDelayMinMs` / `reactionDelayMaxMs`
- `autoRecast` (default `true`)
- `recastDelayMinMs` / `recastDelayMaxMs`
- `recastDecisionTicks` (default `10`)

**Bait Monitoring**
- `baitHudVisible` (default `true`)
- `baitLowThreshold` (default `5`)
- `baitLowAlertSound`
- `baitSwitchAlertSound`

**Sea Creature Tracking**
- `trackSeaCreatures` (default `true`)
- `creatureScanRadius` (default `12.0`)
- `SEA_CREATURE_CAP` = `10` (static constant, Hypixel-standardised)
- `seaCreatureCapSound`
- `despawnWarningEnabled` (default `true`)
- `despawnWarningMinutes` (default `5`)
- `despawnWarningSound`

**Stats & Alerts**
- `fishingStatsHudVisible` (default `true`)
- `rebootAlertEnabled` (default `true`)
- `rebootAlertSound`

**Triggers**
- `triggerLevels` — list of 5 `TriggerLevel` objects (all disabled by default)
- `biteAlertSound` (silent by default, `durationSeconds=0`)

**Developer**
- `debugMode` (default `false`)
- `logLevel` (default `LEVEL_WARN` = 2)

---

## 7. PoseidonHudRenderer — HUD Overlay

`com.poseidon.gui.PoseidonHudRenderer`

**Visibility:** static `hudVisible`, toggled by H keybind. Closing the HUD while active calls `setActive(false)`.

### Panel Layout

Position `(4, 4)`, width 200 px. Height is dynamic.

**Header:** "Poseidon" label left; state label right (coloured by state). Accent bar on left edge; flashes red during reboot alert.

**Rows (in order, each optional):**

| Row | Label | When shown | Color logic |
|-----|-------|------------|-------------|
| `! Reboot` | SOON | Reboot alert active | Red `0xFFFF4444` |
| `Active` | Yes / No | Always | Green / Red |
| `State` | State name | Always | State colour |
| `Bobber` | None / Detected / countdown | Always | Grey=none, Green=detected, Yellow=countdown |
| `Slug` | `--` / `Xs` / `READY` | Slugfish mode on | Grey=no bobber, Orange=counting, Green=ready |
| `Bait` | Name (count) | Bait HUD on | Red if no bait |
| `Area` | Island name | SC tracking on + area known | Grey |
| `SC` | count / 10 | SC tracking on | Grey=0, Orange=>0, Red=at cap |
| `DHC` | stat or `--` | Stats HUD on + any stat known | Light grey |
| `SCC` | stat or `--` | Stats HUD on + any stat known | Light grey |
| `Speed` | stat or `--` | Stats HUD on + any stat known | Light grey |
| `Treasure` | stat or `--` | Stats HUD on + any stat known | Light grey |

**Log panel:** Last 5 log lines below the main panel (prefix stripped, 50-char max).

### Accent Bar Color

| State | Color |
|-------|-------|
| Reboot alert | Red `0xFFFF4444` (overrides state colour) |
| IDLE (active=false) | Red `0xFFEE4444` |
| IDLE (active=true) | Orange `0xFFFFAA00` |
| WAITING | Green `0xFF44EE44` |
| BITING | Yellow `0xFFFFFF44` |
| REELING | Blue `0xFF44AAFF` |

---

## 8. PoseidonConfigScreen — YACL Config UI

`com.poseidon.gui.PoseidonConfigScreen`

Opened via `/poseidon`, the Open Config keybind, or ModMenu.

### Tab 1 — Detection

- Detection Radius slider (1.0 – 10.0)
- **Hook Stuck Detection** section: enabled toggle, max drift slider, auto-recast-on-drift toggle, sound options
- **Slugfish Mode** section: Slugfish Mode toggle (with warning), With Slug Pet toggle (with assumption warning)

### Tab 2 — Reaction Delay

- Min / Max reaction delay sliders
- **Auto Recast** section: toggle, min/max recast delay sliders, trigger wait slider (with ping guidance)

### Tab 3 — Sea Creature Tracking

- Enable tracking toggle, scan radius slider
- Cap alert sound options
- Despawn warning: enable toggle, warning-at slider, sound options

### Tab 4 — Bait

- Show Bait in HUD toggle (with usage note)
- Low Bait section: threshold slider, sound options
- Bait Switch section: sound options

### Tab 5 — Stats & Alerts

- Fishing Stats HUD toggle
- Reboot Alert toggle + sound options

### Tab 6 — Chat Triggers

- Header label explaining syntax
- 5 `OptionGroup` blocks (one per trigger slot): enabled, name, patterns, action (reserved), show title, title text (supports `&x`/`§x` codes), don't recast, stop bot, sound options
- Bite Alert section at the bottom (silent by default)

### Tab 7 — Updates

- Update check toggle

### Tab 8 — Developer

- Debug mode toggle
- Log level cycling list (DEBUG / INFO / WARN / ERROR)

---

## 9. MouseClickMixin — Right-click Guard

`com.poseidon.mixin.MouseClickMixin`

Injects at `HEAD` of `Mouse.onMouseButton()`, cancellable. Blocks right-click when `FishingManager.shouldBlockRightClick()` returns `true`.

**`shouldBlockRightClick()` is true when:**
- Bot is active
- State is `WAITING` or `BITING`
- `mc.currentScreen == null` (no GUI is open — never blocks inside menus)

---

## 10. PoseidonLogger — Logging

`com.poseidon.core.PoseidonLogger` — singleton, `PoseidonLogger.getInstance()`

| Constant | Value | Usage |
|----------|-------|-------|
| `LEVEL_DEBUG` | 0 | Verbose output |
| `LEVEL_INFO` | 1 | Normal events |
| `LEVEL_WARN` | 2 | Unexpected situations (default) |
| `LEVEL_ERROR` | 3 | Failures |

- **Ring buffer:** last 50 lines in synchronized `ArrayDeque<String>` → HUD log panel
- **File output:** append-only to `config/poseidon/poseidon.log`
- **Line format:** `[HH:mm:ss] [LEVEL] message`

---

## 11. PoseidonMod — Entry Point & Event Wiring

`com.poseidon.PoseidonMod` — implements `ClientModInitializer`

### Initialization

1. Log "Poseidon initialising…"
2. `FishingConfig.getInstance().load()`
3. `registerKeybinds()` — Y, H, unbound config key
4. `registerCommands()` — `/poseidon` + ALLOW_COMMAND fallback
5. `HudRenderCallback.EVENT.register(PoseidonHudRenderer::render)`
6. `PlayerAPIEvents.TICK.register(this::onTick)`
7. `PlayerAPIEvents.CHAT_RECEIVED.register(this::onChatReceived)`
8. `PlayerAPIEvents.WORLD_JOIN.register(this::onWorldJoin)` — triggers update check
9. Log "Poseidon ready."

### onChatReceived(String sender, String message)

1. `RebootAlertManager.getInstance().onChatReceived(sender, message)` — always runs
2. `if (!FishingManager.getInstance().isInCatchWindow()) return` — timing gate
3. `if (!isCatchMessage(message)) return` — colour gate (`§a`, `§e`, `⛃`)
4. Iterate trigger levels; first match: play sound, show title (via `parseLegacyText()`), call `notifyTriggerFired()`

### showTitle / parseLegacyText

`showTitle()` calls `mc.inGameHud.setTitle()` / `setSubtitle()` / `setTitleTicks()` directly with a `MutableText` built by `parseLegacyText()`. Both `&` and `§` are accepted as format-code prefixes. Colour codes reset bold/italic/etc. to match vanilla behaviour; `§r`/`&r` resets all.

---

## 12. Chat Triggers System

### Gates (both must pass before patterns are checked)

1. **Timing gate:** `isInCatchWindow()` — true for 200 ticks (10 s) after the reel-in `tapKey` fires.
2. **Colour gate:** `isCatchMessage(msg)` — true if msg starts with `§a` (green catch), `§e` (yellow/gold, e.g. Double Hook), or has `⛃` as the first non-format-code character (treasure catch).

### TriggerLevel Fields

```java
String    name        // label shown in config header and logs
boolean   enabled
String    patterns    // comma-separated, case-insensitive substrings
AlarmSound sound
String    action      // reserved, unused
boolean   dontRecast  // suppress auto-recast for this catch
boolean   stopBot     // deactivate bot after this catch
boolean   showTitle   // show MC title overlay
String    titleText   // supports &x / §x colour codes; falls back to name
```

### Pattern Matching

`TriggerLevel.matches(String chatText)` — splits patterns on `,`, lowercases both, returns true if any non-empty pattern is a substring of the chat text. Case-insensitive, no regex.

---

## 13. Sea Creature Tracking System

### Detection

`isSeaCreatureDisplay(Entity)` — checks for `⚓` (U+2693) in custom name or `TextDisplayEntity` text.

`extractCreatureName(Entity)` — strips `[LvN] ⚓ ` prefix and HP suffix from the full nameplate text.

### Scan Timing

Runs `SCAN_DELAY_TICKS` (5) ticks after the reel-in, anchored to the saved `lastBobberX/Y/Z` position.

At most **one** creature is added per reel-in to avoid picking up other players' nearby creatures.

### Deduplication

Before adding a new entity, the scanner checks all tracked creatures with the same name. If any tracked creature is within 3 blocks of the new entity, it is treated as a nameplate refresh (new display entity for the same mob) and its `entityId` is updated in-place rather than adding a new entry.

### Cleanup

`cleanupDeadCreatures()` runs every `CLEANUP_PERIOD_TICKS` (40) ticks:
1. **Despawn warnings:** checks creature age against `despawnWarningTicks`; fires sound once per creature.
2. **Dead removal:** `removeIf(t -> mc.world.getEntityById(t.entityId) == null)`. Position (`lastX`/`lastZ`) is kept fresh for the deduplication check while the entity is alive.
3. **Cap reset:** if tracked count falls below `SEA_CREATURE_CAP` after removal, clears `capAlertFired`.

### Cap

`SEA_CREATURE_CAP = 10` — a single static constant. The per-area cap map was removed in 1.1.0.

---

## 14. Hook Stuck Detection

After a 1-second settle period (`BOBBER_SETTLE_TICKS` = 20), every WAITING tick:
1. Compute `dist = sqrt((bobber.X - initialX)² + (bobber.Z - initialZ)²)`
2. If `dist > hookStuckMaxDistance`: play `hookStuckSound`, send `tapKey("use", 100)`, schedule recast if `cfg.isHookStuckAutoRecast()` (independent of global `autoRecast`)

XZ-only measurement avoids false positives from normal vertical bobbing (~0.5 blocks).

---

## 15. AlarmSound Data Class

`FishingConfig.AlarmSound` — inner class, used for all sounds in Poseidon.

| Field | Type | Notes |
|-------|------|-------|
| `soundId` | String | Minecraft sound ID |
| `volume` | double | 0.1 – 2.0 |
| `pitch` | double | 0.5 – 2.0 |
| `durationSeconds` | int | 0 = silent/disabled |
| `intervalTicks` | int | Ticks between repeats |

`play()` — `times = (durationSeconds × 20) / intervalTicks`; calls `SoundActions.playByIdRepeated()`. Returns early if `durationSeconds ≤ 0`.

`mergeFrom(src, defaults)` — loads file values over defaults, protecting against zero/null fields.

---

## 16. Config Schema & Migration History

```
v0  → v1:  flat biteAlert* → biteAlertSound AlarmSound object; triggerLevels added
v1  → v2:  seaCreatureCap int → seaCreatureCapByArea Map
v2  → v3:  autoRecast added (inject true — GSON defaults bool to false)
v3  → v4:  recastDecisionTicks added
v4  → v5:  despawnWarningEnabled, despawnWarningMinutes added (inject true)
v5  → v6:  hookStuckDetectionEnabled, hookStuckMaxDistance added (inject true)
v6  → v7:  updateCheckEnabled added (inject true)
v7  → v8:  seaCreatureCapByArea removed; SEA_CREATURE_CAP = 10 (constant)
v8  → v9:  baitHudVisible, baitLowThreshold, baitLowAlertSound, baitSwitchAlertSound added (inject true for baitHudVisible)
v9  → v10: rebootAlertEnabled, rebootAlertSound, fishingStatsHudVisible added (inject true for both booleans)
v10 → v11: hookStuckAutoRecast added (inject true)
v11 → v12: slugfishMode, slugPet added (both default false — no injection needed)
```

**GSON boolean caveat:** GSON bypasses constructors. Any new boolean field that should default `true` must be injected in its migration step — Java field initializers are ignored when GSON deserializes.

---

## 17. Keybinds & Commands

| Key | Default | Action |
|-----|---------|--------|
| Y | Y | Toggle Fishing — starts (if HUD visible) or stops the bot |
| H | H | Toggle HUD — also stops the bot if HUD is closed while active |
| (none) | Unbound | Open Config |

| Command | Effect |
|---------|--------|
| `/poseidon` | Opens config screen (tick-delayed to avoid chat-close race) |

ALLOW_COMMAND fallback handles servers that override the client command tree.

---

## 18. HUD Reference

**Panel:** position `(4, 4)`, width 200 px, height dynamic.  
**Header:** "Poseidon" + right-aligned state label, coloured accent bar on left edge.

```
┌── Poseidon ─────────── [STATE] ──┐  ← accent bar + header
│                                   │  ← divider
│  ! Reboot   SOON            (opt) │  ← red when reboot detected
│  Active     Yes / No              │
│  State      IDLE / WAITING / ...  │
│  Bobber     None / Detected / ... │
│  Slug       -- / 18s / READY (opt)│  ← slugfish mode only
│  Bait       Name (count)    (opt) │
│  Area       Hub             (opt) │
│  SC         4 / 10          (opt) │
│  DHC        value or --     (opt) │  ─┐ stats section
│  SCC        value or --     (opt) │   │ shown only when at least
│  Speed      value or --     (opt) │   │ one stat is available
│  Treasure   value or --     (opt) │  ─┘
└───────────────────────────────────┘

[LOG]──────────────────────────────────
  Last log line
  ...
```

---

## 19. Configuration Reference (All Settings)

### Detection

| Key | Type | Default |
|-----|------|---------|
| `detectionRadius` | double | 4.0 |
| `hookStuckDetectionEnabled` | bool | true |
| `hookStuckMaxDistance` | double | 1.5 |
| `hookStuckAutoRecast` | bool | true |
| `slugfishMode` | bool | false |
| `slugPet` | bool | false |

### Reaction & Recast

| Key | Type | Default |
|-----|------|---------|
| `reactionDelayMinMs` | int | 180 |
| `reactionDelayMaxMs` | int | 700 |
| `autoRecast` | bool | true |
| `recastDelayMinMs` | int | 200 |
| `recastDelayMaxMs` | int | 600 |
| `recastDecisionTicks` | int | 10 |

### Bait Monitoring

| Key | Type | Default |
|-----|------|---------|
| `baitHudVisible` | bool | true |
| `baitLowThreshold` | int | 5 |
| `baitLowAlertSound` | AlarmSound | orb.pickup, 1.0, 0.5, 3s, 20t |
| `baitSwitchAlertSound` | AlarmSound | bell.use, 1.0, 0.8, 2s, 20t |

### Sea Creature Tracking

| Key | Type | Default |
|-----|------|---------|
| `trackSeaCreatures` | bool | true |
| `creatureScanRadius` | double | 12.0 |
| `SEA_CREATURE_CAP` | int constant | 10 |
| `seaCreatureCapSound` | AlarmSound | levelup, 1.0, 0.8, 10s, 20t |
| `despawnWarningEnabled` | bool | true |
| `despawnWarningMinutes` | int | 5 |
| `despawnWarningSound` | AlarmSound | bell.use, 1.0, 0.8, 5s, 20t |

### Stats & Alerts

| Key | Type | Default |
|-----|------|---------|
| `fishingStatsHudVisible` | bool | true |
| `rebootAlertEnabled` | bool | true |
| `rebootAlertSound` | AlarmSound | bell.use, 1.0, 1.0, 300s, 40t |

### Triggers

| Key | Type | Default |
|-----|------|---------|
| `triggerLevels` | List\<TriggerLevel\> | 5 empty/disabled |
| `biteAlertSound` | AlarmSound | orb.pickup, 1.0, 1.5, 0s (silent), 15t |

### Developer

| Key | Type | Default |
|-----|------|---------|
| `debugMode` | bool | false |
| `logLevel` | int | 2 (WARN) |
| `configVersion` | int | 12 (auto-managed) |
| `updateCheckEnabled` | bool | true |

---

## 20. Timing Reference

| Constant | Value | Purpose |
|----------|-------|---------|
| `SCAN_DELAY_TICKS` | 5 | Delay after reel-in before scanning for sea creatures |
| `CLEANUP_PERIOD_TICKS` | 40 | How often dead creatures are removed (2 s) |
| `AREA_REFRESH_TICKS` | 40 | How often the tab-list area is re-read (2 s) |
| `BOBBER_SETTLE_TICKS` | 20 | Settle window before hook-stuck drift check (1 s) |
| `IDLE_TIMEOUT_TICKS` | 100 | Idle watchdog: recast retry if no bobber after this long (5 s) |
| `CATCH_WINDOW_TICKS` | 200 | Trigger evaluation window after a reel-in (10 s) |
| `SLUGFISH_NORMAL_TICKS` | 420 | Slugfish delay, no pet (21 s) |
| `SLUGFISH_PET_TICKS` | 220 | Slugfish delay, max Slug Pet (11 s) |
| `GUI_RESUME_MIN_TICKS` | 15 | Min post-GUI-close lock (~0.75 s) |
| `GUI_RESUME_MAX_TICKS` | 50 | Max post-GUI-close lock (~2.5 s) |
| State reset delay | 10 | REELING → IDLE after reel-in |
| `recastDecisionTicks` | 10 (configurable) | Trigger window before recast decision |

20 ticks = 1 second. 1 tick ≈ 50 ms.

---

## 21. Design Decisions & Notes

### GUI-Close Lock

Reacting to a bite the instant a GUI closes is a strong automation signal. The random 15–50 tick delay after `mc.currentScreen` goes null breaks the deterministic pattern without requiring the player to do anything special. The lock is managed entirely by `FishingManager.tick()` using the existing tick loop — no separate callback is needed.

### Catch-Window Gating for Triggers

Rather than checking all chat, triggers only run within 10 s of a reel-in. This prevents a player's death message (mentioning a creature name) from triggering an alert mid-session. The window is generous enough to cover high-latency servers but tight enough to exclude unrelated chat.

### Colour Gate for Triggers

`isCatchMessage()` strips leading `§X` format-code pairs and checks the remaining content. `§a` covers normal catch lines; `§e` covers the Double Hook announcement (`§e§lDOUBLE HOOK!`) that precedes a catch; `⛃` covers treasure catches which use a variable colour prefix. Any other colour prefix (red death notices, gray system messages) is rejected before pattern matching runs.

### parseLegacyText()

`DisplayActions.showTitle(String, String)` wraps its string in `Text.literal()` internally, which does not parse `§` codes. Poseidon therefore bypasses it and calls `mc.inGameHud.setTitle()` directly with a `MutableText` built by `parseLegacyText()`. Both `&` and `§` are accepted; colour codes reset style flags to match vanilla.

### Sea Creature Deduplication

Hypixel periodically refreshes sea creature nameplate display entities (new entity ID, same physical mob). Without deduplication, each refresh would count as a new catch. The fix stores `lastX`/`lastZ` for each tracked creature (updated every cleanup cycle while the entity is visible) and checks proximity (3-block radius) before adding a same-named entity to the list.

### `shouldBlockRightClick()` and GUIs

The right-click guard is lifted inside GUI screens because: (a) you cannot cancel a cast through a container/menu anyway, and (b) blocking right-click inside a GUI prevents normal item interactions (picking up items, clicking slots). The guard returns to full strength the moment `mc.currentScreen` is null again.

### Idle Watchdog

After `scheduleRecast` fires `tapKey`, `lastRecastTick` is stamped. If the IDLE state persists for more than 100 ticks without a bobber appearing, the watchdog fires again. This recovers from two failure modes: (1) the `tapKey` packet was dropped by the server, and (2) the bobber surfaced and vanished within a single tick before the IDLE case ran, leaving the state machine with nothing to act on.

### hookStuckAutoRecast vs. autoRecast

These are intentionally separate. A player may prefer manual-cast mode (global `autoRecast = false`) so they decide when to cast, but still wants the bot to auto-recover when the hook gets stuck on a mob — which isn't a "catch" and should always retry. `hookStuckAutoRecast` covers exactly that case without forcing global auto-recast on.

### GSON Boolean Default Caveat

GSON deserializes objects by bypassing constructors. Java field initializers such as `private boolean foo = true` are never run during GSON deserialization. Any boolean that should default to `true` in an existing config (i.e., one that was written before the field existed) must be injected in its migration step. This is why migrations v2→v3, v4→v5, v5→v6, v6→v7, v9→v10, and v10→v11 all explicitly inject `true` for their new boolean fields.
