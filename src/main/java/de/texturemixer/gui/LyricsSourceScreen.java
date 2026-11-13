package de.texturemixer.gui;

import de.texturemixer.MixerConfig;
import de.texturemixer.lyrics.LyricsHud;
import de.texturemixer.lyrics.LyricsSettings;
import de.texturemixer.lyrics.MediaSessions;
import de.texturemixer.lyrics.SpotifyClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auswahl der Audio-Quelle für die Lyrics: zeigt alle Programme, die gerade Audio abspielen (Spotify, Browser, ...),
 * und lässt eine davon wählen. Die Liste aktualisiert sich live.
 */
public class LyricsSourceScreen extends Screen {
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 4;

    /** Eine Zeile der Liste. value = Wert für LyricsSettings.source. */
    private record Row(String value, String name, String sub, String tag, int tagKind, boolean enabled) {
    }

    private final Screen parent;
    private final Map<String, Float> anim = new HashMap<>();
    private final long openStart = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float dt = 0.016f;

    private int pxL, pxR, pyT, pyB, listY, maxRows;

    public LyricsSourceScreen(Screen parent) {
        super(Text.literal("Audio-Quelle"));
        this.parent = parent;
    }

    private static LyricsSettings st() {
        return MixerConfig.get().lyrics;
    }

