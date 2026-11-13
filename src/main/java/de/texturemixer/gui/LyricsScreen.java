package de.texturemixer.gui;

import de.texturemixer.MixerConfig;
import de.texturemixer.lyrics.LyricsHud;
import de.texturemixer.lyrics.LyricsSettings;
import de.texturemixer.lyrics.MediaSessions;
import de.texturemixer.lyrics.SpotifyClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Einstellungen für die Lyrics: Spotify verbinden, Position (Himmel / Luft), Größe, Stil, Farbe. */
public class LyricsScreen extends Screen {
    private static int good() {
        return Theme.mix(0xFF2E9E3F, 0xFF5BD174, Theme.blend());
    }

    private static int bad() {
        return Theme.danger();
    }

    private record Ctl(int x, int y, int w, int h, Supplier<String> label, BooleanSupplier selected,
                       BooleanSupplier enabled) {
    }

    private final Screen parent;
    private final List<Ctl> ctls = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private final long openStart = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float dt = 0.016f;

    private int pxL, pxR, pyT, pyB;
    private int colW, leftX, rightX, y0;
    private int swatchX, swatchY, swatchW, swatchGap;
    private int previewX, previewY, previewW;
    private TextFieldWidget idField;

    public LyricsScreen(Screen parent) {
        super(Text.literal("Lyrics"));
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

    private void addCtl(int x, int y, int w, int h, Supplier<String> label, BooleanSupplier selected,
                        BooleanSupplier enabled, Runnable action) {
        ctls.add(new Ctl(x, y, w, h, label, selected, enabled));
        hit(x, y, w, h, () -> {
            if (enabled.getAsBoolean()) action.run();
        });
    }

    @Override
    protected void init() {
        ctls.clear();
        int w = Math.min(this.width - 16, 520);
        int h = Math.min(this.height - 16, 290);
        pxL = (this.width - w) / 2;
        pxR = pxL + w;
        pyT = (this.height - h) / 2;
        pyB = pyT + h;
        colW = (w - 36) / 2;
        leftX = pxL + 12;
        rightX = leftX + colW + 12;
        y0 = pyT + 38;

        // ----- links: Spotify -----
        addCtl(leftX, y0 + 24, colW, 18,
                () -> SpotifyClient.connected() ? "Verbindung trennen" : "Mit Spotify verbinden",
                () -> false, () -> true, () -> {
                    if (SpotifyClient.connected()) SpotifyClient.disconnect();
                    else SpotifyClient.connect();
                });

        String oldId = idField == null ? st().clientId : idField.getText();
        idField = new TextFieldWidget(this.textRenderer, leftX + 6, y0 + 69, colW - 12, 10, Text.literal("Client-ID"));
        idField.setDrawsBackground(false);
        idField.setMaxLength(64);
        idField.setEditableColor(Theme.text());
        idField.setPlaceholder(Text.literal("Client-ID einfügen...").setStyle(Style.EMPTY.withColor(0x9A9684)));
        idField.setText(oldId);
        idField.setChangedListener(t -> st().clientId = t.trim());
        this.addSelectableChild(idField);
        hit(leftX, y0 + 64, colW, 20, () -> {
            this.setFocused(idField);
            idField.setFocused(true);
        });

        // ----- rechts: Anzeige -----
        addCtl(rightX, y0, colW, 18, () -> "Lyrics anzeigen: " + (st().enabled ? "An" : "Aus"),
                () -> st().enabled, () -> true, () -> {
                    st().enabled = !st().enabled;
                    LyricsHud.reset();
                });

        int by = y0 + 42;
        int bw = (colW - 8) / 3;
        addCtl(rightX, by, bw, 18, () -> "Himmel", () -> st().sky(), () -> true, () -> {
            st().mode = LyricsSettings.MODE_SKY;
            LyricsHud.alignSky();
        });
        addCtl(rightX + bw + 4, by, bw, 18, () -> "In der Luft", () -> !st().sky(), () -> true, () -> {
            st().mode = LyricsSettings.MODE_AIR;
            LyricsHud.reset();
        });
        addCtl(rightX + 2 * (bw + 4), by, bw, 18, () -> "Ausrichten", () -> false, () -> st().sky(),
                LyricsHud::alignSky);

        int sy = y0 + 78;
        addCtl(rightX, sy, 24, 18, () -> "-", () -> false, () -> st().size > LyricsSettings.MIN_SIZE, () -> {
            st().size--;
        });
        addCtl(rightX + colW - 24, sy, 24, 18, () -> "+", () -> false, () -> st().size < LyricsSettings.MAX_SIZE,
                () -> {
                    st().size++;
                });
        // Wert-Anzeige in der Mitte (nur Anzeige)
        ctls.add(new Ctl(rightX + 28, sy, colW - 56, 18,
                () -> String.format("%.1fx", st().size / 10.0), () -> false, () -> false));

        int ty = y0 + 114;
        int tw = (colW - 4) / 2;
        addCtl(rightX, ty, tw, 18, () -> "Fett", () -> st().bold, () -> true, () -> {
            st().bold = !st().bold;
        });
        addCtl(rightX + tw + 4, ty, tw, 18, () -> "Kursiv", () -> st().italic, () -> true, () -> {
            st().italic = !st().italic;
        });

        // Farben
        swatchGap = 3;
        int n = LyricsSettings.COLORS.length;
        swatchW = (colW - (n - 1) * swatchGap) / n;
        swatchX = rightX;
        swatchY = y0 + 150;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            hit(swatchX + i * (swatchW + swatchGap), swatchY, swatchW, 16, () -> st().color = idx);
        }

        // Vorschau (links unten)
        previewX = leftX;
        previewY = y0 + 172;
        previewW = colW;

        // ----- Kopfzeile: Audio-Quelle wählen -----
        int srcW = Math.min(230, w - 100);
        addCtl(pxR - 8 - srcW, pyT + 6, srcW, 16, () -> "Quelle: " + SpotifyClient.sourceLabel(),
                () -> false, () -> true, () -> {
                    if (this.client != null) this.client.setScreen(new LyricsSourceScreen(this));
                });
        MediaSessions.ensureStarted();

        addCtl(pxR - 12 - 70, pyB - 26, 70, 18, () -> "Fertig", () -> false, () -> true, this::close);
    }

