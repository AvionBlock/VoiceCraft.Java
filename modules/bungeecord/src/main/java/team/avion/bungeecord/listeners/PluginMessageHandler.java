package team.avion.bungeecord.listeners;

import java.net.InetSocketAddress;

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
            } catch (Exception exception) {
                plugin.Logger.debugLocalized("proxy-message.bind-sync-server-failed", "$server", server.getName(),
                        "$error", exception.getMessage());
            }
        }
    }

    @EventHandler
    public void onPluginMessageReceived(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getTag())) {
            return;
        }

        if (!(event.getSender() instanceof Server backend)) {
            plugin.Logger.warnLocalized("proxy-message.non-backend", "$channel", CHANNEL);
            event.setCancelled(true);
            return;
        }
        String serverName = backend.getInfo().getName();

        byte[] data = event.getData();
        try {
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
            } else if (ProxyMessageCodec.UDP_ROUTE_REGISTER.equals(type)) {
                ProxyMessageCodec.UdpRouteRegister route = ProxyMessageCodec.decodeUdpRouteRegister(data);
                if (!(backend.getInfo().getSocketAddress() instanceof InetSocketAddress backendAddress)) {
                    plugin.Logger.warnLocalized("proxy-message.route-non-ip", "$server", serverName);
                    event.setCancelled(true);
                    return;
                }
                String backendHost = backendAddress.getHostString();
                plugin.getUdpRelayManager().registerRoute(route.adapterId(), route.routeKey(), backendHost,
                        route.backendPort());
            } else if (ProxyMessageCodec.UDP_ROUTE_UNREGISTER.equals(type)) {
                ProxyMessageCodec.UdpRouteUnregister route = ProxyMessageCodec.decodeUdpRouteUnregister(data);
                plugin.getUdpRelayManager().unregisterRoute(route.adapterId(), route.routeKey());
            } else {
                plugin.Logger.debugLocalized("proxy-message.unknown-type-from", "$server", serverName, "$type", type);
            }
        } catch (IllegalArgumentException exception) {
            plugin.Logger.warnLocalized("proxy-message.invalid-from", "$server", serverName, "$error", exception.getMessage());
        }

        event.setCancelled(true);
    }
}
