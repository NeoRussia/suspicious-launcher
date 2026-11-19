package suspiciousspeculars.launcher.ui;

import java.awt.Color;

/**
 * The palette, straight from the web build's tokens.css, which was sampled from patcher.gif frame 0.
 *
 * One value does not come from the frame: LINK, the classic pure blue the window uses for anything it
 * can open. Nothing here is invented, so an element that needs a new colour is a decision, not a
 * convenience.
 */
public final class Palette {

    private Palette() {
    }

    /* surfaces */
    public static final Color CANVAS = new Color(0xFEFEFE);
    public static final Color PANEL = new Color(0xC6C6C6);
    public static final Color FIELD = new Color(0xB5B5B5);

    /* bevels */
    public static final Color BEVEL_LIGHT = new Color(0xFEFEFE);
    public static final Color BEVEL_SHADOW = new Color(0x6E6E6F);
    public static final Color BEVEL_MID = new Color(0x8A8A8B);
    public static final Color BEVEL_DARK = new Color(0x545455);

    /* ink */
    public static final Color INK = new Color(0x3B3B3B);
    public static final Color LINK = new Color(0x0000FF);
    /*
     * Not from the frame: the band's one "this will not work" line, at the player's request. Dark red
     * rather than Minecraft's light red (0xFF5555), because that one all but disappears on the grey
     * panel at 2x.
     */
    public static final Color PROBLEM = new Color(0xAA0000);

    /* list */
    public static final Color LIST_OUTLINE = new Color(0x7C7C84);
    public static final Color LIST_BORDER = new Color(0x5E5E67);
    public static final Color LIST_BG = new Color(0x0F1039);
    public static final Color ROW_LABEL = new Color(0x8C93C4);
    public static final Color ROW_LABEL_SELECTED = new Color(0xFEFEFE);
    public static final Color ROW_STATE = new Color(0x6F76A8);
    public static final Color ROW_STATE_SELECTED = new Color(0xA8B6E6);

    /* slots and progress */
    public static final Color SLOT_BORDER_DARK = new Color(0x3B3B3B);
    public static final Color SLOT_BORDER_LIGHT = new Color(0xFEFEFE);
    public static final Color SLOT_FILL = new Color(0x8A8A8B);
    public static final Color PROGRESS_TRACK = new Color(0x8A8A8B);
    public static final Color PROGRESS_THUMB = new Color(0xC6C6C6);
}
