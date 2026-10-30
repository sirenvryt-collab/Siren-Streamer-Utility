package com.donutstreamer.music;

import com.donutstreamer.Actions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify remote for the Spotify app on your own computer, using the official Spotify Web API.
 *
 * Minecraft cannot stream Spotify audio itself. This shows what is playing in a HUD panel and lets you
 * play/pause/skip/change volume from the game. Everything runs on your machine and only talks to
 * accounts.spotify.com and api.spotify.com. It does nothing until you run "/ds music login".
 *
 * Setup is the usual Spotify way: you register your own free developer app (Premium account needed by
 * Spotify), and sign in with PKCE, so there is no client secret anywhere. Tokens are kept in
 * config/donutstreamer-spotify.json. All network work happens on background threads.
 */
public final class Spotify {
    private Spotify() {}

    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final Gson GSON = new Gson();

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1";
    private static final int PORT = 8888;
    /** Must be registered in the Spotify dashboard exactly like this (127.0.0.1, not localhost). */
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final String DASHBOARD = "https://developer.spotify.com/dashboard";
    private static final long LOGIN_TIMEOUT_MS = 120_000L;

    /** What is playing right now. Progress is extrapolated locally between polls. */
    public record Now(String id, String title, String artist, long durationMs, long progressMs,
                      boolean playing, String device, int volume, long atNanos) {
        public long progressNow() {
            if (!playing) return Math.min(durationMs, progressMs);
            long p = progressMs + (System.nanoTime() - atNanos) / 1_000_000L;
            return durationMs > 0 ? Math.min(durationMs, p) : p;
        }
    }

    private record Resp(int code, String body, int retryAfterSec) {}

    // ---- shared state (read by the HUD on the game thread) ----
    private static volatile Now now = null;
    private static volatile String status = "";
    private static volatile boolean wanted = false;       // HUD panel is on and we are in a world
    private static volatile boolean loggingIn = false;

