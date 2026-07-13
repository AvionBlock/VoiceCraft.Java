package team.avion.adapter.simplevoicechat.net;

import org.junit.jupiter.api.Test;
import team.avion.adapter.VoiceCraftEntityIdentity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleVoiceChatTcpCodecTest {
    private final SimpleVoiceChatTcpCodec codec = new SimpleVoiceChatTcpCodec();

    @Test
    void updateStateDecodesDisabledFlag() {
        assertTrue(codec.decodeUpdateState(new byte[]{1}));
        assertFalse(codec.decodeUpdateState(new byte[]{0}));
    }

    @Test
    void initialStatesPreserveSharedDisabledFlag() {
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        VoiceCraftEntityIdentity identity = new VoiceCraftEntityIdentity(7, playerId, "Player", "plasmo");

        SimpleVoiceChatBuffer input = new SimpleVoiceChatBuffer(
                codec.encodePlayerStates(List.of(identity), playerId::equals));

        assertEquals(1, input.readInt());
        assertTrue(input.readBoolean());
        assertFalse(input.readBoolean());
        assertEquals(playerId, input.readUuid());
        assertEquals("Player", input.readUtf(16));
        assertFalse(input.readBoolean());
    }
}
