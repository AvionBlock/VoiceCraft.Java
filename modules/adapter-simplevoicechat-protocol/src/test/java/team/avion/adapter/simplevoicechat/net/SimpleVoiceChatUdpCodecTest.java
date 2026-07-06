package team.avion.adapter.simplevoicechat.net;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleVoiceChatUdpCodecTest {
    private final SimpleVoiceChatUdpCodec codec = new SimpleVoiceChatUdpCodec();

    @Test
    void micPacketDecodesFromClientEnvelopeWithPacketIdAndFieldOrder() throws Exception {
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SimpleVoiceChatSecret secret = fixedSecret();
        byte[] audio = audioBytes(MicPacket.MAX_OPUS_PAYLOAD_SIZE);
        byte[] encoded = clientEnvelope(playerId, secret, inner -> {
            inner.writeByte(SimpleVoiceChatPacketType.MIC.id());
            inner.writeByteArray(audio);
            inner.writeLong(12345L);
            inner.writeBoolean(true);
        });

        assertEquals(SimpleVoiceChatUdpCodec.MAGIC, encoded[0]);

        SimpleVoiceChatUdpEnvelope envelope = codec.decodeClient(
                encoded,
                new InetSocketAddress("127.0.0.1", 1234),
                id -> playerId.equals(id) ? secret : null
        );
        MicPacket decoded = assertInstanceOf(MicPacket.class, envelope.packet());
        assertEquals(playerId, envelope.playerId());
        assertArrayEquals(audio, decoded.data());
        assertEquals(12345L, decoded.sequenceNumber());
        assertTrue(decoded.whispering());
    }

    @Test
    void micPayloadAboveMaximumIsRejected() throws Exception {
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SimpleVoiceChatSecret secret = fixedSecret();
        byte[] audio = audioBytes(MicPacket.MAX_OPUS_PAYLOAD_SIZE + 1);
        byte[] encoded = clientEnvelope(playerId, secret, inner -> {
            inner.writeByte(SimpleVoiceChatPacketType.MIC.id());
            inner.writeByteArray(audio);
            inner.writeLong(1L);
            inner.writeBoolean(false);
        });

        assertThrows(Exception.class, () -> codec.decodeClient(encoded, null, id -> secret));
    }

    @Test
    void emptyMicPacketDecodesAsZeroLengthAudio() throws Exception {
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        SimpleVoiceChatSecret secret = fixedSecret();
        byte[] encoded = clientEnvelope(playerId, secret, inner -> {
            inner.writeByte(SimpleVoiceChatPacketType.MIC.id());
            inner.writeByteArray(new byte[0]);
            inner.writeLong(987L);
            inner.writeBoolean(false);
        });

        SimpleVoiceChatUdpEnvelope envelope = codec.decodeClient(encoded, null, id -> secret);
        MicPacket decoded = assertInstanceOf(MicPacket.class, envelope.packet());
        assertEquals(0, decoded.data().length);
        assertEquals(987L, decoded.sequenceNumber());
        assertFalse(decoded.whispering());
    }

    @Test
    void playerSoundServerEnvelopeUsesPacketIdAndExpectedFieldOrder() throws Exception {
        SimpleVoiceChatSecret secret = fixedSecret();
        UUID channelId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID sender = UUID.fromString("99999999-8888-7777-6666-555555555555");
        byte[] audio = new byte[] {1, 2, 3, 4};

        byte[] encoded = codec.encodeServer(
                new PlayerSoundPacket(channelId, sender, audio, 112233L, true, 31.5F),
                secret
        );

        SimpleVoiceChatBuffer outer = new SimpleVoiceChatBuffer(encoded);
        assertEquals(SimpleVoiceChatUdpCodec.MAGIC, outer.readByte());
        byte[] encrypted = outer.readByteArray(SimpleVoiceChatUdpCodec.MAX_ENCRYPTED_PACKET_SIZE);
        SimpleVoiceChatBuffer inner = new SimpleVoiceChatBuffer(secret.decrypt(encrypted));
        assertEquals(SimpleVoiceChatPacketType.PLAYER_SOUND.id(), Byte.toUnsignedInt(inner.readByte()));
        assertEquals(channelId, inner.readUuid());
        assertEquals(sender, inner.readUuid());
        assertArrayEquals(audio, inner.readByteArray(MicPacket.MAX_OPUS_PAYLOAD_SIZE));
        assertEquals(112233L, inner.readLong());
        assertEquals(Float.floatToIntBits(31.5F), inner.readInt());
        assertEquals(1, inner.readByte());
        assertEquals(0, inner.readableBytes());
    }

    @Test
    void simplePacketServerEnvelopeRoundTripsThroughDecryptedPayload() throws Exception {
        SimpleVoiceChatSecret secret = fixedSecret();
        byte[] encoded = codec.encodeServer(new SimplePacket(SimpleVoiceChatPacketType.CONNECTION_CHECK_ACK), secret);

        SimpleVoiceChatBuffer outer = new SimpleVoiceChatBuffer(encoded);
        assertEquals(SimpleVoiceChatUdpCodec.MAGIC, outer.readByte());
        byte[] encrypted = outer.readByteArray(SimpleVoiceChatUdpCodec.MAX_ENCRYPTED_PACKET_SIZE);
        SimpleVoiceChatBuffer inner = new SimpleVoiceChatBuffer(secret.decrypt(encrypted));
        assertEquals(SimpleVoiceChatPacketType.CONNECTION_CHECK_ACK.id(), Byte.toUnsignedInt(inner.readByte()));
        assertEquals(0, inner.readableBytes());
    }

    private static byte[] clientEnvelope(UUID playerId, SimpleVoiceChatSecret secret, BufferWriter writer)
            throws Exception {
        SimpleVoiceChatBuffer inner = new SimpleVoiceChatBuffer();
        writer.write(inner);

        SimpleVoiceChatBuffer outer = new SimpleVoiceChatBuffer();
        outer.writeByte(SimpleVoiceChatUdpCodec.MAGIC);
        outer.writeUuid(playerId);
        outer.writeByteArray(secret.encrypt(inner.toByteArray()));
        return outer.toByteArray();
    }

    private static SimpleVoiceChatSecret fixedSecret() {
        byte[] secret = new byte[SimpleVoiceChatSecret.SECRET_BYTES];
        for (int index = 0; index < secret.length; index++) {
            secret[index] = (byte) (index + 1);
        }
        return SimpleVoiceChatSecret.of(secret);
    }

    private static byte[] audioBytes(int length) {
        byte[] audio = new byte[length];
        for (int index = 0; index < audio.length; index++) {
            audio[index] = (byte) (index & 0x7F);
        }
        return audio;
    }

    private interface BufferWriter {
        void write(SimpleVoiceChatBuffer buffer) throws Exception;
    }
}
