package de.texturemixer.lyrics;

import de.texturemixer.MixerConfig;
import de.texturemixer.TextureMixerClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Zeigt die Lyrics des laufenden Songs "in der Welt": Ein Punkt in der Welt wird auf den Bildschirm projiziert
 * und der Text dort mit passender Größe gezeichnet. So bleibt er an seiner Stelle, wenn man sich bewegt oder
 * dreht, und man kann durch ihn hindurchlaufen (er blendet aus, wenn man nah dran ist).
 *
 * Modus "air": Jede neue Zeile erscheint 5 Blöcke vor dem Spieler und bleibt dort stehen.
 * Modus "sky": Die Zeile steht groß und weit weg am Himmel (feste Richtung, bewegt sich mit dem Spieler mit).
 *
 * Es wird auf das HUD gezeichnet, Blöcke davor verdecken den Text also nicht.
 */
public final class LyricsHud {
    private LyricsHud() {
    }

    private static final double AIR_DISTANCE = 5.0;
    private static final double AIR_TEXT_HEIGHT = 0.45;
    private static final double SKY_DISTANCE = 80.0;
    private static final double SKY_HEIGHT = 40.0;
    private static final double SKY_TEXT_HEIGHT = 7.0;

    private static final class Anchor {
        double x, y, z;
        boolean relative; // true = Abstand zum Spieler (Himmel), false = feste Weltposition (Luft)
        String text;
        long born;
        long diedAt = 0;
    }

    private static final List<Anchor> ANCHORS = new ArrayList<>();
    private static String lastKey = "";
    private static int lastIdx = -2;
    private static String lastMode = "";
    private static double skyYaw = Double.NaN; // Bogenmaß

