package team.avion.paper.voicecraft;

import org.bukkit.entity.Player;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.paper.VoiceCraftJava;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.OptionalInt;
import java.util.List;

/** Keeps one VoiceCraft entity alive while a player is connected through one or more native transports. */
public final class PaperVoiceEndpointRegistry {
    private final VoiceCraftJava plugin;
    private final ConcurrentMap<UUID, Set<String>> transports = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> playerNames = new ConcurrentHashMap<>();

    public PaperVoiceEndpointRegistry(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public OptionalInt connect(String transportId, Player player) {
        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            return entityId;
        }
        transports.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(transportId);
        playerNames.put(player.getUniqueId(), player.getName());
        if (plugin.getSessionManager().entityIdentity(entityId.getAsInt()).isEmpty()) {
            plugin.getSessionManager().registerEntityIdentity(new VoiceCraftEntityIdentity(entityId.getAsInt(),
                    player.getUniqueId(), player.getName(), transportId));
        }
        return entityId;
    }

    public void disconnect(String transportId, UUID playerId, String playerName) {
        Set<String> connected = transports.get(playerId);
        if (connected == null) {
            return;
        }
        connected.remove(transportId);
        if (!connected.isEmpty()) {
            return;
        }
        transports.remove(playerId, connected);
        String knownName = playerNames.remove(playerId);
        plugin.getSessionManager().destroyManagedEntityForPlayer(knownName == null ? playerName : knownName);
    }

    public boolean isConnected(UUID playerId, String transportId) {
        Set<String> connected = transports.get(playerId);
        return connected != null && connected.contains(transportId);
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
        transports.clear();
        playerNames.clear();
    }
}
