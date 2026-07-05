package team.avion.bungeecord.tasks;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import team.avion.bungeecord.VoiceCraftJava;
import team.avion.bungeecord.utils.Language;

public class PositionsTask implements Runnable {
    private final VoiceCraftJava plugin;
    private final String lang;
    private int reconnectRetries = 0;
    private boolean reconnecting = false;

    public PositionsTask(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void run() {
        if (reconnecting) {
            reconnect();
            return;
        }

        if (plugin.isConnected() && plugin.getSessionManager().tick()) {
            return;
        }

        if (!plugin.isConnected()) {
            return;
        }

        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-lost"));
        plugin.setNotConnected();

        if (VoiceCraftJava.getConfig().getBoolean("config.auto-reconnect", true)) {
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().broadcast(new ComponentBuilder(Language.getMessage(lang, "plugin-connection-lost-reconnect")).color(ChatColor.RED).create());
            }
            reconnectRetries = 0;
            reconnecting = true;
            reconnect();
            return;
        }

        if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
            plugin.getProxy().broadcast(new ComponentBuilder(Language.getMessage(lang, "plugin-connection-lost")).color(ChatColor.RED).create());
        }
        plugin.getTaskRunner().cancel();
    }

    private void reconnect() {
        if (reconnectRetries >= 5) {
            reconnecting = false;
            plugin.Logger.error(Language.getMessage(lang, "plugin-connection-reconnecting-failed"));
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().broadcast(new ComponentBuilder(Language.getMessage(lang, "plugin-connection-reconnecting-failed")).color(ChatColor.RED).create());
            }
            plugin.getTaskRunner().cancel();
            return;
        }

        reconnectRetries++;
        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-attempt").replace("$attempt", Integer.toString(reconnectRetries)));

        if (plugin.reconnect(true)) {
            reconnecting = false;
            plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-success"));
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-connection-lost-message", true)) {
                plugin.getProxy().broadcast(new ComponentBuilder(Language.getMessage(lang, "plugin-connection-reconnecting-success")).color(ChatColor.GREEN).create());
            }
            return;
        }

        if (reconnectRetries < 5) {
            plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-failed-retry"));
        }
    }
}
