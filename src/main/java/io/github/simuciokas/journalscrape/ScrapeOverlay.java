package io.github.simuciokas.journalscrape;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A full-screen cover drawn while a scrape is running.
 *
 * <p>WHY THIS EXISTS, AND WHY IT IS OPAQUE. The walk opens and closes a container and a dialog once
 * per entry, several times a second, and the screen flashes between them for the whole run. That is
 * unpleasant to watch and genuinely unsafe for anyone photosensitive - a scrape of a large journal
 * means minutes of it. So the mod paints over its own work: a still, opaque panel with the progress
 * on it, and nothing on this panel animates faster than once a second.
 *
 * <p>It is drawn from TWO hooks, because neither alone covers the whole cycle: the screen hook
 * misses the frames between windows, when no screen exists at all, and the HUD hook draws
 * underneath any screen that is open. Together they cover every frame.
 *
 * <p>The overlay is cosmetic only - it never swallows input. Escape still reaches the screen
 * beneath it and still stops the run, which is the one thing a person watching this needs to be
 * able to do.
 */
public final class ScrapeOverlay {

    /** Near-opaque by default: the point is that the flashing underneath cannot be seen. */
    private static final int BACKDROP = 0xF2101018;
    private static final int PANEL = 0xFF1B1E27;
    private static final int LINE = 0xFF2E3341;
    private static final int TITLE = 0xFF5FD3E0;
    private static final int TEXT = 0xFFE8EBF2;
    private static final int DIM = 0xFF98A0B3;
    private static final int ACCENT = 0xFF7BD88F;
    private static final int KEYCAP = 0xFFF6C646;

    private ScrapeOverlay() {
    }

    public static void draw(GuiGraphicsExtractor g) {
        if (!JournalScrape.isRunning()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return;
        }
        final Font font = mc.font;
        final int w = g.guiWidth();
        final int h = g.guiHeight();

        // cover everything first, so nothing underneath shows through at any point
        g.fill(0, 0, w, h, BACKDROP);

        final List<String> lines = JournalScrape.overlayLines();
        final String title = "Journal scrape running";
        final String hint = "[Esc]  stop and keep what has been collected";

        int textW = font.width(title);
        for (String s : lines) {
            textW = Math.max(textW, font.width(s));
        }
        textW = Math.max(textW, font.width(hint));

        final int padding = 14;
        final int barH = 6;
        final int lineH = font.lineHeight + 3;
        final int boxW = Math.min(w - 20, textW + padding * 2);
        final int boxH = padding * 2 + lineH * (lines.size() + 1) + barH + 18;
        final int x = (w - boxW) / 2;
        final int y = (h - boxH) / 2;

        g.fill(x, y, x + boxW, y + boxH, PANEL);
        g.fill(x, y, x + boxW, y + 1, LINE);
        g.fill(x, y + boxH - 1, x + boxW, y + boxH, LINE);
        g.fill(x, y, x + 1, y + boxH, LINE);
        g.fill(x + boxW - 1, y, x + boxW, y + boxH, LINE);

        int ty = y + padding;
        g.centeredText(font, title, x + boxW / 2, ty, TITLE);
        ty += lineH + 4;

        // progress bar: an estimate against the library's size, since the true total is only known
        // once a walk finishes. Indeterminate on a first run, where there is nothing to compare to.
        final float p = JournalScrape.overlayProgress();
        final int barX = x + padding;
        final int barW = boxW - padding * 2;
        g.fill(barX, ty, barX + barW, ty + barH, LINE);
        if (p >= 0) {
            final int filled = Math.max(1, Math.round(barW * Math.min(1f, p)));
            g.fill(barX, ty, barX + filled, ty + barH, ACCENT);
        }
        ty += barH + 8;

        for (String s : lines) {
            g.centeredText(font, s, x + boxW / 2, ty, TEXT);
            ty += lineH;
        }
        ty += 4;
        g.centeredText(font, hint, x + boxW / 2, ty, KEYCAP);

        // a quiet note at the bottom of the screen, so the cover itself is explained
        g.centeredText(font, "the journal is being driven underneath this cover",
                       w / 2, Math.min(h - 12, y + boxH + 10), DIM);
    }
}
