package team.avion.fabric.voicecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import team.avion.protocol.VoiceCraftRuntimeDefaults;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class VoiceCraftReleaseResolver {
    private VoiceCraftReleaseResolver() {
    }

    static String buildReleaseApiUrl(String baseUrl, String projectPath, String release) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String releasePath = "latest".equalsIgnoreCase(release) ? "permalink/latest" : encodePathSegment(release);
        return normalizedBaseUrl + "/api/v4/projects/" + encodePathSegment(projectPath)
                + "/releases/" + releasePath;
    }

    static ReleaseAsset selectReleaseAsset(JsonElement releaseJson, String platform, String architecture)
            throws IOException {
        if (releaseJson == null || !releaseJson.isJsonObject()) {
            throw new IOException("VoiceCraft release metadata root is not an object");
        }
        JsonObject release = releaseJson.getAsJsonObject();
        String tagName = requiredString(release, "tag_name");
        JsonObject assets = release.has("assets") && release.get("assets").isJsonObject()
                ? release.getAsJsonObject("assets") : null;
        JsonArray links = assets != null && assets.has("links") && assets.get("links").isJsonArray()
                ? assets.getAsJsonArray("links") : null;
        if (links == null) {
            throw new IOException("VoiceCraft release " + tagName + " has no asset links");
        }

        String assetPrefix = VoiceCraftRuntimeDefaults.EXECUTABLE_BASE_NAME + "."
                + capitalize(platform) + "." + architecture;
        ReleaseAsset versionedMatch = null;
        for (JsonElement linkElement : links) {
            if (!linkElement.isJsonObject()) {
                continue;
            }
            JsonObject link = linkElement.getAsJsonObject();
            String name = optionalString(link, "name");
            String url = optionalString(link, "url");
            if (name == null || url == null) {
                continue;
            }
            if (name.equals(assetPrefix + ".zip")) {
                return new ReleaseAsset(tagName, name, url);
            }
            if (name.startsWith(assetPrefix + ".") && name.endsWith(".zip")) {
                versionedMatch = new ReleaseAsset(tagName, name, url);
            }
        }
        if (versionedMatch != null) {
            return versionedMatch;
        }
        throw new IOException("VoiceCraft release " + tagName + " has no server asset for "
                + platform + "/" + architecture + " (expected " + assetPrefix + "[.<version>].zip)");
    }

    private static String requiredString(JsonObject object, String propertyName) throws IOException {
        String value = optionalString(object, propertyName);
        if (value == null) {
            throw new IOException("VoiceCraft release metadata has no " + propertyName);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String propertyName) {
        JsonElement value = object.get(propertyName);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    record ReleaseAsset(String tagName, String name, String url) {
    }
}
