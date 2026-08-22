package team.avion.fabric.dependencies;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import team.avion.fabric.VoiceCraftJava;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/** Stages official Fabric voice mods. Fabric Loader can activate them only after restart. */
public final class FabricVoiceDependencyInstaller {
    private final VoiceCraftJava plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public FabricVoiceDependencyInstaller(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void installMissingEnabledDependencies() {
        if (!plugin.getConfig().getBoolean("config.adapters.managed-dependencies.enabled", true)) return;
        boolean staged = install("simple-voice-chat", "voicechat", "9eGKb6K1");
        staged |= install("plasmo", "plasmovoice", "1bZhdhsH");
        if (staged) plugin.Logger.warn("Voice mods were installed into mods/. Restart the server to activate the native bridges.");
    }

    private boolean install(String configId, String modId, String projectId) {
        String path = "config.adapters." + configId;
        if (!plugin.getConfig().getBoolean(path + ".enabled", false)
                || !plugin.getConfig().getBoolean(path + ".install.enabled", true)
                || FabricLoader.getInstance().isModLoaded(modId)) return false;
        try {
            String game = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            JsonObject file = resolve(projectId, game);
            String name = safeName(file.get("filename").getAsString());
            Path destination = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(name);
            if (Files.isRegularFile(destination)) return true;
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
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination); }
            } finally {
                Files.deleteIfExists(temporary);
            }
            plugin.Logger.info("Installed official Fabric dependency: " + name);
            return true;
        } catch (Exception exception) {
            plugin.Logger.error("Unable to install " + modId + ": " + exception.getMessage());
            return false;
        }
    }

    private JsonObject resolve(String projectId, String game) throws Exception {
        String versions = URLEncoder.encode("[\"" + game + "\"]", StandardCharsets.UTF_8);
        byte[] response = send(URI.create("https://api.modrinth.com/v2/project/" + projectId
                + "/version?game_versions=" + versions));
        for (JsonElement element : JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject version = element.getAsJsonObject();
            boolean fabric = version.getAsJsonArray("loaders").asList().stream()
                    .anyMatch(loader -> "fabric".equals(loader.getAsString()));
            if (!fabric || !"release".equals(version.get("version_type").getAsString())) continue;
            JsonArray files = version.getAsJsonArray("files");
            for (JsonElement candidate : files) {
                JsonObject file = candidate.getAsJsonObject();
                if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
            }
            if (!files.isEmpty()) return files.get(0).getAsJsonObject();
        }
        throw new IOException("no compatible Fabric release for Minecraft " + game);
    }

    private byte[] send(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "VoiceCraft.Java/2.0 (https://java.voicecraft.chat)").GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Modrinth returned HTTP " + response.statusCode());
        return response.body();
    }

    private static String safeName(String value) throws IOException {
        String name = Path.of(value).getFileName().toString();
        if (!name.equals(value) || !name.toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("unsafe dependency filename");
        return name;
    }
}
