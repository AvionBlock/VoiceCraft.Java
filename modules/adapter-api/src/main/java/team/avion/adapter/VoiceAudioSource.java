package team.avion.adapter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Protocol-neutral description of a player or add-on-created audio source. */
public record VoiceAudioSource(
        UUID streamId,
        VoiceAudioSourceType type,
        int voiceCraftEntityId,
        UUID playerId,
        UUID minecraftEntityId,
        String worldId,
        double x,
        double y,
        double z,
        float distance,
        String category,
        boolean stereo) {

    public VoiceAudioSource {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(type, "type");
        worldId = worldId == null ? "" : worldId;
        category = category == null ? "" : category;
        if (distance < 0F) {
            throw new IllegalArgumentException("distance must not be negative");
        }
    }

    public static VoiceAudioSource player(int entityId, UUID playerId, float distance) {
        Objects.requireNonNull(playerId, "playerId");
        return new VoiceAudioSource(playerId, VoiceAudioSourceType.PLAYER, entityId, playerId, playerId,
                "", 0D, 0D, 0D, distance, "", false);
    }

    public Optional<UUID> playerIdentity() {
        return Optional.ofNullable(playerId);
    }

    public VoiceAudioSource withVoiceCraftEntityId(int entityId) {
        return new VoiceAudioSource(streamId, type, entityId, playerId, minecraftEntityId, worldId, x, y, z,
                distance, category, stereo);
    }
}
