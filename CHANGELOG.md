# Poseidon Changelog

## [Unreleased]

---

## [1.2.0] — Coming Soon

### Planned
- **Improved sea creature detection** — uses the fish-up chat message to identify which creature
  is yours more accurately, reducing false positives from nearby players' catches.
- **Action implementation** — the reserved `action` field on chat triggers will be wired up,
  enabling per-trigger behaviours beyond the current sound / title / stop-bot options.

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
- Config schema bumped from v6 to v13 (automatic migration on first launch).
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
