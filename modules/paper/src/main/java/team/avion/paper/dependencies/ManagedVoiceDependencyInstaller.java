package team.avion.paper.dependencies;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import team.avion.paper.VoiceCraftJava;

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
import java.util.Set;

/** Downloads missing official voice plugins. Installed plugins become available after the next server start. */
public final class ManagedVoiceDependencyInstaller {
    private static final URI MODRINTH_API = URI.create("https://api.modrinth.com/v2/");
    private static final Set<String> PAPER_LOADERS = Set.of("paper", "purpur", "folia", "bukkit", "spigot");

    private final VoiceCraftJava plugin;
    private final HttpClient httpClient;

    public ManagedVoiceDependencyInstaller(VoiceCraftJava plugin) {
        this(plugin, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ManagedVoiceDependencyInstaller(VoiceCraftJava plugin, HttpClient httpClient) {
        this.plugin = plugin;
        this.httpClient = httpClient;
    }

    public boolean installMissingEnabledDependencies() {
        if (!plugin.getConfig().getBoolean("config.adapters.managed-dependencies.enabled", true)) {
            return false;
        }
        boolean restartRequired = false;
        restartRequired |= installIfMissing(new Dependency("simple-voice-chat", "voicechat", "9eGKb6K1"));
        restartRequired |= installIfMissing(new Dependency("plasmo", "PlasmoVoice", "1bZhdhsH"));
        if (restartRequired) {
            plugin.Logger.warn("Voice plugin dependency installation completed. Restart the server to activate the native bridges.");
        }
        return restartRequired;
    }

    private boolean installIfMissing(Dependency dependency) {
        String basePath = "config.adapters." + dependency.configId();
        if (!plugin.getConfig().getBoolean(basePath + ".enabled", false)
                || !plugin.getConfig().getBoolean(basePath + ".install.enabled", true)
                || Bukkit.getPluginManager().getPlugin(dependency.pluginName()) != null) {
            return false;
        }

        try {
            Artifact artifact = resolveArtifact(dependency.projectId(), Bukkit.getMinecraftVersion());
            Path pluginsDirectory = plugin.getDataFolder().toPath().getParent();
            Path destination = pluginsDirectory.resolve(safeFileName(artifact.fileName()));
            if (Files.isRegularFile(destination)) {
                plugin.Logger.warn(dependency.pluginName() + " is already staged at " + destination.getFileName()
                        + "; restart the server to load it.");
                return true;
            }

            Path stagingDirectory = pluginsDirectory.resolve(".voicecraft-downloads");
            Files.createDirectories(stagingDirectory);
            Path temporary = Files.createTempFile(stagingDirectory, dependency.configId() + "-", ".download");
            try {
                download(artifact, temporary);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination);
                }
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
            plugin.Logger.info("Installed official " + dependency.pluginName() + " dependency: "
                    + destination.getFileName());
            return true;
        } catch (Exception exception) {
            plugin.Logger.error("Unable to install " + dependency.pluginName() + ": " + exception.getMessage());
            return false;
        }
    }

    private Artifact resolveArtifact(String projectId, String minecraftVersion) throws IOException, InterruptedException {
        String versions = URLEncoder.encode("[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8);
        URI uri = MODRINTH_API.resolve("project/" + projectId + "/version?game_versions=" + versions);
        HttpRequest request = request(uri).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "version lookup");

        JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            if (!"release".equals(release.get("version_type").getAsString()) || !supportsPaper(release)) {
                continue;
            }
            JsonObject file = primaryFile(release.getAsJsonArray("files"));
            JsonObject hashes = file.getAsJsonObject("hashes");
            Hash hash = hashes.has("sha512")
                    ? new Hash("SHA-512", hashes.get("sha512").getAsString())
                    : new Hash("SHA-1", hashes.get("sha1").getAsString());
            return new Artifact(URI.create(file.get("url").getAsString()), file.get("filename").getAsString(), hash);
        }
        throw new IOException("no compatible Paper release for Minecraft " + minecraftVersion);
    }

    private boolean supportsPaper(JsonObject release) {
        for (JsonElement loader : release.getAsJsonArray("loaders")) {
            if (PAPER_LOADERS.contains(loader.getAsString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private JsonObject primaryFile(JsonArray files) throws IOException {
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }
        }
        if (!files.isEmpty()) {
            return files.get(0).getAsJsonObject();
        }
        throw new IOException("release does not contain a downloadable file");
    }

    private void download(Artifact artifact, Path target) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request(artifact.uri()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(response.statusCode(), "artifact download");
        byte[] bytes = response.body();
        String actual = HexFormat.of().formatHex(digest(artifact.hash().algorithm(), bytes));
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                artifact.hash().expectedHex().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException(artifact.hash().algorithm() + " checksum mismatch");
        }
        Files.write(target, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "VoiceCraft.Java/2.0 (https://java.voicecraft.chat)");
    }

    private static void requireSuccess(int status, String operation) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException(operation + " returned HTTP " + status);
        }
    }

    private static byte[] digest(String algorithm, byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (Exception exception) {
            throw new IOException("unsupported checksum " + algorithm, exception);
        }
    }

    private static String safeFileName(String value) throws IOException {
        String name = Path.of(value).getFileName().toString();
        if (!name.equals(value) || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("unsafe dependency filename");
        }
        return name;
    }

    private record Dependency(String configId, String pluginName, String projectId) {
    }

    private record Artifact(URI uri, String fileName, Hash hash) {
    }

    private record Hash(String algorithm, String expectedHex) {
    }
}
