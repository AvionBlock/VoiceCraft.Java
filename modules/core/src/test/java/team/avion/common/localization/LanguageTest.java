package team.avion.common.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LanguageTest {
    @Test
    void translatesRussianMessagesAndReplacements() {
        assertEquals("Подготовка среды VoiceCraft v1.7.0 для протокола McApi 1.7.0 (windows/x64).",
                Language.getMessage("ru", "runtime.preparing",
                        "$release", "v1.7.0", "$protocol", "1.7.0",
                        "$platform", "windows", "$architecture", "x64"));
    }

    @Test
    void unsupportedLanguageFallsBackToEnglish() {
        assertEquals("Managed VoiceCraft process is reachable.",
                Language.getMessage("unsupported", "runtime.reachable"));
    }

    @Test
    void loadsLegacyMessagesFromTheSamePropertiesBundle() {
        assertEquals("Конфигурация загружена.", Language.getMessage("ru", "plugin-config-loaded"));
        assertEquals("Configuratie geladen.", Language.getMessage("nl", "plugin-config-loaded"));
        assertEquals("構成を読み込みました。", Language.getMessage("ja", "plugin-config-loaded"));
    }

    @Test
    void loadsRuntimeMessagesForEverySupportedLanguage() {
        for (String language : new String[] {"de", "nl", "pl", "ru", "th", "zh-cn", "zh-tw", "ja"}) {
            String message = Language.getMessage(language, "runtime.reachable");
            assertNotEquals("runtime.reachable", message);
        }
    }

    @Test
    void missingKeyRemainsVisible() {
        assertEquals("missing.key", Language.getMessage("ru", "missing.key"));
    }

    @Test
    void rejectsUnpairedReplacements() {
        assertThrows(IllegalArgumentException.class,
                () -> Language.getMessage("ru", "runtime.reachable", "$unused"));
    }
}
