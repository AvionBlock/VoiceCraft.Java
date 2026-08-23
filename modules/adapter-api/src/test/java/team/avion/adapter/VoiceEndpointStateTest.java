package team.avion.adapter;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceEndpointStateTest {
    @Test
    void snapshotsTransportMembershipDefensively() {
        Set<String> transports = new HashSet<>(Set.of("plasmo"));
        VoiceEndpointState state = new VoiceEndpointState(UUID.randomUUID(), "Player", 42, transports);

        transports.add("simple-voice-chat");

        assertTrue(state.isConnectedThrough("plasmo"));
        assertFalse(state.isConnectedThrough("simple-voice-chat"));
        assertThrows(UnsupportedOperationException.class,
                () -> state.transports().add("simple-voice-chat"));
    }
}
