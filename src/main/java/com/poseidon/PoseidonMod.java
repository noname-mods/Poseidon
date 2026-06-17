package com.poseidon;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingManager;
import com.poseidon.core.PoseidonLogger;
import com.poseidon.core.RebootAlertManager;
import com.poseidon.gui.PoseidonConfigScreen;
import com.poseidon.gui.PoseidonHudRenderer;
import com.playerapi.PlayerAPIEvents;
import com.playerapi.PlayerInfo;
import com.playerapi.Scheduler;
import com.playerapi.UpdateChecker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;

public class PoseidonMod implements ClientModInitializer {

    /**
     * GitHub Releases API endpoint for update checks.
     * Replace GITHUB_USERNAME and the repo name once you have created the repository.
     * Format: https://api.github.com/repos/GITHUB_USERNAME/REPO_NAME/releases/latest
     */
    private static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/noname-mods/Poseidon/releases/latest";

    /** Set to true to open the config screen on the next tick (avoids chat-close race). */
    public static boolean openConfigNextTick = false;

    public static KeyMapping keyToggle;
    public static KeyMapping keyToggleHud;
    public static KeyMapping keyOpenConfig;

    /** Keybinding category — shown in the Controls screen. */
    private static final KeyMapping.Category POSEIDON_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("poseidon", "category"));

    @Override
    public void onInitializeClient() {
        PoseidonLogger.getInstance().logInfo("Poseidon initialising...");

        FishingConfig.getInstance().load();

        registerKeybinds();
        registerCommands();
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("poseidon", "hud"),
                PoseidonHudRenderer::render);
        PlayerAPIEvents.TICK.register(this::onTick);
        PlayerAPIEvents.CHAT_RECEIVED.register(this::onChatReceived);
        PlayerAPIEvents.WORLD_JOIN.register(this::onWorldJoin);

        PoseidonLogger.getInstance().logInfo("Poseidon ready.");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("poseidon")
                        .executes(ctx -> {
                            openConfigNextTick = true;
                            return 1;
                        })));

        // Fallback: fires when ClientCommandManager did NOT handle the command (server overrode tree).
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (command.trim().equalsIgnoreCase("poseidon")) {
                PoseidonLogger.getInstance().logInfo(
                        "/poseidon caught via ALLOW_COMMAND — opening config next tick.");
                openConfigNextTick = true;
                return false;
            }
            return true;
        });
    }

    private void onTick() {
        if (!PlayerInfo.isInWorld()) return;

        if (openConfigNextTick) {
            openConfigNextTick = false;
            try {
                Minecraft.getInstance().setScreen(PoseidonConfigScreen.create(null));
            } catch (Exception e) {
                PoseidonLogger.getInstance().logError("Failed to open config screen: " + e.getMessage());
            }
        }

        FishingManager.getInstance().tick();
        RebootAlertManager.getInstance().tick();
        handleKeybinds();
    }

    private void onChatReceived(String sender, String message) {
        // ── Server-message gate ────────────────────────────────────────────────
        // Every alert below reacts only to messages from the server, never to
        // player chat — so another player typing a trigger phrase (or the Golden
        // Fish line) can't drive the bot. See isFromServer for how player chat is
        // distinguished (signed-message sender + Hypixel player-chat shape).
        if (!isFromServer(sender, message)) return;

        // Reboot alert runs on every server message — it isn't catch-specific.
        RebootAlertManager.getInstance().onChatReceived(sender, message);

        // ── Golden Fish alert ──────────────────────────────────────────────────
        // Checked BEFORE the catch-window / catch-message gates below: the Golden
        // Fish announcement can arrive at any point while fishing, not just in the
        // short window after a reel-in. Only acts while the bot is active — the
        // whole point is to stop the bot and hand control over, so there's nothing
        // to do (and false-positive risk) when the bot isn't running.
        FishingConfig gfCfg = FishingConfig.getInstance();
        if (FishingManager.getInstance().isActive()
                && gfCfg.matchesGoldenFishPhrase(message)) {
            PoseidonLogger.getInstance().logInfo("Golden Fish detected: " + message);
            gfCfg.getGoldenFishSound().play();
            showGoldenFishTitle(gfCfg);
            FishingManager.getInstance().handleGoldenFish();
            return; // don't also process this as a normal catch trigger
        }

        // ── Chat trigger gates ────────────────────────────────────────────────
        // 1. Timing: only process triggers within a short window after a reel-in.
        //    Unrelated chat (e.g. another player's death message containing a sea
        //    creature name) can arrive at any time and must not fire a trigger.
        if (!FishingManager.getInstance().isInCatchWindow()) return;

        // 2. Pattern: only process messages that look like Hypixel catch-related chat.
        //    §a (green) = normal catch lines.
        //    §e (yellow/gold) = special announcements e.g. "§e§lDOUBLE HOOK!".
        //    §X⛃ (any colour + treasure icon) = treasure catches.
        //    Death notices (§c), player chat, and other server messages are rejected.
        if (!isCatchMessage(message)) return;

        List<FishingConfig.TriggerLevel> levels = FishingConfig.getInstance().getTriggerLevels();
        for (FishingConfig.TriggerLevel level : levels) {
            if (level.matches(message)) {
                PoseidonLogger.getInstance().logInfo(
                        "Trigger \"" + level.name + "\" matched: " + message);
                level.sound.play();
                if (level.showTitle) {
                    showTitle(level);
                }
                FishingManager.getInstance().notifyTriggerFired(level.dontRecast, level.stopBot);
                break; // first match wins
            }
        }
    }

    /**
     * Matcher for the Hypixel player-chat shape, applied to a colour-stripped
     * message: an optional channel word and rank brackets, then a single username
     * token immediately followed by ": ". Covers:
     *   "Name: ...", "[MVP+] Name: ...", "[MVP+] Name [TAG]: ...",
     *   "Guild > [MVP+] Name: ...", "Party > ...", "Co-op > ...",
     *   "Officer > ...", "To Name: ...", "From [MVP+] Name: ..."
     */
    private static final java.util.regex.Pattern PLAYER_CHAT_SHAPE =
            java.util.regex.Pattern.compile(
                    "^(?:(?:guild|party|co-op|officer|to|from)\\s*>?\\s*)?" + // optional channel prefix
                    "(?:\\[[^\\]]+\\]\\s*)*" +                                 // optional rank brackets
                    "[A-Za-z0-9_]{1,16}" +                                     // username token
                    "(?:\\s*\\[[^\\]]+\\])?" +                                 // optional guild tag
                    "\\s*:\\s",                                                // ": " separator
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Returns true if a chat message came from the server rather than another
     * player. Every Poseidon chat alert gates on this so player chat can never
     * drive the bot.
     *
     * <p>Two layers, because player chat can reach us two ways:</p>
     * <ol>
     *   <li><b>Signed player messages</b> arrive with a non-empty {@code sender}
     *       (the vanilla signed-chat path) — always a player.</li>
     *   <li><b>Hypixel</b> reformats player chat and sends it through the
     *       system-message path with an <em>empty</em> sender, so the sender check
     *       alone misses it there. Messages shaped like player chat
     *       ("[rank] Name: ...", "Guild &gt; ...", DMs, etc.) are rejected too.</li>
     * </ol>
     *
     * <p>The shape check is conservative: server fishing lines ("You spot a Golden
     * Fish...", "§a✦ You caught...", restart warnings) don't begin with a
     * "username: " token, so they pass, while a player typing a trigger phrase
     * appears as "Name: phrase" and is filtered out.</p>
     */
    private static boolean isFromServer(String sender, String message) {
        if (sender != null && !sender.isEmpty()) return false; // signed player message
        return !looksLikePlayerChat(message);
    }

    private static boolean looksLikePlayerChat(String message) {
        if (message == null || message.isEmpty()) return false;
        String stripped = message.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        return PLAYER_CHAT_SHAPE.matcher(stripped).find();
    }

    /**
     * Returns true if a chat message looks like a Hypixel fishing catch message
     * that should be tested against the configured triggers.
     *
     * <ul>
     *   <li>{@code §a} — green: normal sea creature / fish catch lines.</li>
     *   <li>{@code §e} — yellow/gold: special announcements such as
     *       {@code §e§lDOUBLE HOOK!} that arrive just before the catch line.</li>
     *   <li>{@code §X⛃} — any colour followed immediately by the treasure chest
     *       icon (U+26C3): Hypixel treasure catch messages. The colour code is
     *       stripped before checking so any prefix colour is accepted.</li>
     * </ul>
     */
    private static boolean isCatchMessage(String msg) {
        if (msg.startsWith("§a") || msg.startsWith("§e")) return true;
        // Strip leading §X format-code pairs (§ + one char each) then check for ⛃
        String s = msg;
        while (s.length() >= 2 && s.charAt(0) == '§') s = s.substring(2);
        return s.startsWith("⛃");
    }

    private static void showTitle(FishingConfig.TriggerLevel level) {
        String raw = level.titleText.isBlank() ? level.name : level.titleText;
        if (raw.isBlank()) raw = "Trigger Fired";

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;

        // Drive the MC HUD directly so that colour/style codes in the text are
        // rendered properly. DisplayActions.showTitle() wraps the string in
        // Component.literal() which treats § as a literal character, not a colour code.
        mc.gui.setTitle(parseLegacyText(raw));
        mc.gui.setSubtitle(Component.literal(""));
        mc.gui.setTimes(10, 70, 20); // fade-in, hold, fade-out ticks
    }

    private static void showGoldenFishTitle(FishingConfig cfg) {
        String raw = cfg.getGoldenFishTitleText();
        if (raw == null || raw.isBlank()) raw = "§6§lGOLDEN FISH";

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;

        mc.gui.setTitle(parseLegacyText(raw));
        mc.gui.setSubtitle(parseLegacyText("§eBot stopped — catch it, then re-enable"));
        mc.gui.setTimes(10, 100, 20); // longer hold so the hand-off is unmissable
    }

    /**
     * Converts a legacy-formatted string (§x or &x colour/style codes) into a
     * properly styled {@link Component} object.
     *
     * <p>Both {@code §} (U+00A7) and {@code &} are accepted as the code prefix so
     * users can type either convention. A colour code resets bold/italic/etc. to
     * match vanilla behaviour. {@code §r} / {@code &r} resets everything.
     */
    private static Component parseLegacyText(String raw) {
        // Normalise & → § so both conventions work identically
        String s = raw.replace('&', '§');

        MutableComponent result = Component.literal("");
        // Split on §: index 0 is the un-prefixed prefix, everything after starts
        // with a single format-code character followed by the text it applies to.
        String[] parts = s.split("§", -1);

        if (!parts[0].isEmpty()) {
            result.append(Component.literal(parts[0]));
        }

        ChatFormatting activeColor = null;
        boolean bold              = false;
        boolean italic            = false;
        boolean underline         = false;
        boolean strikethrough     = false;
        boolean obfuscated        = false;

        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            char code = Character.toLowerCase(parts[i].charAt(0));
            String content = parts[i].substring(1);

            ChatFormatting fmt = ChatFormatting.getByCode(code);
            if (fmt != null) {
                if (fmt == ChatFormatting.RESET) {
                    activeColor = null;
                    bold = italic = underline = strikethrough = obfuscated = false;
                } else if (fmt.isColor()) {
                    // Colour resets style flags (vanilla behaviour)
                    activeColor = fmt;
                    bold = italic = underline = strikethrough = obfuscated = false;
                } else {
                    switch (fmt) {
                        case BOLD          -> bold          = true;
                        case ITALIC        -> italic        = true;
                        case UNDERLINE     -> underline     = true;
                        case STRIKETHROUGH -> strikethrough = true;
                        case OBFUSCATED    -> obfuscated    = true;
                        default -> {}
                    }
                }
            }

            if (!content.isEmpty()) {
                Style style = Style.EMPTY;
                if (activeColor != null)  style = style.applyFormat(activeColor);
                if (bold)                 style = style.withBold(true);
                if (italic)               style = style.withItalic(true);
                if (underline)            style = style.withUnderlined(true);
                if (strikethrough)        style = style.withStrikethrough(true);
                if (obfuscated)           style = style.withObfuscated(true);
                result.append(Component.literal(content).setStyle(style));
            }
        }
        return result;
    }

    // ── Update checker ────────────────────────────────────────────────────────

    private void onWorldJoin() {
        if (!FishingConfig.getInstance().isUpdateCheckEnabled()) return;
        UpdateChecker.check("poseidon", GITHUB_RELEASES_URL);
    }

    // ── Keybinds ──────────────────────────────────────────────────────────────

    private void handleKeybinds() {
        if (keyToggle.consumeClick()) {
            FishingManager mgr = FishingManager.getInstance();
            if (mgr.isActive()) {
                mgr.setActive(false);
            } else if (PoseidonHudRenderer.isHudVisible()) {
                mgr.setActive(true);
            } else {
                PoseidonLogger.getInstance().logWarn("Open the HUD first (H) before starting.");
            }
        }

        if (keyToggleHud.consumeClick()) {
            boolean nowVisible = !PoseidonHudRenderer.isHudVisible();
            PoseidonHudRenderer.setHudVisible(nowVisible);
            // Stop fishing if the HUD is being closed while active
            if (!nowVisible && FishingManager.getInstance().isActive()) {
                FishingManager.getInstance().setActive(false);
                PoseidonLogger.getInstance().logInfo("HUD closed — fishing stopped.");
            }
        }

        if (keyOpenConfig.consumeClick()) {
            Minecraft.getInstance().setScreen(PoseidonConfigScreen.create(null));
        }
    }

    private void registerKeybinds() {
        keyToggle     = register("Toggle Fishing", GLFW_KEY_Y);
        keyToggleHud  = register("Toggle HUD",     GLFW_KEY_H);
        keyOpenConfig = register("Open Config",    InputConstants.UNKNOWN.getValue());
    }

    private static KeyMapping register(String name, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.poseidon." + name.toLowerCase().replace(' ', '_'),
                InputConstants.Type.KEYSYM,
                defaultKey,
                POSEIDON_CATEGORY
        ));
    }
}
