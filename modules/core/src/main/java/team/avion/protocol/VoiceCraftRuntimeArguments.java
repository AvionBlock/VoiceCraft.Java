package team.avion.protocol;

import java.util.List;
import team.avion.common.localization.PluginLocalization;

public final class VoiceCraftRuntimeArguments {
    private VoiceCraftRuntimeArguments() {
    }

    public static List<String> tcp(String host, int transportPort, String serverKey, int voicePort, String language) {
        return List.of(
                "--transport-mode", "tcp",
                "--transport-host", host,
                "--transport-port", Integer.toString(transportPort),
                "--server-key", serverKey,
                "--voice-port", Integer.toString(voicePort),
                "--language", PluginLocalization.toVoiceCraftLanguage(language));
    }
}
