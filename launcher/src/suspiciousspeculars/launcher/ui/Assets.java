package suspiciousspeculars.launcher.ui;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Classpath artwork, drawn at 1:1.
 *
 * The jar carries ui/art/*.png from the split UI; every element draws its crop through this class, so
 * the "always natural size × scale" rule lives in one place. An image drawn at any other size would be
 * resampled, which is the one thing the pixel gate would never let through twice.
 */
public final class Assets {

    private static final Map<String, BufferedImage> CACHE = new HashMap<String, BufferedImage>();

    private Assets() {
    }

    public static BufferedImage get(String name) {
        String key = "/ui/art/" + name;
        if (CACHE.containsKey(key)) return CACHE.get(key);
        BufferedImage image = null;
        InputStream in = Assets.class.getResourceAsStream(key);
        if (in != null) {
            try {
                image = ImageIO.read(in);
            } catch (IOException ignored) {
                image = null;
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing left to do
                }
            }
        }
        CACHE.put(key, image);
        return image;
    }

    public static void draw(Graphics2D g, String name, int x, int y, int w, int h) {
        BufferedImage image = get(name);
        if (image == null) return;
        g.drawImage(image, x, y, w, h, null);
    }
}