    // ---- credentials ----
    private static volatile String clientId = "";
    private static volatile String refreshToken = "";
    private static volatile String accessToken = "";
    private static volatile long accessExpiresAt = 0;
    private static volatile long backoffUntil = 0;

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "donutstreamer-spotify");
        t.setDaemon(true);
        return t;
    });

    // ================= lifecycle =================

    public static void init() {
        loadConfig();
        EXEC.scheduleWithFixedDelay(() -> {
            try {
                poll(false);
            } catch (Exception e) {
                LOG.debug("Spotify poll failed", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        EXEC.shutdownNow();
    }

    /** Called every client tick: lets the poller idle when the panel is hidden or you are not in a world. */
    public static void setWanted(boolean value) {
        wanted = value;
    }

    public static Now now() {
        return connected() ? now : null;
    }

    public static boolean connected() {
        return !refreshToken.isEmpty();
    }

    public static String statusText() {
        if (!connected()) return "Not connected: /ds music login";
        return status.isEmpty() ? "Waiting for Spotify..." : status;
    }

    // ================= messages (always posted on the game thread) =================

    private static void chat(Formatting color, String text) {
        MinecraftClient.getInstance().execute(() -> Actions.chat(color, text));
    }

    private static void bar(Formatting color, String text) {
        MinecraftClient.getInstance().execute(() -> Actions.bar(color, text));
    }

    private static void link(String prefix, String url) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            URI uri = URI.create(url);
            mc.player.sendMessage(Text.literal(prefix).formatted(Formatting.GRAY)
                    .append(Text.literal(url).formatted(Formatting.AQUA, Formatting.UNDERLINE)
                            .styled(s -> s.withClickEvent(new ClickEvent.OpenUrl(uri)))), false);
        });
    }

    // ================= commands =================

    /** "/ds music": status, or setup help when not connected. */
    public static void showStatus() {
        if (!connected()) {
            chat(Formatting.GOLD, "Spotify is not connected. One-time setup (needs Spotify Premium):");
            link("1) Create an app at ", DASHBOARD);
            chat(Formatting.GRAY, "2) Tick \"Web API\" and add this Redirect URI exactly: " + REDIRECT_URI);
            chat(Formatting.GRAY, "3) Copy the app's Client ID, then run /ds music login <clientId>");
            return;
        }
        Now n = now;
        if (n == null) {
            chat(Formatting.GRAY, "Spotify: " + statusText());
            return;
        }
        chat(n.playing() ? Formatting.GREEN : Formatting.GRAY,
                (n.playing() ? "Playing: " : "Paused: ") + n.title() + " - " + n.artist()
                        + (n.device().isEmpty() ? "" : "  (" + n.device() + ")"));
    }

    public static void login(String id) {
        if (id != null && !id.isBlank()) {
            String trimmed = id.trim();
            if (!trimmed.matches("[A-Za-z0-9]{16,64}")) {
                chat(Formatting.RED, "That doesn't look like a Spotify Client ID (about 32 letters and digits).");
                return;
            }
            clientId = trimmed;
            saveConfig();
        }
        if (clientId.isEmpty()) {
            showStatus();
            return;
        }
        if (loggingIn) {
            chat(Formatting.GRAY, "A Spotify sign-in is already waiting. Finish it in your browser, or wait for it to time out.");
            return;
        }
        loggingIn = true;
        Thread t = new Thread(Spotify::runLogin, "donutstreamer-spotify-login");
        t.setDaemon(true);
        t.start();
    }

    public static void logout() {
        refreshToken = "";
        accessToken = "";
        now = null;
        status = "";
        saveConfig();
        chat(Formatting.GRAY, "Spotify disconnected. Your Client ID is kept; run /ds music login to reconnect.");
    }

    public static void play() { control("Play", "PUT", "/me/player/play"); }

    public static void pause() { control("Pause", "PUT", "/me/player/pause"); }

    public static void next() { control("Next", "POST", "/me/player/next"); }

    public static void previous() { control("Previous", "POST", "/me/player/previous"); }

    public static void togglePlay() {
        Now n = now;
        if (n != null && n.playing()) pause(); else play();
    }

    public static void volume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        control("Volume " + v + "%", "PUT", "/me/player/volume?volume_percent=" + v);
    }

    // ================= sign-in (PKCE, loopback redirect) =================

    private static void runLogin() {
        String verifier = Pkce.newVerifier();
        String state = Pkce.newState();
        String url = AUTH_URL
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(Pkce.challenge(verifier))
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state);

        try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            server.setReuseAddress(true);
            server.setSoTimeout(5_000);
            chat(Formatting.GOLD, "Click the link to sign in to Spotify (waiting up to 2 minutes):");
            link("", url);

            long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
            String code = null;
            String error = null;
            while (System.currentTimeMillis() < deadline && code == null && error == null) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5_000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    if (!Pkce.isCallback(line)) {
                        reply(s, 404, "Not found");
                        continue;
                    }
                    if (!state.equals(Pkce.queryParam(line, "state"))) {
                        reply(s, 400, "State mismatch. Close this tab and run /ds music login again.");
                        error = "Sign-in rejected (state mismatch).";
                        continue;
                    }
                    String err = Pkce.queryParam(line, "error");
                    String c = Pkce.queryParam(line, "code");
                    if (err != null || c == null) {
                        reply(s, 200, "Spotify sign-in was cancelled. You can close this tab.");
                        error = "Spotify sign-in was cancelled.";
                    } else {
                        reply(s, 200, "Spotify connected to Donut Streamer. You can close this tab and go back to Minecraft.");
                        code = c;
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // keep waiting until the deadline
                }
            }
            if (code == null) {
                chat(Formatting.RED, error != null ? error : "Spotify sign-in timed out. Run /ds music login to try again.");
                return;
            }
            exchangeCode(code, verifier);
        } catch (java.net.BindException e) {
            chat(Formatting.RED, "Port " + PORT + " is in use, so the Spotify sign-in can't listen. Close whatever uses it and retry.");
        } catch (IOException e) {
            LOG.warn("Spotify sign-in failed", e);
            chat(Formatting.RED, "Spotify sign-in failed: " + e.getMessage());
        } finally {
            loggingIn = false;
        }
    }

    private static void reply(Socket s, int code, String message) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><title>Donut Streamer</title>"
                + "<body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:20vh\">"
                + "<h2>" + message.replace("<", "&lt;") + "</h2></body>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void exchangeCode(String code, String verifier) throws IOException {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&client_id=" + enc(clientId)
                + "&code_verifier=" + enc(verifier);
        Resp r = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        if (r.code != 200) {
            LOG.warn("Spotify token exchange failed: {} {}", r.code, r.body);
            chat(Formatting.RED, "Spotify refused the sign-in (" + r.code + "). Check the Client ID and that the Redirect URI is exactly "
                    + REDIRECT_URI);
            return;
        }
        applyTokens(r.body);
        saveConfig();
        status = "Connected. Start playing something in Spotify.";
        chat(Formatting.GREEN, "Spotify connected. Open Spotify on your PC and play something.");
        EXEC.execute(() -> {
            try {
                poll(true);
            } catch (Exception e) {
                LOG.debug("Spotify poll failed", e);
            }
        });
    }

    private static void applyTokens(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        accessToken = str(o, "access_token");
        long expires = o.has("expires_in") && !o.get("expires_in").isJsonNull() ? o.get("expires_in").getAsLong() : 3600;
        accessExpiresAt = System.currentTimeMillis() + Math.max(60, expires - 30) * 1000L;
        String refresh = str(o, "refresh_token");
        if (!refresh.isEmpty()) refreshToken = refresh;
    }

    /** Makes sure accessToken is valid. Returns false when we are not signed in (or the sign-in was revoked). */
    private static boolean ensureToken(boolean force) throws IOException {
        if (refreshToken.isEmpty() || clientId.isEmpty()) return false;
        if (!force && !accessToken.isEmpty() && System.currentTimeMillis() < accessExpiresAt) return true;
        String form = "grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId);
        Resp r = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null);
        if (r.code == 200) {
            applyTokens(r.body);
            saveConfig();
            return true;
        }
        if (r.code == 400 || r.code == 401) {
            // Revoked or expired for good: forget it so the HUD stops polling and tells the user.
            refreshToken = "";
            accessToken = "";
            now = null;
            saveConfig();
            chat(Formatting.RED, "Spotify sign-in expired. Run /ds music login to reconnect.");
        } else {
            status = "Spotify is unreachable right now.";
        }
        return false;
    }

    // ================= API =================

    private static Resp authed(String method, String url) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!ensureToken(attempt > 0)) return new Resp(401, "", 0);
            Resp r = http(method, url, "GET".equals(method) ? null : "", "application/json", accessToken);
            if (r.code != 401) return r;
            accessToken = "";   // force a refresh and retry once
        }
        return new Resp(401, "", 0);
    }

    private static void poll(boolean force) throws IOException {
        if (!connected()) return;
        if (!force && !wanted) return;
        if (System.currentTimeMillis() < backoffUntil) return;

        Resp r = authed("GET", API + "/me/player?additional_types=episode");
        if (r.code == 204 || (r.code == 200 && r.body.isBlank())) {
            now = null;
            status = "Nothing playing. Start Spotify on your PC.";
        } else if (r.code == 200) {
            Now parsed = parse(r.body);
            now = parsed;
            status = parsed == null ? "Nothing playing. Start Spotify on your PC." : "";
        } else if (r.code == 429) {
            backoffUntil = System.currentTimeMillis() + Math.max(5, r.retryAfterSec) * 1000L;
        } else if (r.code >= 500) {
            status = "Spotify is having problems (" + r.code + ").";
        } else if (r.code != 401) {
            status = "Spotify error " + r.code + ".";
        }
    }

    /** Turns a /me/player response into a Now, or null when there is no track (or only an ad). */
    private static Now parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonElement itemEl = o.get("item");
            boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
            String device = "";
            int volume = -1;
            if (o.has("device") && o.get("device").isJsonObject()) {
                JsonObject d = o.getAsJsonObject("device");
                device = str(d, "name");
                if (d.has("volume_percent") && !d.get("volume_percent").isJsonNull()) volume = d.get("volume_percent").getAsInt();
            }
            long progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
            if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
                if ("ad".equals(str(o, "currently_playing_type"))) {
                    return new Now("ad", "Advertisement", "Spotify", 0, 0, playing, device, volume, System.nanoTime());
                }
                return null;
            }
            JsonObject item = itemEl.getAsJsonObject();
            String artist;
            if (item.has("artists") && item.get("artists").isJsonArray()) {
                JsonArray arr = item.getAsJsonArray("artists");
                StringBuilder sb = new StringBuilder();
                for (JsonElement a : arr) {
                    if (!a.isJsonObject()) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(str(a.getAsJsonObject(), "name"));
                }
                artist = sb.toString();
            } else if (item.has("show") && item.get("show").isJsonObject()) {
                artist = str(item.getAsJsonObject("show"), "name");   // podcast episode
            } else {
                artist = "";
            }
            long duration = item.has("duration_ms") && !item.get("duration_ms").isJsonNull() ? item.get("duration_ms").getAsLong() : 0;
            return new Now(str(item, "id"), str(item, "name"), artist, duration, progress, playing, device, volume, System.nanoTime());
        } catch (RuntimeException e) {
            LOG.debug("Could not read Spotify response", e);
            return null;
        }
    }

    private static void control(String label, String method, String path) {
        if (!connected()) {
            showStatus();
            return;
        }
        EXEC.execute(() -> {
            try {
                Resp r = authed(method, API + path);
                if (r.code >= 200 && r.code < 300) {
                    bar(Formatting.GREEN, "Spotify: " + label);
                } else if (r.code == 403) {
                    chat(Formatting.RED, "Spotify only allows playback control on Premium accounts.");
                } else if (r.code == 404) {
                    chat(Formatting.RED, "No active Spotify device. Open Spotify on your PC and press play once.");
                } else if (r.code == 429) {
                    backoffUntil = System.currentTimeMillis() + Math.max(5, r.retryAfterSec) * 1000L;
                    chat(Formatting.RED, "Spotify is rate limiting requests. Try again in a few seconds.");
                } else if (r.code != 401) {
                    chat(Formatting.RED, "Spotify error " + r.code + " for \"" + label + "\".");
                }
                // Spotify applies commands a moment later; refresh the panel shortly after.
                EXEC.schedule(() -> {
                    try {
                        poll(true);
                    } catch (Exception e) {
                        LOG.debug("Spotify poll failed", e);
                    }
                }, 600, TimeUnit.MILLISECONDS);
            } catch (IOException e) {
                chat(Formatting.RED, "Couldn't reach Spotify: " + e.getMessage());
            }
        });
    }

    // ================= HTTP / config helpers =================

    private static Resp http(String method, String url, String body, String contentType, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "DonutStreamer");
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int retry = 0;
            String ra = c.getHeaderField("Retry-After");
            if (ra != null) {
                try {
                    retry = Integer.parseInt(ra.trim());
                } catch (NumberFormatException ignored) {
                    // leave 0
                }
            }
            return new Resp(code, text, retry);
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutstreamer-spotify.json");
    }

    private static synchronized void saveConfig() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("clientId", clientId);
            o.addProperty("refreshToken", refreshToken);
            Path p = configPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not save Spotify settings", e);
        }
    }

    private static void loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            clientId = str(o, "clientId");
            refreshToken = str(o, "refreshToken");
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read Spotify settings", e);
        }
    }
}
