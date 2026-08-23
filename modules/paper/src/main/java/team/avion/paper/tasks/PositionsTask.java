package team.avion.paper.tasks;

import org.bukkit.scheduler.BukkitRunnable;

import team.avion.paper.VoiceCraftJava;
import team.avion.common.localization.Language;
import team.avion.proxy.ProxyPlayerSnapshot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

public class PositionsTask extends BukkitRunnable {
    private static final Set<Material> CAVE_MATERIALS = Set.of(
            Material.STONE, Material.DIORITE, Material.GRANITE, Material.DEEPSLATE, Material.TUFF);

    private final VoiceCraftJava plugin;
    private final String lang;
    private final AtomicBoolean networkOperationRunning = new AtomicBoolean(false);
    private final Map<UUID, CaveSample> caveSamples = new HashMap<>();
    private boolean isConnected = false;
    private int reconnectRetries = 0;
    private boolean reconnecting = false;
    private int caveScansRemaining;

    public PositionsTask(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void run() {
        List<Player> players = List.copyOf(plugin.getServer().getOnlinePlayers());
        caveSamples.keySet().removeIf(playerId -> players.stream().noneMatch(player -> player.getUniqueId().equals(playerId)));
        caveScansRemaining = Math.max(1,
                plugin.getConfig().getInt("config.voice.cave-scans-per-update", 2));

        if (plugin.usesProxy) {
            isConnected = plugin.isConnected();
            if (!isConnected) {
                return;
            }

            List<ProxyPlayerSnapshot> snapshots = players.stream().map(this::getPlayerSnapshot).toList();
            for (int index = 0; index < players.size(); index++) {
                plugin.getMessageHandler().sendSnapshot(players.get(index), snapshots.get(index));
            }
            if (plugin.getSessionManager().isConnected()) {
                scheduleTick(snapshots);
            }
            return;
        }

        if (reconnecting) {
            scheduleReconnect();
            return;
        }

        isConnected = plugin.isConnected();
        if (!isConnected) {
            return;
        }

        plugin.getVoiceEndpointRegistry().retryPending();

        List<ProxyPlayerSnapshot> snapshots = players.stream()
                .map(this::getPlayerSnapshot)
                .toList();
        scheduleTick(snapshots);
    }

    private void scheduleTick(List<ProxyPlayerSnapshot> snapshots) {
        if (!networkOperationRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean connected = tickSafely(snapshots);
                runPluginTask(() -> {
                    networkOperationRunning.set(false);
                    if (!connected && plugin.isConnected() && !plugin.usesProxy) {
                        handleConnectionLost();
                    }
                });
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException exception) {
            networkOperationRunning.set(false);
        }
    }

    private void handleConnectionLost() {
        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-lost"));
        plugin.setNotConnected();
        isConnected = false;

        if (plugin.getConfig().getBoolean("config.auto-reconnect")) {
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-lost-reconnect"))
                        .color(NamedTextColor.RED));
            }
            reconnectRetries = 0;
            reconnecting = true;
            scheduleReconnect();
        } else {
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-lost"))
                        .color(NamedTextColor.RED));
            }
            cancel();
        }
    }

    public double getCaveDensity(Player player) {
        if (!isConnected) {
            return 0.0;
        }

        long now = System.currentTimeMillis();
        CaveSample cached = caveSamples.get(player.getUniqueId());
        if (cached != null && now < cached.refreshAfterMillis()) {
            return cached.density();
        }
        if (caveScansRemaining <= 0) {
            return cached == null ? 0.0 : cached.density();
        }
        caveScansRemaining--;

        int blockCount = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue; // a vector of 0,0,0 won't go anywhere, so skip it...
                    Vector direction = new Vector(x, y, z);
                    blockCount += castRayUntilBlock(
                            new BlockIterator(player.getWorld(), player.getEyeLocation().toVector(), direction, 0, 50));
                }
            }
        }

        // (3 * 3 * 3) - 1 = 26.0
        double density = blockCount / 26.0;
        long refreshInterval = Math.max(250L,
                plugin.getConfig().getLong("config.voice.cave-scan-interval-ms", 1000L));
        caveSamples.put(player.getUniqueId(), new CaveSample(density, now + refreshInterval));
        return density;
    }

    private int castRayUntilBlock(BlockIterator blockIterator) {
        while (blockIterator.hasNext()) {
            Block block = blockIterator.next();
            if (block.getType().isSolid()) {
                if (CAVE_MATERIALS.contains(block.getType())) {
                    return 1;
                }
                break;
            }
        }
        return 0;
    }

    public ProxyPlayerSnapshot getPlayerSnapshot(Player player) {
        Location headLocation = player.getEyeLocation();
        return new ProxyPlayerSnapshot(
                player.getUniqueId().toString(),
                player.getName(),
                getDimensionId(player),
                headLocation.getX(),
                headLocation.getY(),
                headLocation.getZ(),
                player.getLocation().getYaw(),
                player.getWorld().getEnvironment() == World.Environment.NORMAL ? getCaveDensity(player) : 0.0,
                player.isInWater(),
                player.isDead());
    }

    private String getDimensionId(Player player) {
        String worldName = player.getWorld().getName();
        return switch (worldName) {
            case "world" -> "minecraft:overworld";
            case "world_nether" -> "minecraft:nether";
            case "world_the_end" -> "minecraft:the_end";
            default -> worldName;
        };
    }

    private void scheduleReconnect() {
        if (!networkOperationRunning.compareAndSet(false, true)) {
            return;
        }
        if (reconnectRetries >= 5) {
            networkOperationRunning.set(false);
            reconnecting = false;
            plugin.Logger.error(Language.getMessage(lang, "plugin-connection-reconnecting-failed"));
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-reconnecting-failed"))
                        .color(NamedTextColor.RED));
            }
            cancel();
            return;
        }

        reconnectRetries++;
        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-attempt").replace("$attempt",
                Integer.toString(reconnectRetries)));

        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean connected = reconnectSafely();
                runPluginTask(() -> {
                    networkOperationRunning.set(false);
                    if (connected) {
                        reconnecting = false;
                        isConnected = true;
                        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-success"));
                        if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                            Bukkit.broadcast(Component.text(Language.getMessage(lang,
                                    "plugin-connection-reconnecting-success")).color(NamedTextColor.GREEN));
                        }
                    } else {
                        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-failed-retry"));
                    }
                });
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException exception) {
            networkOperationRunning.set(false);
        }
    }

    private void runPluginTask(Runnable task) {
        if (!plugin.isEnabled()) {
            networkOperationRunning.set(false);
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            networkOperationRunning.set(false);
        }
    }

    private boolean tickSafely(List<ProxyPlayerSnapshot> snapshots) {
        try {
            return plugin.getSessionManager().tick(snapshots);
        } catch (Exception exception) {
            plugin.Logger.warnLocalized("session.position-update-failed", "$error", exception.getMessage());
            return false;
        }
    }

    private boolean reconnectSafely() {
        try {
            return plugin.reconnect(true);
        } catch (Exception exception) {
            plugin.Logger.warnLocalized("session.reconnect-failed", "$error", exception.getMessage());
            return false;
        }
    }

    private record CaveSample(double density, long refreshAfterMillis) {
    }
}
