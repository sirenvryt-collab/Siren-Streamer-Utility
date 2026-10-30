package com.donutstreamer;

import com.donutstreamer.auction.AuctionBrowserScreen;
import com.donutstreamer.auction.AuctionState;
import com.donutstreamer.auction.ServerDetector;
import com.donutstreamer.chat.StreamChat;
import com.donutstreamer.music.Music;
import com.donutstreamer.music.Spotify;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only. Reads payment messages the server already shows you in chat, keeps numbers, draws a HUD,
 * runs the DonutSMP auction tools, and tags doubled payments on your own screen.
 * It never sends packets and never presses or clicks anything for you.
 */
public class DonutStreamerClient implements ClientModInitializer {
    public static final String MOD_ID = "donutstreamer";
    public static Session session;

    private static final KeyBinding.Category KEY_CATEGORY =
            new KeyBinding.Category(Identifier.of(MOD_ID, "general"));

    private static KeyBinding openBrowserKey;
    private static KeyBinding toggleAuctionKey;
    private static KeyBinding musicToggleKey;
    private static KeyBinding musicNextKey;
    private static KeyBinding musicPrevKey;

    private static long lastNanos = System.nanoTime();

    @Override
    public void onInitializeClient() {
        session = Session.load();

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS, Identifier.of(MOD_ID, "hud"), new HudRenderer());
        HudEditor.init();

        openBrowserKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutstreamer.open_browser", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY));
        toggleAuctionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutstreamer.toggle_auction", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, KEY_CATEGORY));

        musicToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutstreamer.music_toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, KEY_CATEGORY));
        musicNextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutstreamer.music_next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, KEY_CATEGORY));
        musicPrevKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutstreamer.music_prev", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, KEY_CATEGORY));
        Spotify.init();   // does nothing on the network until you run /ds music login (only with the Spotify source)
        Music.init();     // reads your computer's media controls, only while the music panel is on
        StreamChat.init(); // does nothing on the network until you run /ds chat twitch|youtube

        ClientReceiveMessageEvents.MODIFY_GAME.register(PaymentChat::modify);
        ClientReceiveMessageEvents.MODIFY_GAME.register(StreamMode::modifyChat);
        ClientTickEvents.END_CLIENT_TICK.register(DonutStreamerClient::tick);
        ClientCommandRegistrationCallback.EVENT.register(Commands::register);

        // Build the ~1,400 item list once at startup instead of freezing for a moment the first time B is pressed.
        ClientLifecycleEvents.CLIENT_STARTED.register(c -> AuctionState.INSTANCE.getItems());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            session.shutdown();
            Spotify.shutdown();
            Music.shutdown();
            StreamChat.shutdown();
        });
    }

    private static void tick(MinecraftClient client) {
        long now = System.nanoTime();
        Session s = session;
        if (client.world != null && client.player != null) {
            // Capped so a long freeze (or a laptop waking from sleep) doesn't inflate the session timer.
            s.elapsedMs += Math.min(5_000L, (now - lastNanos) / 1_000_000L);
        }
        lastNanos = now;

        s.tick();   // debounced background save
        Music.setWanted(client.player != null && s.hudVisible && s.cfg(Widgets.W.MUSIC).on);

        AuctionState auction = AuctionState.INSTANCE;
        auction.isActive();   // notices a countdown that just hit zero
        if (client.player != null) {
            AuctionState.AuctionResult result = auction.takePendingResult();
            if (result != null) Actions.announceAuctionResult(result);
        }

        while (openBrowserKey.wasPressed()) {
            if (client.currentScreen != null) continue;
            if (ServerDetector.isDonutSmp()) {
                client.setScreen(new AuctionBrowserScreen());
            } else if (client.player != null) {
                Actions.bar(Formatting.GRAY, "Auction tools only work on DonutSMP");
            }
        }

        while (musicToggleKey.wasPressed()) {
            if (client.currentScreen == null) Music.togglePlay();
        }
        while (musicNextKey.wasPressed()) {
            if (client.currentScreen == null) Music.next();
        }
        while (musicPrevKey.wasPressed()) {
            if (client.currentScreen == null) Music.previous();
        }

        while (toggleAuctionKey.wasPressed()) {
            if (client.currentScreen == null) Actions.toggleWidget(Widgets.W.AUCTION);
        }
    }
}
