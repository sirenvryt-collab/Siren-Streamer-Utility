package com.donutstreamer.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.donutstreamer.Theme;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional on-screen Twitch / YouTube chat. Nothing connects until you run /ds chat twitch <channel> or
 * /ds chat youtube <video>. Messages land in a small ring buffer that the HUD panel reads; everything network
 * related runs on daemon threads and only ever reads chat (it cannot post).
 */
public final class StreamChat {
    private StreamChat() {}

    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_]{3,25}");
    private static final Pattern VIDEO_ID = Pattern.compile("(?:v=|youtu\\.be/|/live/|/shorts/)([A-Za-z0-9_-]{11})");
    private static final Pattern BARE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private static final int CAPACITY = 60;
    private static final ChatMessage[] RING = new ChatMessage[CAPACITY];
    private static int head = 0;      // next write slot
    private static int count = 0;
    private static long revision = 0;

    /** Everything saved to config/donutstreamer-chat.json. */
    static final class Cfg {
        String twitch = "";
        String youtubeKey = "";
        String youtubeVideo = "";
        // look
        int rows = 14;               // lines of chat that fit in the panel
        int width = 260;             // panel width in GUI pixels
        int spacing = 2;             // extra pixels between lines
        boolean wrap = true;         // wrap long messages instead of cutting them off
        boolean bubbles = true;      // soft background behind each message
        boolean header = true;       // title bar with the connected platforms
        boolean platformMarker = true;
        boolean badges = true;       // owner / mod / vip / sub squares before the name
        boolean timestamps = false;
        boolean textShadow = true;
        boolean nameColors = true;   // use each chatter's own colour
        boolean newestAtTop = false;
        int textColor = 0xFFE6EDF3;
        int fadeSeconds = 60;        // messages dim after this long (0 = never)
        int expireSeconds = 0;       // messages disappear after this long (0 = never)
        // filters
        boolean hideCommands = true; // lines starting with !
        boolean hideBots = true;     // Nightbot, StreamElements, etc.
        boolean hideLinks = false;   // replace links with [link]
        List<String> ignored = new ArrayList<>();
        List<String> highlights = new ArrayList<>();
    }

    private static Cfg cfg = new Cfg();
    private static volatile int layoutRev = 0;
    private static final String[] KNOWN_BOTS = {"nightbot", "streamelements", "streamlabs", "moobot", "fossabot",
            "wizebot", "sery_bot", "soundalerts", "commanderroot", "streamlabs_chat", "pretzelrocks"};
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");


    private static TwitchIrc twitch;
    private static YouTubeChat youtube;
    private static volatile String twitchStatus = "";
    private static volatile String youtubeStatus = "";

    // ---------------- lifecycle ----------------

    /** Loads the saved settings and reconnects whatever you had set up. Does nothing if you never configured chat. */
    public static void init() {
        load();
        if (!cfg.twitch.isEmpty()) startTwitch();
        if (!cfg.youtubeKey.isEmpty() && !cfg.youtubeVideo.isEmpty()) startYouTube();
    }

    public static void shutdown() {
        stopTwitch();
        stopYouTube();
    }

    // ---------------- queries (HUD + commands) ----------------

    /** True when at least one chat source is set up (so the HUD panel has something to say). */
    public static boolean configured() {
        return !cfg.twitch.isEmpty() || (!cfg.youtubeVideo.isEmpty() && !cfg.youtubeKey.isEmpty());
    }

    static Cfg cfg() { return cfg; }

    static int layoutRev() { return layoutRev; }

    public static boolean twitchOn() { return twitch != null; }

    public static boolean twitchConnected() { return twitch != null && twitchStatus.startsWith("Connected"); }

    public static boolean youtubeConnected() { return youtube != null && youtubeStatus.startsWith("Connected"); }

    public static boolean youtubeOn() { return youtube != null; }

    public static String twitchChannel() { return cfg.twitch; }

    public static String youtubeVideo() { return cfg.youtubeVideo; }

    public static boolean hasYouTubeKey() { return !cfg.youtubeKey.isEmpty(); }

    public static boolean hideCommands() { return cfg.hideCommands; }

    public static String statusLine() {
        String t = twitch != null ? twitchStatus : "";
        String y = youtube != null ? youtubeStatus : "";
        if (!t.isEmpty() && !y.isEmpty()) return t + "  |  " + y;
        if (!t.isEmpty()) return t;
        if (!y.isEmpty()) return y;
        return "Not connected";
    }

    static void setTwitchStatus(String s) { twitchStatus = s; }

    static void setYouTubeStatus(String s) { youtubeStatus = s; }

    public static synchronized long revision() { return revision; }

    /** Copies up to out.length of the newest messages into out (oldest first). Returns how many. */
    static synchronized int newest(ChatMessage[] out) {
        int n = Math.min(count, out.length);
        for (int i = 0; i < n; i++) {
            int idx = (head - n + i + CAPACITY * 2) % CAPACITY;
            out[i] = RING[idx];
        }
        return n;
    }

    // ---------------- commands ----------------

    /** @return an error message, or null when started. */
    public static String setTwitch(String channel) {
        String c = channel == null ? "" : channel.trim().replace("#", "");
        int slash = c.lastIndexOf('/');
        if (slash >= 0) c = c.substring(slash + 1);   // accepts https://twitch.tv/name
        if (!CHANNEL.matcher(c).matches()) return "That doesn't look like a Twitch channel name.";
        stopTwitch();
        cfg.twitch = c.toLowerCase();
        startTwitch();
        saveAsync();
        return null;
    }

    public static void clearTwitch() {
        stopTwitch();
        cfg.twitch = "";
        twitchStatus = "";
        saveAsync();
    }

    /** @return an error message, or null when started (or saved while waiting for a key). */
    public static String setYouTube(String input) {
        String v = parseVideoId(input);
        if (v == null) return "Give me the live stream's link or its 11-character video id.";
        stopYouTube();
        cfg.youtubeVideo = v;
        saveAsync();
        if (!cfg.youtubeKey.isEmpty()) startYouTube();
        return null;
    }

    public static void clearYouTube() {
        stopYouTube();
        cfg.youtubeVideo = "";
        youtubeStatus = "";
        saveAsync();
    }

    public static String setYouTubeKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.length() < 20 || k.length() > 80 || !k.matches("[A-Za-z0-9_-]+")) {
            return "That doesn't look like a YouTube API key (letters, digits, - and _ only).";
        }
        cfg.youtubeKey = k;
        saveAsync();
        if (!cfg.youtubeVideo.isEmpty()) {
            stopYouTube();
            startYouTube();
        }
        return null;
    }

    public static void clearYouTubeKey() {
        stopYouTube();
        cfg.youtubeKey = "";
        youtubeStatus = "";
        saveAsync();
    }

    public static boolean toggleHideCommands() {
        cfg.hideCommands = !cfg.hideCommands;
        layoutRev++;
        saveAsync();
        return cfg.hideCommands;
    }

    public static void disconnectAll() {
        stopTwitch();
        stopYouTube();
        twitchStatus = "";
        youtubeStatus = "";
    }

    public static synchronized void clearMessages() {
        for (int i = 0; i < CAPACITY; i++) RING[i] = null;
        head = 0;
        count = 0;
        revision++;
    }

    static String parseVideoId(String input) {
        if (input == null) return null;
        String s = input.trim();
        Matcher m = VIDEO_ID.matcher(s);
        if (m.find()) return m.group(1);
        return BARE_ID.matcher(s).matches() ? s : null;
    }

    // ---------------- connections ----------------

    private static void startTwitch() {
        stopTwitch();
        twitchStatus = "Connecting to #" + cfg.twitch + "...";
        twitch = new TwitchIrc(cfg.twitch);
        twitch.start();
    }

    private static void stopTwitch() {
        if (twitch != null) twitch.stop();
        twitch = null;
    }

    private static void startYouTube() {
        stopYouTube();
        youtubeStatus = "Connecting to YouTube...";
        youtube = new YouTubeChat(cfg.youtubeKey, cfg.youtubeVideo);
        youtube.start();
    }

    private static void stopYouTube() {
        if (youtube != null) youtube.stop();
        youtube = null;
    }

    // ---------------- messages ----------------

    static void add(int platform, String rawName, String rawText, int color, int badges) {
        String text = clean(rawText, 200);
        String name = clean(rawName, 25);
        if (text.isEmpty()) return;
        if (name.isEmpty()) name = "user";
        Cfg c = cfg;
        if (c.hideCommands && text.startsWith("!")) return;
        String lower = name.toLowerCase(Locale.ROOT);
        if (c.hideBots) {
            for (String b : KNOWN_BOTS) {
                if (b.equals(lower)) return;
            }
        }
        for (String ig : c.ignored) {
            if (ig.equals(lower)) return;
        }
        if (c.hideLinks) text = LINK.matcher(text).replaceAll("[link]");
        boolean hl = false;
        if (!c.highlights.isEmpty()) {
            String lt = text.toLowerCase(Locale.ROOT);
            for (String k : c.highlights) {
                if (lt.contains(k)) { hl = true; break; }
            }
        }
        ChatMessage m = new ChatMessage(platform, name, text, color, badges, hl);
        synchronized (StreamChat.class) {
            RING[head] = m;
            head = (head + 1) % CAPACITY;
            if (count < CAPACITY) count++;
            revision++;
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch == 127 || ch == '\u00a7') continue;   // control characters and Minecraft colour codes
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    // ---------------- settings (/ds chat set ...) ----------------

    public record Result(boolean ok, String message) {}

    private static final String[][] OPTIONS = {
            {"rows", "number 4-30", "how many lines of chat fit in the panel"},
            {"width", "number 140-420", "panel width"},
            {"spacing", "number 0-6", "extra space between lines"},
            {"wrap", "on/off", "wrap long messages onto more lines (off = cut with ...)"},
            {"bubbles", "on/off", "soft background behind each message"},
            {"header", "on/off", "title bar with connected platforms"},
            {"marker", "on/off", "little Twitch / YouTube colour square per message"},
            {"badges", "on/off", "owner / mod / VIP / sub badges before names"},
            {"timestamps", "on/off", "show the time each message arrived"},
            {"shadow", "on/off", "text shadow"},
            {"namecolors", "on/off", "use each chatter's own colour"},
            {"top", "on/off", "newest message at the top instead of the bottom"},
            {"textcolor", "colour or #RRGGBB", "message text colour"},
            {"fade", "seconds, 0 = never", "dim messages after this long"},
            {"expire", "seconds, 0 = never", "remove messages after this long"},
            {"commands", "on/off", "hide lines starting with !"},
            {"bots", "on/off", "hide Nightbot, StreamElements and other common bots"},
            {"hidelinks", "on/off", "replace links with [link]"},
    };

    public static String[] optionKeys() {
        String[] out = new String[OPTIONS.length];
        for (int i = 0; i < OPTIONS.length; i++) out[i] = OPTIONS[i][0];
        return out;
    }

    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (String[] o : OPTIONS) out.add(o[0] + " = " + valueOf(o[0]) + "   (" + o[1] + ") " + o[2]);
        return out;
    }

    private static String onoff(boolean b) { return b ? "on" : "off"; }

    private static String valueOf(String key) {
        Cfg c = cfg;
        switch (key) {
            case "rows": return Integer.toString(c.rows);
            case "width": return Integer.toString(c.width);
            case "spacing": return Integer.toString(c.spacing);
            case "wrap": return onoff(c.wrap);
            case "bubbles": return onoff(c.bubbles);
            case "header": return onoff(c.header);
            case "marker": return onoff(c.platformMarker);
            case "badges": return onoff(c.badges);
            case "timestamps": return onoff(c.timestamps);
            case "shadow": return onoff(c.textShadow);
            case "namecolors": return onoff(c.nameColors);
            case "top": return onoff(c.newestAtTop);
            case "textcolor": return String.format("#%06X", c.textColor & 0xFFFFFF);
            case "fade": return c.fadeSeconds == 0 ? "never" : c.fadeSeconds + "s";
            case "expire": return c.expireSeconds == 0 ? "never" : c.expireSeconds + "s";
            case "commands": return onoff(c.hideCommands);
            case "bots": return onoff(c.hideBots);
            case "hidelinks": return onoff(c.hideLinks);
            default: return "?";
        }
    }

    private static Boolean parseBool(String v, boolean current) {
        if (v == null || v.isBlank() || v.equalsIgnoreCase("toggle")) return !current;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "yes": case "1": return true;
            case "off": case "false": case "no": case "0": return false;
            default: return null;
        }
    }

    private static Integer parseInt(String v, int min, int max) {
        try {
            int n = Integer.parseInt(v.trim().replace("s", ""));
            return n < min || n > max ? null : n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Result set(String key, String value) {
        Cfg c = cfg;
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value.trim();
        Boolean b;
        Integer n;
        switch (k) {
            case "rows": n = parseInt(v, 4, 30); if (n == null) return bad("rows is a number from 4 to 30"); c.rows = n; break;
            case "width": n = parseInt(v, 140, 420); if (n == null) return bad("width is a number from 140 to 420"); c.width = n; break;
            case "spacing": n = parseInt(v, 0, 6); if (n == null) return bad("spacing is a number from 0 to 6"); c.spacing = n; break;
            case "fade": n = parseInt(v, 0, 600); if (n == null) return bad("fade is seconds from 0 to 600 (0 = never)"); c.fadeSeconds = n; break;
            case "expire": n = parseInt(v, 0, 3600); if (n == null) return bad("expire is seconds from 0 to 3600 (0 = never)"); c.expireSeconds = n; break;
            case "textcolor": {
                Integer col = Theme.parseColor(v);
                if (col == null) return bad("textcolor wants a colour name (white, gold, aqua...) or #RRGGBB");
                c.textColor = col;
                break;
            }
            case "wrap": b = parseBool(v, c.wrap); if (b == null) return bad("use on or off"); c.wrap = b; break;
            case "bubbles": b = parseBool(v, c.bubbles); if (b == null) return bad("use on or off"); c.bubbles = b; break;
            case "header": b = parseBool(v, c.header); if (b == null) return bad("use on or off"); c.header = b; break;
            case "marker": b = parseBool(v, c.platformMarker); if (b == null) return bad("use on or off"); c.platformMarker = b; break;
            case "badges": b = parseBool(v, c.badges); if (b == null) return bad("use on or off"); c.badges = b; break;
            case "timestamps": b = parseBool(v, c.timestamps); if (b == null) return bad("use on or off"); c.timestamps = b; break;
            case "shadow": b = parseBool(v, c.textShadow); if (b == null) return bad("use on or off"); c.textShadow = b; break;
            case "namecolors": b = parseBool(v, c.nameColors); if (b == null) return bad("use on or off"); c.nameColors = b; break;
            case "top": b = parseBool(v, c.newestAtTop); if (b == null) return bad("use on or off"); c.newestAtTop = b; break;
            case "commands": b = parseBool(v, c.hideCommands); if (b == null) return bad("use on or off"); c.hideCommands = b; break;
            case "bots": b = parseBool(v, c.hideBots); if (b == null) return bad("use on or off"); c.hideBots = b; break;
            case "hidelinks": b = parseBool(v, c.hideLinks); if (b == null) return bad("use on or off"); c.hideLinks = b; break;
            default: return bad("unknown option \"" + key + "\". Run /ds chat settings for the list.");
        }
        layoutRev++;
        saveAsync();
        return new Result(true, "Chat " + k + " = " + valueOf(k));
    }

    private static Result bad(String m) {
        return new Result(false, m);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace("@", "");
    }

    public static Result ignore(boolean add, String name) {
        String n = norm(name);
        if (n.isEmpty()) return bad("Give a chatter name.");
        boolean changed = add ? (!cfg.ignored.contains(n) && cfg.ignored.add(n)) : cfg.ignored.remove(n);
        if (changed) saveAsync();
        return new Result(true, (add ? "Ignoring " : "No longer ignoring ") + n + (changed ? "" : " (no change)"));
    }

    public static List<String> ignored() { return List.copyOf(cfg.ignored); }

    public static void clearIgnored() { cfg.ignored.clear(); saveAsync(); }

    public static Result highlight(boolean add, String word) {
        String w = norm(word);
        if (w.isEmpty() || w.length() > 40) return bad("Give a word or phrase (up to 40 characters).");
        boolean changed = add ? (!cfg.highlights.contains(w) && cfg.highlights.add(w)) : cfg.highlights.remove(w);
        if (changed) saveAsync();
        return new Result(true, (add ? "Highlighting \"" : "No longer highlighting \"") + w + "\"" + (changed ? "" : " (no change)"));
    }

    public static List<String> highlights() { return List.copyOf(cfg.highlights); }

    public static void clearHighlights() { cfg.highlights.clear(); saveAsync(); }

    // ---------------- settings file ----------------

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutstreamer-chat.json");
    }

    private static void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            Cfg loaded = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Cfg.class);
            if (loaded == null) return;
            if (loaded.twitch == null || !CHANNEL.matcher(loaded.twitch).matches()) loaded.twitch = "";
            if (loaded.youtubeKey == null) loaded.youtubeKey = "";
            if (loaded.youtubeVideo == null) loaded.youtubeVideo = "";
            if (loaded.ignored == null) loaded.ignored = new ArrayList<>();
            if (loaded.highlights == null) loaded.highlights = new ArrayList<>();
            loaded.rows = Math.max(4, Math.min(30, loaded.rows));
            loaded.width = Math.max(140, Math.min(420, loaded.width));
            loaded.spacing = Math.max(0, Math.min(6, loaded.spacing));
            loaded.fadeSeconds = Math.max(0, Math.min(600, loaded.fadeSeconds));
            loaded.expireSeconds = Math.max(0, Math.min(3600, loaded.expireSeconds));
            cfg = loaded;
            layoutRev++;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read donutstreamer-chat.json", e);
        }
    }

    private static void saveAsync() {
        String json = GSON.toJson(cfg);
        Path p = file();
        Thread t = new Thread(() -> {
            try {
                Files.writeString(p, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Could not write donutstreamer-chat.json", e);
            }
        }, "donutstreamer-chat-io");
        t.setDaemon(true);
        t.start();
    }
}
