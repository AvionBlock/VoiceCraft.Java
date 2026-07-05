package team.avion.paper.tasks;

import org.bukkit.scheduler.BukkitRunnable;

import team.avion.paper.VoiceCraftJava;
import team.avion.paper.utils.Language;
import team.avion.proxy.ProxyPlayerSnapshot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.Location;

import java.util.Arrays;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

public class PositionsTask extends BukkitRunnable {
    private final VoiceCraftJava plugin;
    private final String lang;
    private boolean isConnected = false;
    private int reconnectRetries = 0;
    private boolean reconnecting = false;

    public PositionsTask(VoiceCraftJava plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    @Override
    public void run() {
        if (plugin.usesProxy) {
            isConnected = plugin.isConnected();
            if (!isConnected) {
                return;
            }

            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.getMessageHandler().sendSnapshot(player, getPlayerSnapshot(player));
            }
            return;
        }

        if (reconnecting) {
            reconnect();
            return;
        }

        isConnected = plugin.isConnected();
        List<ProxyPlayerSnapshot> snapshots = plugin.getServer().getOnlinePlayers().stream()
                .map(this::getPlayerSnapshot)
                .toList();
        if (isConnected && plugin.getSessionManager().tick(snapshots)) {
            return;
        }

        if (!isConnected) {
            return;
        }

        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-lost"));
        plugin.setNotConnected();

        if (plugin.getConfig().getBoolean("config.auto-reconnect")) {
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-lost-reconnect"))
                        .color(NamedTextColor.RED));
            }
            reconnectRetries = 0;
            reconnecting = true;
            reconnect();
            return;
        }
        if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
            Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-lost"))
                    .color(NamedTextColor.RED));
        }
        cancel();
    }

    public double getCaveDensity(Player player) {
        if (!isConnected) {
            return 0.0;
        }

        String[] caveBlocks = {
                "STONE",
                "DIORITE",
                "GRANITE",
                "DEEPSLATE",
                "TUFF"
        };

        int blockCount = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0)
                        continue; // a vector of 0,0,0 won't go anywhere, so skip it...
                    Vector direction = new Vector(x, y, z);
                    blockCount += castRayUntilBlock(
                            new BlockIterator(player.getWorld(), player.getLocation().toVector(), direction, 0, 50),
                            caveBlocks);
                }
            }
        }

        // (3 * 3 * 3) - 1 = 26.0
        return blockCount / 26.0; // Total blocks checked
    }

    private int castRayUntilBlock(BlockIterator blockIterator, String[] caveBlocks) {
        while (blockIterator.hasNext()) {
            Block block = blockIterator.next();
            if (block.getType().isSolid()) {
                if (Arrays.asList(caveBlocks).contains(getBlockType(block))) {
                    return 1;
                }
                break;
            }
        }
        return 0;
    }

    private String getBlockType(Block block) {
        return block.getType().toString();
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

    private boolean reconnect() {
        if (reconnectRetries >= 5) {
            reconnecting = false;
            plugin.Logger.error(Language.getMessage(lang, "plugin-connection-reconnecting-failed"));
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-reconnecting-failed"))
                        .color(NamedTextColor.RED));
            }
            cancel();
            return false;
        }

        reconnectRetries++;
        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-attempt").replace("$attempt",
                Integer.toString(reconnectRetries)));

        if (plugin.reconnect(true)) {
            reconnecting = false;
            plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-success"));
            if (plugin.getConfig().getBoolean("config.voice.send-connection-lost-message")) {
                Bukkit.broadcast(Component.text(Language.getMessage(lang, "plugin-connection-reconnecting-success"))
                        .color(NamedTextColor.GREEN));
            }
            return true;
        }

        plugin.Logger.warn(Language.getMessage(lang, "plugin-connection-reconnecting-failed-retry"));
        return false;
    }
}
