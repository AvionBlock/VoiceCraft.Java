package team.avion.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VoiceAudioReturnOrdererTest {
    private static final UUID STREAM = UUID.randomUUID();

    @Test
    void restoresQueueOrderAcrossOutOfOrderReturns() {
        VoiceAudioReturnOrderer orderer = new VoiceAudioReturnOrderer();
        VoiceAudioFrame zero = frame(0);
        VoiceAudioFrame one = frame(1);
        VoiceAudioFrame two = frame(2);
        orderer.queued(zero);
        orderer.queued(one);
        orderer.queued(two);

        assertTrue(orderer.returned(one).isEmpty());
        assertEquals(List.of(0L, 1L), sequences(orderer.returned(zero)));
        assertEquals(List.of(2L), sequences(orderer.returned(two)));
    }

    @Test
    void skipsAStaleMissingReturnWithoutReorderingLaterFrames() {
        AtomicLong clock = new AtomicLong();
        VoiceAudioReturnOrderer orderer = new VoiceAudioReturnOrderer(100L, clock::get);
        VoiceAudioFrame zero = frame(0);
        VoiceAudioFrame one = frame(1);
        orderer.queued(zero);
        orderer.queued(one);

        clock.set(101_000_000L);
        assertEquals(List.of(1L), sequences(orderer.returned(one)));
        assertTrue(orderer.returned(zero).isEmpty());
    }

    private static List<Long> sequences(List<VoiceAudioFrame> frames) {
        return frames.stream().map(VoiceAudioFrame::sequenceNumber).toList();
    }

    private static VoiceAudioFrame frame(long sequence) {
        return new VoiceAudioFrame(UUID.randomUUID(), "test",
                VoiceAudioSource.player(7, STREAM, 30F), VoiceAudioAudience.PROXIMITY,
                sequence, (int) sequence, 1F, new byte[] {1});
    }
}
