package team.avion.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import team.avion.protocol.McPackets.EventRequestPacket;
import team.avion.protocol.McPackets.OnEntityAudioDataReceivedPacket;
import team.avion.protocol.McProtocolEnums.EventType;

class McProtocolAudioEventTest {
    @Test
    void audioDataEventRoundTripsWithPayload() {
        OnEntityAudioDataReceivedPacket audio = new OnEntityAudioDataReceivedPacket();
        audio.Id = 42;
        audio.Timestamp = 65_535;
        audio.FrameLoudness = 0.75F;
        audio.Data = new byte[] { 1, 2, 3, 4 };
        audio.Length = audio.Data.length;

        EventRequestPacket event = new EventRequestPacket();
        event.EventTypeValue = EventType.ON_ENTITY_AUDIO_DATA_RECEIVED;
        event.Event = audio;

        EventRequestPacket decoded = assertInstanceOf(EventRequestPacket.class,
                McProtocolCodec.decode(McProtocolCodec.encode(event)));
        OnEntityAudioDataReceivedPacket decodedAudio = assertInstanceOf(OnEntityAudioDataReceivedPacket.class,
                decoded.Event);

        assertEquals(EventType.ON_ENTITY_AUDIO_DATA_RECEIVED, decoded.EventTypeValue);
        assertEquals(audio.Id, decodedAudio.Id);
        assertEquals(audio.Timestamp, decodedAudio.Timestamp);
        assertEquals(audio.FrameLoudness, decodedAudio.FrameLoudness);
        assertArrayEquals(audio.Data, decodedAudio.Data);
    }
}
