package team.avion.paper.voicecraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import team.avion.adapter.VoiceActivityState;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.adapter.VoiceCraftPlayerState;
import team.avion.adapter.VoiceCraftPlayerStateListener;
import team.avion.paper.VoiceCraftJava;
import team.avion.paper.scheduling.PaperScheduler.Task;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a vanilla voice status hologram only between players that do not expose a supported
 * voice-chat mod channel. Armor stands are invisible by default and are explicitly shown to each
 * eligible viewer, which keeps the overlay out of modded clients and works through Geyser.
 */
public final class PlayerVoiceStatusOverlay implements Listener, VoiceCraftAudioSink,
        VoiceCraftIdentityListener, VoiceCraftPlayerStateListener {
    private static final String CONFIG_PATH = "config.voice.vanilla-status-overlay";
    private static final String OVERLAY_TAG = "voicecraft-status-overlay";

    private final VoiceCraftJava plugin;
    private final Map<UUID, Hologram> hologramsByTargetId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> entityIdsByPlayerId = new ConcurrentHashMap<>();
    private final Map<Integer, Long> speakingUntilByEntityId = new ConcurrentHashMap<>();
    private final Set<UUID> modVoicePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> capabilityKnownAfterByPlayerId = new ConcurrentHashMap<>();

    private Task task;
    private boolean running;
    private boolean enabled;
    private int updateIntervalTicks;
    private long handshakeGraceMillis;
    private long speakingHoldMillis;
    private double heightOffset;
    private double viewDistanceSquared;
    private Map<PlayerVoiceStatus, StatusStyle> styles = Map.of();

    public PlayerVoiceStatusOverlay(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        long now = System.currentTimeMillis();
        plugin.getServer().getOnlinePlayers().forEach(player ->
                capabilityKnownAfterByPlayerId.put(player.getUniqueId(), now));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getSessionManager().registerAudioSink(this);
        plugin.getSessionManager().registerIdentityListener(this);
        plugin.getSessionManager().registerPlayerStateListener(this);
        reload();
    }

    public void reload() {
        removeAllHolograms();
        enabled = plugin.getConfig().getBoolean(CONFIG_PATH + ".enabled", true);
        updateIntervalTicks = Math.max(1,
                plugin.getConfig().getInt(CONFIG_PATH + ".update-interval-ticks", 5));
        handshakeGraceMillis = Math.max(0L,
                plugin.getConfig().getLong(CONFIG_PATH + ".handshake-grace-ticks", 60L)) * 50L;
        speakingHoldMillis = Math.max(100L,
                plugin.getConfig().getLong(CONFIG_PATH + ".speaking-hold-ms", 600L));
        heightOffset = plugin.getConfig().getDouble(CONFIG_PATH + ".height-offset", 2.55D);
        double viewDistance = Math.max(4D,
                plugin.getConfig().getDouble(CONFIG_PATH + ".view-distance", 48D));
        viewDistanceSquared = viewDistance * viewDistance;
        styles = Map.of(
                PlayerVoiceStatus.DISCONNECTED, style("disconnected", "×", NamedTextColor.DARK_GRAY),
                PlayerVoiceStatus.CONNECTED, style("connected", "○", NamedTextColor.GRAY),
                PlayerVoiceStatus.SPEAKING, style("speaking", "●", NamedTextColor.GREEN),
                PlayerVoiceStatus.MUTED, style("muted", "M", NamedTextColor.RED),
                PlayerVoiceStatus.DEAFENED, style("deafened", "D", NamedTextColor.YELLOW)
        );
        restartTask();
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
        plugin.getSessionManager().unregisterPlayerStateListener(this);
        HandlerList.unregisterAll(this);
        removeAllHolograms();
        entityIdsByPlayerId.clear();
        speakingUntilByEntityId.clear();
        modVoicePlayers.clear();
        capabilityKnownAfterByPlayerId.clear();
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
        } else {
            speakingUntilByEntityId.put(entityId, System.currentTimeMillis() + speakingHoldMillis);
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        entityIdsByPlayerId.put(identity.playerId(), identity.entityId());
        if (isModAdapter(identity.adapterId())) {
            modVoicePlayers.add(identity.playerId());
        }
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        entityIdsByPlayerId.remove(identity.playerId(), identity.entityId());
        speakingUntilByEntityId.remove(identity.entityId());
        if (isModAdapter(identity.adapterId())) {
            modVoicePlayers.remove(identity.playerId());
        }
    }

    @Override
    public void playerVoiceStateUpdated(VoiceCraftPlayerState state) {
        // The main-thread update task reads the shared state. No Bukkit work is done on this callback thread.
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        capabilityKnownAfterByPlayerId.put(event.getPlayer().getUniqueId(),
                System.currentTimeMillis() + handshakeGraceMillis);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        removeHologram(playerId);
        hologramsByTargetId.values().forEach(hologram -> hologram.visibleTo().remove(playerId));
        capabilityKnownAfterByPlayerId.remove(playerId);
        modVoicePlayers.remove(playerId);
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
        if (enabled && running && plugin.isEnabled()) {
            task = plugin.getScheduler().runGlobalTimer(this::tick,
                    1L, updateIntervalTicks);
        }
    }

    private void tick() {
        if (!enabled) {
            return;
        }
        List<Player> onlinePlayers = List.copyOf(plugin.getServer().getOnlinePlayers());
        long now = System.currentTimeMillis();
        Set<UUID> onlineIds = onlinePlayers.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        for (UUID targetId : List.copyOf(hologramsByTargetId.keySet())) {
            if (!onlineIds.contains(targetId)) {
                removeHologram(targetId);
            }
        }

        for (Player target : onlinePlayers) {
            plugin.getScheduler().runAtEntity(target, () -> updateTarget(target, onlinePlayers, now));
        }
    }

    private void updateTarget(Player target, List<Player> viewers, long now) {
        capabilityKnownAfterByPlayerId.putIfAbsent(target.getUniqueId(), now + handshakeGraceMillis);
        if (!isVanillaClient(target, now)) {
            removeHologram(target.getUniqueId());
            return;
        }

        PlayerVoiceStatus status = statusFor(target, now);
        StatusStyle style = styles.get(status);
        Hologram hologram = hologramsByTargetId.get(target.getUniqueId());
        String targetWorld = target.getWorld().getKey().toString();
        if (hologram == null || !hologram.worldId().equals(targetWorld)) {
            removeHologram(target.getUniqueId());
            hologram = spawnHologram(target, status, style, targetWorld);
            hologramsByTargetId.put(target.getUniqueId(), hologram);
        }

        if (hologram.status() != status) {
            Hologram current = hologram;
            plugin.getScheduler().runAtEntity(current.armorStand(), () -> {
                current.armorStand().customName(style.component());
                current.armorStand().setCustomNameVisible(!style.symbol().isBlank());
            });
            hologram.status(status);
        }
        Location targetLocation = target.getLocation();
        Location desiredLocation = targetLocation.clone().add(0D, heightOffset, 0D);
        plugin.getScheduler().teleport(hologram.armorStand(), desiredLocation);

        for (Player viewer : viewers) {
            Hologram current = hologram;
            UUID targetId = target.getUniqueId();
            plugin.getScheduler().runAtEntity(viewer, () -> {
                boolean shouldSee = !viewer.getUniqueId().equals(targetId)
                        && isVanillaClient(viewer, now)
                        && viewer.getWorld().getKey().toString().equals(targetWorld)
                        && viewer.getLocation().distanceSquared(targetLocation) <= viewDistanceSquared;
                setVisible(viewer, current, shouldSee);
            });
        }
    }

    private Hologram spawnHologram(Player target, PlayerVoiceStatus status, StatusStyle style, String worldId) {
        ArmorStand armorStand = target.getWorld().spawn(hologramLocation(target), ArmorStand.class, stand -> {
            stand.setVisibleByDefault(false);
            stand.setVisible(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setSilent(true);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            stand.setPersistent(false);
            stand.setCanPickupItems(false);
            stand.addScoreboardTag(OVERLAY_TAG);
            stand.customName(style.component());
            stand.setCustomNameVisible(!style.symbol().isBlank());
        });
        return new Hologram(armorStand, worldId, status, ConcurrentHashMap.newKeySet());
    }

    private void setVisible(Player viewer, Hologram hologram, boolean visible) {
        UUID viewerId = viewer.getUniqueId();
        if (visible) {
            if (hologram.visibleTo().add(viewerId)) {
                viewer.showEntity(plugin, hologram.armorStand());
            }
        } else if (hologram.visibleTo().remove(viewerId)) {
            viewer.hideEntity(plugin, hologram.armorStand());
        }
    }

    private PlayerVoiceStatus statusFor(Player player, long now) {
        boolean connected = plugin.getVoiceEndpointRegistry().isConnected(player.getUniqueId())
                || plugin.getPlayerBinds().getOrDefault(player.getName(), false);
        VoiceCraftPlayerState state = plugin.getSessionManager().playerVoiceState(player.getUniqueId())
                .orElse(null);
        boolean speaking = false;
        Integer entityId = entityIdsByPlayerId.get(player.getUniqueId());
        if (entityId != null) {
            Long speakingUntil = speakingUntilByEntityId.get(entityId);
            if (speakingUntil != null && speakingUntil >= now) {
                speaking = true;
            }
            if (speakingUntil != null && speakingUntil < now) {
                speakingUntilByEntityId.remove(entityId, speakingUntil);
            }
        }
        return PlayerVoiceStatus.resolve(connected, speaking, state);
    }

    private boolean isVanillaClient(Player player, long now) {
        if (modVoicePlayers.contains(player.getUniqueId()) || hasSupportedModChannel(player)) {
            return false;
        }
        return now >= capabilityKnownAfterByPlayerId.getOrDefault(player.getUniqueId(), Long.MAX_VALUE);
    }

    private boolean hasSupportedModChannel(Player player) {
        return player.getListeningPluginChannels().stream().anyMatch(channel ->
                channel.startsWith("plasmovoice:") || channel.startsWith("voicechat:"));
    }

    private boolean isModAdapter(String adapterId) {
        return "plasmo".equals(adapterId) || "simple-voice-chat".equals(adapterId);
    }

    private Location hologramLocation(Player player) {
        return player.getLocation().clone().add(0D, heightOffset, 0D);
    }

    private StatusStyle style(String key, String fallbackSymbol, NamedTextColor fallbackColor) {
        String symbol = plugin.getConfig().getString(CONFIG_PATH + ".symbols." + key, fallbackSymbol);
        String colorName = plugin.getConfig().getString(CONFIG_PATH + ".colors." + key,
                fallbackColor.toString());
        NamedTextColor color = NamedTextColor.NAMES.value(
                colorName == null ? "" : colorName.toLowerCase(Locale.ROOT));
        if (color == null) {
            color = fallbackColor;
        }
        String safeSymbol = symbol == null ? fallbackSymbol : symbol;
        return new StatusStyle(safeSymbol, Component.text(safeSymbol, color));
    }

    private void removeHologram(UUID targetId) {
        Hologram hologram = hologramsByTargetId.remove(targetId);
        if (hologram != null) {
            plugin.getScheduler().runAtEntity(hologram.armorStand(), hologram.armorStand()::remove);
        }
    }

    private void removeAllHolograms() {
        for (UUID targetId : List.copyOf(hologramsByTargetId.keySet())) {
            removeHologram(targetId);
        }
    }

    private record StatusStyle(String symbol, Component component) {
    }

    private static final class Hologram {
        private final ArmorStand armorStand;
        private final String worldId;
        private final Set<UUID> visibleTo;
        private PlayerVoiceStatus status;

        private Hologram(ArmorStand armorStand, String worldId, PlayerVoiceStatus status, Set<UUID> visibleTo) {
            this.armorStand = armorStand;
            this.worldId = worldId;
            this.status = status;
            this.visibleTo = visibleTo;
        }

        private ArmorStand armorStand() {
            return armorStand;
        }

        private String worldId() {
            return worldId;
        }

        private PlayerVoiceStatus status() {
            return status;
        }

        private void status(PlayerVoiceStatus status) {
            this.status = status;
        }

        private Set<UUID> visibleTo() {
            return visibleTo;
        }
    }
}
