package team.avion.velocity.tasks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import team.avion.velocity.VoiceCraftJava;
import team.avion.velocity.utils.Language;

public class PositionsTask {
    private final VoiceCraftJava plugin;
    private final String lang;
    private int reconnectRetries = 0;
    private boolean reconnecting = false;

    public PositionsTask(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public boolean run() {
        if (reconnecting) {
            return reconnect();
        }

        if (plugin.isConnected() && plugin.getSessionManager().tick()) {
            return true;
        }

        if (!plugin.isConnected()) {
            return true;
        }

        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-lost"));
        plugin.setNotConnected();

        if (VoiceCraftJava.getConfig().getBoolean("config.auto-reconnect", true)) {
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().sendMessage(Component.text(Language.getMessage(lang, "plugin-connection-lost-reconnect")).color(NamedTextColor.RED));
            }
            reconnectRetries = 0;
            reconnecting = true;
            return reconnect();
        }

        if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
            plugin.getProxy().sendMessage(Component.text(Language.getMessage(lang, "plugin-connection-lost")).color(NamedTextColor.RED));
        }
        return false;
    }

    private boolean reconnect() {
        if (reconnectRetries >= 5) {
            reconnecting = false;
            plugin.Logger.error(Language.getMessage(lang, "plugin-connection-reconnecting-failed"));
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().sendMessage(Component.text(Language.getMessage(lang, "plugin-connection-reconnecting-failed")).color(NamedTextColor.RED));
            }
            return false;
        }

        reconnectRetries++;
        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-attempt").replace("$attempt", Integer.toString(reconnectRetries)));

        if (plugin.reconnect(true)) {
            reconnecting = false;
            plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-success"));
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().sendMessage(Component.text(Language.getMessage(lang, "plugin-connection-reconnecting-success")).color(NamedTextColor.GREEN));
            }
            return true;
        }

        if (reconnectRetries < 5) {
            plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-failed-retry"));
            return true;
        }
        return reconnect();
    }
}
