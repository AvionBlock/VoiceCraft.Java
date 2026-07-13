package team.avion.common.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PluginLocalizationTest {
    @Test
    void resolvesVoiceCraftLocaleAliases() {
        assertEquals("de", PluginLocalization.resolveConfiguredLanguage("de-DE"));
        assertEquals("pl", PluginLocalization.resolveConfiguredLanguage("pl_PL"));
        assertEquals("zh-cn", PluginLocalization.resolveConfiguredLanguage("zh-Hans"));
        assertEquals("zh-tw", PluginLocalization.resolveConfiguredLanguage("zh-Hant"));
    }

    @Test
    void mapsPluginLanguagesToVoiceCraftCliLocales() {
        assertEquals("de-DE", PluginLocalization.toVoiceCraftLanguage("de"));
        assertEquals("zh-CN", PluginLocalization.toVoiceCraftLanguage("zh-cn"));
        assertEquals("zh-TW", PluginLocalization.toVoiceCraftLanguage("zh-tw"));
        assertEquals("en-US", PluginLocalization.toVoiceCraftLanguage("ja"));
    }
}
