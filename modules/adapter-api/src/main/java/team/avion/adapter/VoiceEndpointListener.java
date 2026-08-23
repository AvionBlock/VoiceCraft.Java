package team.avion.adapter;

import java.util.UUID;

/** Receives native voice endpoint lifecycle changes used to project cross-mod presence. */
public interface VoiceEndpointListener {
    void endpointUpdated(VoiceEndpointState state);

    void endpointRemoved(UUID playerId, String playerName);
}
