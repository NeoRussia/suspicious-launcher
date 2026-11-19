package suspiciousspeculars;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;
import suspiciousspeculars.patch.Patcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The installer prompt: what is installed, what can be installed, and one action.
 *
 * Drawn with bevels and the vanilla font rather than GT sprites, so it carries no third-party
 * artwork. It is reachable two ways - a command, and by itself on the main menu when no Eclipse pack
 * is installed yet or a pack update is waiting - because a pack file cannot configure a launcher
 * pre-launch command, so this screen is the only thing that can tell the player what is missing.
 */
public final class GuiInstaller extends GuiScreen {

    private static final int GREY = 0xC6C6C6;
    private static final int GREY_DARK = 0x8B8B8B;
    private static final int LIGHT = 0xFFFFFF;
    private static final int SHADOW = 0x6F6F6F;
    private static final int INK = 0x3F3F3F;

    private final SuspiciousSpeculars mod;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private String statusLine = "";

    public GuiInstaller(SuspiciousSpeculars mod) {
        this.mod = mod;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        List<String> lines = bodyLines();
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, fontRenderer.getStringWidth(line));
        widest = Math.max(widest, fontRenderer.getStringWidth("Suspicious Speculars"));

        int panelWidth = Math.min(width - 40, Math.max(300, widest + 24));
        int panelHeight = Math.max(120, 30 + lines.size() * 11 + 14 + (statusLine.isEmpty() ? 0 : 16));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2 - 14;
        right = left + panelWidth;
        bottom = top + panelHeight;

        int cx = (left + right) / 2;
        String action = mod.needsSetup() ? "Fetch Eclipse" : "Quit and install";
        buttonList.add(new GuiButton(0, cx - 102, bottom + 12, 100, 20, action));
        buttonList.add(new GuiButton(1, cx + 2, bottom + 12, 100, 20, "Close"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0 && mod.needsSetup()) {
            mod.downloadEclipse();
            statusLine = "Fetching Eclipse, about 45 MB - progress goes to the log.";
            button.enabled = false;
            initGui();
        } else if (button.id == 0) {
            // the game holds these files open; the next start installs them before it loads
            mod.requestInstallAndQuit();
        } else if (button.id == 1) {
            if (mod.updatePendingFromState() && !mod.needsSetup()) mod.dismissUpdate();
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        panel(left, top, right, bottom, GREY);

        int x = left + 12;
        int y = top + 12;
        drawString(fontRenderer, "Suspicious Speculars", x, y, INK);
        y += 16;
        for (String line : bodyLines()) {
            drawString(fontRenderer, line, x, y, INK);
            y += 11;
        }
        if (!statusLine.isEmpty()) {
            y += 4;
            drawString(fontRenderer, statusLine, x, y, 0x8B5A00);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Only what is true for the current state: never "up to date" while there is nothing installed. */
    private List<String> bodyLines() {
        List<String> lines = new ArrayList<String>();
        lines.add("Version " + SuspiciousSpeculars.VERSION
                + "    installed packs: " + currentVersion());
        if (mod.needsSetup()) {
            lines.add("No Eclipse shader pack found in shaderpacks/.");
            lines.add("Fetch it, or drop Eclipse-e89e6c6.zip in there yourself.");
            lines.add("");
            lines.add("Demonica " + demonicaState() + "    Cleanroom " + cleanroomState());
            return lines;
        }
        String installed = currentVersion();
        String offered = mod.versionFromState();
        if (installed.equals(offered)) {
            lines.add("Installed packs differ from the published " + offered + " (repair).");
        } else {
            lines.add("The repository offers: " + offered);
        }
        String detail = mod.pendingUpdateDetail();
        if (detail != null && !detail.isEmpty()) {
            for (String part : detail.split(" \\| ")) {
                if (!part.trim().isEmpty()) lines.add("- " + part.trim());
            }
        } else {
            lines.add("Installed packs match the repository.");
        }
        return lines;
    }

    private String currentVersion() {
        String v = mod.state().getProperty("packVersion", "");
        return v == null || v.isEmpty() ? "unknown (not recorded)" : v;
    }

    private String demonicaState() {
        String v = mod.freshDetection().demonica();
        return v == null || v.isEmpty() ? "missing" : v;
    }

    private String cleanroomState() {
        String v = mod.freshDetection().cleanroom();
        return v == null || v.isEmpty() ? "missing" : v;
    }

    /** A raised GT-style panel: grey face, light top-left edge, shadow bottom-right. */
    private void panel(int x0, int y0, int x1, int y1, int face) {
        drawRect(x0, y0, x1, y1, 0xFF000000 | face);
        drawRect(x0, y0, x1, y0 + 2, 0xFF000000 | LIGHT);
        drawRect(x0, y0, x0 + 2, y1, 0xFF000000 | LIGHT);
        drawRect(x0, y1 - 2, x1, y1, 0xFF000000 | SHADOW);
        drawRect(x1 - 2, y0, x1, y1, 0xFF000000 | SHADOW);
        drawRect(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0xFF000000 | GREY_DARK);
        drawRect(x0 + 4, y0 + 4, x1 - 4, y1 - 4, 0xFF000000 | face);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
