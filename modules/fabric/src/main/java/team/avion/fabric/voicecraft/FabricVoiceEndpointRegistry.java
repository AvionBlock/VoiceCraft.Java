package team.avion.fabric.voicecraft;

import net.minecraft.server.network.ServerPlayerEntity;
import team.avion.fabric.VoiceCraftJava;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricVoiceEndpointRegistry {
    private final VoiceCraftJava plugin;
    private final ConcurrentHashMap<UUID, Set<String>> transports = new ConcurrentHashMap<>();

    public FabricVoiceEndpointRegistry(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void connect(String transport, ServerPlayerEntity player) {
        if (plugin.getSessionManager().createEntityForPlayer(player).isPresent()) {
            transports.computeIfAbsent(player.getUuid(), ignored -> ConcurrentHashMap.newKeySet()).add(transport);
        }
    }

    public void disconnect(String transport, UUID playerId) {
        Set<String> active = transports.get(playerId);
        if (active == null) return;
        active.remove(transport);
        if (active.isEmpty()) {
            transports.remove(playerId, active);
            if (plugin.getServer() != null) {
                ServerPlayerEntity player = plugin.getServer().getPlayerManager().getPlayer(playerId);
                if (player != null) plugin.getSessionManager().destroyManagedEntityForPlayer(player);
            }
        }
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
}
