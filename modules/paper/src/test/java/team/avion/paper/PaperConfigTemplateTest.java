package team.avion.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class PaperConfigTemplateTest {
    @Test
    void nativeBridgeDefaultsArePresentInEveryTemplate() throws Exception {
        for (String resource : List.of("config.yml", "config/en.yml", "config/ja.yml", "config/nl.yml", "config/ru.yml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);

                assertTrue(config.startsWith("config-version: 9"), resource);
                for (String status : List.of("disconnected", "connected", "speaking", "muted", "deafened")) {
                    assertTrue(config.contains(status + ": \""), resource + " missing symbol " + status);
                }
                assertTrue(config.contains("client-priority: [\"simple-voice-chat\", \"plasmo\"]"), resource);
                assertTrue(config.contains("managed-dependencies:"), resource);
                assertTrue(config.contains("native-configs:"), resource);
                assertTrue(config.contains("owner: \"voicecraft\""), resource);
                assertTrue(config.contains("bind-port: 24454"), resource);
                assertTrue(config.contains("port: 9050"), resource);
                assertFalse(config.contains("compatibility-version:"), resource);
                assertFalse(config.contains("bind-host:"), resource);
            }
        }
    }
}
