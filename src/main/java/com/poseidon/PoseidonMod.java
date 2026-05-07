package com.poseidon;

import com.poseidon.core.FishingConfig;
import com.poseidon.core.FishingManager;
import com.poseidon.core.PoseidonLogger;
import com.poseidon.gui.PoseidonConfigScreen;
import com.poseidon.gui.PoseidonHudRenderer;
import com.playerapi.DisplayActions;
import com.playerapi.PlayerAPIEvents;
import com.playerapi.PlayerInfo;
import com.playerapi.Scheduler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

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

    public static KeyBinding keyToggle;
    public static KeyBinding keyToggleHud;
    public static KeyBinding keyOpenConfig;

    private static final KeyBinding.Category POSEIDON_CATEGORY =
            KeyBinding.Category.create(Identifier.of("poseidon", "controls"));

    @Override
    public void onInitializeClient() {
        PoseidonLogger.getInstance().logInfo("Poseidon initialising...");

        FishingConfig.getInstance().load();

        registerKeybinds();
        registerCommands();
        HudRenderCallback.EVENT.register(PoseidonHudRenderer::render);
        PlayerAPIEvents.TICK.register(this::onTick);
        PlayerAPIEvents.CHAT_RECEIVED.register(this::onChatReceived);
        PlayerAPIEvents.WORLD_JOIN.register(this::onWorldJoin);

        PoseidonLogger.getInstance().logInfo("Poseidon ready.");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("poseidon")
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
                MinecraftClient.getInstance().setScreen(PoseidonConfigScreen.create(null));
            } catch (Exception e) {
                PoseidonLogger.getInstance().logError("Failed to open config screen: " + e.getMessage());
            }
        }

        FishingManager.getInstance().tick();
        handleKeybinds();
    }

    private void onChatReceived(String sender, String message) {
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

    private static void showTitle(FishingConfig.TriggerLevel level) {
        String text = level.titleText.isBlank() ? level.name : level.titleText;
        if (text.isBlank()) text = "Trigger Fired";
        DisplayActions.showTitle(text, "");
    }

    // ── Update checker ────────────────────────────────────────────────────────

    private void onWorldJoin() {
        if (!FishingConfig.getInstance().isUpdateCheckEnabled()) return;
        checkForUpdate();
    }

    private static void checkForUpdate() {
        String currentVersion = FabricLoader.getInstance()
                .getModContainer("poseidon")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        Thread thread = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_RELEASES_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "PoseidonMod-UpdateCheck/1.0")
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String latestVersion = parseTagName(response.body());
                    if (latestVersion != null && isNewerVersion(latestVersion, currentVersion)) {
                        // Deliver the notification on the main thread after the world has settled
                        Scheduler.schedule(60, () -> sendUpdateNotification(latestVersion, currentVersion));
                    }
                }
            } catch (Exception e) {
                // Silent fail — update check is optional and best-effort
                PoseidonLogger.getInstance().logInfo("Update check failed (offline?): " + e.getMessage());
            }
        }, "poseidon-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static String parseTagName(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String tag = obj.get("tag_name").getAsString();
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true if {@code remote} is a strictly higher semantic version than {@code local}.
     * Compares each dot-separated numeric segment left-to-right. Non-numeric segments are ignored.
     */
    private static boolean isNewerVersion(String remote, String local) {
        try {
            String[] r = remote.replaceAll("[^0-9.]", "").split("\\.");
            String[] l = local.replaceAll("[^0-9.]", "").split("\\.");
            int len = Math.max(r.length, l.length);
            for (int i = 0; i < len; i++) {
                int rv = i < r.length && !r[i].isEmpty() ? Integer.parseInt(r[i]) : 0;
                int lv = i < l.length && !l[i].isEmpty() ? Integer.parseInt(l[i]) : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void sendUpdateNotification(String latestVersion, String currentVersion) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        mc.player.sendMessage(
            Text.literal("[Poseidon] ").formatted(Formatting.AQUA)
                .append(Text.literal("Update available! ").formatted(Formatting.GREEN))
                .append(Text.literal("v" + latestVersion).formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(" (you have v" + currentVersion + ") ").formatted(Formatting.GRAY))
                .append(Text.literal("— " + GITHUB_RELEASES_URL
                        .replace("api.github.com/repos", "github.com")
                        .replace("/releases/latest", "/releases"))
                        .formatted(Formatting.YELLOW)),
            false);
        PoseidonLogger.getInstance().logInfo(
                "Update available: v" + latestVersion + " (current: v" + currentVersion + ")");
    }

    // ── Keybinds ──────────────────────────────────────────────────────────────

    private void handleKeybinds() {
        if (keyToggle.wasPressed()) {
            FishingManager mgr = FishingManager.getInstance();
            if (mgr.isActive()) {
                mgr.setActive(false);
            } else if (PoseidonHudRenderer.isHudVisible()) {
                mgr.setActive(true);
            } else {
                PoseidonLogger.getInstance().logWarn("Open the HUD first (H) before starting.");
            }
        }

        if (keyToggleHud.wasPressed()) {
            boolean nowVisible = !PoseidonHudRenderer.isHudVisible();
            PoseidonHudRenderer.setHudVisible(nowVisible);
            // Stop fishing if the HUD is being closed while active
            if (!nowVisible && FishingManager.getInstance().isActive()) {
                FishingManager.getInstance().setActive(false);
                PoseidonLogger.getInstance().logInfo("HUD closed — fishing stopped.");
            }
        }

        if (keyOpenConfig.wasPressed()) {
            MinecraftClient.getInstance().setScreen(PoseidonConfigScreen.create(null));
        }
    }

    private void registerKeybinds() {
        keyToggle     = register("Toggle Fishing", InputUtil.GLFW_KEY_Y);
        keyToggleHud  = register("Toggle HUD",     InputUtil.GLFW_KEY_H);
        keyOpenConfig = register("Open Config",    -1);
    }

    private static KeyBinding register(String name, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.poseidon." + name.toLowerCase().replace(' ', '_'),
                InputUtil.Type.KEYSYM,
                defaultKey,
                POSEIDON_CATEGORY
        ));
    }
}
