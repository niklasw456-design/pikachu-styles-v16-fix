package de.texturemixer.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturemixer.MixerConfig;
import de.texturemixer.TextureMixerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verbindung zu Spotify (Anmeldung mit PKCE, kein Client-Secret nötig) und Abruf des laufenden Songs.
 * Die Lyrics (mit Zeitstempeln) kommen von LRCLIB (lrclib.net), weil Spotify keine Lyrics über die API liefert.
 * Alles Netzwerk läuft in Hintergrund-Threads.
 */
public final class SpotifyClient {
    private SpotifyClient() {
    }

    /** Diese Adresse muss in der Spotify-App als Redirect-URI eingetragen sein. */
    public static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
    private static final int PORT = 8888;
    private static final String SCOPE = "user-read-currently-playing user-read-playback-state";

    public record Line(long ms, String text) {
    }

    private record Resp(int code, String body) {
    }

    private static volatile String status = "Nicht verbunden";
    private static volatile String accessToken = null;
    private static volatile long tokenExpiresMs = 0;
    private static volatile long pauseUntilMs = 0;

    private static volatile boolean playing = false;
    private static volatile String trackKey = "";
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile long progressMs = 0;
    private static volatile long pollNano = 0;
    private static volatile List<Line> lyrics = List.of();
    /** 0 = nichts, 1 = lädt, 2 = gefunden, 3 = nicht gefunden. */
    private static volatile int lyricsState = 0;

    /** Quelle, aus der gerade gelesen wird (bei "Automatisch" die gefundene). */
    private static volatile String activeId = "";
    private static volatile String sourceStatus = "";
    private static volatile long lastApiMs = 0;

