package team.avion.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import team.avion.protocol.McPackets.EntityAudioRequestPacket;
import team.avion.protocol.McPackets.EventRequestPacket;
import team.avion.protocol.McPackets.OnEntityAudioDataReceivedPacket;
import team.avion.protocol.McPackets.ProximityEchoEffect;
import team.avion.protocol.McPackets.ProximityMuffleEffect;
import team.avion.protocol.McPackets.SetEffectRequestPacket;
import team.avion.protocol.McPackets.SetEntityPropertyRequestPacket;
import team.avion.protocol.McProtocolEnums.EventType;
import team.avion.protocol.McProtocolEnums.McApiPacketType;
import team.avion.protocol.McProtocolEnums.PropertyType;

class McProtocol171CompatibilityTest {
    @Test
    void reportsVoiceCraft171() {
        assertEquals("1.7.1", VoiceCraftProtocol.VERSION_STRING);
    }

    @Test
    void packetAndEventIdsMatchVoiceCraft171() {
        assertEquals(24, McApiPacketType.SET_ENTITY_PROPERTY_REQUEST.ordinal());
        assertEquals(27, McApiPacketType.DESTROY_ENTITY_RESPONSE.ordinal());
        assertEquals(28, McApiPacketType.values().length);
        assertEquals(17, EventType.ON_ENTITY_PROPERTY_UPDATED.ordinal());
        assertEquals(19, EventType.ON_ENTITY_AUDIO_DATA_RECEIVED.ordinal());
        assertEquals(20, EventType.values().length);
    }

    @Test
    void entityAudioRequestMatchesLiteNetLibWireBytes() {
        EntityAudioRequestPacket packet = new EntityAudioRequestPacket();
        packet.Id = 0x01020304;
        packet.Timestamp = 0xBEEF;
        packet.FrameLoudness = 0.5F;
        packet.Data = new byte[] { 0x11, 0x22 };
        packet.Length = packet.Data.length;

        assertArrayEquals(hex("0C 04 03 02 01 EF BE 00 00 00 3F 11 22"), McProtocolCodec.encode(packet));
    }

    @Test
    void audioDataEventMatchesVoiceCraft171Envelope() {
        OnEntityAudioDataReceivedPacket audio = new OnEntityAudioDataReceivedPacket();
        audio.Id = 7;
        audio.Timestamp = 9;
        audio.FrameLoudness = 0.25F;
        audio.Data = new byte[] { 0x33 };
        audio.Length = audio.Data.length;

        EventRequestPacket event = new EventRequestPacket();
        event.Event = audio;

        byte[] encoded = McProtocolCodec.encode(event);
        assertArrayEquals(hex("06 13 07 00 00 00 09 00 00 00 80 3E 33"), encoded);

        EventRequestPacket decoded = assertInstanceOf(EventRequestPacket.class, McProtocolCodec.decode(encoded));
        assertInstanceOf(OnEntityAudioDataReceivedPacket.class, decoded.Event);
    }

    @Test
    void floatPropertyMatchesVoiceCraft171WireBytes() {
        SetEntityPropertyRequestPacket packet = new SetEntityPropertyRequestPacket();
        packet.Id = 7;
        packet.Key = "x";
        packet.PropertyTypeValue = PropertyType.FLOAT;
        packet.Value = 0.25F;

        byte[] encoded = McProtocolCodec.encode(packet);
        assertArrayEquals(hex("18 07 00 00 00 02 00 78 0A 00 00 80 3E"), encoded);

        SetEntityPropertyRequestPacket decoded = assertInstanceOf(SetEntityPropertyRequestPacket.class,
                McProtocolCodec.decode(encoded));
        assertEquals(PropertyType.FLOAT, decoded.PropertyTypeValue);
        assertEquals(0.25F, decoded.Value);
    }

    @Test
    void proximityEchoIncludesFactorBeforeWetDry() {
        ProximityEchoEffect effect = new ProximityEchoEffect();
        effect.Delay = 0.5F;
        effect.Range = 16F;
        effect.Factor = 0.25F;
        effect.WetDry = 0.75F;

        SetEffectRequestPacket packet = new SetEffectRequestPacket();
        packet.Bitmask = 0x1234;
        packet.Effect = effect;

        assertArrayEquals(hex("08 34 12 04 00 00 00 3F 00 00 80 41 00 00 80 3E 00 00 40 3F"),
                McProtocolCodec.encode(packet));
    }

    @Test
    void proximityMuffleIncludesFactorBeforeWetDry() {
        ProximityMuffleEffect effect = new ProximityMuffleEffect();
        effect.Factor = 0.25F;
        effect.WetDry = 0.75F;

        SetEffectRequestPacket packet = new SetEffectRequestPacket();
        packet.Bitmask = 1;
        packet.Effect = effect;

        assertArrayEquals(hex("08 01 00 06 00 00 80 3E 00 00 40 3F"), McProtocolCodec.encode(packet));
    }

    @Test
    void incomingLoudnessIsClampedLikeVoiceCraft171() {
        EntityAudioRequestPacket decoded = assertInstanceOf(EntityAudioRequestPacket.class,
                McProtocolCodec.decode(hex("0C 01 00 00 00 02 00 00 00 00 40 11")));
        assertEquals(1.0F, decoded.FrameLoudness);
    }

    @Test
    void mctpRequestFrameMatchesVoiceCraft171Framing() {
        byte[] frame = McTcpFrameCodec.encodeRequest("t", List.of(new byte[] { 0x02 }));
        assertArrayEquals(hex(
                "4D 43 54 50 00 01 00 01 00 00 00 0E 00 00 00 01 74 00 00 00 01 00 00 00 01 02"), frame);
    }

    private static byte[] hex(String value) {
        String compact = value.replace(" ", "");
        byte[] bytes = new byte[compact.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(compact.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
