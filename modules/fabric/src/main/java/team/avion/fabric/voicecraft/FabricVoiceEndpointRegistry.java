package team.avion.fabric.voicecraft;

import net.minecraft.server.level.ServerPlayer;
import team.avion.adapter.VoiceEndpointListener;
import team.avion.adapter.VoiceEndpointState;
import team.avion.fabric.VoiceCraftJava;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collection;
import java.util.OptionalInt;

public final class FabricVoiceEndpointRegistry {
    private final VoiceCraftJava plugin;
    private final ConcurrentHashMap<UUID, Set<String>> transports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> pendingTransports = new ConcurrentHashMap<>();
    private final List<VoiceEndpointListener> listeners = new CopyOnWriteArrayList<>();

    public FabricVoiceEndpointRegistry(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void connect(String transport, ServerPlayer player) {
        pendingTransports.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet()).add(transport);
        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) return;
        removePending(player.getUUID(), transport);
        boolean changed = transports.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet())
                .add(transport);
        playerNames.put(player.getUUID(), player.getName().getString());
        entityIds.put(player.getUUID(), entityId.getAsInt());
        if (changed) notifyUpdated(state(player.getUUID()));
    }

    public void disconnect(String transport, UUID playerId) {
        removePending(playerId, transport);
        Set<String> active = transports.get(playerId);
        if (active == null) return;
        active.remove(transport);
        if (active.isEmpty()) {
            transports.remove(playerId, active);
            String playerName = playerNames.remove(playerId);
            entityIds.remove(playerId);
            notifyRemoved(playerId, playerName == null ? playerId.toString() : playerName);
            if (plugin.getServer() != null) {
                ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) plugin.getSessionManager().destroyManagedEntityForPlayer(player);
            }
        } else notifyUpdated(state(playerId));
    }

    public boolean isConnected(UUID playerId, String transport) {
        Set<String> active = transports.get(playerId);
        return active != null && active.contains(transport);
    }

    public VoiceEndpointState state(UUID playerId) {
        Set<String> active = transports.get(playerId);
        String name = playerNames.get(playerId);
        Integer entityId = entityIds.get(playerId);
        return active == null || name == null || entityId == null
                ? null : new VoiceEndpointState(playerId, name, entityId, active);
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

    public void retryPending() {
        if (plugin.getServer() == null) return;
        pendingTransports.forEach((playerId, waiting) -> {
            ServerPlayer player = plugin.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                pendingTransports.remove(playerId, waiting);
                return;
            }
            List.copyOf(waiting).forEach(transport -> connect(transport, player));
        });
    }

    public boolean isPrimary(UUID playerId, String transport) {
        Set<String> active = transports.get(playerId);
        if (active == null || !active.contains(transport)) return false;
        List<String> priority = plugin.getConfig().getStringList("config.adapters.client-priority");
        for (String candidate : priority) {
            if (active.contains(candidate)) return candidate.equals(transport);
        }
        return active.stream().sorted().findFirst().map(transport::equals).orElse(false);
    }

    private void notifyUpdated(VoiceEndpointState state) {
        if (state != null) listeners.forEach(listener -> listener.endpointUpdated(state));
    }

    private void notifyRemoved(UUID playerId, String playerName) {
        listeners.forEach(listener -> listener.endpointRemoved(playerId, playerName));
    }

    private void removePending(UUID playerId, String transport) {
        Set<String> pending = pendingTransports.get(playerId);
        if (pending == null) return;
        pending.remove(transport);
        if (pending.isEmpty()) pendingTransports.remove(playerId, pending);
    }
}
