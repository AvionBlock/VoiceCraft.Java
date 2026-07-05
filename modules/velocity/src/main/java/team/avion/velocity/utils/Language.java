package team.avion.velocity.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import team.avion.common.localization.PluginLocalization;
import team.avion.velocity.VoiceCraftJava;
import team.avion.velocity.config.VelocityYamlConfig;

public final class Language {
    private static final Map<String, VelocityYamlConfig> languageConfigs = new HashMap<>();
    private static final String DEFAULT_LANGUAGE = PluginLocalization.DEFAULT_LANGUAGE;

    private Language() {
    }

    public static void init(VoiceCraftJava plugin) {
        File languageFolder = new File(plugin.getDataFolder(), "locale");
        languageFolder.mkdirs();
        plugin.saveResource("locale/en.yml");
        plugin.saveResource("locale/ru.yml");
        plugin.saveResource("locale/nl.yml");
        plugin.saveResource("locale/ja.yml");

        loadLanguages(languageFolder);
    }

    private static void loadLanguages(File languageFolder) {
        languageConfigs.clear();
        File[] files = languageFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String language = file.getName().replace(".yml", "");
            try {
                languageConfigs.put(language, new VelocityYamlConfig(file.toPath()));
            } catch (IOException ignored) {
            }
        }
    }

    public static String getMessage(String language, String key) {
        String resolvedLanguage = PluginLocalization.resolveConfiguredLanguage(language);
        VelocityYamlConfig config = languageConfigs.getOrDefault(resolvedLanguage, languageConfigs.get(DEFAULT_LANGUAGE));
        return config == null ? key : config.getString("messages." + key, key);
    }
}
