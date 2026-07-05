package team.avion.bungeecord.listeners;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import team.avion.bungeecord.VoiceCraftJava;
import team.avion.proxy.ProxyMessageCodec;
import team.avion.proxy.ProxyPlayerSnapshot;

public class PluginMessageHandler implements Listener {
    public static final String CHANNEL = ProxyMessageCodec.CHANNEL_NAME;

    private final VoiceCraftJava plugin;

    public PluginMessageHandler(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void sendPlayerBindSync(ProxiedPlayer player) {
        sendBindSync(player.getName(), plugin.getPlayerBinds().getOrDefault(player.getName(), false));
    }

    public void sendBindSync(String playerName, boolean bound) {
        byte[] message = ProxyMessageCodec.encodeBindSync(playerName, bound);
        for (ServerInfo server : plugin.getProxy().getServers().values()) {
            try {
                server.sendData(CHANNEL, message, true);
            } catch (Exception ignored) {
            }
        }
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getTag())) {
            return;
        }

        String serverName;
        if (event.getSender() instanceof Server backend) {
            serverName = backend.getInfo().getName();
        } else if (event.getSender() instanceof ProxiedPlayer player) {
            serverName = player.getServer().getInfo().getName();
        } else {
            return;
        }

        byte[] data = event.getData();
        String type = ProxyMessageCodec.peekType(data);
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

        event.setCancelled(true);
    }
}
