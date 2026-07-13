package team.avion.paper.voicecraft;

import org.junit.jupiter.api.Test;
import team.avion.adapter.VoiceCraftPlayerState;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerVoiceStatusTest {
    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void disconnectedAlwaysWins() {
        assertEquals(PlayerVoiceStatus.DISCONNECTED, PlayerVoiceStatus.resolve(false, true,
                new VoiceCraftPlayerState(PLAYER_ID, true, true)));
    }

    @Test
    void voiceControlsTakePriorityOverSpeaking() {
        assertEquals(PlayerVoiceStatus.DEAFENED, PlayerVoiceStatus.resolve(true, true,
                new VoiceCraftPlayerState(PLAYER_ID, true, true)));
        assertEquals(PlayerVoiceStatus.MUTED, PlayerVoiceStatus.resolve(true, true,
                new VoiceCraftPlayerState(PLAYER_ID, false, true)));
    }

    @Test
    void connectedPlayerSwitchesBetweenIdleAndSpeaking() {
        assertEquals(PlayerVoiceStatus.CONNECTED, PlayerVoiceStatus.resolve(true, false, null));
        assertEquals(PlayerVoiceStatus.SPEAKING, PlayerVoiceStatus.resolve(true, true, null));
    }
}