    // ---------- Hilfen ----------

    static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    static float easeOut(float t) {
        float u = 1 - clamp01(t);
        return 1 - u * u * u;
    }

    static int a(int color, float f) {
        int al = Math.round((color >>> 24) * clamp01(f));
        return (al << 24) | (color & 0xFFFFFF);
    }

    static int mix(int c1, int c2, float t) {
        t = clamp01(t);
        int r = 0;
        for (int sh = 24; sh >= 0; sh -= 8) {
            int x = (c1 >>> sh) & 0xFF;
            int y = (c2 >>> sh) & 0xFF;
            r |= (Math.round(x + (y - x) * t) & 0xFF) << sh;
        }
        return r;
    }

    private float anim(String key, boolean on) {
        float t = on ? 1f : 0f;
        float v = anim.getOrDefault(key, 0f);
        v += (t - v) * Math.min(1f, dt * 16f);
        if (Math.abs(t - v) < 0.01f) v = t;
        anim.put(key, v);
        return v;
    }

    static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Fläche mit 1 px Rand und abgeschrägten (gekappten) Ecken. */
    static void panel(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        if (w < 6 || h < 6) {
            c.fill(x, y, x + w, y + h, border);
            return;
        }
        if ((fill >>> 24) != 0) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        c.fill(x + 2, y, x + w - 2, y + 1, border);
        c.fill(x + 2, y + h - 1, x + w - 2, y + h, border);
        c.fill(x, y + 2, x + 1, y + h - 2, border);
        c.fill(x + w - 1, y + 2, x + w, y + h - 2, border);
        c.fill(x + 1, y + 1, x + 2, y + 2, border);
        c.fill(x + w - 2, y + 1, x + w - 1, y + 2, border);
        c.fill(x + 1, y + h - 2, x + 2, y + h - 1, border);
        c.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, border);
    }

    private String fit(String s, int maxW) {
        if (tw(s) <= maxW) return s;
        String e = "...";
        while (s.length() > 1 && tw(s + e) > maxW) s = s.substring(0, s.length() - 1);
        return s + e;
    }

    /** Breite eines Textes in der Menü-Schrift (Mono). */
    private int tw(String s) {
        return this.textRenderer.getWidth(MonoFont.text(s));
    }

    private void txt(DrawContext ctx, String s, int x, int y, int color) {
        if ((color >>> 24) < 8) return;
        ctx.drawText(this.textRenderer, MonoFont.text(s), x, y, color, false);
    }

    private void drawCtl(DrawContext ctx, Ctl ct, int mx, int my, float gA) {
        boolean en = ct.enabled().getAsBoolean();
        boolean sel = ct.selected().getAsBoolean();
        boolean hov = en && inside(mx, my, ct.x(), ct.y(), ct.w(), ct.h());
        String key = "ctl:" + ct.x() + ":" + ct.y();
        float h = anim(key, hov);
        float s = anim(key + "s", sel);
        boolean display = !en && ct.label().get().endsWith("x"); // reine Anzeige (Größe)
        int bg = display ? Theme.field() : !en ? Theme.disabledFill() : mix(mix(Theme.button(), Theme.cardHover(), h), Theme.accent(), s);
        int bd = display ? Theme.border() : !en ? Theme.border() : mix(mix(Theme.border(), Theme.hoverBorder(), h), Theme.accent(), s);
        panel(ctx, ct.x(), ct.y(), ct.w(), ct.h(), a(bg, gA), a(bd, gA));
        int tc = display ? Theme.text() : !en ? Theme.disabledText() : mix(Theme.text(), Theme.onAccent(), s);
        String label = fit(ct.label().get(), ct.w() - 6);
        ctx.drawText(this.textRenderer, MonoFont.text(label), ct.x() + ct.w() / 2 - tw(label) / 2,
                ct.y() + (ct.h() - 8) / 2, a(tc, gA), false);
    }

    // ---------- Zeichnen ----------

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;

        Theme.update();
        if (idField != null) idField.setEditableColor(Theme.text());
        super.render(ctx, mx, my, delta); // Hintergrund; die Klickflächen sind unsichtbar
        float gA = easeOut((System.currentTimeMillis() - openStart) / 200f);
        ctx.fill(0, 0, this.width, this.height, a(Theme.scrim(), gA));
        LyricsSettings s = st();

        // weiß, ruhige Kopfzeile mit dünner gelber Linie (keine Warnstreifen)
        ctx.fill(pxL, pyT, pxR, pyB, a(Theme.bg(), gA));
        ctx.fill(pxL, pyT, pxR, pyT + 28, a(Theme.header(), gA));
        ctx.fill(pxL, pyT + 28, pxR, pyT + 29, a(Theme.border(), gA));
        ctx.fill(pxL, pyT + 28, pxL + Math.round((pxR - pxL) * gA), pyT + 30, a(Theme.accent(), gA));
        panel(ctx, pxL, pyT, pxR - pxL, pyB - pyT, 0x00000000, a(Theme.border(), gA));
        ctx.fill(pxL + 6, pyT + 8, pxL + 9, pyT + 20, a(Theme.accent(), gA));
        txt(ctx, "LYRICS", pxL + 14, pyT + 10, a(Theme.text(), gA));

        // links: Spotify
        txt(ctx, "SPOTIFY", leftX, y0 + 2, a(Theme.muted(), gA));
        String status = SpotifyClient.status();
        boolean bad = status.contains("fehlgeschlagen") || status.contains("abgelaufen") || status.contains("belegt")
                || status.contains("Fehler") || status.contains("Bitte");
        int sc = SpotifyClient.connected() && !bad ? good() : bad ? bad() : Theme.text();
        txt(ctx, fit(status, colW), leftX, y0 + 13, a(sc, gA));

        txt(ctx, "CLIENT-ID", leftX, y0 + 52, a(Theme.muted(), gA));
        panel(ctx, leftX, y0 + 64, colW, 20, a(Theme.field(), gA),
                a(idField != null && idField.isFocused() ? Theme.accent() : Theme.border(), gA));
        txt(ctx, "Redirect-URI in der Spotify-App:", leftX, y0 + 90, a(Theme.muted(), gA));
        txt(ctx, fit(SpotifyClient.REDIRECT_URI, colW), leftX, y0 + 101, a(Theme.accentText(), gA));
        txt(ctx, fit("developer.spotify.com/dashboard", colW), leftX, y0 + 112, a(Theme.muted(), gA));

        txt(ctx, "AKTUELLER SONG", leftX, y0 + 130, a(Theme.muted(), gA));
        String song = SpotifyClient.title().isEmpty() ? "-" : SpotifyClient.artist() + " - " + SpotifyClient.title();
        txt(ctx, fit(song, colW), leftX, y0 + 141, a(Theme.text(), gA));
        String ls = switch (SpotifyClient.lyricsState()) {
            case 1 -> "Lyrics werden geladen...";
            case 2 -> "Lyrics gefunden";
            case 3 -> "Keine Lyrics mit Zeitstempel gefunden";
            default -> s.enabled ? "" : "Lyrics sind ausgeschaltet";
        };
        txt(ctx, fit(ls, colW), leftX, y0 + 152, a(SpotifyClient.lyricsState() == 3 ? bad() : Theme.muted(), gA));

        // rechts: Beschriftungen
        txt(ctx, "POSITION", rightX, y0 + 30, a(Theme.muted(), gA));
        txt(ctx, "GRÖSSE", rightX, y0 + 66, a(Theme.muted(), gA));
        txt(ctx, "STIL", rightX, y0 + 102, a(Theme.muted(), gA));
        txt(ctx, "FARBE", rightX, y0 + 138, a(Theme.muted(), gA));

        for (Ctl ct : ctls) drawCtl(ctx, ct, mx, my, gA);

        // Farbfelder
        for (int i = 0; i < LyricsSettings.COLORS.length; i++) {
            int x = swatchX + i * (swatchW + swatchGap);
            boolean sel = i == s.color;
            boolean hov = inside(mx, my, x, swatchY, swatchW, 16);
            panel(ctx, x, swatchY, swatchW, 16, a(0xFF000000 | LyricsSettings.COLORS[i], gA),
                    a(sel ? Theme.accent() : hov ? Theme.hoverBorder() : Theme.border(), gA));
            if (sel) ctx.fill(x + 2, swatchY + 2, x + swatchW - 2, swatchY + 3, a(0xFF000000, gA));
        }

        // Vorschau
        int ph = 32;
        panel(ctx, previewX, previewY, previewW, ph, a(0xFF070707, gA), a(Theme.border(), gA));
        float k = Math.min(2.2f, Math.max(0.8f, s.size / 10f));
        var text = LyricsHud.styled("Beispiel-Lyrics", s);
        int tw = this.textRenderer.getWidth(text);
        while (k > 0.8f && tw * k > previewW - 8) k -= 0.1f;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(previewX + previewW / 2f, previewY + ph / 2f);
        m.scale(k, k);
        ctx.drawTextWithShadow(this.textRenderer, text, -tw / 2, -4, a(0xFF000000 | s.rgb(), gA));
        m.popMatrix();

        // Textfeld zuletzt, damit es über dem Fenster liegt
        idField.render(ctx, mx, my, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (idField != null && idField.isFocused()) {
            idField.setFocused(false);
            this.setFocused(null);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        MixerConfig.get().lyrics.normalize();
        MixerConfig.get().save();
        if (this.client != null) this.client.setScreen(parent);
    }
}
