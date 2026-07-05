package team.avion.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import team.avion.velocity.VoiceCraftJava;
import team.avion.velocity.utils.Language;

public class PlayerQuitHandler {
    private final VoiceCraftJava plugin;
    private final String lang;

    public PlayerQuitHandler(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Subscribe
    public void onPlayerQuit(DisconnectEvent event) {
        Player player = event.getPlayer();
        boolean isBound = plugin.getPlayerBinds().getOrDefault(player.getUsername(), false);

        plugin.getSessionManager().removePlayerSnapshot(player.getUniqueId().toString());
        if (plugin.isConnected() && isBound) {
            handlePlayerDisconnect(player);
        }
    }

    private void handlePlayerDisconnect(Player player) {
        boolean isDisconnected = plugin.disconnectPlayer(player);
        String disconnectMessage = Language.getMessage(lang, "player-disconnect-success").replace("$player", player.getUsername());

        if (isDisconnected) {
            plugin.Logger.info(disconnectMessage);
            if (VoiceCraftJava.getConfig().getBoolean("config.voice.send-disconnect-message", true)) {
                plugin.getProxy().sendMessage(Component.text(disconnectMessage).color(NamedTextColor.YELLOW));
            }
        } else {
            plugin.Logger.error(Language.getMessage(lang, "player-disconnect-failed").replace("$player", player.getUsername()));
            plugin.getPlayerBinds().remove(player.getUsername());
        }
    }
}
