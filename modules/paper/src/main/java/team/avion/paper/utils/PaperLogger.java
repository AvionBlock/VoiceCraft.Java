package team.avion.paper.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import team.avion.common.utils.BaseLogger;
import team.avion.paper.VoiceCraftJava;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class PaperLogger extends BaseLogger {

    @Override
    protected String language() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin == null ? null : plugin.getLanguage();
    }

    public void log(Component msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Component coloredLogo = Component.text("[")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("VoiceCraft.Java")
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("] ")
                        .color(NamedTextColor.WHITE)
                        .decorate(TextDecoration.BOLD))
                .append(msg);

        console.sendMessage(coloredLogo);
    }

    public void info(String msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Component coloredLogo = Component.text("[")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("VoiceCraft.Java")
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("] ")
                        .color(NamedTextColor.WHITE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(msg).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD));

        console.sendMessage(coloredLogo);
    }

    public void warn(String msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Component coloredLogo = Component.text("[")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("VoiceCraft.Java")
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("] ")
                        .color(NamedTextColor.WHITE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(msg).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));

        console.sendMessage(coloredLogo);
    }

    public void error(String msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Component coloredLogo = Component.text("[")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("VoiceCraft.Java")
                        .color(NamedTextColor.LIGHT_PURPLE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("] ")
                        .color(NamedTextColor.WHITE)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(msg).color(NamedTextColor.RED).decorate(TextDecoration.BOLD));

        console.sendMessage(coloredLogo);
    }

    public void debug(String msg) {
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Boolean isDebug = VoiceCraftJava.getInstance().getConfig().getBoolean("config.debug");
        if (isDebug) {
            Component coloredLogo = Component.text("[")
                    .color(NamedTextColor.WHITE)
                    .decorate(TextDecoration.BOLD)
                    .append(Component.text("VoiceCraft.Java")
                            .color(NamedTextColor.LIGHT_PURPLE)
                            .decorate(TextDecoration.BOLD))
                    .append(Component.text("] ")
                            .color(NamedTextColor.WHITE)
                            .decorate(TextDecoration.BOLD))
                    .append(Component.text(msg).color(NamedTextColor.BLUE).decorate(TextDecoration.BOLD));

            console.sendMessage(coloredLogo);
        }
    }
}
