package team.avion.paper.voicecraft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import team.avion.adapter.VoiceActivityState;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.paper.VoiceCraftJava;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows small vanilla item icons above players that do not run a supported voice-chat mod.
 */
public final class PlayerVoiceStatusOverlay implements Listener, VoiceCraftAudioSink, VoiceCraftIdentityListener {
    private static final Set<String> MOD_ADAPTER_IDS = Set.of("plasmo", "simple-voice-chat");

    private final VoiceCraftJava plugin;
    private final Map<UUID, ItemDisplay> displaysByPlayerId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> entityIdsByPlayerId = new ConcurrentHashMap<>();
    private final Map<Integer, Long> speakingUntilByEntityId = new ConcurrentHashMap<>();
    private final Set<UUID> modVoicePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();

    private BukkitTask task;
    private boolean running;
    private boolean enabled;
    private int updateIntervalTicks;
    private long speakingHoldMillis;
    private double heightOffset;
    private Material speakingIcon;
    private Material idleIcon;
    private Material noVoiceIcon;
    private Material mutedIcon;

    public PlayerVoiceStatusOverlay(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getSessionManager().registerAudioSink(this);
        plugin.getSessionManager().registerIdentityListener(this);
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("config.voice.status-icons.enabled", true);
        updateIntervalTicks = Math.max(1, plugin.getConfig().getInt("config.voice.status-icons.update-interval-ticks", 5));
        speakingHoldMillis = Math.max(100L, plugin.getConfig().getLong("config.voice.status-icons.speaking-hold-ms", 600L));
        heightOffset = plugin.getConfig().getDouble("config.voice.status-icons.height-offset", 2.45D);
        speakingIcon = icon("config.voice.status-icons.speaking-icon", Material.NOTE_BLOCK);
        idleIcon = icon("config.voice.status-icons.idle-icon", Material.SCULK_SENSOR);
        noVoiceIcon = icon("config.voice.status-icons.no-voice-icon", Material.BARRIER);
        mutedIcon = icon("config.voice.status-icons.muted-icon", Material.GOAT_HORN);

        restartTask();
        if (!enabled) {
            removeAllDisplays();
        }
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        plugin.getSessionManager().unregisterAudioSink(this);
        plugin.getSessionManager().unregisterIdentityListener(this);
        HandlerList.unregisterAll(this);
        removeAllDisplays();
        modVoicePlayers.clear();
        mutedPlayers.clear();
        entityIdsByPlayerId.clear();
        speakingUntilByEntityId.clear();
    }

    public void setMuted(UUID playerId, boolean muted) {
        if (muted) {
            mutedPlayers.add(playerId);
        } else {
            mutedPlayers.remove(playerId);
        }
    }

    @Override
    public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        if (opusFrame != null && opusFrame.length > 0) {
            speakingUntilByEntityId.put(entityId, System.currentTimeMillis() + speakingHoldMillis);
        }
    }

    @Override
    public void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
        speakingUntilByEntityId.remove(entityId);
    }

    @Override
    public void receiveEntityVoiceActivity(int entityId, VoiceActivityState state) {
        if (state == VoiceActivityState.END) {
            speakingUntilByEntityId.remove(entityId);
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        entityIdsByPlayerId.put(identity.playerId(), identity.entityId());
        if (MOD_ADAPTER_IDS.contains(identity.adapterId())) {
            modVoicePlayers.add(identity.playerId());
            removeDisplay(identity.playerId());
        }
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        entityIdsByPlayerId.remove(identity.playerId(), identity.entityId());
        speakingUntilByEntityId.remove(identity.entityId());
        if (MOD_ADAPTER_IDS.contains(identity.adapterId())) {
            modVoicePlayers.remove(identity.playerId());
        }
        removeDisplay(identity.playerId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeDisplay(playerId);
        modVoicePlayers.remove(playerId);
        mutedPlayers.remove(playerId);
        Integer entityId = entityIdsByPlayerId.remove(playerId);
        if (entityId != null) {
            speakingUntilByEntityId.remove(entityId);
        }
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled || !plugin.isEnabled()) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, updateIntervalTicks);
    }

    private void tick() {
        if (!enabled) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updatePlayer(player);
        }
        displaysByPlayerId.keySet().removeIf(playerId -> {
            if (plugin.getServer().getPlayer(playerId) != null) {
                return false;
            }
            removeDisplay(playerId);
            return true;
        });
    }

    private void updatePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        if (modVoicePlayers.contains(playerId)) {
            removeDisplay(playerId);
            return;
        }

        Material icon = iconFor(player);
        ItemDisplay display = displaysByPlayerId.computeIfAbsent(playerId, ignored -> spawnDisplay(player));
        if (display.isDead()) {
            display = spawnDisplay(player);
            displaysByPlayerId.put(playerId, display);
        }
        ItemStack current = display.getItemStack();
        if (current == null || current.getType() != icon) {
            display.setItemStack(new ItemStack(icon));
        }
        display.teleport(iconLocation(player));
    }

    private Material iconFor(Player player) {
        if (!plugin.getPlayerBinds().getOrDefault(player.getName(), false)) {
            return noVoiceIcon;
        }
        if (mutedPlayers.contains(player.getUniqueId())) {
            return mutedIcon;
        }
        return isSpeaking(player) ? speakingIcon : idleIcon;
    }

    private boolean isSpeaking(Player player) {
        int entityId = entityIdFor(player);
        Long speakingUntil = speakingUntilByEntityId.get(entityId);
        if (speakingUntil == null) {
            return false;
        }
        if (speakingUntil < System.currentTimeMillis()) {
            speakingUntilByEntityId.remove(entityId);
            return false;
        }
        return true;
    }

    private int entityIdFor(Player player) {
        Integer knownEntityId = entityIdsByPlayerId.get(player.getUniqueId());
        if (knownEntityId != null) {
            return knownEntityId;
        }
        OptionalInt boundEntityId = plugin.getSessionManager().getBoundEntityId(player.getName());
        if (boundEntityId.isPresent()) {
            entityIdsByPlayerId.put(player.getUniqueId(), boundEntityId.getAsInt());
            return boundEntityId.getAsInt();
        }
        return -1;
    }

    private ItemDisplay spawnDisplay(Player player) {
        ItemDisplay display = (ItemDisplay) player.getWorld().spawnEntity(iconLocation(player), EntityType.ITEM_DISPLAY);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setViewRange(32.0F);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.setTransformation(new org.bukkit.util.Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(0.45F, 0.45F, 0.45F),
                new Quaternionf()
        ));
        return display;
    }

    private Location iconLocation(Player player) {
        Location location = player.getLocation().clone();
        location.setY(location.getY() + heightOffset);
        return location;
    }

    private Material icon(String path, Material fallback) {
        String configured = plugin.getConfig().getString(path, fallback.name());
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(configured);
        return material == null || material.isAir() ? fallback : material;
    }

    private void removeDisplay(UUID playerId) {
        ItemDisplay display = displaysByPlayerId.remove(playerId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    private void removeAllDisplays() {
        displaysByPlayerId.keySet().forEach(this::removeDisplay);
        displaysByPlayerId.clear();
    }
}
