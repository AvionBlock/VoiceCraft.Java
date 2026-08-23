package team.avion.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the official voice-chat transports configured from VoiceCraft.Java's
 * single server configuration without taking ownership of unrelated settings.
 */
public final class NativeVoiceConfigSynchronizer {
    private static final Pattern PROPERTIES_ENTRY = Pattern.compile("^(\\s*)([^#!\\s][^=:#]*?)(\\s*[=:]\\s*)(.*)$");
    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[([^]]+)]\\s*(?:#.*)?$");
    private static final Pattern TOML_ENTRY = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+)(\\s*=\\s*)(.*)$");

    private NativeVoiceConfigSynchronizer() {
    }

    public static Result synchronize(Path simpleVoiceChatConfig, Path plasmoVoiceConfig, Settings settings)
            throws IOException {
        Objects.requireNonNull(simpleVoiceChatConfig, "simpleVoiceChatConfig");
        Objects.requireNonNull(plasmoVoiceConfig, "plasmoVoiceConfig");
        Objects.requireNonNull(settings, "settings");

        if (settings.owner() == Owner.NATIVE) {
            return new Result(false, false);
        }

        boolean simpleVoiceChatChanged = settings.simpleVoiceChat().enabled()
                && synchronizeSimpleVoiceChat(simpleVoiceChatConfig, settings.simpleVoiceChat());
        boolean plasmoVoiceChanged = settings.plasmoVoice().enabled()
                && synchronizePlasmoVoice(plasmoVoiceConfig, settings.plasmoVoice());
        return new Result(simpleVoiceChatChanged, plasmoVoiceChanged);
    }

    private static boolean synchronizeSimpleVoiceChat(Path path, Endpoint endpoint) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("port", Integer.toString(endpoint.bindPort()));
        entries.put("bind_address", endpoint.bindAddress());
        entries.put("voice_host", advertisedAddress(endpoint));
        return updateProperties(path, entries);
    }

    private static boolean synchronizePlasmoVoice(Path path, Endpoint endpoint) throws IOException {
        String original = read(path);
        String newline = newline(original);
        List<String> lines = splitLines(original);
        upsertTomlSection(lines, "host", orderedEntries(
                "ip", tomlString(endpoint.bindAddress()),
                "port", Integer.toString(endpoint.bindPort())));
        upsertTomlSection(lines, "host.public", orderedEntries(
                "ip", tomlString(endpoint.advertisedHost().isBlank() ? "0.0.0.0" : endpoint.advertisedHost()),
                "port", Integer.toString(endpoint.advertisedPort())));
        return writeIfChanged(path, original, joinLines(lines, newline));
    }

    private static boolean updateProperties(Path path, Map<String, String> desired) throws IOException {
        String original = read(path);
        String newline = newline(original);
        List<String> lines = splitLines(original);
        Map<String, Boolean> found = new LinkedHashMap<>();
        desired.keySet().forEach(key -> found.put(key, false));

        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = PROPERTIES_ENTRY.matcher(lines.get(index));
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(2).trim();
            if (!desired.containsKey(key)) {
                continue;
            }
            lines.set(index, matcher.group(1) + key + matcher.group(3) + desired.get(key));
            found.put(key, true);
        }

        desired.forEach((key, value) -> {
            if (!found.get(key)) {
                lines.add(key + "=" + value);
            }
        });
        return writeIfChanged(path, original, joinLines(lines, newline));
    }

    private static void upsertTomlSection(List<String> lines, String section, Map<String, String> desired) {
        int sectionStart = findTomlSection(lines, section);
        if (sectionStart < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            desired.forEach((key, value) -> lines.add(key + " = " + value));
            return;
        }

        int sectionEnd = sectionStart + 1;
        while (sectionEnd < lines.size() && !TOML_SECTION.matcher(lines.get(sectionEnd)).matches()) {
            sectionEnd++;
        }

        Map<String, Boolean> found = new LinkedHashMap<>();
        desired.keySet().forEach(key -> found.put(key, false));
        for (int index = sectionStart + 1; index < sectionEnd; index++) {
            Matcher matcher = TOML_ENTRY.matcher(lines.get(index));
            if (!matcher.matches() || !desired.containsKey(matcher.group(2))) {
                continue;
            }
            String key = matcher.group(2);
            lines.set(index, matcher.group(1) + key + matcher.group(3) + desired.get(key));
            found.put(key, true);
        }

        int insertion = sectionEnd;
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            if (!found.get(entry.getKey())) {
                lines.add(insertion++, entry.getKey() + " = " + entry.getValue());
            }
        }
    }

    private static int findTomlSection(List<String> lines, String section) {
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = TOML_SECTION.matcher(lines.get(index));
            if (matcher.matches() && matcher.group(1).trim().equals(section)) {
                return index;
            }
        }
        return -1;
    }

    private static String advertisedAddress(Endpoint endpoint) {
        String host = endpoint.advertisedHost().trim();
        if (host.isEmpty()) {
            return endpoint.advertisedPort() == 0 ? "" : Integer.toString(endpoint.advertisedPort());
        }
        if (endpoint.advertisedPort() == 0) {
            return host;
        }
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            host = "[" + host + "]";
        }
        return host + ":" + endpoint.advertisedPort();
    }

    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, String> orderedEntries(String firstKey, String firstValue,
            String secondKey, String secondValue) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(firstKey, firstValue);
        entries.put(secondKey, secondValue);
        return entries;
    }

    private static String read(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    private static String newline(String value) {
        return value.contains("\r\n") ? "\r\n" : "\n";
    }

    private static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        if (value.isEmpty()) {
            return lines;
        }
        String[] split = value.split("\\R", -1);
        int limit = split.length;
        if (limit > 0 && split[limit - 1].isEmpty()) {
            limit--;
        }
        for (int index = 0; index < limit; index++) {
            lines.add(split[index]);
        }
        return lines;
    }

    private static String joinLines(List<String> lines, String newline) {
        return lines.isEmpty() ? "" : String.join(newline, lines) + newline;
    }

    private static boolean writeIfChanged(Path path, String original, String updated) throws IOException {
        if (original.equals(updated)) {
            return false;
        }
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().normalize().getParent(),
                path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    public enum Owner {
        VOICECRAFT,
        NATIVE;

        public static Owner parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "voicecraft", "voicecraft-java", "voicecraft.java" -> VOICECRAFT;
                case "native" -> NATIVE;
                default -> throw new IllegalArgumentException(
                        "config.adapters.native-configs.owner must be 'voicecraft' or 'native', got '" + value + "'");
            };
        }
    }

    public record Endpoint(boolean enabled, String bindAddress, int bindPort, String advertisedHost,
            int advertisedPort) {
        public Endpoint {
            bindAddress = Objects.requireNonNull(bindAddress, "bindAddress").trim();
            advertisedHost = Objects.requireNonNull(advertisedHost, "advertisedHost").trim();
            validatePort("bindPort", bindPort);
            validatePort("advertisedPort", advertisedPort);
        }

        private static void validatePort(String name, int value) {
            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException(name + " must be between 0 and 65535");
            }
        }
    }

    public record Settings(Owner owner, Endpoint simpleVoiceChat, Endpoint plasmoVoice) {
        public Settings {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(simpleVoiceChat, "simpleVoiceChat");
            Objects.requireNonNull(plasmoVoice, "plasmoVoice");
        }
    }

    public record Result(boolean simpleVoiceChatChanged, boolean plasmoVoiceChanged) {
        public boolean changed() {
            return simpleVoiceChatChanged || plasmoVoiceChanged;
        }
    }
}
