package team.avion.paper.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import team.avion.paper.VoiceCraftJava;
import team.avion.common.localization.Language;

public class PlayerQuitHandler implements Listener {

    private final VoiceCraftJava plugin;
    private final String lang;

    public PlayerQuitHandler(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean isBound = plugin.getPlayerBinds().getOrDefault(player.getName(), false);

        if (isBound) {
            if (plugin.isConnected()) {
                handlePlayerDisconnect(player);
            } else {
                plugin.getPlayerBinds().remove(player.getName());
            }
        }
    }

    private void handlePlayerDisconnect(Player player) {
        boolean isDisconnected = plugin.disconnectPlayer(player);

        String playerName = player.getName();
        String disconnectMessage = Language.getMessage(lang, "player-disconnect-success").replace("$player",
                playerName);

        if (isDisconnected) {
            plugin.Logger.info(disconnectMessage);
        } else {
            plugin.Logger.error(Language.getMessage(lang, "player-disconnect-failed").replace("$player", playerName));
            plugin.getPlayerBinds().remove(playerName);
        }
    }
}
