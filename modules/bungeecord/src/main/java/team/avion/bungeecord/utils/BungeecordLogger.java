package team.avion.bungeecord.utils;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import team.avion.bungeecord.VoiceCraftJava;
import team.avion.common.utils.BaseLogger;

public class BungeecordLogger extends BaseLogger {
    @Override
    protected String language() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin == null ? null : plugin.getLanguage();
    }

    @Override
    public void info(String msg) {
        console().sendMessage(prefix().append(msg).color(ChatColor.WHITE).bold(true).create());
    }

    @Override
    public void warn(String msg) {
        console().sendMessage(prefix().append(msg).color(ChatColor.YELLOW).bold(true).create());
    }

    @Override
    public void error(String msg) {
        console().sendMessage(prefix().append(msg).color(ChatColor.RED).bold(true).create());
    }

    @Override
    public void debug(String msg) {
        if (VoiceCraftJava.getConfig().getBoolean("config.debug", false)) {
            console().sendMessage(prefix().append(msg).color(ChatColor.BLUE).bold(true).create());
        }
    }

    private CommandSender console() {
        return VoiceCraftJava.getInstance().getProxy().getConsole();
    }

    private ComponentBuilder prefix() {
        return new ComponentBuilder("[").color(ChatColor.WHITE).bold(true)
                .append("VoiceCraft.Java").color(ChatColor.LIGHT_PURPLE).bold(true)
                .append("] ").color(ChatColor.WHITE).bold(true);
    }
}