    private ButtonWidget hit(int x, int y, int w, int h, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> action.run()).dimensions(x, y, w, h).build();
        b.setAlpha(0f); // Klickfläche, gezeichnet wird alles selbst
        return this.addDrawableChild(b);
    }

    @Override
    protected void init() {
        MediaSessions.ensureStarted();
        int w = Math.min(this.width - 16, 420);
        int h = Math.min(this.height - 16, 290);
        pxL = (this.width - w) / 2;
        pxR = pxL + w;
        pyT = (this.height - h) / 2;
        pyB = pyT + h;
        listY = pyT + 58;
        maxRows = Math.max(2, (pyB - 40 - listY) / (ROW_H + ROW_GAP));

        for (int i = 0; i < maxRows; i++) {
            final int idx = i;
            hit(pxL + 10, listY + i * (ROW_H + ROW_GAP), w - 20, ROW_H, () -> choose(idx));
        }
        hit(pxR - 12 - 70, pyB - 26, 70, 18, this::close);
    }

    // ---------- Liste ----------

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        MediaSessions.Session active = null;
        for (MediaSessions.Session s : MediaSessions.list()) {
            if (s.playing()) {
                active = s;
                break;
            }
        }
        out.add(new Row(LyricsSettings.SOURCE_AUTO, "Automatisch",
                active != null ? "Gerade aktiv: " + active.app() : "Nimmt, was gerade Audio abspielt", "", 0, true));
        List<MediaSessions.Session> all = MediaSessions.list();
        int room = Math.max(0, maxRows - 2);
        for (int i = 0; i < all.size() && i < room; i++) {
            MediaSessions.Session s = all.get(i);
            String song = s.title().isBlank() ? "-" : (s.artist().isBlank() ? s.title() : s.artist() + " - " + s.title());
            out.add(new Row(s.id(), s.app(), song, s.playing() ? "SPIELT" : "PAUSIERT", s.playing() ? 1 : 2, true));
        }
        boolean api = SpotifyClient.connected();
        out.add(new Row(LyricsSettings.SOURCE_API, "Spotify (Web-Verbindung)",
                api ? "Liest über dein verbundenes Spotify-Konto" : "Nicht verbunden - im Lyrics-Menü verbinden",
                api ? "" : "AUS", api ? 0 : 3, api));
        return out;
    }

    private void choose(int idx) {
        List<Row> rows = rows();
        if (idx < 0 || idx >= rows.size()) return;
        Row r = rows.get(idx);
        if (!r.enabled()) return;
        st().source = r.value();
        MixerConfig.get().save();
        LyricsHud.reset();
    }

    // ---------- Hilfen ----------

    private float anim(String key, boolean on) {
        float t = on ? 1f : 0f;
        float v = anim.getOrDefault(key, 0f);
        v += (t - v) * Math.min(1f, dt * 16f);
        if (Math.abs(t - v) < 0.01f) v = t;
        anim.put(key, v);
        return v;
    }

    private String fit(String s, int maxW) {
        if (tw(s) <= maxW) return s;
        String e = "...";
        while (s.length() > 1 && tw(s + e) > maxW) s = s.substring(0, s.length() - 1);
        return s + e;
    }

    private int tw(String s) {
        return this.textRenderer.getWidth(MonoFont.text(s));
    }

    private void txt(DrawContext ctx, String s, int x, int y, int color) {
        if ((color >>> 24) < 8) return;
        ctx.drawText(this.textRenderer, MonoFont.text(s), x, y, color, false);
    }

    // ---------- Zeichnen ----------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;

        Theme.update();
        super.render(ctx, mx, my, delta); // Hintergrund; die Klickflächen sind unsichtbar
        float gA = LyricsScreen.easeOut((System.currentTimeMillis() - openStart) / 200f);
        ctx.fill(0, 0, this.width, this.height, LyricsScreen.a(Theme.scrim(), gA));

        // Fenster mit Kopfzeile
        ctx.fill(pxL, pyT, pxR, pyB, LyricsScreen.a(Theme.bg(), gA));
        ctx.fill(pxL, pyT, pxR, pyT + 28, LyricsScreen.a(Theme.header(), gA));
        ctx.fill(pxL, pyT + 28, pxR, pyT + 29, LyricsScreen.a(Theme.border(), gA));
        ctx.fill(pxL, pyT + 28, pxL + Math.round((pxR - pxL) * gA), pyT + 30, LyricsScreen.a(Theme.accent(), gA));
        LyricsScreen.panel(ctx, pxL, pyT, pxR - pxL, pyB - pyT, 0x00000000, LyricsScreen.a(Theme.border(), gA));
        ctx.fill(pxL + 6, pyT + 8, pxL + 9, pyT + 20, LyricsScreen.a(Theme.accent(), gA));
        txt(ctx, "AUDIO-QUELLE", pxL + 14, pyT + 10, LyricsScreen.a(Theme.text(), gA));

        int innerW = pxR - pxL - 20;
        txt(ctx, fit("Wähle, von welchem Programm die Lyrics kommen.", innerW), pxL + 10, pyT + 38,
                LyricsScreen.a(Theme.muted(), gA));

        List<Row> rows = rows();
        for (int i = 0; i < rows.size() && i < maxRows; i++) {
            drawRow(ctx, rows.get(i), i, mx, my, gA);
        }

        // Hinweise unter der Liste
        int hintY = listY + Math.min(rows.size(), maxRows) * (ROW_H + ROW_GAP) + 2;
        String hint = null;
        int hintColor = Theme.muted();
        if (!MediaSessions.supported()) {
            hint = "Die automatische Erkennung gibt es nur unter Windows.";
        } else if (!MediaSessions.error().isEmpty()) {
            hint = MediaSessions.error();
            hintColor = Theme.danger();
        } else if (MediaSessions.list().isEmpty()) {
            hint = "Gerade meldet kein Programm Audio. Starte Musik in Spotify oder im Browser.";
        }
        if (hint != null && hintY + 10 < pyB - 30) {
            txt(ctx, fit(hint, innerW), pxL + 10, hintY, LyricsScreen.a(hintColor, gA));
        }
        String ss = SpotifyClient.sourceStatus();
        if (!ss.isEmpty() && hintY + 22 < pyB - 30) {
            txt(ctx, fit(ss, innerW), pxL + 10, hintY + 12, LyricsScreen.a(Theme.danger(), gA));
        }

        // Fertig-Knopf
        int bx = pxR - 12 - 70;
        int by = pyB - 26;
        boolean hov = LyricsScreen.inside(mx, my, bx, by, 70, 18);
        float hv = anim("done", hov);
        LyricsScreen.panel(ctx, bx, by, 70, 18,
                LyricsScreen.a(LyricsScreen.mix(Theme.button(), Theme.cardHover(), hv), gA),
                LyricsScreen.a(LyricsScreen.mix(Theme.border(), Theme.hoverBorder(), hv), gA));
        String done = "Fertig";
        txt(ctx, done, bx + 35 - tw(done) / 2, by + 5, LyricsScreen.a(Theme.text(), gA));
    }

    private void drawRow(DrawContext ctx, Row r, int i, int mx, int my, float gA) {
        int x = pxL + 10;
        int y = listY + i * (ROW_H + ROW_GAP);
        int w = pxR - pxL - 20;
        boolean sel = st().source.equals(r.value());
        boolean hov = r.enabled() && LyricsScreen.inside(mx, my, x, y, w, ROW_H);
        float h = anim("row:" + i, hov);
        float s = anim("rows:" + i + ":" + r.value(), sel);

        int bg = !r.enabled() ? Theme.disabledFill()
                : LyricsScreen.mix(LyricsScreen.mix(Theme.button(), Theme.cardHover(), h), Theme.accent(), s);
        int bd = !r.enabled() ? Theme.border()
                : LyricsScreen.mix(LyricsScreen.mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s);
        LyricsScreen.panel(ctx, x, y, w, ROW_H, LyricsScreen.a(bg, gA), LyricsScreen.a(bd, gA));

        int nameC = !r.enabled() ? Theme.disabledText() : LyricsScreen.mix(Theme.text(), Theme.onAccent(), s);
        int subC = !r.enabled() ? Theme.disabledText() : LyricsScreen.mix(Theme.muted(), Theme.onAccent(), s);

        // Auswahl-Punkt links
        int dx = x + 9;
        int dy = y + ROW_H / 2;
        ctx.fill(dx - 4, dy - 4, dx + 4, dy + 4, LyricsScreen.a(LyricsScreen.mix(Theme.border(), Theme.onAccent(), s), gA));
        ctx.fill(dx - 3, dy - 3, dx + 3, dy + 3, LyricsScreen.a(LyricsScreen.mix(Theme.field(), Theme.accent(), s), gA));

        int tagW = r.tag().isEmpty() ? 0 : tw(r.tag()) + 8;
        int textX = x + 20;
        int textW = w - 20 - tagW - 8;
        txt(ctx, fit(r.name(), textW), textX, y + 5, LyricsScreen.a(nameC, gA));
        txt(ctx, fit(r.sub(), textW), textX, y + 16, LyricsScreen.a(subC, gA));

        if (!r.tag().isEmpty()) {
            int tc = r.tagKind() == 1 ? good() : r.tagKind() == 3 ? Theme.disabledText() : Theme.muted();
            if (sel && r.tagKind() == 2) tc = LyricsScreen.mix(tc, Theme.onAccent(), s);
            txt(ctx, r.tag(), x + w - 8 - tw(r.tag()), y + (ROW_H - 8) / 2, LyricsScreen.a(tc, gA));
        }
    }

    private static int good() {
        return Theme.mix(0xFF2E9E3F, 0xFF5BD174, Theme.blend());
    }

    @Override
    public void close() {
        MixerConfig.get().save();
        if (this.client != null) this.client.setScreen(parent);
    }
}
