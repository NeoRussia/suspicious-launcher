package suspiciousspeculars.launcher.ui;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The patcher window, drawn element by element from the split design and driven by the install worker.
 *
 * States: idle -> busy (steps 0..24) -> complete, or idle -> busy -> failed. The button starts the run,
 * becomes Continue when the run is over, and Continue is what ends the launcher so the staging
 * hand-off can resume. Close is ignored while a run is in flight: killing the process mid-write is the
 * one thing this window must not make easy.
 *
 * Drawing rules, enforced by the frame harness rather than by discipline: one unit is one pixel of
 * patcher.gif, the scale is a single integer, no antialiasing, no drawString, fillRect and 1:1 blits.
 */
public final class PatcherWindow extends JPanel {

    private static final String[] ROW_STATES = {"ready", "patching...", "installed"};

    /** Arrow fill in pixels for steps 0..24, measured from the GIF. */
    private static final int[] FILL = {
            0, 0, 0, 4, 8, 12, 16, 16, 20, 24, 28, 32, 36, 36,
            40, 44, 48, 52, 56, 56, 60, 64, 68, 72, 72,
    };

    /* layout, in drawing units */
    private static final Rectangle ACTION = new Rectangle(396, 434, 97, 35);
    private static final Rectangle CLOSE = new Rectangle(462, 12, 40, 40);
    private static final int TILE_Y = 322;
    private static final int TILE_SIZE = 40;
    private static final int[] TILE_X = {26, 71, 116, 161};
    /** The tiles in drawing order, by the id the design's manifest uses. */
    private static final String[] TILE_IDS = {"duck", "github", "modrinth", "discord"};
    private static final String[] TILE_URLS = {
            null,                                                   // duck: a button, not a link
            "https://github.com/NeoRussia/suspicious-speculars",
            "https://modrinth.com/mod/suspicious-speculars",
            "https://discord.com/invite/KYF5a4scRj",
    };
    private static final String[] TILE_ART = {
            "icon-duck.png", "icon-github.png", "icon-modrinth.png", "icon-discord.png",
    };
    /** The four tiles are the only controls with a hint; a label is not a description. */
    private static final String[] TILE_HINTS = {
            "Duck mode", "GitHub repository", "Modrinth page", "Join the Discord server",
    };
    /** Duck mode's cue: the mp3 the web build plays, as PCM for Java 8's javax.sound. */
    private static final String QUACK = "/ui/sound/quack.wav";

    /* tooltip box, in drawing units: one 2x run, 5px side padding and 3px vertical, 1px border */
    private static final int HINT_SCALE = 2;
    private static final int HINT_PAD_X = 5;
    private static final int HINT_PAD_Y = 3;
    private static final int HINT_GAP = 4;
    private static final int HINT_HEIGHT = 8 * HINT_SCALE + HINT_PAD_Y * 2 + 2;

    /* band, in drawing units: error-list.js's region, 18px pitch, 3 lines */
    private static final int BAND_X = 26;
    private static final int BAND_Y = 368;
    private static final int BAND_W = 414;
    private static final int BAND_PITCH = 18;
    private static final int BAND_MAX = 3;

    private final int scale;
    private final TextRun font;

    /* what the window shows */
    private boolean busy;
    private boolean complete;
    private boolean installed;

    /** A newer pack release than what is installed, shown in the band and on the button. */
    private String updateVersion = "";
    private boolean failed;
    /** Frame 26 of the GIF: the same installed look, but with its own "restart" wording. */
    private boolean gifDone;
    private int done;
    private final List<String[]> messages = new ArrayList<String[]>();
    /** The file the run writes, shown in the field. The default is the GIF's own string. */
    private String outputName = "Eclipse-Suspicious.zip";
    /** Blocking problems found before a run, drawn ahead of the results. */
    private final List<String[]> warnings = new ArrayList<String[]>();
    /** Clickable name boxes and their targets, in drawing units, rebuilt on every paint. */
    private final List<Rectangle> linkHits = new ArrayList<Rectangle>();
    private final List<String> linkHrefs = new ArrayList<String>();

