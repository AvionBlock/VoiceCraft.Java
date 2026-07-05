package team.avion.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;

import team.avion.velocity.VoiceCraftJava;

public class PlayerJoinHandler {
    private final VoiceCraftJava plugin;

    public PlayerJoinHandler(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPlayerConnect(ServerPostConnectEvent event) {
        plugin.getMessageHandler().sendPlayerBindSync(event.getPlayer());
    }
}
