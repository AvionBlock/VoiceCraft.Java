package team.avion.adapter;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAudioModelTest {
    @Test
    void frameDefensivelyCopiesEncodedAudio() {
        byte[] opus = {1, 2, 3};
        VoiceAudioFrame frame = new VoiceAudioFrame(UUID.randomUUID(), "test",
                VoiceAudioSource.player(7, UUID.randomUUID(), 30F), VoiceAudioAudience.PROXIMITY,
                1L, 2, 0.5F, opus);

        opus[0] = 9;
        byte[] returned = frame.opusFrame();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, frame.opusFrame());
    }

    @Test
    void audienceCopiesIdsAndAppliesReceiverPolicy() {
        UUID included = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Set<UUID> mutable = new HashSet<>(Set.of(included));
        VoiceAudioAudience audience = new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE, mutable);
        mutable.clear();

        assertTrue(audience.allows(included));
        assertFalse(audience.allows(other));
        assertTrue(new VoiceAudioAudience(VoiceAudioAudience.Mode.EXCLUDE, Set.of(included)).allows(other));
        assertFalse(new VoiceAudioAudience(VoiceAudioAudience.Mode.EXCLUDE, Set.of(included)).allows(included));
    }

    @Test
    void negativeSourceDistanceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VoiceAudioSource(UUID.randomUUID(),
                VoiceAudioSourceType.POSITION, 0, null, null, "world", 0D, 0D, 0D, -1F, "", false));
    }
}