    /* interaction */
    private boolean pressedAction;
    private boolean pressedClose;
    /** Tile whose hint is up, or -1. */
    private int hintTile = -1;
    private Runnable onAction = new Runnable() {
        public void run() {
        }
    };
    private Runnable onClose = new Runnable() {
        public void run() {
        }
    };
    private LinkHandler onLink = new LinkHandler() {
        public void open(String url) {
        }
    };

    /** Opens a link outside the window. */
    public interface LinkHandler {
        void open(String url);
    }

    /* The tile row as the design specifies it. Public so --links can report what the jar will open and
     * the build can diff that against ui/manifest.json: no frame contains a URL, so the pixel gate
     * cannot catch a mistyped link. */

    public static int tileCount() {
        return TILE_IDS.length;
    }

    public static String tileId(int index) {
        return TILE_IDS[index];
    }

    /** The URL the tile opens, or "quack" for the tile that is a button. */
    public static String tileTarget(int index) {
        return TILE_URLS[index] != null ? TILE_URLS[index] : "quack";
    }

    public PatcherWindow(int scale) throws IOException {
        this.scale = Math.max(1, scale);
        this.font = new TextRun("/ui/font/mc_font_ascii.png");
        setPreferredSize(new Dimension(Metrics.CANVAS_W * this.scale, Metrics.CANVAS_H * this.scale));
        setOpaque(true);
        setBackground(Color.WHITE);
        installMouse();
    }

    // ---- state the launcher drives -----------------------------------------

    public void onAction(Runnable value) {
        onAction = value;
    }

    public void onClose(Runnable value) {
        onClose = value;
    }

    public void onLink(LinkHandler value) {
        onLink = value;
    }

    /** True once a patched shader exists, which changes Install to Reinstall. */
    public void setInstalled(boolean value) {
        installed = value;
        repaint();
    }

    /**
     * A newer pack release than the installed one: the band says so and the button reads Update, so
     * a hand-over caused by a release does not look like an invitation to reinstall the same thing.
     */
    public void setUpdateAvailable(String version) {
        updateVersion = version == null ? "" : version.trim();
        repaint();
    }

    /**
     * The file the run writes, for the field. The harness keeps the GIF's string; a live window
     * shows the real name, which changed to Eclipse-SUSY.zip after the frames were captured.
     */
    public void setOutputName(String name) {
        if (name != null && !name.isEmpty()) outputName = name;
        repaint();
    }

    public void setBusy(boolean value) {
        busy = value;
        if (value) {
            complete = false;
            gifDone = false;
            failed = false;
            messages.clear();
        }
        repaint();
    }

    /** Steps finished, 0..24. */
    public void setProgress(int value) {
        done = Math.max(0, Math.min(24, value));
        repaint();
    }

    /** The run is over: summary in the band, Continue on the button. */
    public void setComplete(List<String[]> fetched) {
        busy = false;
        failed = false;
        complete = true;
        gifDone = false;
        installed = true;
        done = 24;
        messages.clear();
        messages.addAll(fetched);
        repaint();
    }

    /** The run failed: the reason in the band, the button back to Install/Reinstall. */
    public void setFailure(List<String[]> why) {
        busy = false;
        complete = false;
        failed = true;
        messages.clear();
        messages.addAll(why);
        repaint();
    }

    /**
     * Problems that keep the shader from working, shown before any result: each is
     * {@code {name, detail, href}}. A missing prerequisite is why the window exists, so it must not be
     * pushed out of the band by a list of downloaded packs.
     */
    public void setWarnings(List<String[]> lines) {
        warnings.clear();
        if (lines != null) warnings.addAll(lines);
        repaint();
    }

