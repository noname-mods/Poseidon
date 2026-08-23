# Poseidon Changelog

## [Unreleased]

---

## [1.2.1] - 2026-08-22

### Changed
- **Minecraft 26.2** support.
- **New config screen** — Poseidon now uses PlayerAPI's built-in config library; the **YACL** dependency is gone.
- Requires **PlayerAPI 2.0.0**.

---

## [1.2.0]

### Added
- **Chat-confirmed, mob-anchored sea-creature detection.** Identifies the exact creature from its catch
  chat line (bundled registry of 81 creatures + an optional live-editable debug JSON), then tracks the
  underlying **mob** — not the floating name plate — keyed on the mob's stable entity id (the model
  SkyHanni uses). The plate only identifies the creature; the mob beneath it is resolved by
  **entity-id adjacency** (Hypixel spawns a mob and its plate consecutively), which is immune to the
  common stacked case where player, bobber and several creatures sit on one spot. Handles **Double
  Hook** (tracks two); unknown/blank lines fall back to a positional scan. Death is detected when the
  mob entity leaves the world, with a 6-minute max-age safety net.
- **Fishing abilities.** Auto-use **Fire Veil** and **Totem of Corruption** after catches — Constant
  or At-Cap modes, configurable hotbar slot (1–8) and cooldown, a name-check safety, and a HUD
  status row. An **Action Speed** slider (100–1000 ms/step, default 400) paces the switch→use→
  switch-back sequence at a human speed. Uses fire via the **use key** (the game's real right-click
  routing), so placed items like the Totem place correctly rather than no-opping. Replaces the
  previously-planned generic per-trigger `action` field (dropped as too limited).
- **6 new sea creatures** — Haggard, Brineling, Sprawl, Torrid, Silkbreeze, and Giant Isopod added to
  the catch-line registry.
- **"Bobber not in water" auto-recast.** Watches for the blue "not in water" particle burst around a
  settled bobber and force-reels + recasts (on by default) — recovers from a cast that lands on ice, a
  lily pad, or the water's edge.
- **HUD editor + split log.** The HUD panel and its log are movable/scalable via PlayerAPI's shared HUD
  editor (an **Edit HUD** button; the log is its own element with a **Show Log** toggle).
- **Per-stat fishing-HUD toggles.** Double Hook Chance / Sea Creature Chance / Speed / Treasure each get
  their own show/hide toggle; a line renders only when its toggle is on **and** the stat is in the tab list.

### Changed
- **Rod & bait detection now uses the SkyBlock item id.** Rod and bait identity read the item's stable
  SkyBlock internal id instead of display-name substrings (which Hypixel renames). Requires PlayerAPI 1.18.0+.

### Fixed
- **Stacked-creature undercount.** When the player, bobber and several creatures overlapped on one spot,
  every name plate resolved to the single nearest mob and the rest were dropped from the count. Resolving
  plate→mob by entity-id adjacency (rather than "nearest below") now gives each creature its own mob, so
  the count is correct even when everything is piled together.
- **Player-model creatures (e.g. Banshee) not tracked.** Creatures rendered with a player model arrive as
  player entities and were being skipped. They're now tracked; only *real* players (identified by a v4
  UUID, per SkyHanni) are excluded.
- **Count drift over long sessions.** Tracking the churning name-plate entity (which Hypixel recreates on
  every HP change) caused double-counts and phantom deaths. Keying on the stable mob entity removes both.
- **At-cap ability timing.** An at-cap ability fired a cast late; it now waits for the catch count to
  settle so it triggers on the catch that actually reaches the cap, before the next cast.
- **Totem not activating.** The Totem of Corruption is a *placed* item, so it needs the block-placement
  right-click (`useItemOn`), not use-in-air. Abilities now fire by tapping the **use key** — the client's
  full vanilla right-click routing — so the Totem places correctly. Fire Veil is unaffected (a wand casts
  in air either way).
- **Fast-catch miss.** Back-to-back catches (<1s apart) could miss the second creature when its name plate
  spawned a hair late; the catch scan now retries for a few ticks until the target(s) bind.
- **Double Hook line.** Hypixel sends the double hook as its own `It's a Double Hook!` line before the
  catch line — it's now parsed there (formatting stripped) and applied to the following catch.
