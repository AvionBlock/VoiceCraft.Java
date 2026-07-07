package team.avion.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProxyMessageCodecTest {
    @Test
    void snapshotRoundTrips() {
        ProxyPlayerSnapshot snapshot = new ProxyPlayerSnapshot("uuid-1", "Alpha", "world", 1.5, 64.0, -2.25,
                180.0, 0.4, true, false);

        ProxyPlayerSnapshot decoded = ProxyMessageCodec.decodeSnapshot(ProxyMessageCodec.encodeSnapshot(snapshot));

        assertEquals(snapshot, decoded);
    }

    @Test
    void bindRequestRoundTrips() {
        ProxyMessageCodec.BindRequest decoded = ProxyMessageCodec.decodeBindRequest(
                ProxyMessageCodec.encodeBindRequest("uuid-2", "Beta", 1234));

        assertEquals("uuid-2", decoded.playerId());
        assertEquals("Beta", decoded.playerName());
        assertEquals(1234, decoded.bindingKey());
    }

    @Test
    void bindSyncRoundTrips() {
        ProxyMessageCodec.BindSync decoded = ProxyMessageCodec.decodeBindSync(
                ProxyMessageCodec.encodeBindSync("Gamma", true));

        assertEquals("Gamma", decoded.playerName());
        assertEquals(true, decoded.bound());
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProxyMessageCodec.peekType(new byte[] { 1, 2 }));
    }

    @Test
    void wrongMessageTypeIsRejected() {
        byte[] bindSync = ProxyMessageCodec.encodeBindSync("Delta", false);

        assertThrows(IllegalArgumentException.class, () -> ProxyMessageCodec.decodeSnapshot(bindSync));
    }
}