    /** Harness only: the web build's demo errors, so the link drawing can be diffed against it. */
    public void showDemoErrors() {
        messages.clear();
        messages.add(new String[]{"Demonica", "0.9.1 missing",
                "https://github.com/GTNewHorizons/Demonica"});
        messages.add(new String[]{"manifest", "not found (404)",
                "https://example.invalid/packs/manifest.json"});
        messages.add(new String[]{"", "failed to init Celeritas", null});
        repaint();
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean isBusy() {
        return busy;
    }

    /** Frame harness entry point: 0 idle, 1 pressed, 2..25 steps, 26 done, 27 complete. */
    public void setFrame(int frame) {
        done = frame <= 1 ? 0 : Math.min(24, frame - 1);
        complete = frame >= 27;
        gifDone = frame == 26;
        installed = frame >= 26;
        busy = false;
        failed = false;
        pressedAction = frame == 1;
        hintTile = -1;
        messages.clear();
        if (complete) {
            messages.add(new String[]{"Cleanroom", "0.7.14 installed"});
            messages.add(new String[]{"Demonica", "0.9.1 installed"});
        }
        repaint();
    }

    /**
     * Harness entry point: shows the hint of tile {@code index} as if it were hovered, -1 to hide it.
     * Hover does the same thing on a real display.
     */
    public void showHint(int index) {
        setHint(index >= 0 && index < TILE_HINTS.length ? index : -1);
    }

    // ---- painting -----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.scale(scale, scale);

        chrome(g);
        title(g);
        close(g);
        slot(g, Metrics.SLOT_IN, "eclipse-slot.png", 6, 6, 60, 60);
        arrow(g);
        slotOut(g);
        list(g);
        rows(g);
        scrollGrip(g);
        tiles(g);
        badge(g);
        field(g);
        button(g);
        band(g);
        hint(g);

        g.dispose();
    }

    private void chrome(Graphics2D g) {
        g.setColor(Palette.CANVAS);
        g.fillRect(0, 0, Metrics.CANVAS_W, Metrics.CANVAS_H);
        g.setColor(Palette.PANEL);
        for (int[] r : Metrics.PANEL_RECTS) g.fillRect(r[0], r[1], r[2], r[3]);
        g.setColor(Palette.BEVEL_DARK);
        for (int[] r : Metrics.SHADOW_RECTS) g.fillRect(r[0], r[1], r[2], r[3]);
    }

    private void title(Graphics2D g) {
        font.draw(g, "Suspicious Speculars", 24, 14, Metrics.TEXT_TITLE, Palette.INK);
    }

    private void close(Graphics2D g) {
        if (pressedClose) {
            Frame.raised(g, CLOSE.x, CLOSE.y, CLOSE.width, CLOSE.height, 2, Palette.BEVEL_SHADOW,
                    Palette.BEVEL_LIGHT, Palette.BEVEL_SHADOW);
        } else {
            Frame.raised(g, CLOSE.x, CLOSE.y, CLOSE.width, CLOSE.height, 2, Palette.BEVEL_LIGHT,
                    Palette.BEVEL_SHADOW, Palette.BEVEL_MID);
        }
        Assets.draw(g, "close-cross.png", 472, 23, 20, 18);
    }

    private void slot(Graphics2D g, int[] box, String art, int ax, int ay, int aw, int ah) {
        int x = box[0];
        int y = box[1];
        int outer = box[2];
        int bevel = Metrics.SLOT_BEVEL;

        g.setColor(Palette.SLOT_FILL);
        g.fillRect(x, y, outer, outer);
        g.setColor(Palette.SLOT_BORDER_DARK);
        g.fillRect(x, y, outer - bevel, bevel);
        g.fillRect(x, y, bevel, outer - bevel);
        g.setColor(Palette.SLOT_BORDER_LIGHT);
        g.fillRect(x + bevel, y + outer - bevel, outer - bevel, bevel);
        g.fillRect(x + outer - bevel, y + bevel, bevel, outer - bevel);

        Assets.draw(g, art, x + ax, y + ay, aw, ah);
    }

    private void slotOut(Graphics2D g) {
        if (complete || gifDone) {
            slot(g, Metrics.SLOT_OUT, "output-block.png", 6, 6, 60, 60);
        } else {
            slot(g, Metrics.SLOT_OUT, "out-lettering.png", 12, 44, 52, 20);
        }
    }

