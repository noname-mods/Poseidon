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

Triggers only fire within 10 seconds of a reel-in and are filtered to Hypixel catch-related messages (`§a` catch lines, `§e` double hook announcements, and treasure lines prefixed with the resource pack's treasure icon `U+E025`), so unrelated chat never causes a false trigger.

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
Poseidon identifies the **exact** creature from its catch chat line (a bundled registry of 80+ creatures — including the newer Haggard, Brineling, Sprawl, Torrid, Silkbreeze, and Giant Isopod), then tracks the actual **mob** it spawned rather than the floating name plate. This keeps the count accurate where a plate-based tracker drifts: creatures stacked on one spot (standing still, facing down) are each counted separately, player-model creatures like Banshee are tracked, and a creature is only removed when it truly dies or despawns. **Double Hook** is handled (two at once); unknown/blank lines fall back to a positional scan. Tracked creatures show in the HUD with an alert at the Hypixel cap of 10, and a second alert as a creature nears its despawn timer (~6 minutes).

### Bobber Not-In-Water Recovery
If a cast lands somewhere it can't fish — on ice, a lily pad, or the water's edge — Poseidon detects the blue "not in water" particle burst around the settled bobber and automatically reels in and recasts, instead of sitting on a dead cast. On by default; toggle in the config.

### Fishing Abilities
Optionally auto-uses **Fire Veil** and **Totem of Corruption** after catches — Constant or At-Cap modes, a configurable hotbar slot (1–8) and cooldown, a name-check safety, and a HUD status row.

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

The full design & documentation is maintained in [POSEIDON_DOCS.md](POSEIDON_DOCS.md). A summary of the internals:

- **`FishingManager`** — the central singleton: the `IDLE → WAITING → BITING → REELING` state machine and all fishing logic (bite detection via the `!!!` signal, reel/recast timing, watchdogs).
- **Chat-confirmed, mob-anchored sea-creature tracking** — identifies the exact creature from its catch chat line (`SeaCreatureCatches`), then tracks the underlying **mob** keyed on its stable entity id (the SkyHanni model). Plate→mob is resolved by **entity-id adjacency** (immune to stacked creatures) with a spatial fallback; player-model creatures (e.g. Banshee) are tracked, real players excluded by v4 UUID. Death = the mob entity leaving the world.
- **Fishing abilities** (`AbilityManager`) — auto-uses Fire Veil / Totem of Corruption after catches (Constant / At-Cap modes, human-paced via the Action Speed slider). Fires via the use key so placed items like the Totem route through the game's real right-click (place) path.
- **Bobber "not in water" recovery** — a particle-burst watcher (`ParticleWatch` + `ParticleCaptureMixin`) that force-recasts a bobber that landed off-water.
- **HUD editor** — the panel and its log are movable/scalable elements via PlayerAPI's shared `HudManager`.
- **`FishingConfig`** — all persistent settings, JSON, versioned migrations (schema v18).
- **`PoseidonMod`** — entry point, event wiring, keybinds, `/poseidon` command.

See [POSEIDON_DOCS.md](POSEIDON_DOCS.md) for the full section-by-section reference (state machine, sea-creature system, abilities, config schema & migration history, HUD, timing, and design notes).
