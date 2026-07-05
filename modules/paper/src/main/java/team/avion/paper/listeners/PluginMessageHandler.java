package team.avion.paper.listeners;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import team.avion.paper.VoiceCraftJava;
import team.avion.proxy.ProxyMessageCodec;
import team.avion.proxy.ProxyPlayerSnapshot;

public class PluginMessageHandler implements PluginMessageListener {
    private final VoiceCraftJava plugin;

    public PluginMessageHandler(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public boolean sendSnapshot(Player player, ProxyPlayerSnapshot snapshot) {
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME, ProxyMessageCodec.encodeSnapshot(snapshot));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debug("Failed to send proxy snapshot: " + exception.getMessage());
            return false;
        }
    }

    public boolean sendBindRequest(Player player, int bindingKey) {
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeBindRequest(player.getUniqueId().toString(), player.getName(), bindingKey));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debug("Failed to send proxy bind request: " + exception.getMessage());
            return false;
        }
    }

    public boolean sendUnbindRequest(Player player) {
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeUnbindRequest(player.getUniqueId().toString(), player.getName()));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debug("Failed to send proxy unbind request: " + exception.getMessage());
            return false;
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ProxyMessageCodec.CHANNEL_NAME.equals(channel) || message == null || message.length == 0) {
            return;
        }

        String type = ProxyMessageCodec.peekType(Objects.requireNonNull(message, "message"));
        if (!ProxyMessageCodec.BIND_SYNC.equals(type)) {
            return;
        }

        ProxyMessageCodec.BindSync bindSync = ProxyMessageCodec.decodeBindSync(message);
        boolean previousState = plugin.getPlayerBinds().getOrDefault(bindSync.playerName(), false);
        if (previousState == bindSync.bound()) {
            return;
        }

        plugin.getPlayerBinds().put(bindSync.playerName(), bindSync.bound());
        if (bindSync.bound()) {
            plugin.Logger.info(bindSync.playerName() + " has joined the voicechat!");
        } else {
            plugin.Logger.info(bindSync.playerName() + " has left the voicechat!");
        }
    }
}
