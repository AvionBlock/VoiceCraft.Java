package team.avion.bungeecord.utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import team.avion.common.localization.PluginLocalization;
import team.avion.bungeecord.VoiceCraftJava;

public final class Language {
    private static final Map<String, Configuration> languageConfigs = new HashMap<>();
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

        languageConfigs.clear();
        File[] files = languageFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                languageConfigs.put(file.getName().replace(".yml", ""), ConfigurationProvider.getProvider(YamlConfiguration.class).load(file));
            } catch (IOException ignored) {
            }
        }
    }

    public static String getMessage(String language, String key) {
        String resolvedLanguage = PluginLocalization.resolveConfiguredLanguage(language);
        Configuration config = languageConfigs.getOrDefault(resolvedLanguage, languageConfigs.get(DEFAULT_LANGUAGE));
        return config == null ? key : config.getString("messages." + key, key);
    }
}