    private void arrow(Graphics2D g) {
        int x = 122;
        int y = 90;
        Assets.draw(g, "canner-arrow-empty.png", x, y, 80, 60);

        int filled = FILL[Math.max(0, Math.min(24, done))];
        if (filled <= 0) return;

        Rectangle previous = g.getClipBounds();
        g.setClip(x, y, 4 + filled, 60);
        Assets.draw(g, "canner-arrow-fill.png", x, y, 80, 60);
        g.setClip(previous);
    }

    private void list(Graphics2D g) {
        int[] o = Metrics.LIST_OUTLINE;
        g.setColor(Palette.LIST_OUTLINE);
        g.fillRect(o[0], o[1], o[2], 1);
        g.fillRect(o[0], o[1] + o[3] - 1, o[2], 1);
        g.fillRect(o[0], o[1], 1, o[3]);
        g.fillRect(o[0] + o[2] - 1, o[1], 1, o[3]);

        int[] b = Metrics.LIST_BOX;
        g.setColor(Palette.LIST_BORDER);
        g.fillRect(b[0], b[1], b[2], 1);
        g.fillRect(b[0], b[1] + b[3] - 1, b[2], 1);
        g.fillRect(b[0], b[1], 1, b[3]);
        g.fillRect(b[0] + b[2] - 1, b[1], 1, b[3]);
        g.setColor(Palette.LIST_BG);
        g.fillRect(b[0] + 1, b[1] + 1, b[2] - 2, b[3] - 2);
    }

    private void rows(Graphics2D g) {
        String[] labels = {"Eclipse e89e6c6", "Complementary", "Photon"};
        String state = complete || installed ? ROW_STATES[2] : (busy || done > 0 ? ROW_STATES[1] : ROW_STATES[0]);
        String[] states = {state, "soon", "soon"};
        boolean[] selected = {true, false, false};

        for (int i = 0; i < 3; i++) {
            int y = Metrics.ROW_TEXT_Y[i];
            boolean sel = selected[i];
            int labelX = sel ? Metrics.ROW_SELECTED_TEXT_X : Metrics.ROW_TEXT_X;
            if (sel) {
                font.draw(g, ">", Metrics.ROW_TEXT_X, y, Metrics.TEXT_ROW,
                        Palette.ROW_LABEL_SELECTED);
            }
            font.draw(g, labels[i], labelX, y, Metrics.TEXT_ROW,
                    sel ? Palette.ROW_LABEL_SELECTED : Palette.ROW_LABEL);
            font.drawRight(g, states[i], Metrics.ROW_STATE_RIGHT, y, Metrics.TEXT_ROW,
                    sel ? Palette.ROW_STATE_SELECTED : Palette.ROW_STATE);
        }
    }

    private void scrollGrip(Graphics2D g) {
        int x = 473;
        int y = 182;
        int w = 28;
        int h = 128;

        g.setColor(Palette.BEVEL_MID);
        g.fillRect(x, y, w, h);
        g.setColor(Palette.BEVEL_DARK);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y, 1, h);
        g.setColor(Palette.BEVEL_SHADOW);
        g.fillRect(x + w - 1, y, 1, h);
        g.setColor(Palette.BEVEL_LIGHT);
        g.fillRect(x, y + h - 1, w, 1);
        g.setColor(Palette.BEVEL_DARK);
        g.fillRect(x + w - 1, y, 1, 1);
        g.fillRect(x, y + h - 1, 1, 1);
        g.setColor(Palette.BEVEL_SHADOW);
        g.fillRect(x + w - 1, y + h - 1, 1, 1);

        int gx = x + 1;
        int gy = y + 1;
        g.setColor(Palette.PANEL);
        g.fillRect(gx, gy, 26, 26);
        g.setColor(Palette.BEVEL_SHADOW);
        g.fillRect(gx, gy + 25, 26, 1);
        g.fillRect(gx + 25, gy, 1, 26);
        g.setColor(Palette.BEVEL_LIGHT);
        g.fillRect(gx, gy, 26, 1);
        g.fillRect(gx, gy, 1, 26);

