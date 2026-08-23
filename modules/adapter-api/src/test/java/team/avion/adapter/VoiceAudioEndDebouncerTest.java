package team.avion.adapter;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAudioEndDebouncerTest {

    @Test
    void newAudioCancelsTransientEnd() throws Exception {
        UUID streamId = UUID.randomUUID();
        CountDownLatch fired = new CountDownLatch(1);
        try (VoiceAudioEndDebouncer<UUID> debouncer =
                     new VoiceAudioEndDebouncer<>("audio-end-test", 50L)) {
            debouncer.schedule(streamId, fired::countDown);
            debouncer.cancel(streamId);
            assertFalse(fired.await(120L, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void onlyLatestEndMarkerIsDelivered() throws Exception {
        UUID streamId = UUID.randomUUID();
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger marker = new AtomicInteger();
        try (VoiceAudioEndDebouncer<UUID> debouncer =
                     new VoiceAudioEndDebouncer<>("audio-end-test", 50L)) {
            debouncer.schedule(streamId, () -> marker.set(1));
            debouncer.schedule(streamId, () -> {
                marker.set(2);
                fired.countDown();
            });
            assertTrue(fired.await(1L, TimeUnit.SECONDS));
        }
        assertEquals(2, marker.get());
    }
}
