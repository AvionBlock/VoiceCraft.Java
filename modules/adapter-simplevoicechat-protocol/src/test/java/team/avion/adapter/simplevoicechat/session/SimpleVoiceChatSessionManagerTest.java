package team.avion.adapter.simplevoicechat.session;

import org.junit.jupiter.api.Test;
import team.avion.adapter.VoiceCraftAudioBridge;
import team.avion.adapter.simplevoicechat.net.MicPacket;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleVoiceChatSessionManagerTest {
    @Test
    void replacingSessionRemovesOldEntityLookup() {
        TestAudioBridge bridge = new TestAudioBridge();
        SimpleVoiceChatSessionManager manager = new SimpleVoiceChatSessionManager(bridge);
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        try {
            manager.createOrReplace(playerId, "Player", 41);
            SimpleVoiceChatClientSession replacement = manager.createOrReplace(playerId, "Player", 42);

            assertTrue(manager.byEntityId(41).isEmpty());
            assertEquals(replacement, manager.byEntityId(42).orElseThrow());
            assertEquals(replacement, manager.byPlayerId(playerId).orElseThrow());
        } finally {
            manager.close();
        }
    }

    @Test
    void emptyMicSchedulesAudioEndAfterDelayAndReportsMetrics() throws Exception {
        TestAudioBridge bridge = new TestAudioBridge();
        TestAudioEndMetrics metrics = new TestAudioEndMetrics();
        SimpleVoiceChatSessionManager manager = new SimpleVoiceChatSessionManager(bridge, metrics);
        try {
            SimpleVoiceChatClientSession session = manager.createOrReplace(
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    "Player",
                    42
            );

            manager.forwardAudio(session, new MicPacket(new byte[0], 1234L, false));

            assertEquals(1, metrics.emptyMicPackets.get());
            assertEquals(0, metrics.audioEndsSent.get());
            assertFalse(bridge.audioEndLatch.await(100, TimeUnit.MILLISECONDS));

            assertTrue(bridge.audioEndLatch.await(500, TimeUnit.MILLISECONDS));
            assertEquals(42, bridge.lastAudioEndEntityId.get());
            assertEquals(1234L, bridge.lastAudioEndSequence.get());
            assertEquals(1, metrics.audioEndsSent.get());
            assertEquals(0, metrics.audioEndsCancelledByAudio.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void realAudioCancelsPendingEmptyMicAudioEnd() throws Exception {
        TestAudioBridge bridge = new TestAudioBridge();
        TestAudioEndMetrics metrics = new TestAudioEndMetrics();
        SimpleVoiceChatSessionManager manager = new SimpleVoiceChatSessionManager(bridge, metrics);
        try {
            SimpleVoiceChatClientSession session = manager.createOrReplace(
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    "Player",
                    42
            );

            manager.forwardAudio(session, new MicPacket(new byte[0], 1234L, false));
            manager.forwardAudio(session, new MicPacket(new byte[] {10, 11, 12}, 1235L, true));

            assertEquals(1, metrics.emptyMicPackets.get());
            assertEquals(1, metrics.audioEndsCancelledByAudio.get());
            assertEquals(0, metrics.audioEndsSent.get());
            assertEquals(1, bridge.audioFramesSent.get());
            assertFalse(bridge.audioEndLatch.await(500, TimeUnit.MILLISECONDS));
        } finally {
            manager.close();
        }
    }

    private static final class TestAudioBridge implements VoiceCraftAudioBridge {
        private final CountDownLatch audioEndLatch = new CountDownLatch(1);
        private final AtomicInteger audioFramesSent = new AtomicInteger();
        private final AtomicInteger lastAudioEndEntityId = new AtomicInteger();
        private final AtomicLong lastAudioEndSequence = new AtomicLong();

        @Override
        public void sendEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
            audioFramesSent.incrementAndGet();
        }

        @Override
        public void sendEntityAudioEnd(int entityId, long sequenceNumber) {
            lastAudioEndEntityId.set(entityId);
            lastAudioEndSequence.set(sequenceNumber);
            audioEndLatch.countDown();
        }
    }

    private static final class TestAudioEndMetrics implements SimpleVoiceChatSessionManager.AudioEndDebugMetrics {
        private final AtomicInteger emptyMicPackets = new AtomicInteger();
        private final AtomicInteger audioEndsSent = new AtomicInteger();
        private final AtomicInteger audioEndsCancelledByAudio = new AtomicInteger();

        @Override
        public void svcEmptyMicReceived() {
            emptyMicPackets.incrementAndGet();
        }

        @Override
        public void svcAudioEndSent() {
            audioEndsSent.incrementAndGet();
        }

        @Override
        public void svcAudioEndCancelledByAudio() {
            audioEndsCancelledByAudio.incrementAndGet();
        }
    }
}
