package suspiciousspeculars.launcher.ui;

/**
 * The window's measurements, in GIF pixels.
 *
 * One drawing unit is one pixel of patcher.gif, so the numbers here are the numbers in the web build's
 * manifest.json. Output scaling is a single integer applied to the Graphics2D; nothing is measured in
 * "art units" any more.
 */
public final class Metrics {

    private Metrics() {
    }

    public static final int CANVAS_W = 528;
    public static final int CANVAS_H = 498;

    /** Panel, staircase and drop shadow: (x, y, w, h), panel first, then the steps and the shadow. */
    public static final int[][] PANEL_RECTS = {
            {9, 9, 510, 480},
            {519, 6, 3, 3}, {522, 3, 3, 3}, {525, 0, 3, 3},
            {6, 489, 3, 3}, {3, 492, 3, 3}, {0, 495, 3, 3},
    };
    public static final int[][] SHADOW_RECTS = {
            {519, 9, 9, 489},
            {522, 6, 6, 3},
            {525, 3, 3, 3},
            {9, 489, 510, 9},
            {6, 492, 3, 6},
            {3, 495, 3, 3},
    };

    /* list */
    public static final int[] LIST_OUTLINE = {26, 182, 444, 133};
    public static final int[] LIST_BOX = {32, 188, 432, 121};

    /* rows: text cell origins and the right edge each state ends on */
    public static final int[] ROW_TEXT_Y = {196, 236, 276};
    public static final int ROW_TEXT_X = 39;
    public static final int ROW_SELECTED_TEXT_X = 55;
    public static final int ROW_STATE_RIGHT = 454;

    /* slots */
    public static final int[] SLOT_IN = {26, 86, 72, 72};
    public static final int[] SLOT_OUT = {224, 86, 72, 72};
    public static final int SLOT_BEVEL = 4;
    public static final int SLOT_INSET = 6;
    public static final int SLOT_ART = 60;

    /* text scales, in output pixels per glyph pixel */
    public static final int TEXT_ROW = 2;
    public static final int TEXT_TITLE = 3;
    public static final int SPACE = 3;      // the frames measure 3, the game uses 4
}
