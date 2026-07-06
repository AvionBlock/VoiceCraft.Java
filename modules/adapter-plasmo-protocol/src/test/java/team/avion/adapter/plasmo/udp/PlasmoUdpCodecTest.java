package team.avion.adapter.plasmo.udp;

import org.junit.jupiter.api.Test;
import team.avion.adapter.plasmo.PlasmoProtocolConstants;
import team.avion.adapter.plasmo.udp.packet.PlayerAudioPacket;
import team.avion.adapter.plasmo.udp.packet.SourceAudioPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlasmoUdpCodecTest {
    private final PlasmoUdpCodec codec = PlasmoUdpCodecs.createBaseCodec();

    @Test
    void sourceAudioRoundTripsWithHeaderPacketIdAndFieldOrder() throws Exception {
        UUID secret = UUID.fromString("01010101-0202-0303-0404-050505050505");
        UUID sourceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] audio = audioBytes(PlasmoProtocolConstants.MAX_AUDIO_BYTES);
        SourceAudioPacket packet = new SourceAudioPacket(sourceId, (byte) 7, (short) 32, 123456789L, audio);

        byte[] encoded = codec.encode(packet, secret);

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(PlasmoProtocolConstants.UDP_MAGIC, input.readInt());
        assertEquals(PlasmoUdpPacketType.SOURCE_AUDIO.id(), input.readUnsignedByte());
        assertEquals(secret.getMostSignificantBits(), input.readLong());
        assertEquals(secret.getLeastSignificantBits(), input.readLong());
        input.readLong();
        assertEquals(123456789L, input.readLong());
        assertEquals(audio.length, input.readInt());
        assertArrayEquals(audio, input.readNBytes(audio.length));
        assertEquals(sourceId.getMostSignificantBits(), input.readLong());
        assertEquals(sourceId.getLeastSignificantBits(), input.readLong());
        assertEquals(7, input.readByte());
        assertEquals(32, input.readShort());
        assertEquals(0, input.available());

        PlasmoUdpEnvelope decodedEnvelope = codec.decode(encoded);
        SourceAudioPacket decoded = assertInstanceOf(SourceAudioPacket.class, decodedEnvelope.packet());
        assertEquals(secret, decodedEnvelope.secret());
        assertEquals(PlasmoUdpPacketType.SOURCE_AUDIO, decodedEnvelope.type());
        assertEquals(123456789L, decoded.sequenceNumber());
        assertArrayEquals(audio, decoded.data());
        assertEquals(sourceId, decoded.sourceId());
        assertEquals(7, decoded.sourceState());
        assertEquals(32, decoded.distance());
    }

    @Test
    void playerAudioDecodesAndReEncodesMaximumAudioPayload() throws Exception {
        UUID secret = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID activationId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        byte[] audio = audioBytes(PlasmoProtocolConstants.MAX_AUDIO_BYTES);
        byte[] encoded = udpBytes(secret, PlasmoUdpPacketType.PLAYER_AUDIO, output -> {
            output.writeLong(77L);
            output.writeInt(audio.length);
            output.write(audio);
            writeUuid(output, activationId);
            output.writeShort(48);
            output.writeBoolean(true);
        });

        PlasmoUdpEnvelope envelope = codec.decode(encoded);
        PlayerAudioPacket decoded = assertInstanceOf(PlayerAudioPacket.class, envelope.packet());
        assertEquals(secret, envelope.secret());
        assertEquals(PlasmoUdpPacketType.PLAYER_AUDIO, envelope.type());
        assertEquals(77L, decoded.sequenceNumber());
        assertArrayEquals(audio, decoded.data());
        assertEquals(activationId, decoded.activationId());
        assertEquals(48, decoded.distance());
        assertTrue(decoded.stereo());

        byte[] reencoded = codec.encode(decoded, secret);
        assertArrayEquals(Arrays.copyOfRange(encoded, 29, encoded.length),
                Arrays.copyOfRange(reencoded, 29, reencoded.length));
    }

    @Test
    void audioPayloadAboveMaximumIsRejected() throws Exception {
        UUID secret = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID sourceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] audio = audioBytes(PlasmoProtocolConstants.MAX_AUDIO_BYTES + 1);
        byte[] encoded = udpBytes(secret, PlasmoUdpPacketType.SOURCE_AUDIO, output -> {
            output.writeLong(1L);
            output.writeInt(audio.length);
            output.write(audio);
            writeUuid(output, sourceId);
            output.writeByte(1);
            output.writeShort(10);
        });

        assertThrows(Exception.class, () -> codec.decode(encoded));
    }

    @Test
    void emptyAudioPayloadIsRejectedForPlasmoAudioPackets() throws Exception {
        UUID secret = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID sourceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] encoded = udpBytes(secret, PlasmoUdpPacketType.SOURCE_AUDIO, output -> {
            output.writeLong(1L);
            output.writeInt(0);
            writeUuid(output, sourceId);
            output.writeByte(1);
            output.writeShort(10);
        });

        assertThrows(Exception.class, () -> codec.decode(encoded));
    }

    private static byte[] udpBytes(UUID secret, PlasmoUdpPacketType type, IoWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(PlasmoProtocolConstants.UDP_MAGIC);
        output.writeByte(type.id());
        writeUuid(output, secret);
        output.writeLong(987654321L);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] audioBytes(int length) {
        byte[] audio = new byte[length];
        for (int index = 0; index < audio.length; index++) {
            audio[index] = (byte) (index & 0x7F);
        }
        return audio;
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws Exception {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private interface IoWriter {
        void write(DataOutputStream output) throws Exception;
    }
}
