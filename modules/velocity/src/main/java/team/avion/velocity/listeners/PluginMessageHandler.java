package team.avion.velocity.listeners;

import java.util.Optional;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import team.avion.proxy.ProxyMessageCodec;
import team.avion.proxy.ProxyPlayerSnapshot;
import team.avion.velocity.VoiceCraftJava;

public class PluginMessageHandler {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(ProxyMessageCodec.CHANNEL_NAME);

    private final VoiceCraftJava plugin;

    public PluginMessageHandler(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void sendPlayerBindSync(Player player) {
        sendBindSync(player.getUsername(), plugin.getPlayerBinds().getOrDefault(player.getUsername(), false));
    }

    public void sendBindSync(String playerName, boolean bound) {
        byte[] message = ProxyMessageCodec.encodeBindSync(playerName, bound);
        for (Player player : plugin.getProxy().getAllPlayers()) {
            Optional<ServerConnection> connection = player.getCurrentServer();
            if (connection.isPresent()) {
                try {
                    connection.get().sendPluginMessage(CHANNEL, message);
                } catch (Exception ignored) {
                }
            }
        }

        for (RegisteredServer server : plugin.getProxy().getAllServers()) {
            try {
                server.sendPluginMessage(CHANNEL, message);
            } catch (Exception ignored) {
            }
        }
    }

    @Subscribe
    public void onMessageReceived(PluginMessageEvent event) {
        if (event.getIdentifier() != CHANNEL) {
            return;
        }
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }

        byte[] data = event.getData();
        String type = ProxyMessageCodec.peekType(data);
        String serverName = backend.getServerInfo().getName();

        if (ProxyMessageCodec.SNAPSHOT.equals(type)) {
            ProxyPlayerSnapshot snapshot = ProxyMessageCodec.decodeSnapshot(data);
            plugin.getSessionManager().updatePlayerSnapshot(snapshot.withDimensionId(serverName + "_" + snapshot.dimensionId()));
        } else if (ProxyMessageCodec.BIND_REQUEST.equals(type)) {
            ProxyMessageCodec.BindRequest bindRequest = ProxyMessageCodec.decodeBindRequest(data);
            boolean bound = plugin.handleProxyBindRequest(bindRequest.bindingKey(), bindRequest.playerId(), bindRequest.playerName());
            sendBindSync(bindRequest.playerName(), bound);
        } else if (ProxyMessageCodec.UNBIND_REQUEST.equals(type)) {
            ProxyMessageCodec.UnbindRequest unbindRequest = ProxyMessageCodec.decodeUnbindRequest(data);
            plugin.handleProxyUnbindRequest(unbindRequest.playerId(), unbindRequest.playerName());
            sendBindSync(unbindRequest.playerName(), false);
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }
}