        g.setColor(Palette.BEVEL_SHADOW);
        for (int i = 0; i < 6; i++) {
            g.fillRect(gx + 3, gy + 3 + i * 4, 20, 1);
        }
    }

    private void tiles(Graphics2D g) {
        for (int i = 0; i < 4; i++) {
            Frame.raised(g, TILE_X[i], TILE_Y, TILE_SIZE, TILE_SIZE, 2, Palette.BEVEL_LIGHT,
                    Palette.BEVEL_SHADOW, Palette.BEVEL_MID);
            Assets.draw(g, TILE_ART[i], TILE_X[i] + 2, TILE_Y + 2, 36, 36);
        }
    }

    private void badge(Graphics2D g) {
        Assets.draw(g, "gregtech-badge.png", 447, 351, 51, 51);
    }

    private void field(Graphics2D g) {
        Frame.raised(g, 26, 424, 477, 57, 2, Palette.BEVEL_LIGHT, Palette.BEVEL_SHADOW,
                Palette.FIELD);

        String name = gifDone ? "24/24 anchors" : outputName;
        font.draw(g, name, 40, 442, Metrics.TEXT_ROW, Palette.INK);
        String counter = gifDone ? "restart"
                : (complete ? "24/24" : (done > 0 ? done + "/24" : ""));
        if (!counter.isEmpty()) {
            font.drawRight(g, counter, 378, 442, Metrics.TEXT_ROW, Palette.INK);
        }
    }

    private void button(Graphics2D g) {
        if (pressedAction) {
            Frame.raised(g, ACTION.x, ACTION.y, ACTION.width, ACTION.height, 2,
                    Palette.BEVEL_SHADOW, Palette.BEVEL_LIGHT, Palette.FIELD);
        } else {
            Frame.raised(g, ACTION.x, ACTION.y, ACTION.width, ACTION.height, 2,
                    Palette.BEVEL_LIGHT, Palette.BEVEL_SHADOW, Palette.PANEL);
        }
        String label;
        int labelX;
        if (complete) {
            label = "Continue";
            labelX = 405;
        } else if (!updateVersion.isEmpty()) {
            label = "Update";
            labelX = 411 + (font.width("Install", Metrics.TEXT_ROW)
                    - font.width("Update", Metrics.TEXT_ROW)) / 2;
        } else if (installed) {
            label = "Reinstall";
            labelX = 401;
        } else {
            label = "Install";
            labelX = 411;
        }
        font.draw(g, label, labelX, 442, Metrics.TEXT_ROW, Palette.INK);
    }

    /**
     * The band between the tiles and the bar: blocking problems first, then what was fetched. The name
     * of a problem is a link - blue and underlined, the one colour the window uses for "this opens
     * something" - and the pointer opens it; a result is plain ink.
     *
     * Three lines at 18px pitch, as error-list.js lays them out: two plus "+N more" when they do not
     * fit, so a missing prerequisite can never be pushed off the band by a list of downloads.
     */
    private void band(Graphics2D g) {
        linkHits.clear();
        List<String[]> lines = bandLines();
        if (lines.isEmpty()) return;

        for (int i = 0; i < lines.size(); i++) {
            String[] m = lines.get(i);
            String name = m[0] == null ? "" : m[0];
            String detail = m[1] == null ? "" : m[1];
            String href = m.length > 2 ? m[2] : null;
            boolean link = href != null && !name.isEmpty();
            // "problem" is the band's one red line: something is installed that will not work
            boolean problem = "problem".equals(m.length > 3 ? m[3] : null);
            Color colour = problem ? Palette.PROBLEM : Palette.INK;
            int y = BAND_Y + i * BAND_PITCH;
            int x = BAND_X;

            if (!name.isEmpty()) {
                int nameWidth = font.width(name, Metrics.TEXT_ROW);
                String drawn = name;
                if (nameWidth > BAND_W) {
                    // a long error or pack name must never run into the corner badge
                    drawn = font.fit(name, BAND_W, Metrics.TEXT_ROW);
                    nameWidth = font.width(drawn, Metrics.TEXT_ROW);
                }
                font.draw(g, drawn, x, y, Metrics.TEXT_ROW, link ? Palette.LINK : colour);
                if (link) {
                    // under the 16px cell, as wide as the name: an underline the masked glyphs cannot draw
                    g.setColor(Palette.LINK);
                    g.fillRect(x, y + 16, nameWidth, 1);
                    linkHits.add(new Rectangle(x, y, nameWidth, 16));
                    linkHrefs.add(href);
                }
                x += nameWidth + Metrics.SPACE * Metrics.TEXT_ROW;
            }
            if (!detail.isEmpty() && x < BAND_X + BAND_W) {
                font.draw(g, font.fit(detail, BAND_X + BAND_W - x, Metrics.TEXT_ROW), x, y,
                        Metrics.TEXT_ROW, colour);
            }
        }
    }

    /** What the band shows, in order, with the "+N more" line the web build's error list adds. */
    private List<String[]> bandLines() {
        List<String[]> lines = new ArrayList<String[]>();
        if (!updateVersion.isEmpty() && !complete) {
            // the release the hand-over came for: say what it is before anything else
            lines.addAll(wrap(new String[]{"", "update " + updateVersion + " available", null}));
        }
        if (failed) {
            for (String[] m : messages) lines.addAll(wrap(m));
        }
        for (String[] w : warnings) lines.addAll(wrap(w));
        if (!failed) {
            if (!warnings.isEmpty() && messages.size() > 1) {
                // with a prerequisite on screen, the results shrink to one line rather than hiding
                // behind "+N more": the warning is the point, but "installed" still has to show
                String first = messages.get(0)[0];
                lines.add(new String[]{"", first + " + " + (messages.size() - 1) + " more installed",
                        null});
            } else {
                lines.addAll(messages);
            }
        }

        if (lines.size() > BAND_MAX) {
            int hidden = lines.size() - (BAND_MAX - 1);
            lines = new ArrayList<String[]>(lines.subList(0, BAND_MAX - 1));
            lines.add(new String[]{"", "+" + hidden + " more", null});
        }
        return lines;
    }

    /**
     * A failure line, split into as many band lines as its detail needs. The band holds about 40
     * glyphs at 2x, and the reason is the point of a failed run - cutting it with ".." hides the
     * version or the HTTP status that says what to fix ("that Eclipse zip is not the supported
     * version (e89e6c6)" lost exactly that). Words stay whole unless one alone is wider than a line.
     */
    private List<String[]> wrap(String[] m) {
        String name = m[0] == null ? "" : m[0];
        String detail = m[1] == null ? "" : m[1];
        String href = m.length > 2 ? m[2] : null;
        String tone = m.length > 3 ? m[3] : null;

        int room = BAND_W;
        if (!name.isEmpty()) {
            room -= font.width(name, Metrics.TEXT_ROW) + Metrics.SPACE * Metrics.TEXT_ROW;
        }
        if (room < Metrics.TEXT_ROW * 8) room = BAND_W;

        List<String[]> out = new ArrayList<String[]>();
        String rest = detail;
        boolean first = true;
        while (true) {
            String chunk = head(rest, room);
            out.add(new String[]{first ? name : "", chunk, first ? href : null, tone});
            first = false;
            rest = rest.substring(chunk.length()).trim();
            if (rest.isEmpty() || out.size() >= 8) break;
            room = BAND_W;
        }
        return out;
    }

    /** Longest prefix of {@code text} that fits {@code width}, cut at a space where there is one. */
    private String head(String text, int width) {
        if (font.width(text, Metrics.TEXT_ROW) <= width) return text;
        int cut = text.length();
        while (cut > 0 && font.width(text.substring(0, cut), Metrics.TEXT_ROW) > width) cut--;
        if (cut == 0) return text.substring(0, 1);
        int space = text.lastIndexOf(' ', cut);
        if (space > 0) cut = space;
        return text.substring(0, cut);
    }

    /**
     * The floating hint above a hovered tile: panel fill, one dark border, one 2x run. It sits above the
     * control, or below when there is no room, and is clamped inside the canvas - exactly the web
     * build's tooltip.js, so both stacks show the same box.
     */
    private void hint(Graphics2D g) {
        if (hintTile < 0) return;
        String text = TILE_HINTS[hintTile];

        int w = font.width(text, HINT_SCALE) + HINT_PAD_X * 2 + 2;
        int h = HINT_HEIGHT;
        int top = TILE_Y - h - HINT_GAP;
        if (top < 2) top = TILE_Y + TILE_SIZE + HINT_GAP;
        int left = TILE_X[hintTile] + (int) Math.round((TILE_SIZE - w) / 2.0);
        left = Math.max(2, Math.min(Metrics.CANVAS_W - w - 2, left));

        g.setColor(Palette.PANEL);
        g.fillRect(left, top, w, h);
        g.setColor(Palette.BEVEL_DARK);
        g.fillRect(left, top, w, 1);
        g.fillRect(left, top + h - 1, w, 1);
        g.fillRect(left, top, 1, h);
        g.fillRect(left + w - 1, top, 1, h);

        font.draw(g, text, left + 1 + HINT_PAD_X, top + 1 + HINT_PAD_Y, HINT_SCALE, Palette.INK);
    }

    // ---- mouse --------------------------------------------------------------

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point p = unit(e);
                if (!busy && ACTION.contains(p)) {
                    pressedAction = true;
                    repaint();
                } else if (!busy && CLOSE.contains(p)) {
                    pressedClose = true;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point p = unit(e);
                if (pressedAction) {
                    pressedAction = false;
                    repaint();
                    if (!busy && ACTION.contains(p)) onAction.run();
                } else if (pressedClose) {
                    pressedClose = false;
                    repaint();
                    if (!busy && CLOSE.contains(p)) onClose.run();
                } else {
                    String href = linkAt(p);
                    if (href != null) {
                        onLink.open(href);
                    } else {
                        tileClick(p);
                    }
                }
            }
        };
        addMouseListener(adapter);

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Point p = unit(e);
                setHint(tileAt(p));
                boolean hot = (!busy && ACTION.contains(p)) || (!busy && CLOSE.contains(p))
                        || linkAt(p) != null
                        || (tileAt(p) >= 0 && TILE_URLS[tileAt(p)] != null);
                setCursor(Cursor.getPredefinedCursor(
                        hot ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHint(-1);
                setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseMotionListener(hover);
    }

    /** The hint follows the pointer; repaint only when it actually changes. */
    private void setHint(int tile) {
        if (tile == hintTile) return;
        hintTile = tile;
        repaint();
    }

    private Point unit(MouseEvent e) {
        return new Point(e.getX() / scale, e.getY() / scale);
    }

    private int tileAt(Point p) {
        for (int i = 0; i < TILE_X.length; i++) {
            if (p.x >= TILE_X[i] && p.x < TILE_X[i] + TILE_SIZE
                    && p.y >= TILE_Y && p.y < TILE_Y + TILE_SIZE) return i;
        }
        return -1;
    }

    private void tileClick(Point p) {
        int i = tileAt(p);
        if (i < 0) return;
        if (TILE_URLS[i] != null) {
            onLink.open(TILE_URLS[i]);
        } else {
            // duck mode, the web build's quack.mp3 as PCM; Sound falls back to the old beep
            Sound.play(QUACK);
        }
    }

    /** The target under {@code p} when it sits on a linked band name, else null. */
    private String linkAt(Point p) {
        for (int i = 0; i < linkHits.size(); i++) {
            if (linkHits.get(i).contains(p)) return linkHrefs.get(i);
        }
        return null;
    }

    // ---- offscreen render ---------------------------------------------------

    /** The window as an image, for the frame harness. */
    public BufferedImage render() {
        BufferedImage image = new BufferedImage(Metrics.CANVAS_W * scale, Metrics.CANVAS_H * scale,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        paintComponent(g);
        g.dispose();
        return image;
    }

    public void savePreview(File file) throws IOException {
        ImageIO.write(render(), "png", file);
    }
}