    private static ServerSocket authSocket = null;
    private static boolean started = false;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "texturemixer-spotify");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "texturemixer-lyrics-io");
        t.setDaemon(true);
        return t;
    });

    private static LyricsSettings settings() {
        return MixerConfig.get().lyrics;
    }

    // ---------- Zugriff für Anzeige und Menü ----------

    public static String status() {
        return status;
    }

    public static boolean connected() {
        return !settings().refreshToken.isBlank();
    }

    public static boolean playing() {
        return playing;
    }

    public static String trackKey() {
        return trackKey;
    }

    public static String title() {
        return title;
    }

    public static String artist() {
        return artist;
    }

    /** Hinweistext zur Quelle (z. B. "Quelle nicht gefunden"), leer wenn alles in Ordnung ist. */
    public static String sourceStatus() {
        return sourceStatus;
    }

    /** Kurzer Name der Quelle für Knöpfe, z. B. "Automatisch (Chrome)". */
    public static String sourceLabel() {
        String src = settings().source;
        if (LyricsSettings.SOURCE_API.equals(src)) return "Spotify (Web)";
        if (src.isEmpty()) {
            MediaSessions.Session a = activeId.isEmpty() ? null : MediaSessions.find(activeId);
            return a == null ? "Automatisch" : "Automatisch (" + a.app() + ")";
        }
        return MediaSessions.appName(src);
    }

    public static int lyricsState() {
        return lyricsState;
    }

    public static String lineText(int idx) {
        List<Line> l = lyrics;
        return idx >= 0 && idx < l.size() ? l.get(idx).text() : "";
    }

    /** Index der aktuellen Zeile, -1 wenn nichts läuft oder die erste Zeile noch nicht dran ist. */
    public static int currentIndex() {
        List<Line> l = lyrics;
        if (!playing || l.isEmpty()) return -1;
        long pos = progressMs + (System.nanoTime() - pollNano) / 1_000_000L + 250;
        int lo = 0;
        int hi = l.size() - 1;
        int res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).ms() <= pos) {
                res = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return res;
    }

    // ---------- Start / Abfrage-Schleife ----------

    public static synchronized void start() {
        if (started) return;
        started = true;
        if (connected()) status = "Verbunden";
        TIMER.scheduleWithFixedDelay(() -> {
            try {
                poll();
            } catch (Throwable t) {
                TextureMixerClient.LOGGER.warn("Spotify-Abfrage fehlgeschlagen", t);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static void poll() throws IOException {
        LyricsSettings s = settings();
        if (!s.enabled) {
            playing = false;
            return;
        }
        String src = s.source;
        if (!LyricsSettings.SOURCE_API.equals(src)) {
            MediaSessions.ensureStarted();
            MediaSessions.Session ss = pickSession(src);
            if (ss != null) {
                applySession(ss);
                return;
            }
            boolean specific = !src.isEmpty();
            boolean apiPossible = !specific && connected() && !s.clientId.isBlank();
            if (!apiPossible) {
                playing = false;
                activeId = specific ? src : "";
                if (specific) sourceStatus = MediaSessions.appName(src) + " wurde nicht gefunden (Programm geschlossen?)";
                else if (!MediaSessions.supported()) sourceStatus = "Automatische Erkennung gibt es nur unter Windows - verbinde Spotify";
                else if (!MediaSessions.error().isEmpty()) sourceStatus = MediaSessions.error();
                else sourceStatus = "Es spielt gerade nichts";
                return;
            }
            // Automatisch, nichts gefunden: Spotify über die Web-Verbindung
        }
        if (s.refreshToken.isBlank() || s.clientId.isBlank()) {
            playing = false;
            sourceStatus = "Spotify ist nicht verbunden";
            return;
        }
        if (System.currentTimeMillis() < pauseUntilMs) return;
        if (System.currentTimeMillis() - lastApiMs < 2000) return;
        lastApiMs = System.currentTimeMillis();
        activeId = "";
        sourceStatus = "";
        if (!ensureToken()) return;

        Resp r = http("GET", "https://api.spotify.com/v1/me/player/currently-playing", null, accessToken);
        if (r.code() == 204) {
            playing = false;
            status = "Verbunden - es läuft gerade nichts";
            return;
        }
        if (r.code() == 401) {
            accessToken = null;
            return;
        }
        if (r.code() == 429) {
            pauseUntilMs = System.currentTimeMillis() + 15_000;
            return;
        }
        if (r.code() != 200) {
            status = "Spotify-Fehler " + r.code();
            return;
        }
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        JsonElement itemEl = o.get("item");
        if (itemEl == null || !itemEl.isJsonObject()) {
            playing = false;
            status = "Verbunden - kein Song (Podcast oder Werbung)";
            return;
        }
        JsonObject it = itemEl.getAsJsonObject();
        String id = str(it, "id");
        String name = str(it, "name");
        String art = "";
        JsonElement arts = it.get("artists");
        if (arts != null && arts.isJsonArray()) {
            JsonArray arr = arts.getAsJsonArray();
            if (arr.size() > 0 && arr.get(0).isJsonObject()) art = str(arr.get(0).getAsJsonObject(), "name");
        }
        long duration = lng(it, "duration_ms");
        long prog = lng(o, "progress_ms");
        boolean isPlaying = o.has("is_playing") && !o.get("is_playing").isJsonNull() && o.get("is_playing").getAsBoolean();

        String key = id.isEmpty() ? name + "|" + art : id;
        progressMs = prog;
        pollNano = System.nanoTime();
        playing = isPlaying;
        status = "Verbunden";
        if (!key.equals(trackKey)) {
            trackKey = key;
            title = name;
            artist = art;
            lyrics = List.of();
            lyricsState = 1;
            fetchLyricsAsync(key, name, art, duration);
        }
    }

    /** Wählt die Quelle: eine bestimmte ID, oder bei "Automatisch" das, was gerade spielt. */
    private static MediaSessions.Session pickSession(String src) {
        List<MediaSessions.Session> all = MediaSessions.list();
        if (!src.isEmpty()) return MediaSessions.find(src);
        for (MediaSessions.Session x : all) {
            if (x.playing() && !x.title().isBlank()) {
                if (x.id().equals(activeId)) return x; // beim aktuellen bleiben, wenn es noch spielt
            }
        }
        for (MediaSessions.Session x : all) {
            if (x.playing() && !x.title().isBlank()) return x;
        }
        MediaSessions.Session last = activeId.isEmpty() ? null : MediaSessions.find(activeId);
        return last != null && !last.title().isBlank() ? last : null;
    }

    private static void applySession(MediaSessions.Session ss) {
        activeId = ss.id();
        sourceStatus = "";
        progressMs = ss.posMs();
        pollNano = ss.recvNano();
        playing = ss.playing();
        String key = ss.id() + "|" + ss.title() + "|" + ss.artist();
        if (key.equals(trackKey)) return;
        String[] ta = cleanTrack(ss);
        trackKey = key;
        title = ta[0];
        artist = ta[1];
        lyrics = List.of();
        if (ta[0].isBlank()) {
            lyricsState = 0;
            return;
        }
        lyricsState = 1;
        fetchLyricsAsync(key, ta[0], ta[1], ss.durMs());
    }

    private static final Pattern NOISE = Pattern.compile(
            "\\s*[(\\[][^)\\]]*(official|video|lyric|audio|visuali[sz]er|remaster|musikvideo|clip|\\bhd\\b|\\bhq\\b|4k)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    /** Räumt Titel und Künstler auf (besonders bei Browser-Videos wie "Künstler - Song (Official Video)"). */
    private static String[] cleanTrack(MediaSessions.Session ss) {
        String t = ss.title().trim();
        String a = ss.artist().trim();
        if (a.toLowerCase().endsWith(" - topic")) a = a.substring(0, a.length() - 8).trim();
        if (ss.browser() || a.isEmpty()) {
            t = NOISE.matcher(t).replaceAll("").trim();
            int dash = t.indexOf(" - ");
            if (dash > 0) {
                a = t.substring(0, dash).trim();
                t = t.substring(dash + 3).trim();
            }
        }
        return new String[]{t, a};
    }

    private static boolean ensureToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresMs - 60_000) return true;
        LyricsSettings s = settings();
        String body = "grant_type=refresh_token&refresh_token=" + enc(s.refreshToken) + "&client_id=" + enc(s.clientId);
        Resp r = http("POST", "https://accounts.spotify.com/api/token", body, null);
        if (r.code() == 400 || r.code() == 401) {
            s.refreshToken = "";
            accessToken = null;
            playing = false;
            status = "Verbindung abgelaufen - bitte neu verbinden";
            MixerConfig.get().save();
            return false;
        }
        if (r.code() != 200) {
            status = "Spotify-Anmeldung fehlgeschlagen (" + r.code() + ")";
            return false;
        }
        applyTokens(JsonParser.parseString(r.body()).getAsJsonObject());
        return true;
    }

    private static void applyTokens(JsonObject o) {
        accessToken = str(o, "access_token");
        long expires = o.has("expires_in") && !o.get("expires_in").isJsonNull() ? o.get("expires_in").getAsLong() : 3600;
        tokenExpiresMs = System.currentTimeMillis() + expires * 1000L;
        String refresh = str(o, "refresh_token");
        if (!refresh.isEmpty() && !refresh.equals(settings().refreshToken)) {
            settings().refreshToken = refresh;
            MixerConfig.get().save();
        }
    }

    // ---------- Anmeldung (PKCE, lokaler Empfänger auf 127.0.0.1:8888) ----------

    public static synchronized void connect() {
        LyricsSettings s = settings();
        if (s.clientId.isBlank()) {
            status = "Bitte zuerst die Client-ID eintragen";
            return;
        }
        try {
            if (authSocket != null) authSocket.close();
        } catch (IOException ignored) {
        }
        ServerSocket server;
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            status = "Port " + PORT + " ist belegt - bitte andere Programme schließen";
            return;
        }
        authSocket = server;

        SecureRandom rnd = new SecureRandom();
        String verifier = randomUrlString(rnd, 64);
        String state = randomUrlString(rnd, 16);
        String challenge;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            status = "Anmeldung nicht möglich";
            return;
        }
        String clientId = s.clientId;
        String url = "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPE)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&state=" + enc(state)
                + "&code_challenge_method=S256&code_challenge=" + enc(challenge);
        status = "Warte auf den Browser... (Link ist auch in der Zwischenablage)";
        IO.execute(() -> waitForCallback(server, state, verifier, clientId));

        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            mc.keyboard.setClipboard(url);
        } catch (Throwable ignored) {
        }
        try {
            Util.getOperatingSystem().open(URI.create(url));
        } catch (Throwable t) {
            TextureMixerClient.LOGGER.warn("Browser konnte nicht geöffnet werden", t);
        }
    }

    public static synchronized void disconnect() {
        try {
            if (authSocket != null) authSocket.close();
        } catch (IOException ignored) {
        }
        authSocket = null;
        settings().refreshToken = "";
        accessToken = null;
        playing = false;
        trackKey = "";
        lyrics = List.of();
        lyricsState = 0;
        status = "Nicht verbunden";
        MixerConfig.get().save();
    }

    private static void waitForCallback(ServerSocket server, String state, String verifier, String clientId) {
        try (ServerSocket ss = server) {
            ss.setSoTimeout(180_000);
            while (true) {
                Socket sock;
                try {
                    sock = ss.accept();
                } catch (SocketTimeoutException e) {
                    status = "Zeitüberschreitung - bitte erneut verbinden";
                    return;
                }
                try (Socket c = sock) {
                    if (handleCallback(c, state, verifier, clientId)) return;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            // Socket wurde beim erneuten Verbinden oder Trennen geschlossen
        }
    }

    /** @return true, wenn die Anmeldung abgeschlossen ist (Erfolg oder Fehler) */
    private static boolean handleCallback(Socket c, String state, String verifier, String clientId) throws IOException {
        c.setSoTimeout(5000);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line = br.readLine();
        if (line == null) return false;
        String[] parts = line.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("/callback")) {
            respond(c, 404, "Nicht gefunden");
            return false;
        }
        Map<String, String> q = query(parts[1]);
        if (q.containsKey("error")) {
            respond(c, 200, "Anmeldung abgebrochen. Du kannst dieses Fenster schließen.");
            status = "Anmeldung abgebrochen";
            return true;
        }
        String code = q.get("code");
        if (code == null || !state.equals(q.get("state"))) {
            respond(c, 400, "Ungültige Anfrage");
            return false;
        }
        respond(c, 200, "Verbunden! Du kannst dieses Fenster schließen und zu Minecraft zurückgehen.");
        String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier);
        Resp r = http("POST", "https://accounts.spotify.com/api/token", body, null);
        if (r.code() != 200) {
            status = "Anmeldung fehlgeschlagen (" + r.code() + ") - stimmt die Client-ID?";
            return true;
        }
        applyTokens(JsonParser.parseString(r.body()).getAsJsonObject());
        settings().enabled = true;
        MixerConfig.get().save();
        status = "Verbunden";
        return true;
    }

    private static void respond(Socket c, int code, String text) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><title>Pikachu Styles</title>"
                + "<body style=\"font-family:sans-serif;background:#101319;color:#e6e9ef;text-align:center;padding-top:80px\">"
                + "<h2>" + text + "</h2></body>";
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Connection: close\r\nContent-Length: " + b.length + "\r\n\r\n";
        OutputStream o = c.getOutputStream();
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }

    private static Map<String, String> query(String path) {
        Map<String, String> m = new HashMap<>();
        int i = path.indexOf('?');
        if (i < 0) return m;
        for (String kv : path.substring(i + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, e), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    // ---------- Lyrics (LRCLIB) ----------

    private static void fetchLyricsAsync(String key, String name, String art, long durationMs) {
        IO.execute(() -> {
            List<Line> result = List.of();
            try {
                result = fetchLyrics(name, art, durationMs);
            } catch (Throwable t) {
                TextureMixerClient.LOGGER.warn("Lyrics konnten nicht geladen werden", t);
            }
            if (!key.equals(trackKey)) return; // inzwischen anderer Song
            lyrics = result;
            lyricsState = result.isEmpty() ? 3 : 2;
        });
    }

    private static List<Line> fetchLyrics(String name, String art, long durationMs) throws IOException {
        String q = "track_name=" + enc(name) + "&artist_name=" + enc(art);
        String synced = null;
        Resp r = http("GET", "https://lrclib.net/api/get?" + q + "&duration=" + (durationMs / 1000), null, null);
        if (r.code() == 200) synced = synced(JsonParser.parseString(r.body()));
        if (synced == null) {
            r = http("GET", "https://lrclib.net/api/search?" + q, null, null);
            if (r.code() == 200) {
                JsonElement root = JsonParser.parseString(r.body());
                if (root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        synced = synced(e);
                        if (synced != null) break;
                    }
                }
            }
        }
        if (synced == null) {
            r = http("GET", "https://lrclib.net/api/search?q=" + enc((art + " " + name).trim()), null, null);
            if (r.code() == 200) {
                JsonElement root = JsonParser.parseString(r.body());
                if (root.isJsonArray()) {
                    for (JsonElement e : root.getAsJsonArray()) {
                        synced = synced(e);
                        if (synced != null) break;
                    }
                }
            }
        }
        return synced == null ? List.of() : parseLrc(synced);
    }

    private static String synced(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        String s = str(e.getAsJsonObject(), "syncedLyrics");
        return s.isBlank() ? null : s;
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                stamps.add((min * 60 + sec) * 1000 + ms);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : stamps) out.add(new Line(t, text));
        }
        out.sort((a, b) -> Long.compare(a.ms(), b.ms()));
        return List.copyOf(out);
    }

    // ---------- Hilfen ----------

    private static Resp http(String method, String url, String form, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "PikachuStyles/1.1 (Minecraft-Mod)");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (form != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) {
                o.write(form.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Resp(code, body);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomUrlString(SecureRandom rnd, int len) {
        byte[] b = new byte[len];
        rnd.nextBytes(b);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return s.substring(0, len);
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
