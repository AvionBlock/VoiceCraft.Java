package team.avion.fabric.tasks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import team.avion.common.localization.Language;
import team.avion.fabric.VoiceCraftJava;
import team.avion.proxy.ProxyPlayerSnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricPositionsTask {
    private final VoiceCraftJava plugin;
    private final AtomicBoolean networkOperationRunning = new AtomicBoolean(false);
    private int ticksUntilUpdate;
    private boolean reconnecting;
    private int reconnectRetries;

    public FabricPositionsTask(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void tick(MinecraftServer server) {
        int interval = Math.max(1, plugin.getConfig().getInt("config.voice.position-update-interval-ticks", 5));
        if (++ticksUntilUpdate < interval) {
            return;
        }
        ticksUntilUpdate = 0;

        if (reconnecting) {
            scheduleReconnect();
            return;
        }
        if (!plugin.isConnected()) {
            return;
        }

        List<ProxyPlayerSnapshot> snapshots = server.getPlayerManager().getPlayerList().stream()
                .map(this::getPlayerSnapshot)
                .toList();
        scheduleTick(snapshots);
    }

    private ProxyPlayerSnapshot getPlayerSnapshot(ServerPlayerEntity player) {
        return new ProxyPlayerSnapshot(
                player.getUuid().toString(),
                player.getName().getString(),
                player.getWorld().getRegistryKey().getValue().toString(),
                player.getX(),
                player.getEyeY(),
                player.getZ(),
                player.getYaw(),
                0.0,
                player.isSubmergedInWater(),
                player.isDead());
    }

    private void scheduleTick(List<ProxyPlayerSnapshot> snapshots) {
        if (!networkOperationRunning.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> tickSafely(snapshots)).whenComplete((connected, throwable) -> {
            networkOperationRunning.set(false);
            if ((throwable != null || !connected) && plugin.isConnected()) {
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
