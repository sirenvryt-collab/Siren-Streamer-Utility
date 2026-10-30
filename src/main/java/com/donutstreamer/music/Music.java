package com.donutstreamer.music;

import com.donutstreamer.Actions;
import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import net.minecraft.util.Formatting;

/**
 * One front door for "what is playing". The default source reads your computer's media controls (no account, works
 * with Spotify Free); the old Spotify Web API source is still there if you prefer it ({@code /ds music source spotify}).
 */
public final class Music {
    private Music() {}

    private static boolean system() {
        Session s = DonutStreamerClient.session;
        return s == null || !"spotify".equals(s.musicSource);
    }

    public static void init() {
        Session s = DonutStreamerClient.session;
        SystemMedia.setFilter(s == null ? "" : s.musicApp);
        SystemMedia.init();
    }

    public static void shutdown() {
        SystemMedia.shutdown();
    }

    /** Called every client tick: only the active source works, and only while the panel is on and you are in a world. */
    public static void setWanted(boolean wanted) {
        boolean sys = system();
        SystemMedia.setWanted(wanted && sys);
        Spotify.setWanted(wanted && !sys);
        Session s = DonutStreamerClient.session;
        if (s != null) SystemMedia.setFilter(s.musicApp);
    }

    public static Spotify.Now now() {
        return system() ? SystemMedia.now() : Spotify.now();
    }

    public static boolean connected() {
        return system() || Spotify.connected();
    }

    public static String statusText() {
        return system() ? SystemMedia.statusText() : Spotify.statusText();
    }

    public static void togglePlay() {
        if (system()) SystemMedia.playPause(); else Spotify.togglePlay();
    }

    public static void play() {
        if (system()) SystemMedia.playPause(); else Spotify.play();
    }

    public static void pause() {
        if (system()) SystemMedia.playPause(); else Spotify.pause();
    }

    public static void next() {
        if (system()) SystemMedia.next(); else Spotify.next();
    }

    public static void previous() {
        if (system()) SystemMedia.previous(); else Spotify.previous();
    }

    public static void showStatus() {
        if (!system()) {
            Spotify.showStatus();
            return;
        }
        Spotify.Now n = SystemMedia.now();
        Actions.chat(Formatting.GREEN, "Music source: your computer's media controls (" + SystemMedia.describeEnvironment()
                + "), no account needed.");
        if (n == null) {
            Actions.chat(Formatting.GRAY, "  " + SystemMedia.statusText() + ". Start Spotify (Free is fine), YouTube Music, etc., and turn the panel on with /ds hud music.");
        } else {
            Actions.chat(n.playing() ? Formatting.GREEN : Formatting.GRAY,
                    "  " + (n.playing() ? "Playing: " : "Paused: ") + n.title() + " - " + n.artist() + "  (" + n.device() + ")");
        }
        Session s = DonutStreamerClient.session;
        if (s != null && !s.musicApp.isEmpty()) Actions.chat(Formatting.GRAY, "  Only following players matching \"" + s.musicApp + "\" (/ds music app any to clear)");
    }

    public static void setSource(String source) {
        Session s = DonutStreamerClient.session;
        s.musicSource = source;
        s.save();
        if (source.equals("spotify")) {
            Actions.chat(Formatting.GREEN, "Music source: Spotify Web API. " + (Spotify.connected() ? "Connected." : "Run /ds music login to connect (needs Premium)."));
        } else {
            Actions.chat(Formatting.GREEN, "Music source: system media controls (no account, works with Spotify Free).");
        }
    }

    public static void setApp(String app) {
        Session s = DonutStreamerClient.session;
        boolean any = app == null || app.equalsIgnoreCase("any") || app.isBlank();
        s.musicApp = any ? "" : app.trim();
        s.save();
        SystemMedia.setFilter(s.musicApp);
        Actions.chat(Formatting.GRAY, any ? "Following whatever your system says is playing."
                : "Only following players whose name contains \"" + s.musicApp + "\" (for example spotify, chrome, music).");
    }
}
