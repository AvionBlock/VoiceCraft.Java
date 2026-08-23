package team.avion.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import team.avion.common.config.NativeVoiceConfigSynchronizer;
import team.avion.common.platform.TargetCapabilities;
import team.avion.fabric.config.FabricConfig;

/** Applies existing port ownership settings before SVC can read its server config. */
public final class FabricNativeVoiceConfigPreLaunch implements PreLaunchEntrypoint {
    private static final System.Logger LOGGER = System.getLogger("VoiceCraft.Java");

    @Override
    public void onPreLaunch() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Path voiceCraftConfig = configDirectory.resolve("voicecraft-java/config.yml");
        if (!Files.isRegularFile(voiceCraftConfig)) {
            return;
        }

        try {
            FabricConfig config = new FabricConfig(voiceCraftConfig);
            config.load();
            TargetCapabilities capabilities = TargetCapabilities.load(getClass().getClassLoader());
            String root = "config.adapters.native-configs";
            NativeVoiceConfigSynchronizer.Settings settings = new NativeVoiceConfigSynchronizer.Settings(
                    NativeVoiceConfigSynchronizer.Owner.parse(config.getString(root + ".owner", "voicecraft")),
                    endpoint(config, capabilities, root + ".simple-voice-chat",
                            TargetCapabilities.SIMPLE_VOICE_CHAT, "", 24454, "", 24454),
                    endpoint(config, capabilities, root + ".plasmo",
                            TargetCapabilities.PLASMO_VOICE, "0.0.0.0", 0, "0.0.0.0", 0));
            NativeVoiceConfigSynchronizer.Result result = NativeVoiceConfigSynchronizer.synchronize(
                    configDirectory.resolve("voicechat/voicechat-server.properties"),
                    configDirectory.resolve("plasmovoice/server/config.toml"), settings);
            if (result.changed()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Applied VoiceCraft-managed native voice ports during Fabric pre-launch.");
            }
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "Unable to apply native voice ports during Fabric pre-launch: " + exception.getMessage());
        }
    }

    private static NativeVoiceConfigSynchronizer.Endpoint endpoint(FabricConfig config,
            TargetCapabilities capabilities, String root, String adapter, String defaultBindAddress,
            int defaultBindPort, String defaultAdvertisedHost, int defaultAdvertisedPort) {
        return new NativeVoiceConfigSynchronizer.Endpoint(
                capabilities.supports(adapter)
                        && config.getBoolean("config.adapters." + adapter + ".enabled", false),
                config.getString(root + ".bind-address", defaultBindAddress),
                config.getInt(root + ".bind-port", defaultBindPort),
                config.getString(root + ".advertised-host", defaultAdvertisedHost),
                config.getInt(root + ".advertised-port", defaultAdvertisedPort));
    }
}
