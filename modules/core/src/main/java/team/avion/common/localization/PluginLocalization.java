package team.avion.common.localization;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PluginLocalization {
    public static final String DEFAULT_LANGUAGE = "en";
    public static final String SYSTEM_LANGUAGE = "system";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "de", "nl", "pl", "ru", "th", "zh-cn", "zh-tw", "ja");
    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("en-us", "en"),
            Map.entry("de-de", "de"),
            Map.entry("nl-nl", "nl"),
            Map.entry("pl-pl", "pl"),
            Map.entry("ru-ru", "ru"),
            Map.entry("th-th", "th"),
            Map.entry("zh-cn", "zh-cn"),
            Map.entry("zh-sg", "zh-cn"),
            Map.entry("zh-hans", "zh-cn"),
            Map.entry("zh-tw", "zh-tw"),
            Map.entry("zh-hk", "zh-tw"),
            Map.entry("zh-mo", "zh-tw"),
            Map.entry("zh-hant", "zh-tw"),
            Map.entry("ja-jp", "ja"));
    private static final Map<String, String> VOICECRAFT_LANGUAGE_TAGS = Map.ofEntries(
            Map.entry("en", "en-US"),
            Map.entry("de", "de-DE"),
            Map.entry("nl", "nl-NL"),
            Map.entry("pl", "pl-PL"),
            Map.entry("ru", "ru-RU"),
            Map.entry("th", "th-TH"),
            Map.entry("zh-cn", "zh-CN"),
            Map.entry("zh-tw", "zh-TW"),
            Map.entry("ja", "en-US"));

    private PluginLocalization() {
    }

    public static String resolveConfiguredLanguage(String configuredLanguage) {
        if (configuredLanguage == null || configuredLanguage.isBlank()
                || SYSTEM_LANGUAGE.equalsIgnoreCase(configuredLanguage)) {
            return resolveSystemLanguage();
        }

        String normalized = normalizeLanguageTag(configuredLanguage);
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : DEFAULT_LANGUAGE;
    }

    public static String resolveSystemLanguage() {
        String languageTag = Locale.getDefault().toLanguageTag();
        if (languageTag == null || languageTag.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = normalizeLanguageTag(languageTag);
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : DEFAULT_LANGUAGE;
    }

    public static String normalizeConfigLanguage(String configuredLanguage) {
        if (configuredLanguage == null || configuredLanguage.isBlank()) {
            return SYSTEM_LANGUAGE;
        }

        String normalized = normalizeLanguageTag(configuredLanguage);
        if (SYSTEM_LANGUAGE.equals(normalized)) {
            return SYSTEM_LANGUAGE;
        }
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : DEFAULT_LANGUAGE;
    }

    public static String toVoiceCraftLanguage(String configuredLanguage) {
        return VOICECRAFT_LANGUAGE_TAGS.getOrDefault(resolveConfiguredLanguage(configuredLanguage), "en-US");
    }

    private static String normalizeLanguageTag(String language) {
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        String alias = LANGUAGE_ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        if (normalized.startsWith("zh-")) {
            return normalized.contains("hant") ? "zh-tw" : "zh-cn";
        }
        int separator = normalized.indexOf('-');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }
}