- **Keybind category label.** The Controls-menu category showed a raw translation key; corrected.
- **Cross-mod build against a newer PlayerAPI.** The PlayerAPI dependency is now a version *range*
  (`[floor,)`) instead of an exact pin, so a fresh clone builds against whatever PlayerAPI is published
  to mavenLocal rather than demanding one exact version.

---

## [1.1.2] - 2026-07-08

### Changed
- **Resource pack now required (dropped pre-pack symbols).** Hypixel's resource pack is live and
  mandatory, so the temporary dual-support from 1.1.1 is gone. Sea-creature detection now keys only
  on the pack's water (Aquatic) / lava (Magmatic) type glyphs, and treasure catch detection only on
  the pack's treasure glyph. The old ⚓ / ♆ / ⛃ symbols are no longer matched.

---

## [1.1.1] - 2026-06-30

### Changed
- **Sea-creature detection: new mandatory resource pack support (dual).** Hypixel's mandatory pack
  replaces the mob-type nameplate symbols with custom Private-Use-Area glyphs. Detection now matches
  **both** the old symbols and the new glyphs, so this build works before *and* after the pack ships:
  - water: `⚓` (U+2693) **or** Aquatic `U+E072`
  - lava:  `♆` (U+2646) **or** Magmatic `U+E07D`

  The old symbols will be dropped in a later release once the pack is fully mandatory.
- Markers are now centralised in `SEA_CREATURE_MARKERS`, and name extraction uses the matched
  marker's length (so multi-character glyphs parse correctly).
- **Treasure catch detection** likewise matches both the old `⛃` (U+26C3) and the new pack glyph
  (U+E025), so treasure triggers keep working across the transition.

---

## [1.1.0]

### Platform
- Ported to Minecraft 26.1.2 (Fabric 0.149.1+26.1.2, Loader 0.19.2)
- Requires PlayerAPI 1.12.0+ (Mojang-mapped; uses the shared `UpdateChecker`)

### Added

**Bait Monitoring**
- New Bait category in the config screen.
- Reads the bait item in the last hotbar slot (the Fishing Bag slot) at cast time.
- Count is read from the `Bait Remaining: N` lore line rather than stack size.
- **Low-bait alert** — plays a configurable sound when the count drops to or below a threshold; resets automatically when restocked.
- **Bait-switch alert** — plays when the bait type changes while the bot is active.
- HUD row shows current bait name and count (toggle in config).
- Bait check only runs when holding a rod; skipped entirely if a GUI is open.

**Fishing Stats HUD**
- New Stats & Alerts category in the config screen.
- Reads Double Hook Chance, Sea Creature Chance, Fishing Speed, and Treasure Chance from
  the Hypixel tab list every 2 seconds.
- Stats section appears in the HUD only when at least one value is available and the toggle is on.

**Server Reboot Alert**
- Detects Hypixel's "This server will restart soon" server message.
- Plays a looping alarm sound (configurable) until you warp to a different area.
- HUD accent bar flashes red and a `! Reboot / SOON` row appears at the top of the panel.
- Alarm is immune to `Scheduler.cancelAll()` — driven by a tick counter instead.

**Slugfish Mode**
- New toggle in the Detection category (with a prominent warning — only for Slugfish trophy fishing).
- Suppresses all reel-ins for 21 seconds after each cast (the slugfish only bites after ≥ 20 s).
- Sub-option: **With Slug Pet** — reduces the timer to 11 s (assumes level-100 Slug Pet; not verified automatically).
- HUD shows a live countdown (`18s`, orange) and turns green (`READY`) when the timer elapses.

**Hook-Stuck Auto-Recast**
- New `Auto Recast on Drift` toggle in the Detection category.
- When enabled, automatically recasts after a drift event regardless of the global Auto Recast setting — useful for manual-cast mode where you still want drift recovery.
- The alert sound plays either way.

**GUI-Close Lock (Anti-detection)**
- After the player closes any GUI screen, the bot waits a random 0.75 – 2.5 second delay
  before it will react to bite signals.
- Prevents the suspicious pattern of reeling in the instant a menu is dismissed.
- Right-click is no longer blocked inside open GUI screens (previously blocked all use while
  in WAITING/BITING state, breaking normal menu interactions).

