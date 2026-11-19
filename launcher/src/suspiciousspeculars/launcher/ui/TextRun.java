package suspiciousspeculars.launcher.ui;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Mojangles from the 1x sheet, laid out and blitted as blocks.
 *
 * One fillRect per ink pixel at the run's scale: no drawString, no font metrics, nothing the platform
 * can hint or antialias. Advances are the game's (last ink column + 1) except the space, which the
 * reference frames measure at 3 rather than 4.
 */
public final class TextRun {

    private static final int CELL = 8;
    private static final int COLS = 16;

    private final boolean[][][] ink = new boolean[256][CELL][CELL];
    private final int[] advance = new int[256];

    public TextRun(String resource) throws IOException {
        InputStream in = TextRun.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("missing glyph sheet " + resource);
        BufferedImage sheet;
        try {
            sheet = ImageIO.read(in);
        } finally {
            in.close();
        }
        for (int code = 0; code < 256; code++) {
            int cx = (code % COLS) * CELL;
            int cy = (code / COLS) * CELL;
            int last = -1;
            for (int y = 0; y < CELL; y++) {
                for (int x = 0; x < CELL; x++) {
                    boolean on = cx + x < sheet.getWidth() && cy + y < sheet.getHeight()
                            && (sheet.getRGB(cx + x, cy + y) >>> 24) > 96;
                    ink[code][y][x] = on;
                    if (on && x > last) last = x;
                }
            }
            advance[code] = code == ' ' ? Metrics.SPACE : Math.max(2, last + 2);
        }
    }

    /** Width of a run in drawing units, at the given glyph scale. */
    public int width(String text, int scale) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += advance[text.charAt(i) & 0xFF] * scale;
        return total;
    }

    /** Draw a run with its cell's top-left at (x, y). */
    public void draw(Graphics2D g, String text, int x, int y, int scale, Color colour) {
        g.setColor(colour);
        int pen = x;
        for (int i = 0; i < text.length(); i++) {
            int code = text.charAt(i) & 0xFF;
            boolean[][] glyph = ink[code];
            for (int gy = 0; gy < CELL; gy++) {
                for (int gx = 0; gx < CELL; gx++) {
                    if (glyph[gy][gx]) {
                        g.fillRect(pen + gx * scale, y + gy * scale, scale, scale);
                    }
                }
            }
            pen += advance[code] * scale;
        }
    }

    /** Draw a run whose right edge sits at {@code right}. */
    public void drawRight(Graphics2D g, String text, int right, int y, int scale, Color colour) {
        draw(g, text, right - width(text, scale), y, scale, colour);
    }

    /** The longest prefix of {@code text} that fits {@code width}, with two dots when cut. */
    public String fit(String text, int width, int scale) {
        if (width(text, scale) <= width) return text;
        int dots = width("..", scale);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String next = out.toString() + text.charAt(i);
            if (width(next, scale) + dots > width) break;
            out.append(text.charAt(i));
        }
        return out.toString() + "..";
    }
}
