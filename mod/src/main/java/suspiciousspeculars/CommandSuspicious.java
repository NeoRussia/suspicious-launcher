package suspiciousspeculars;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import suspiciousspeculars.patch.EclipseFetch;
import suspiciousspeculars.patch.Patcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * {@code /suspiciousspeculars status|update|install|download|toggle}
 *
 * status   what the last run did, and whether anything is waiting for a restart
 * install  run the patcher now, ignoring the recorded state
 * download fetch Eclipse e89e6c6 from the author's repository, then patch; needs the internet
 * toggle   turn the "check at start-up" behaviour on or off
 */
public final class CommandSuspicious extends CommandBase {

    private final SuspiciousSpeculars mod;

    CommandSuspicious(SuspiciousSpeculars mod) {
        this.mod = mod;
    }

    @Override
    public String getName() {
        return "suspiciousspeculars";
    }

    @Override
    public List<String> getAliases() {
        return new ArrayList<String>(Arrays.asList("susyspeculars"));
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/suspiciousspeculars <status|update|install|download|toggle>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String action = args.length == 0 ? "status" : args[0].toLowerCase();
        if (action.equals("status")) {
            status(sender);
        } else if (action.equals("install")) {
            mod.runPatcher(true, false);
            report(sender, "Patching finished.");
        } else if (action.equals("update")) {
            update(sender);
        } else if (action.equals("download")) {
            download(sender);
        } else if (action.equals("toggle")) {
            boolean next = !mod.checkOnLaunch();
            mod.setCheckOnLaunch(next);
            tell(sender, TextFormatting.GRAY, "Check at start-up is now " + (next ? "on" : "off") + ".");
        } else {
            throw new CommandException(getUsage(sender));
        }
    }

    /** Pops the installer screen when an update is waiting; the command is just the way in. */
    private void update(ICommandSender sender) {
        if (!mod.updatePendingFromState()) {
            tell(sender, TextFormatting.GRAY, "No resource pack update is waiting.");
            return;
        }
        if (!(sender instanceof net.minecraft.entity.player.EntityPlayer)
                || !sender.getEntityWorld().isRemote) {
            tell(sender, TextFormatting.GRAY, "Update " + mod.versionFromState()
                    + " is ready; install it from the computer running the game.");
            return;
        }
        net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            public void run() {
                net.minecraft.client.Minecraft.getMinecraft()
                        .displayGuiScreen(new GuiInstaller(mod));
            }
        });
    }

    private void status(ICommandSender sender) {
        Properties state = mod.state();
        tell(sender, TextFormatting.GOLD, "Suspicious Speculars " + SuspiciousSpeculars.VERSION);
        tell(sender, TextFormatting.GRAY, "state: " + or(state.getProperty("status"), "never run")
                + "   check at start-up: " + (mod.checkOnLaunch() ? "on" : "off"));
        String message = or(state.getProperty("message"), "");
        if (!message.isEmpty()) tell(sender, TextFormatting.RED, message);
        tell(sender, TextFormatting.GRAY, "base: " + or(state.getProperty("base"), "none")
                + "   demonica: " + or(state.getProperty("demonica"), "missing")
                + "   cleanroom: " + or(state.getProperty("cleanroom"), "missing"));
        String shader = state.getProperty("shader", Patcher.OUT_SHADER);
        Path out = mod.gameDir().resolve("shaderpacks").resolve(shader);
        tell(sender, TextFormatting.GRAY, shader + (Files.exists(out) ? " present" : " missing")
                + "   restart pending: " + ("true".equals(state.getProperty("pendingRestart")) ? "yes" : "no"));
    }

    /** The archive is 45 MB, so it is fetched off-thread and reported in chat when it lands. */
    private void download(final ICommandSender sender) {
        tell(sender, TextFormatting.GRAY, "Fetching Eclipse " + EclipseFetch.COMMIT
                + " (about 45 MB) from the author's repository...");
        final SuspiciousSpeculars mod = this.mod;
        final Patcher patcher = new Patcher(mod.gameDir(), mod.dataDir());
        Thread worker = new Thread("suspicious-speculars-download") {
            @Override
            public void run() {
                try {
                    EclipseFetch.fetch(mod.gameDir(), patcher.detectFile(), patcher.detectMarker(),
                            new Patcher.Log() {
                                public void line(String text) {
                                    mod.log().info(text);
                                }
                            });
                    mod.runPatcher(true, false);
                    sender.sendMessage(chat(TextFormatting.GOLD, "Eclipse fetched; " + summary()));
                } catch (Throwable t) {
                    mod.log().error("download failed", t);
                    sender.sendMessage(chat(TextFormatting.RED, "Download failed: " + t.getMessage()));
                }
            }
        };
        worker.setDaemon(true);
        worker.start();
    }

    private void report(ICommandSender sender, String lead) {
        tell(sender, TextFormatting.GOLD, lead + " " + summary());
        if (!mod.lastMessage().isEmpty()) tell(sender, TextFormatting.RED, mod.lastMessage());
    }

    private String summary() {
        if (mod.patchedThisSession()) {
            return "Restart Minecraft to apply the shader.";
        }
        if (Patcher.CURRENT.equals(mod.lastStatus())) {
            return "Already installed and current.";
        }
        if (Patcher.BASE_MISSING.equals(mod.lastStatus())) {
            return "No Eclipse pack yet - /suspiciousspeculars download fetches it.";
        }
        if (Patcher.BASE_MISMATCH.equals(mod.lastStatus())) {
            return "That Eclipse zip is not the supported version (" + EclipseFetch.COMMIT + ").";
        }
        return "state: " + mod.lastStatus();
    }

    private void tell(ICommandSender sender, TextFormatting colour, String text) {
        sender.sendMessage(chat(colour, text));
    }

    private static TextComponentString chat(TextFormatting colour, String text) {
        TextComponentString line = new TextComponentString(text);
        line.getStyle().setColor(colour);
        return line;
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
