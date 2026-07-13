package team.avion.paper.listeners;

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
            plugin.Logger.debugLocalized("proxy-message.snapshot-send-failed", "$error", exception.getMessage());
            return false;
        }
    }

    public boolean sendBindRequest(Player player, int bindingKey) {
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeBindRequest(player.getUniqueId().toString(), player.getName(), bindingKey));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debugLocalized("proxy-message.bind-send-failed", "$error", exception.getMessage());
            return false;
        }
    }

    public boolean sendUnbindRequest(Player player) {
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeUnbindRequest(player.getUniqueId().toString(), player.getName()));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debugLocalized("proxy-message.unbind-send-failed", "$error", exception.getMessage());
            return false;
        }
    }

    public boolean sendUdpRouteRegister(Player player, String adapterId, String routeKey, int backendPort) {
        if (!plugin.usesProxy || player == null || !player.isOnline()) {
            return false;
        }
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeUdpRouteRegister(adapterId, routeKey, backendPort));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debugLocalized("proxy-message.route-register-send-failed", "$error", exception.getMessage());
            return false;
        }
    }

    public boolean sendUdpRouteUnregister(Player player, String adapterId, String routeKey) {
        if (!plugin.usesProxy || player == null || !player.isOnline()) {
            return false;
        }
        try {
            player.sendPluginMessage(plugin, ProxyMessageCodec.CHANNEL_NAME,
                    ProxyMessageCodec.encodeUdpRouteUnregister(adapterId, routeKey));
            return true;
        } catch (Exception exception) {
            plugin.Logger.debugLocalized("proxy-message.route-unregister-send-failed", "$error", exception.getMessage());
            return false;
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!ProxyMessageCodec.CHANNEL_NAME.equals(channel) || message == null || message.length == 0) {
            return;
        }

        ProxyMessageCodec.BindSync bindSync;
        try {
            String type = ProxyMessageCodec.peekType(message);
            if (!ProxyMessageCodec.BIND_SYNC.equals(type)) {
                plugin.Logger.debugLocalized("proxy-message.unknown-type", "$type", type);
                return;
            }
            bindSync = ProxyMessageCodec.decodeBindSync(message);
        } catch (IllegalArgumentException exception) {
            plugin.Logger.warnLocalized("proxy-message.invalid", "$error", exception.getMessage());
            return;
        }

        boolean previousState = plugin.getPlayerBinds().getOrDefault(bindSync.playerName(), false);
        if (previousState == bindSync.bound()) {
            return;
        }

        plugin.getPlayerBinds().put(bindSync.playerName(), bindSync.bound());
        if (bindSync.bound()) {
            plugin.Logger.infoLocalized("voicechat.joined", "$player", bindSync.playerName());
        } else {
            plugin.Logger.infoLocalized("voicechat.left", "$player", bindSync.playerName());
        }
    }
}
