package team.avion.adapter;

import java.util.Objects;
import java.util.UUID;

/**
 * Voice controls that a client explicitly exposed to its server protocol.
 *
 * @param playerId Minecraft player UUID
 * @param voiceDisabled whether the player disabled voice chat playback
 * @param microphoneMuted whether the player explicitly disabled their microphone
 */
public record VoiceCraftPlayerState(UUID playerId, boolean voiceDisabled, boolean microphoneMuted) {
    public VoiceCraftPlayerState {
        Objects.requireNonNull(playerId, "playerId");
    }
}
