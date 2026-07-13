package team.avion.velocity.utils;

import com.velocitypowered.api.proxy.ConsoleCommandSource;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import team.avion.common.utils.BaseLogger;
import team.avion.velocity.VoiceCraftJava;

public class VelocityLogger extends BaseLogger {
    @Override
    protected String language() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin == null ? null : plugin.getLanguage();
    }

    @Override
    public void log(Component msg) {
        console().sendMessage(prefix().append(msg));
    }

    @Override
    public void info(String msg) {
        console().sendMessage(prefix().append(Component.text(msg).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD)));
    }

    @Override
    public void warn(String msg) {
        console().sendMessage(prefix().append(Component.text(msg).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)));
    }

    @Override
    public void error(String msg) {
        console().sendMessage(prefix().append(Component.text(msg).color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));
    }

    @Override
    public void debug(String msg) {
        if (VoiceCraftJava.getConfig().getBoolean("config.debug", false)) {
            console().sendMessage(prefix().append(Component.text(msg).color(NamedTextColor.BLUE).decorate(TextDecoration.BOLD)));
        }
    }

    private ConsoleCommandSource console() {
        return VoiceCraftJava.getInstance().getProxy().getConsoleCommandSource();
    }

    private Component prefix() {
        return Component.text("[").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                .append(Component.text("VoiceCraft.Java").color(NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
                .append(Component.text("] ").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
    }
}
