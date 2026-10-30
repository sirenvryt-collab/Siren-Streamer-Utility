package com.donutstreamer.music;

import com.donutstreamer.Actions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * "Now playing" read straight from your computer's media controls, so it works with Spotify Free, YouTube Music,
 * Apple Music, browsers and most other players. No account, no login, no Premium, no internet.
 * <ul>
 *   <li>Windows: one small hidden PowerShell process asks the system media session list once a second.</li>
 *   <li>Linux: {@code playerctl} (install it from your package manager).</li>
 *   <li>macOS: Spotify and Music through {@code osascript} (best effort).</li>
 * </ul>
 * It only reads what is playing and sends the standard media keys for play/pause/next/previous. It runs on its own
 * daemon thread, only while the music panel is on, and the HUD just reads one volatile field.
 */
public final class SystemMedia {
    private SystemMedia() {}

    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");
    private static final char TAB = '\t';

    private static volatile Spotify.Now now = null;
    private static volatile String status = "Starting...";
    private static volatile boolean wanted = false;
    private static volatile boolean stopped = false;
    private static volatile String filter = "";
    private static volatile boolean restart = false;
    private static Thread thread;
    private static volatile Process proc;

    // ---------------- API ----------------

    public static void init() {
        stopped = false;
        thread = new Thread(SystemMedia::loop, "donutstreamer-media");
        thread.setDaemon(true);
        thread.start();
    }

    public static void shutdown() {
        stopped = true;
        killProc();
        if (thread != null) thread.interrupt();
    }

    public static void setWanted(boolean value) {
        wanted = value;
    }

    public static void setFilter(String app) {
        String f = sanitize(app);
        if (!f.equals(filter)) {
            filter = f;
            restart = true;
            now = null;
        }
    }

    public static Spotify.Now now() {
        return now;
    }

    public static String statusText() {
        return status;
    }