    // Position pro Tick (zum Glätten zwischen den Ticks)
    private static boolean have = false;
    private static double prevX, prevY, prevZ, curX, curY, curZ, eyeOff;
    private static long tickNs = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(LyricsHud::onTick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
                Identifier.of(TextureMixerClient.MOD_ID, "lyrics"), LyricsHud::render);
    }

    /** Himmel-Richtung auf die aktuelle Blickrichtung setzen. */
    public static void alignSky() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) skyYaw = Math.toRadians(mc.player.getYaw());
        reset();
    }

    /** Alle Zeilen verwerfen, die aktuelle wird neu gesetzt. */
    public static void reset() {
        ANCHORS.clear();
        lastIdx = -2;
    }

    /** Text im gewählten Stil (für Anzeige und Vorschau). */
    public static MutableText styled(String s, LyricsSettings st) {
        MutableText t = Text.literal(s);
        if (st.bold) t = t.formatted(Formatting.BOLD);
        if (st.italic) t = t.formatted(Formatting.ITALIC);
        return t;
    }

    private static void onTick(MinecraftClient mc) {
        if (mc.player == null) {
            have = false;
            return;
        }
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        if (!have) {
            prevX = x;
            prevY = y;
            prevZ = z;
            have = true;
        } else {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
        }
        curX = x;
        curY = y;
        curZ = z;
        eyeOff = mc.player.getEyeY() - y;
        tickNs = System.nanoTime();
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        LyricsSettings s = MixerConfig.get().lyrics;
        if (!s.enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !have || !mc.options.getPerspective().isFirstPerson()) return;

        double a = Math.max(0.0, Math.min(1.0, (System.nanoTime() - tickNs) / 50_000_000.0));
        double ex = prevX + (curX - prevX) * a;
        double ey = prevY + (curY - prevY) * a + eyeOff;
        double ez = prevZ + (curZ - prevZ) * a;
        double yaw = Math.toRadians(mc.player.getYaw());
        double pitch = Math.toRadians(mc.player.getPitch());
        long now = System.currentTimeMillis();

        sync(s, ex, ey, ez, yaw, now);

        // Blickrichtung (Minecraft: Yaw 0 = nach +Z), rechts und oben
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw);
        double ry = 0;
        double rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double f = 1.0 / Math.tan(fov / 2.0);
        double factor = s.size / 10.0;
        TextRenderer tr = mc.textRenderer;

        Iterator<Anchor> it = ANCHORS.iterator();
        while (it.hasNext()) {
            Anchor an = it.next();
            long fadeOut = an.relative ? 250 : 1400;
            if (an.diedAt != 0 && now - an.diedAt >= fadeOut) {
                it.remove();
                continue;
            }
            double ax = an.relative ? ex + an.x : an.x;
            double ay = an.relative ? ey + an.y : an.y;
            double az = an.relative ? ez + an.z : an.z;
            double dx = ax - ex;
            double dy = ay - ey;
            double dz = az - ez;
            double vz = dx * fx + dy * fy + dz * fz;
            if (vz < 0.25) continue; // hinter oder direkt an der Kamera
            double vx = dx * rx + dy * ry + dz * rz;
            double vy = dx * ux + dy * uy + dz * uz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            float fin = clamp01((now - an.born) / 250f);
            float fout = an.diedAt == 0 ? 1f : 1f - clamp01((now - an.diedAt) / (float) fadeOut);
            float near = an.relative ? 1f : clamp01((float) ((dist - 0.7) / 1.5));
            float alpha = fin * fout * near;
            if (alpha < 0.04f) continue;

            double sx = w / 2.0 + vx / vz * f * h / 2.0;
            double sy = h / 2.0 - vy / vz * f * h / 2.0;
            double worldH = (an.relative ? SKY_TEXT_HEIGHT : AIR_TEXT_HEIGHT) * factor;
            float k = (float) Math.min(14.0, worldH / 9.0 * (f * h / 2.0 / vz));
            if (k < 0.05f) continue;

            MutableText text = styled(an.text, s);
            int tw = tr.getWidth(text);
            if (sx + tw * k / 2 < 0 || sx - tw * k / 2 > w) continue;

            int al = Math.round(alpha * 255f);
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) sx, (float) sy);
            m.scale(k, k);
            ctx.fill(-tw / 2 - 3, -6, tw / 2 + 3, 6, (Math.round(alpha * 90f) << 24));
            ctx.drawTextWithShadow(tr, text, -tw / 2, -4, (al << 24) | s.rgb());
            m.popMatrix();
        }
    }

    /** Neue Zeile erkannt: alte ausblenden, neue an ihre Stelle setzen. */
    private static void sync(LyricsSettings s, double ex, double ey, double ez, double yaw, long now) {
        if (!s.mode.equals(lastMode)) {
            lastMode = s.mode;
            reset();
        }
        String key = SpotifyClient.trackKey();
        int idx = SpotifyClient.currentIndex();
        if (key.equals(lastKey) && idx == lastIdx) return;
        if (!key.equals(lastKey)) ANCHORS.clear();
        lastKey = key;
        lastIdx = idx;

        for (Anchor old : ANCHORS) {
            if (old.diedAt == 0) old.diedAt = now;
        }
        String text = idx >= 0 ? SpotifyClient.lineText(idx) : "";
        if (text.isBlank()) return;

        Anchor an = new Anchor();
        an.text = text;
        an.born = now;
        if (s.sky()) {
            if (Double.isNaN(skyYaw)) skyYaw = yaw;
            an.relative = true;
            an.x = -Math.sin(skyYaw) * SKY_DISTANCE;
            an.y = SKY_HEIGHT;
            an.z = Math.cos(skyYaw) * SKY_DISTANCE;
        } else {
            an.relative = false;
            an.x = ex - Math.sin(yaw) * AIR_DISTANCE;
            an.y = ey - 0.1;
            an.z = ez + Math.cos(yaw) * AIR_DISTANCE;
        }
        ANCHORS.add(an);
        while (ANCHORS.size() > 3) ANCHORS.remove(0);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
