package team.avion.paper.utils;

import org.bukkit.entity.Player;

import team.avion.common.utils.BasePlaceholder;
import team.avion.paper.VoiceCraftJava;

public class Placeholder extends BasePlaceholder {
    private final VoiceCraftJava plugin;

    // Get the plugin interface.
    public Placeholder(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        // Voice icon placeholder "%voice_status%"
        if (identifier.equalsIgnoreCase("status")) {
            if (plugin.getPlayerBinds().getOrDefault(player.getName(), false)) {
                return plugin.getConfig().getString("config.voice.in-voice-symbol");
            } else {
                return plugin.getConfig().getString("config.voice.not-in-voice-symbol");
            }
        }
        return null;
    }
}
