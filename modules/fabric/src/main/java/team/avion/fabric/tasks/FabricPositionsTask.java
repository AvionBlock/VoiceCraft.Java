package team.avion.fabric.tasks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import team.avion.common.localization.Language;
import team.avion.fabric.VoiceCraftJava;
import team.avion.fabric.utils.FabricMinecraftCompat;
import team.avion.proxy.ProxyPlayerSnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricPositionsTask {
    private final VoiceCraftJava plugin;
    private final AtomicBoolean networkOperationRunning = new AtomicBoolean(false);
    private final AtomicBoolean auxiliaryReconnectRunning = new AtomicBoolean(false);
    private int ticksUntilUpdate;
    private boolean reconnecting;
    private int reconnectRetries;
    private int ticksUntilAuxiliaryReconnect;

    public FabricPositionsTask(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void tick(MinecraftServer server) {
        int interval = Math.max(1, plugin.getConfig().getInt("config.voice.position-update-interval-ticks", 5));
        if (++ticksUntilUpdate < interval) {
            return;
        }
        ticksUntilUpdate = 0;

        if (reconnecting && !plugin.usesProxy()) {
            scheduleReconnect();
            return;
        }
        if (!plugin.isConnected()) {
            return;
        }

        if (plugin.getSessionManager().isConnected()) {
            plugin.getVoiceEndpointRegistry().retryPending();
        }

        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        List<ProxyPlayerSnapshot> snapshots = players.stream().map(this::getPlayerSnapshot).toList();
        if (plugin.usesProxy()) {
            for (int i = 0; i < players.size(); i++) {
                plugin.getProxyBridge().sendSnapshot(players.get(i), snapshots.get(i));
            }
            if (!plugin.getSessionManager().isConnected()) {
                if (++ticksUntilAuxiliaryReconnect >= 40
                        && auxiliaryReconnectRunning.compareAndSet(false, true)) {
                    ticksUntilAuxiliaryReconnect = 0;
                    CompletableFuture.runAsync(() -> {
                        try {
                            if (!plugin.getSessionManager().isConnected()) {
                                plugin.getSessionManager().connect();
                            }
                        } finally {
                            auxiliaryReconnectRunning.set(false);
                        }
                    });
                }
                return;
            }
        }
        scheduleTick(plugin.usesProxy()
                ? snapshots.stream().map(snapshot -> snapshot.withDimensionId(
                        plugin.qualifyWorldId(snapshot.dimensionId()))).toList()
                : snapshots);
    }

    private ProxyPlayerSnapshot getPlayerSnapshot(ServerPlayer player) {
        return new ProxyPlayerSnapshot(
                player.getUUID().toString(),
                player.getName().getString(),
                FabricMinecraftCompat.dimensionId(player),
                player.getX(),
                player.getEyeY(),
                player.getZ(),
                player.getYRot(),
                0.0,
                player.isUnderWater(),
                player.isDeadOrDying());
    }

    private void scheduleTick(List<ProxyPlayerSnapshot> snapshots) {
        if (!networkOperationRunning.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> tickSafely(snapshots)).whenComplete((connected, throwable) -> {
            networkOperationRunning.set(false);
            if ((throwable != null || !connected) && plugin.isConnected() && !plugin.usesProxy()) {
                handleConnectionLost();
            }
        });
    }

    private void handleConnectionLost() {
        plugin.Logger.warn(Language.getMessage(plugin.getLanguage(), "plugin-connection-lost"));
        plugin.setNotConnected();
        if (plugin.getConfig().getBoolean("config.auto-reconnect", true)) {
            reconnectRetries = 0;
            reconnecting = true;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!networkOperationRunning.compareAndSet(false, true)) {
            return;
        }
        if (reconnectRetries >= 5) {
            networkOperationRunning.set(false);
            reconnecting = false;
            plugin.Logger.error(Language.getMessage(plugin.getLanguage(), "plugin-connection-reconnecting-failed"));
            return;
        }

        reconnectRetries++;
        plugin.Logger.warn(Language.getMessage(plugin.getLanguage(), "plugin-connection-reconnecting-attempt")
                .replace("$attempt", Integer.toString(reconnectRetries)));
        CompletableFuture.supplyAsync(() -> {
            try {
                return plugin.reconnect(true);
            } catch (Exception exception) {
                plugin.Logger.warnLocalized("session.reconnect-failed", "$error", exception.getMessage());
                return false;
            }
        }).whenComplete((connected, throwable) -> {
            networkOperationRunning.set(false);
            if (connected != null && connected) {
                reconnecting = false;
                plugin.Logger.warn(Language.getMessage(plugin.getLanguage(), "plugin-connection-reconnecting-success"));
            }
        });
    }

    private boolean tickSafely(List<ProxyPlayerSnapshot> snapshots) {
        try {
            return plugin.getSessionManager().tick(snapshots);
        } catch (Exception exception) {
            plugin.Logger.warnLocalized("session.position-update-failed", "$error", exception.getMessage());
            return false;
        }
    }
}