**Chat Trigger Improvements**
- **Colour-code title support** — trigger Title Text now renders `&x` and `§x` formatting codes
  correctly (e.g. `&dOld Leather Boot` shows in light purple). All 16 colour codes and
  bold/italic/underline/strikethrough/obfuscated styles are supported.
- **Timing gate** — triggers are only evaluated within 10 seconds of a reel-in, preventing
  unrelated chat (e.g. another player's death message matching a creature name) from firing.
- **Colour filter** — only messages that look like Hypixel catch-related chat are checked:
  `§a` green (normal catch lines), `§e` yellow/gold (Double Hook announcements),
  and any colour code followed by the `⛃` treasure icon.
- Double Hook (`§e§lDOUBLE HOOK!`) and treasure catch messages now reach trigger patterns correctly.

**Recast / IDLE Reliability Fixes**
- Fixed: if the bobber vanished during the reaction delay window, the reel-in lambda did nothing
  and no recast was ever scheduled, leaving the bot stuck. The lambda now handles both the
  normal (bobber still present) and pre-vanished (IDLE) paths, scheduling a recast in both cases.
- Added **idle watchdog**: if the bot stays in IDLE for more than 100 ticks after a recast
  was sent without detecting a bobber, it retries the recast automatically. This recovers from
  failed casts and bobbers that surfaced and sank before the state machine saw them.
- Added **rod check** before recast: if the player is not holding an item whose name contains
  "rod", the recast is skipped (allows manual-stop by swapping to another item).

**Sea Creature Tracking Improvements**
- **Deduplication** — when a sea creature's nameplate display entity is refreshed by the server
  (new entity ID, same creature), the tracked entry is updated in-place using position proximity
  (within 3 blocks) rather than counting it as a new catch.
- **Unified cap** — `SEA_CREATURE_CAP` is now a single constant (`10`) applied across all islands,
  matching Hypixel's standardised cap. The per-area cap map has been removed.

**Golden Fish Alert**
- New optional Golden Fish category in the config screen (off by default).
- Watches chat for the Golden Fish surface message ("You spot a Golden Fish surface from beneath
  the lava/waves!"). On a match it shows a golden title card, plays a configurable sound, reels in
  any active cast, and stops the bot so you can catch the Golden Fish manually.
- Re-enable the bot afterwards to resume normal fishing.
- Checked outside the post-reel catch window, since the announcement can arrive at any time while
  fishing. Only fires while the bot is active. Trigger phrase, title text, and sound are configurable.

**Server-Message Gate (anti-grief)**
- Every chat alert — Golden Fish, chat triggers, and the reboot alert — now reacts only to server
  messages, never to player chat. Another player typing a trigger phrase (or the Golden Fish line)
  can no longer drive the bot.
- Distinguishes player chat two ways: signed messages carry a sender name, and Hypixel-style
  reformatted player chat ("[rank] Name: …", "Guild > …", DMs) is rejected by shape. Server fishing
  lines and restart warnings pass through.

### Changed
- `shouldBlockRightClick()` no longer returns `true` when a GUI screen is open.
- Bait detection moved from every-tick to cast-time only (IDLE → WAITING transition and after each auto-recast), reducing unnecessary inventory reads.
- Config schema bumped from v6 to v18 (automatic migration on first launch).
- Update checker moved to PlayerAPI's shared `UpdateChecker`: adds a click-to-hide link and a distinct
  message when the latest release targets a different Minecraft version (tags use `<modVersion>+<mcVersion>`).

---

## [1.0.0]

### Added
- Initial release — automated fishing bot: cast, watch for bite, reel in, recast.
- Smart bite detection via the `!!!` entity signal (armor stand and text display entity support).
- Configurable human-like reaction delay (min/max ms, randomised).
- Auto-recast with configurable delay and ping-aware trigger decision window.
- Chat triggers — 5 configurable slots with sound, title overlay, suppress-recast, and stop-bot actions.
- Sea creature tracking — scans for `⚓` nameplate entities after each reel-in; cap alert and despawn warning sounds.
- Bobber drift detection (hook-stuck) — detects when the hook attaches to a moving mob.
- Live HUD overlay — state, bobber status, sea creature count, log panel.
- YACL config screen with Detection, Reaction Delay, Sea Creature Tracking, Chat Triggers, and Developer tabs.
- ModMenu integration — Config button in the mods list.
- Update checker — contacts GitHub Releases API on world join; notifies in chat if a newer version is available.
