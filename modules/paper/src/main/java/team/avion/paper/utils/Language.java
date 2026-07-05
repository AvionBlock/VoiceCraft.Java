package team.avion.paper.utils;

import org.bukkit.configuration.file.YamlConfiguration;

import team.avion.common.localization.PluginLocalization;
import team.avion.paper.VoiceCraftJava;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Language {
    private static final Map<String, YamlConfiguration> languageConfigs = new HashMap<>();
    private static final String defaultLanguage = PluginLocalization.DEFAULT_LANGUAGE;

    public static void init(VoiceCraftJava plugin) {
        File languageFolder = new File(plugin.getDataFolder(), "locale");
        languageFolder.mkdirs();
        plugin.saveResource("locale/en.yml");
        plugin.saveResource("locale/ru.yml");
        plugin.saveResource("locale/nl.yml");
        plugin.saveResource("locale/ja.yml");

        loadLanguages(languageFolder.getAbsolutePath());
    }

    private static void loadLanguages(String pluginFolder) {
        File languageFolder = new File(pluginFolder);

        if (languageFolder.exists() && languageFolder.isDirectory()) {
            for (File file : languageFolder.listFiles()) {
                if (file.getName().endsWith(".yml")) {
                    String language = file.getName().replace(".yml", "");
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    languageConfigs.put(language, config);
                }
            }
        }
    }

    public static String getMessage(String language, String key) {
        String resolvedLanguage = PluginLocalization.resolveConfiguredLanguage(language);
        if (languageConfigs.containsKey(resolvedLanguage)) {
            YamlConfiguration config = languageConfigs.get(resolvedLanguage);
            if (config.contains("messages." + key)) {
                return config.getString("messages." + key);
            }
        }
        return languageConfigs.get(defaultLanguage).getString("messages." + key);
    }
}