    public static String describeEnvironment() {
        if (WINDOWS) return "Windows media controls";
        if (MAC) return "macOS (Spotify / Music)";
        return "Linux playerctl";
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9._ -]", "").trim();
    }

    // ---------------- reader thread ----------------

    private static void loop() {
        while (!stopped) {
            try {
                if (!wanted) {
                    killProc();
                    Thread.sleep(1000);
                    continue;
                }
                restart = false;
                if (WINDOWS) runWindows();
                else runPolling();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                LOG.debug("System media reader failed", e);
                status = "Media reader error";
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void killProc() {
        Process p = proc;
        if (p != null) {
            p.destroy();
            proc = null;
        }
    }

    // ---- Windows: persistent PowerShell reading the system media sessions ----

    private static String windowsScript() {
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
                "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
                "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]",
                "$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
                "function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }",
                "$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]",
                "$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]",
                "$mgr = Await ($mgrType::RequestAsync()) $mgrType",
                "$filter = '" + filter + "'",
                "while ($true) {",
                "  try {",
                "    $sess = $null",
                "    if ($filter -ne '') { foreach ($x in $mgr.GetSessions()) { if ($x.SourceAppUserModelId -like ('*' + $filter + '*')) { $sess = $x; break } } }",
                "    else { $sess = $mgr.GetCurrentSession() }",
                "    if ($sess -eq $null) { Write-Output 'NONE' }",
                "    else {",
                "      $p = Await ($sess.TryGetMediaPropertiesAsync()) $propsType",
                "      $info = $sess.GetPlaybackInfo()",
                "      $tl = $sess.GetTimelineProperties()",
                "      $end = [int64]($tl.EndTime.TotalMilliseconds - $tl.StartTime.TotalMilliseconds)",
                "      $pos = [int64]$tl.Position.TotalMilliseconds",
                "      $st = $info.PlaybackStatus.ToString()",
                "      if ($st -eq 'Playing') { $pos += [int64]([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds }",
                "      if ($end -gt 0 -and $pos -gt $end) { $pos = $end }",
                "      if ($pos -lt 0) { $pos = 0 }",
                "      $title = ($p.Title -replace \"[\\t\\r\\n]\", ' ')",
                "      $artist = ($p.Artist -replace \"[\\t\\r\\n]\", ' ')",
                "      Write-Output (\"T`t$title`t$artist`t$st`t$pos`t$end`t\" + $sess.SourceAppUserModelId)",
                "    }",
                "  } catch { Write-Output (\"ERR`t\" + $_.Exception.Message) }",
                "  [Console]::Out.Flush()",
                "  Start-Sleep -Milliseconds 1000",
                "}");
    }

    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void runWindows() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encode(windowsScript()));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        proc = p;
        status = "Looking for a player...";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!stopped && wanted && !restart && (line = r.readLine()) != null) {
                handleLine(line);
            }
        } finally {
            p.destroy();
            proc = null;
        }
    }

    // ---- Linux / macOS: poll a small command every 1.5 seconds ----

    private static void runPolling() throws InterruptedException {
        while (!stopped && wanted && !restart) {
            String out = MAC ? runCommand(macCommand()) : runCommand(new String[]{"playerctl", "metadata", "--format",
                    "{{title}}" + TAB + "{{artist}}" + TAB + "{{status}}" + TAB + "{{position}}" + TAB + "{{mpris:length}}"});
            if (out == null) {
                if (!MAC) status = "Install playerctl to read your player";
                now = null;
            } else {
                parsePolled(out.trim());
            }
            Thread.sleep(1500);
        }
    }

    private static String[] macCommand() {
        return new String[]{"osascript", "-e",
                "set o to \"\"\n"
                        + "if application \"Spotify\" is running then\n"
                        + "tell application \"Spotify\"\n"
                        + "if player state is not stopped then set o to (name of current track) & tab & (artist of current track) & tab & (player state as text) & tab & ((player position * 1000) as integer) & tab & (duration of current track)\n"
                        + "end tell\n"
                        + "end if\n"
                        + "if o is \"\" and application \"Music\" is running then\n"
                        + "tell application \"Music\"\n"
                        + "if player state is not stopped then set o to (name of current track) & tab & (artist of current track) & tab & (player state as text) & tab & ((player position * 1000) as integer) & tab & (((duration of current track) * 1000) as integer)\n"
                        + "end tell\n"
                        + "end if\n"
                        + "return o"};
    }

    private static String runCommand(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? (out == null ? "" : out) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void parsePolled(String out) {
        if (out.isEmpty()) {
            now = null;
            status = "Nothing is playing";
            return;
        }
        String[] f = out.split("\t", -1);
        if (f.length < 5) return;
        boolean playing = f[2].toLowerCase(Locale.ROOT).startsWith("play");
        long pos = parseLong(f[3]);
        long len = parseLong(f[4]);
        if (!MAC) {   // playerctl reports microseconds
            pos /= 1000;
            len /= 1000;
        }
        publish(f[0], f[1], playing, pos, len, MAC ? "Music" : "playerctl");
    }

    // ---- shared ----

    private static void handleLine(String line) {
        if (line.equals("NONE")) {
            now = null;
            status = filter.isEmpty() ? "Nothing is playing" : "No player matching \"" + filter + "\"";
            return;
        }
        String[] f = line.split("\t", -1);
        if (f[0].equals("ERR")) {
            status = "Media error: " + (f.length > 1 ? f[1] : "?");
            return;
        }
        if (!f[0].equals("T") || f.length < 7) return;
        boolean playing = f[3].equals("Playing");
        if (!playing && !f[3].equals("Paused")) {   // stopped / closed / changing
            now = null;
            status = "Nothing is playing";
            return;
        }
        publish(f[1], f[2], playing, parseLong(f[4]), parseLong(f[5]), friendlyApp(f[6]));
    }

    private static void publish(String title, String artist, boolean playing, long posMs, long lenMs, String app) {
        String t = title == null || title.isBlank() ? "Unknown" : title.trim();
        String a = artist == null ? "" : artist.trim();
        String id = app + "|" + t + "|" + a;
        now = new Spotify.Now(id, t, a.isEmpty() ? app : a, Math.max(0, lenMs), Math.max(0, posMs),
                playing, app, -1, System.nanoTime());
        status = playing ? "Playing" : "Paused";
    }

    private static String friendlyApp(String id) {
        String s = id == null ? "" : id;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("spotify")) return "Spotify";
        if (low.contains("chrome")) return "Chrome";
        if (low.contains("msedge") || low.contains("edge")) return "Edge";
        if (low.contains("firefox")) return "Firefox";
        if (low.contains("applemusic") || low.contains("itunes")) return "Apple Music";
        if (low.contains("zune") || low.contains("media player")) return "Media Player";
        int bang = s.indexOf('!');
        if (bang > 0) s = s.substring(0, bang);
        if (s.toLowerCase(Locale.ROOT).endsWith(".exe")) s = s.substring(0, s.length() - 4);
        return s.isEmpty() ? "Player" : s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------- controls (media keys) ----------------

    public static void playPause() { sendKey(0xB3, "play-pause", "playpause"); }

    public static void next() { sendKey(0xB0, "next", "next track"); }

    public static void previous() { sendKey(0xB1, "previous", "previous track"); }

    private static void sendKey(int vk, String playerctlVerb, String macVerb) {
        Thread t = new Thread(() -> {
            try {
                if (WINDOWS) {
                    String script = "Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class K { "
                            + "[DllImport(\"user32.dll\")] public static extern void keybd_event(byte v, byte s, uint f, int e); }'\n"
                            + "[K]::keybd_event(" + vk + ",0,0,0); [K]::keybd_event(" + vk + ",0,2,0)";
                    new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                            "-EncodedCommand", encode(script)).redirectErrorStream(true).start().waitFor(8, TimeUnit.SECONDS);
                } else if (MAC) {
                    new ProcessBuilder("osascript", "-e", "tell application \"Spotify\" to " + macVerb)
                            .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                } else {
                    new ProcessBuilder("playerctl", playerctlVerb).redirectErrorStream(true).start()
                            .waitFor(5, TimeUnit.SECONDS);
                }
            } catch (IOException | InterruptedException e) {
                MinecraftClient.getInstance().execute(() -> Actions.bar(Formatting.RED, "Could not send the media key"));
            }
        }, "donutstreamer-mediakey");
        t.setDaemon(true);
        t.start();
    }
}
