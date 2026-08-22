package team.avion.adapter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** One immutable encoded frame travelling through the VoiceCraft routing graph. */
public record VoiceAudioFrame(
        UUID routeId,
        String originAdapter,
        VoiceAudioSource source,
        VoiceAudioAudience audience,
        long sequenceNumber,
        int timestamp,
        float loudness,
        byte[] opusFrame) {

    public VoiceAudioFrame {
        Objects.requireNonNull(routeId, "routeId");
        originAdapter = Objects.requireNonNull(originAdapter, "originAdapter");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(audience, "audience");
        opusFrame = Arrays.copyOf(Objects.requireNonNull(opusFrame, "opusFrame"), opusFrame.length);
    }

    @Override
    public byte[] opusFrame() {
        return Arrays.copyOf(opusFrame, opusFrame.length);
    }
}
