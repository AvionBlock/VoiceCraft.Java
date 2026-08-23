package team.avion.common.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TargetCapabilitiesTest {
    @Test
    void resolvesAdapterAliases() {
        TargetCapabilities capabilities = new TargetCapabilities("1.21.4-forge", "forge", "1.21.4", true, false);

        assertTrue(capabilities.supports("simple-voice-chat"));
        assertTrue(capabilities.supports("svc"));
        assertFalse(capabilities.supports("plasmo"));
        assertTrue(capabilities.unsupportedMessage("Plasmo Voice").contains("1.21.4"));
        assertTrue(capabilities.unsupportedMessage("Plasmo Voice").contains("forge"));
    }
}
