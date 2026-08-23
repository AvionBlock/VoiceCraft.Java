package team.avion.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceAudioDebugMetricsTest {
    @Test
    void reportsSequenceLossWhenEndFlushesShortUtterance() {
        List<String> messages = new ArrayList<>();
        VoiceAudioDebugMetrics metrics = new VoiceAudioDebugMetrics(() -> true, messages::add);

        metrics.recordFrame("test", 10L, 20);
        metrics.recordFrame("test", 12L, 30);
        metrics.recordEnd("test", "fired");

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("flow=test"));
        assertTrue(messages.get(0).contains("frames=2"));
        assertTrue(messages.get(0).contains("bytes=50"));
        assertTrue(messages.get(0).contains("gaps=1"));
        assertTrue(messages.get(0).contains("ends(s/c/f)=0/0/1"));
    }
}
