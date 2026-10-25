package team.avion.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProxyDimensionIdsTest {
    @Test
    void scopesWorldsToBackend() {
        assertEquals("lobby_minecraft:overworld", ProxyDimensionIds.qualify("lobby", "minecraft:overworld"));
        assertEquals("vanilla_minecraft:overworld", ProxyDimensionIds.qualify("vanilla", "minecraft:overworld"));
    }

    @Test
    void preservesGlobalAndAlreadyQualifiedIds() {
        assertEquals("", ProxyDimensionIds.qualify("lobby", ""));
        assertEquals("lobby_minecraft:nether", ProxyDimensionIds.qualify("lobby", "lobby_minecraft:nether"));
    }

    @Test
    void rejectsMissingBackendName() {
        assertThrows(IllegalArgumentException.class,
                () -> ProxyDimensionIds.qualify("", "minecraft:overworld"));
    }
}
