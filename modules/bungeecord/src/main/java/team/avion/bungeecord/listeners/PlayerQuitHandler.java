package team.avion.bungeecord.listeners;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import team.avion.bungeecord.VoiceCraftJava;
import team.avion.common.localization.Language;

public class PlayerQuitHandler implements Listener {
    private final VoiceCraftJava plugin;
    private final String lang;

    public PlayerQuitHandler(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isBound = plugin.getPlayerBinds().getOrDefault(player.getName(), false);

        plugin.getSessionManager().removePlayerSnapshot(player.getUniqueId().toString());
        if (plugin.isConnected() && isBound) {
            handlePlayerDisconnect(player);
        }
    }

    private void handlePlayerDisconnect(ProxiedPlayer player) {
        boolean isDisconnected = plugin.disconnectPlayer(player);
        String disconnectMessage = Language.getMessage(lang, "player-disconnect-success").replace("$player", player.getName());

        if (isDisconnected) {
            plugin.Logger.info(disconnectMessage);
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-disconnect-message", true)) {
                plugin.getProxy().broadcast(new ComponentBuilder(disconnectMessage).color(ChatColor.YELLOW).create());
            }
        } else {
            plugin.Logger.error(Language.getMessage(lang, "player-disconnect-failed").replace("$player", player.getName()));
            plugin.getPlayerBinds().remove(player.getName());
        }
    }
}
