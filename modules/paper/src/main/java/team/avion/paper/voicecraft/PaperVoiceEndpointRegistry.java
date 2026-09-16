package team.avion.paper.voicecraft;

import org.bukkit.entity.Player;
import team.avion.adapter.VoiceEndpointListener;
import team.avion.adapter.VoiceEndpointState;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.paper.VoiceCraftJava;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.OptionalInt;
import java.util.List;
import java.util.Collection;

/** Keeps one VoiceCraft entity alive while a player is connected through one or more native transports. */
public final class PaperVoiceEndpointRegistry {
    private final VoiceCraftJava plugin;
    private final ConcurrentMap<UUID, Set<String>> transports = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> entityIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<String>> pendingTransports = new ConcurrentHashMap<>();
    private final List<VoiceEndpointListener> listeners = new CopyOnWriteArrayList<>();

    public PaperVoiceEndpointRegistry(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public OptionalInt connect(String transportId, Player player) {
        pendingTransports.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(transportId);
        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            return entityId;
        }
        removePending(player.getUniqueId(), transportId);
        boolean changed = transports.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(transportId);
        playerNames.put(player.getUniqueId(), player.getName());
        entityIds.put(player.getUniqueId(), entityId.getAsInt());
        if (plugin.getSessionManager().entityIdentity(entityId.getAsInt()).isEmpty()) {
            plugin.getSessionManager().registerEntityIdentity(new VoiceCraftEntityIdentity(entityId.getAsInt(),
                    player.getUniqueId(), player.getName(), transportId));
        }
        if (changed) {
            notifyUpdated(state(player.getUniqueId()));
        }
        return entityId;
    }

    public void disconnect(String transportId, UUID playerId, String playerName) {
        removePending(playerId, transportId);
        Set<String> connected = transports.get(playerId);
        if (connected == null) {
            return;
        }
        connected.remove(transportId);
        if (!connected.isEmpty()) {
            notifyUpdated(state(playerId));
            return;
        }
        transports.remove(playerId, connected);
        String knownName = playerNames.remove(playerId);
        entityIds.remove(playerId);
        notifyRemoved(playerId, knownName == null ? playerName : knownName);
        plugin.getSessionManager().destroyManagedEntityForPlayer(knownName == null ? playerName : knownName);
    }

    public boolean isConnected(UUID playerId, String transportId) {
        Set<String> connected = transports.get(playerId);
        return connected != null && connected.contains(transportId);
    }

    public boolean isConnected(UUID playerId) {
        Set<String> connected = transports.get(playerId);
        return connected != null && !connected.isEmpty();
    }

    public VoiceEndpointState state(UUID playerId) {
        Set<String> connected = transports.get(playerId);
        String playerName = playerNames.get(playerId);
        Integer entityId = entityIds.get(playerId);
        if (connected == null || playerName == null || entityId == null) {
            return null;
        }
        return new VoiceEndpointState(playerId, playerName, entityId, connected);
    }

    public Collection<VoiceEndpointState> states() {
        return transports.keySet().stream().map(this::state).filter(java.util.Objects::nonNull).toList();
    }

    public void registerListener(VoiceEndpointListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            states().forEach(listener::endpointUpdated);
        }
    }

    public void unregisterListener(VoiceEndpointListener listener) {
        listeners.remove(listener);
    }

    public void retryPending(Player player) {
        Set<String> waiting = pendingTransports.get(player.getUniqueId());
        if (waiting != null) {
            List.copyOf(waiting).forEach(transport -> connect(transport, player));
        }
    }

    public String playerName(UUID playerId) {
        return playerNames.getOrDefault(playerId, playerId.toString());
    }

    public boolean isPrimary(UUID playerId, String transportId) {
        Set<String> connected = transports.get(playerId);
        if (connected == null || !connected.contains(transportId)) {
            return false;
        }
        List<String> priority = plugin.getConfig().getStringList("config.adapters.client-priority");
        for (String candidate : priority) {
            if (connected.contains(candidate)) {
                return candidate.equals(transportId);
            }
        }
        return connected.stream().sorted().findFirst().map(transportId::equals).orElse(false);
    }

    public void clear() {
        states().forEach(state -> notifyRemoved(state.playerId(), state.playerName()));
        transports.clear();
        playerNames.clear();
        entityIds.clear();
        pendingTransports.clear();
        listeners.clear();
    }

    private void notifyUpdated(VoiceEndpointState state) {
        if (state != null) {
            listeners.forEach(listener -> listener.endpointUpdated(state));
        }
    }

    private void notifyRemoved(UUID playerId, String playerName) {
        listeners.forEach(listener -> listener.endpointRemoved(playerId, playerName));
    }

    private void removePending(UUID playerId, String transportId) {
        Set<String> pending = pendingTransports.get(playerId);
        if (pending == null) return;
        pending.remove(transportId);
        if (pending.isEmpty()) {
            pendingTransports.remove(playerId, pending);
        }
    }
}
