package team.avion.adapter.plasmo.tcp;

import org.junit.jupiter.api.Test;
import team.avion.adapter.plasmo.tcp.packet.ConnectionPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceInfoRequestPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlasmoTcpCodecTest {
    private final PlasmoTcpCodec codec = PlasmoTcpCodecs.createBaseCodec();

    @Test
    void connectionPacketRoundTripsWithPacketIdAndFieldOrder() throws Exception {
        UUID secret = UUID.fromString("11111111-2222-3333-4444-555555555555");
        byte[] encoded = codec.encode(new ConnectionPacket(secret, "127.0.0.1", 24454));

        assertEquals(PlasmoTcpPacketType.CONNECTION.id(), Byte.toUnsignedInt(encoded[0]));

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(PlasmoTcpPacketType.CONNECTION.id(), input.readUnsignedByte());
        assertEquals(secret.getMostSignificantBits(), input.readLong());
        assertEquals(secret.getLeastSignificantBits(), input.readLong());
        assertEquals("127.0.0.1", input.readUTF());
        assertEquals(24454, input.readInt());
        assertEquals(0, input.available());

        ConnectionPacket decoded = assertInstanceOf(ConnectionPacket.class, codec.decode(encoded));
        assertEquals(secret, decoded.secret());
        assertEquals("127.0.0.1", decoded.ip());
        assertEquals(24454, decoded.port());
        assertArrayEquals(encoded, codec.encode(decoded));
    }

    @Test
    void sourceInfoRequestRoundTrips() throws Exception {
        UUID sourceId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        byte[] encoded = tcpBytes(PlasmoTcpPacketType.SOURCE_INFO_REQUEST, output -> writeUuid(output, sourceId));

        SourceInfoRequestPacket decoded = assertInstanceOf(SourceInfoRequestPacket.class, codec.decode(encoded));
        assertEquals(sourceId, decoded.sourceId());
        assertArrayEquals(encoded, codec.encode(decoded));
    }

    @Test
    void playerAudioEndRoundTripsWithSequenceActivationAndDistanceOrder() throws Exception {
        long sequenceNumber = 0x0102030405060708L;
        UUID activationId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        short distance = 42;
        byte[] encoded = tcpBytes(PlasmoTcpPacketType.PLAYER_AUDIO_END, output -> {
            output.writeLong(sequenceNumber);
            writeUuid(output, activationId);
            output.writeShort(distance);
        });

        PlayerAudioEndPacket decoded = assertInstanceOf(PlayerAudioEndPacket.class, codec.decode(encoded));
        assertEquals(sequenceNumber, decoded.sequenceNumber());
        assertEquals(activationId, decoded.activationId());
        assertEquals(distance, decoded.distance());
        assertArrayEquals(encoded, codec.encode(decoded));
    }

    @Test
    void sourceAudioEndWritesPacketIdSourceIdThenSequenceNumber() throws Exception {
        UUID sourceId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        long sequenceNumber = 99L;
        byte[] encoded = codec.encode(new SourceAudioEndPacket(sourceId, sequenceNumber));

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
        assertEquals(PlasmoTcpPacketType.SOURCE_AUDIO_END.id(), input.readUnsignedByte());
        assertEquals(sourceId.getMostSignificantBits(), input.readLong());
        assertEquals(sourceId.getLeastSignificantBits(), input.readLong());
        assertEquals(sequenceNumber, input.readLong());
        assertEquals(0, input.available());
    }

    private static byte[] tcpBytes(PlasmoTcpPacketType type, IoWriter writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(type.id());
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws Exception {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private interface IoWriter {
        void write(DataOutputStream output) throws Exception;
    }
}
