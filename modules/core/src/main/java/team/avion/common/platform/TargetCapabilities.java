package team.avion.common.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Capabilities embedded into a platform artifact from the target catalog. */
public record TargetCapabilities(
        String target,
        String loader,
        String minecraftVersion,
        boolean simpleVoiceChat,
        boolean plasmoVoice) {

    public static final String SIMPLE_VOICE_CHAT = "simple-voice-chat";
    public static final String PLASMO_VOICE = "plasmo";
    private static final String RESOURCE = "voicecraft-target.properties";

    public TargetCapabilities {
        target = normalized(target, "unknown");
        loader = normalized(loader, "unknown");
        minecraftVersion = normalized(minecraftVersion, "unknown");
    }

    public static TargetCapabilities load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        Properties properties = new Properties();
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return unknown();
            }
            properties.load(input);
        } catch (IOException ignored) {
            return unknown();
        }
        return new TargetCapabilities(
                properties.getProperty("target"),
                properties.getProperty("loader"),
                properties.getProperty("minecraft"),
                Boolean.parseBoolean(properties.getProperty("adapter.simple-voice-chat", "false")),
                Boolean.parseBoolean(properties.getProperty("adapter.plasmo", "false")));
    }

    public static TargetCapabilities unknown() {
        return new TargetCapabilities("unknown", "unknown", "unknown", false, false);
    }

    public boolean supports(String adapterId) {
        if (adapterId == null) {
            return false;
        }
        return switch (adapterId.toLowerCase(Locale.ROOT)) {
            case SIMPLE_VOICE_CHAT, "voicechat", "svc" -> simpleVoiceChat;
            case PLASMO_VOICE, "plasmovoice", "pv" -> plasmoVoice;
            default -> false;
        };
    }

    public String unsupportedMessage(String displayName) {
        return displayName + " adapter is enabled, but no compatible upstream mod exists for Minecraft "
                + minecraftVersion + " on " + loader + " (VoiceCraft target " + target + "). The adapter is disabled.";
    }

    private static String normalized(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
