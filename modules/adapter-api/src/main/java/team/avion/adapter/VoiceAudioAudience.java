package team.avion.adapter;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable receiver policy retained while audio crosses protocol bridges. */
public record VoiceAudioAudience(Mode mode, Set<UUID> playerIds) {
    public enum Mode {
        PROXIMITY,
        INCLUDE,
        EXCLUDE,
        EVERYONE
    }

    public static final VoiceAudioAudience PROXIMITY = new VoiceAudioAudience(Mode.PROXIMITY, Set.of());
    public static final VoiceAudioAudience EVERYONE = new VoiceAudioAudience(Mode.EVERYONE, Set.of());

    public VoiceAudioAudience {
        Objects.requireNonNull(mode, "mode");
        playerIds = Set.copyOf(Objects.requireNonNull(playerIds, "playerIds"));
    }

    public boolean allows(UUID playerId) {
        return switch (mode) {
            case PROXIMITY, EVERYONE -> true;
            case INCLUDE -> playerIds.contains(playerId);
            case EXCLUDE -> !playerIds.contains(playerId);
        };
    }
}
