package de.texturemixer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturemixer.TextureMixerClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Erkennt, welche Programme gerade Audio abspielen (Spotify, Browser, Medienwiedergabe, VLC, ...).
 * Unter Windows melden sich diese Programme als "Medien-Player" an (die Anzeige mit Lautstärketasten-Overlay).
 * Diese Liste wird über einen einzigen, dauerhaft laufenden PowerShell-Prozess gelesen (einmal pro Sekunde),
 * es ist nichts zu installieren. Auf anderen Systemen bleibt die Liste leer.
 */
public final class MediaSessions {
    private MediaSessions() {
    }

    /** Eine erkannte Quelle. posMs gilt zum Zeitpunkt recvNano (System.nanoTime). */
    public record Session(String id, String app, String title, String artist, boolean playing,
                          long posMs, long durMs, long recvNano) {
        public boolean browser() {
            String l = id.toLowerCase(Locale.ROOT);
            return l.contains("chrome") || l.contains("msedge") || l.contains("edge") || l.contains("firefox")
                    || l.contains("opera") || l.contains("brave") || l.contains("vivaldi")
                    || l.contains("308046b0af4a39cb");
        }
    }

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }",
            "$tMgr = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]",
            "$tProps = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]",
            "$mgr = Await ($tMgr::RequestAsync()) $tMgr",
            "while ($true) {",
            "  $out = @()",
            "  try {",
            "    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()",
            "    foreach ($s in $mgr.GetSessions()) {",
            "      $p = Await ($s.TryGetMediaPropertiesAsync()) $tProps",
            "      $pb = $s.GetPlaybackInfo()",
            "      $tl = $s.GetTimelineProperties()",
            "      $st = [int]$pb.PlaybackStatus",
            "      $pos = [int64]$tl.Position.TotalMilliseconds",
            "      $dur = [int64]$tl.EndTime.TotalMilliseconds",
            "      $age = $now - $tl.LastUpdatedTime.ToUnixTimeMilliseconds()",
            "      if ($age -lt 0 -or $age -gt 21600000) { $age = 0 }",
            "      if ($st -eq 4) { $pos = $pos + $age }",
            "      if ($dur -gt 0 -and $pos -gt $dur) { $pos = $dur }",
            "      $out += [pscustomobject]@{ id = $s.SourceAppUserModelId; title = $p.Title; artist = $p.Artist; status = $st; pos = $pos; dur = $dur }",
            "    }",
            "  } catch { }",
            "  [Console]::Out.WriteLine((ConvertTo-Json -Compress -InputObject @($out)))",
            "  [Console]::Out.Flush()",
            "  Start-Sleep -Milliseconds 1000",
            "}");

    private static volatile List<Session> sessions = List.of();
    private static volatile String error = "";
    private static volatile Process proc = null;
    private static boolean started = false;

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Aktuell erkannte Quellen (höchstens eine Sekunde alt). */
    public static List<Session> list() {
        return sessions;
    }

    /** Fehlertext, wenn die Erkennung nicht läuft, sonst leer. */
    public static String error() {
        return error;
    }

    public static Session find(String id) {
        for (Session s : sessions) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    /** Startet die Erkennung (nur einmal). Läuft im Hintergrund, blockiert nichts. */
    public static synchronized void ensureStarted() {
        if (started || !supported()) return;
        started = true;
        Thread t = new Thread(MediaSessions::runLoop, "texturemixer-media-sessions");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = proc;
            if (p != null) p.destroyForcibly();
        }));
    }

    private static void runLoop() {
        int failures = 0;
        while (failures < 4) {
            long begin = System.currentTimeMillis();
            try {
                String enc = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                proc = p;
                error = "";
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        handle(line);
                    }
                }
                p.waitFor();
            } catch (Throwable t) {
                TextureMixerClient.LOGGER.warn("Audio-Erkennung gestoppt", t);
            }
            sessions = List.of();
            // lief es länger als eine halbe Minute, war es kein Startproblem
            failures = System.currentTimeMillis() - begin > 30_000 ? 1 : failures + 1;
            error = "Audio-Erkennung nicht verfügbar (PowerShell)";
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void handle(String line) {
        if (line.startsWith("\uFEFF")) line = line.substring(1);
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) != '[') return;
        try {
            JsonArray arr = JsonParser.parseString(line).getAsJsonArray();
            long now = System.nanoTime();
            List<Session> out = new ArrayList<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = str(o, "id");
                if (id.isEmpty()) continue;
                int status = (int) lng(o, "status");
                out.add(new Session(id, appName(id), str(o, "title"), str(o, "artist"),
                        status == 4, lng(o, "pos"), lng(o, "dur"), now));
            }
            sessions = List.copyOf(out);
        } catch (Exception ignored) {
            // kaputte Zeile überspringen
        }
    }

    /** Lesbarer Programmname aus der Windows-App-ID. */
    public static String appName(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        if (l.contains("spotify")) return "Spotify";
        if (l.contains("msedge") || l.contains("edge")) return "Edge";
        if (l.contains("chrome")) return "Chrome";
        if (l.contains("firefox") || l.contains("308046b0af4a39cb")) return "Firefox";
        if (l.contains("opera")) return "Opera";
        if (l.contains("brave")) return "Brave";
        if (l.contains("vivaldi")) return "Vivaldi";
        if (l.contains("zunemusic") || l.contains("mediaplayer")) return "Medienwiedergabe";
        if (l.contains("applemusic") || l.contains("itunes")) return "Apple Music";
        if (l.contains("vlc")) return "VLC";
        if (l.contains("tidal")) return "Tidal";
        if (l.contains("deezer")) return "Deezer";
        if (l.contains("amazonmusic")) return "Amazon Music";
        if (l.contains("discord")) return "Discord";
        String s = id;
        int bang = s.indexOf('!');
        if (bang > 0) s = s.substring(0, bang);
        int us = s.indexOf('_');
        if (us > 0) s = s.substring(0, us);
        if (s.toLowerCase(Locale.ROOT).endsWith(".exe")) s = s.substring(0, s.length() - 4);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        return s.isEmpty() ? id : s;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }
}
