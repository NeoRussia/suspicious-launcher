package suspiciousspeculars.launcher.ui;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * The bevel primitive: a face, four border strips, and the two corners CSS would blend.
 *
 * Drawn with fillRect only. No drawRect (it straddles the coordinate), no antialiasing, no gradients -
 * those are the three ways a pixel-art frame goes soft, and the harness catches any of them.
 */
public final class Frame {

    private Frame() {
    }

    /**
     * A raised box: light on the top/left, shadow on the bottom/right, face inside.
     *
     * @param bevel  border thickness in drawing units
     * @param light  top/left colour
     * @param dark   bottom/right colour
     * @param face   interior colour
     */
    public static void raised(Graphics2D g, int x, int y, int w, int h, int bevel,
                              Color light, Color dark, Color face) {
        g.setColor(face);
        g.fillRect(x + bevel, y + bevel, w - bevel * 2, h - bevel * 2);
        g.setColor(light);
        g.fillRect(x, y, w, bevel);
        g.fillRect(x, y, bevel, h);
        g.setColor(dark);
        g.fillRect(x, y + h - bevel, w, bevel);
        g.fillRect(x + w - bevel, y, bevel, h);
        corners(g, x, y, w, h, bevel, light, dark);
    }

    /**
     * A sunken well: dark on the top/left, light on the bottom/right. Used by the list outline and the
     * bottom field.
     */
    public static void sunken(Graphics2D g, int x, int y, int w, int h, int bevel,
                              Color dark, Color light, Color face) {
        raised(g, x, y, w, h, bevel, dark, light, face);
    }

    /**
     * The two pixels CSS leaves half-blended at a mitred corner. The reference gives them to the dark
     * side; here that is explicit, which is also why the Java corner and the CSS corner agree.
     */
    private static void corners(Graphics2D g, int x, int y, int w, int h, int bevel,
                                Color light, Color dark) {
        g.setColor(dark);
        // top-right: everything but the outermost top pixel of the face side
        g.fillRect(x + w - bevel, y, bevel, bevel);
        g.setColor(light);
        g.fillRect(x + w - bevel, y, 1, 1);
        // bottom-left: the mirror
        g.setColor(dark);
        g.fillRect(x, y + h - bevel, bevel, bevel);
        g.setColor(light);
        g.fillRect(x, y + h - bevel, 1, 1);
    }
}
