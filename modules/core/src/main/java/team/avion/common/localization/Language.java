package team.avion.common.localization;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class Language {
    private static final String BUNDLE_NAME = "team.avion.common.localization.messages";

    private Language() {
    }

    public static String getMessage(String language, String key, String... replacements) {
        String resolvedLanguage = PluginLocalization.resolveConfiguredLanguage(language);
        String message = lookup(resolvedLanguage, key);
        if (message == null) {
            message = lookup(PluginLocalization.DEFAULT_LANGUAGE, key);
        }
        if (message == null) {
            message = key;
        }

        if (replacements == null) {
            return message;
        }
        if ((replacements.length & 1) != 0) {
            throw new IllegalArgumentException("Localization replacements must be key/value pairs");
        }
        for (int index = 0; index < replacements.length; index += 2) {
            message = message.replace(replacements[index], String.valueOf(replacements[index + 1]));
        }
        return message;
    }

    private static String lookup(String language, String key) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.forLanguageTag(language),
                    Language.class.getClassLoader(), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            return bundle.containsKey(key) ? bundle.getString(key) : null;
        } catch (MissingResourceException exception) {
            return null;
        }
    }
}
