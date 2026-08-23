package team.avion.adapter;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable view of the native voice transports currently attached to a Minecraft player. */
public record VoiceEndpointState(
        UUID playerId,
        String playerName,
        int voiceCraftEntityId,
        Set<String> transports) {

    public VoiceEndpointState {
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        transports = Set.copyOf(Objects.requireNonNull(transports, "transports"));
    }

    public boolean isConnectedThrough(String transportId) {
        return transports.contains(transportId);
    }
}
