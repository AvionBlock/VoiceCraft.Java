package team.avion.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class PaperConfigTemplateTest {
    @Test
    void plasmoDefaultsUseMinecraftHostSentinelInEveryTemplate() throws Exception {
        for (String resource : List.of("config.yml", "config/en.yml", "config/ja.yml", "config/nl.yml", "config/ru.yml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);

                assertTrue(config.startsWith("config-version: 6"), resource);
                assertEquals(1, config.lines()
                                .filter(line -> line.stripLeading().startsWith("host: \"0.0.0.0\""))
                                .count(),
                        resource);
            }
        }
    }
}
