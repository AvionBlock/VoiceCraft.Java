package team.avion.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import team.avion.common.platform.TargetCapabilities;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Predicate;

/** Loader-neutral installer for official SVC and Plasmo Voice mod artifacts. */
public final class ManagedModDependencyInstaller {
    private final AbstractVoiceCraftMod plugin;
    private final String loader;
    private final Path modsDirectory;
    private final Predicate<String> modLoaded;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public ManagedModDependencyInstaller(AbstractVoiceCraftMod plugin, String loader, Path modsDirectory,
            Predicate<String> modLoaded) {
        this.plugin = plugin;
        this.loader = loader;
        this.modsDirectory = modsDirectory;
        this.modLoaded = modLoaded;
    }

    public boolean installMissingEnabledDependencies() {
        if (!plugin.getConfig().getBoolean("config.adapters.managed-dependencies.enabled", true)) {
            return false;
        }
        boolean staged = install(TargetCapabilities.SIMPLE_VOICE_CHAT, "voicechat", "9eGKb6K1");
        staged |= install(TargetCapabilities.PLASMO_VOICE, "plasmovoice", "1bZhdhsH");
        if (staged) {
            plugin.Logger.warn("Voice mods were installed into mods/. Restart the server to activate native bridges.");
        }
        return staged;
    }

    private boolean install(String configId, String modId, String projectId) {
        String path = "config.adapters." + configId;
        if (!plugin.getConfig().getBoolean(path + ".enabled", false)
                || !plugin.getConfig().getBoolean(path + ".install.enabled", true)
                || modLoaded.test(modId)
                || !plugin.getTargetCapabilities().supports(configId)) {
            return false;
        }
        try {
            JsonObject file = resolve(projectId, plugin.getTargetCapabilities().minecraftVersion());
            String name = safeName(file.get("filename").getAsString());
            Path destination = modsDirectory.resolve(name);
            if (Files.isRegularFile(destination)) {
                plugin.Logger.warn(modId + " is already staged as " + name + "; restart the server to load it.");
                return true;
            }
            Files.createDirectories(destination.getParent());
            byte[] bytes = send(URI.create(file.get("url").getAsString()));
            String expected = file.getAsJsonObject("hashes").get("sha512").getAsString();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-512 checksum mismatch");
            }
            Path temporary = Files.createTempFile(destination.getParent(), configId + "-", ".download");
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            plugin.Logger.info("Installed official " + loader + " dependency: " + name);
            return true;
        } catch (Exception exception) {
            plugin.Logger.error("Unable to install " + modId + " for Minecraft "
                    + plugin.getTargetCapabilities().minecraftVersion() + " on " + loader + ": "
                    + exception.getMessage());
            return false;
        }
    }

    private JsonObject resolve(String projectId, String game) throws Exception {
        String versions = URLEncoder.encode("[\"" + game + "\"]", StandardCharsets.UTF_8);
        String loaders = URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8);
        byte[] response = send(URI.create("https://api.modrinth.com/v2/project/" + projectId
                + "/version?game_versions=" + versions + "&loaders=" + loaders));
        for (JsonElement element : JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject version = element.getAsJsonObject();
            if (!"release".equals(version.get("version_type").getAsString())) {
                continue;
            }
            JsonArray files = version.getAsJsonArray("files");
            for (JsonElement candidate : files) {
                JsonObject file = candidate.getAsJsonObject();
                if (file.has("primary") && file.get("primary").getAsBoolean()) {
                    return file;
                }
            }
            if (!files.isEmpty()) {
                return files.get(0).getAsJsonObject();
            }
        }
        throw new IOException("no compatible " + loader + " release");
    }

    private byte[] send(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "VoiceCraft.Java/2.0 (https://java.voicecraft.chat)").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String safeName(String value) throws IOException {
        String name = Path.of(value).getFileName().toString();
        if (!name.equals(value) || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("unsafe dependency filename");
        }
        return name;
    }
}
