package team.avion.adapter.plasmo.session;

import org.junit.jupiter.api.Test;
import team.avion.adapter.VoiceCraftAudioBridge;
import team.avion.adapter.plasmo.PlasmoPacketUtil;
import team.avion.adapter.plasmo.udp.PlasmoUdpEnvelope;
import team.avion.adapter.plasmo.udp.PlasmoUdpPacketType;
import team.avion.adapter.plasmo.udp.packet.PlayerAudioPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlasmoSessionManagerTest {
    @Test
    void audioEndIsDelayedToLetQueuedFramesFinish() throws Exception {
        TestAudioBridge bridge = new TestAudioBridge();
        PlasmoSessionManager manager = new PlasmoSessionManager(bridge);
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        try {
            manager.createOrReplace(playerId, "Player", 42);
            manager.forwardAudioEnd(playerId, 1234L);

            assertFalse(bridge.audioEndLatch.await(100, TimeUnit.MILLISECONDS));
            assertTrue(bridge.audioEndLatch.await(500, TimeUnit.MILLISECONDS));
            assertEquals(42, bridge.lastAudioEndEntityId.get());
            assertEquals(1234L, bridge.lastAudioEndSequence.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void newAudioCancelsPendingAudioEnd() throws Exception {
        TestAudioBridge bridge = new TestAudioBridge();
        PlasmoSessionManager manager = new PlasmoSessionManager(bridge);
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        try {
            PlasmoClientSession session = manager.createOrReplace(playerId, "Player", 42);
            manager.forwardAudioEnd(playerId, 1234L);
            manager.handleUdpPacket(
                    new PlasmoUdpEnvelope(session.udpSecret(), 0L, PlasmoUdpPacketType.PLAYER_AUDIO,
                            playerAudioPacket(1235L)),
                    new InetSocketAddress("127.0.0.1", 24454)
            );

            assertEquals(1, bridge.audioFramesSent.get());
            assertFalse(bridge.audioEndLatch.await(500, TimeUnit.MILLISECONDS));
        } finally {
            manager.close();
        }
    }

    private static PlayerAudioPacket playerAudioPacket(long sequenceNumber) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeLong(sequenceNumber);
        PlasmoPacketUtil.writeBytes(output, new byte[] {1, 2, 3});
        PlasmoPacketUtil.writeUuid(output, UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        output.writeShort(30);
        output.writeBoolean(false);

        PlayerAudioPacket packet = new PlayerAudioPacket();
        packet.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        return packet;
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
}
