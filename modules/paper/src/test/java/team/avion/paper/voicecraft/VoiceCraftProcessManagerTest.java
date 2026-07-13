package team.avion.paper.voicecraft;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import team.avion.protocol.VoiceCraftRuntimeArguments;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceCraftProcessManagerTest {
    @Test
    void buildsRuntimeOverridesWithoutEditingServerProperties() {
        assertEquals(List.of(
                        "--transport-mode", "tcp",
                        "--transport-host", "127.0.0.1",
                        "--transport-port", "9050",
                        "--server-key", "secret-key",
                        "--voice-port", "1111",
                        "--language", "de-DE"),
                VoiceCraftRuntimeArguments.tcp("127.0.0.1", 9050, "secret-key", 1111, "de"));
    }

    @Test
    void selectsVersionedServerAssetFromGitLabReleaseMetadata() throws Exception {
        String json = """
                {
                  "tag_name": "v1.7.0",
                  "assets": {
                    "links": [
                      {
                        "name": "VoiceCraft.Client.Windows.x64.v1.7.0.zip",
                        "url": "https://example.invalid/client.zip"
                      },
                      {
                        "name": "VoiceCraft.Server.Windows.x64.v1.7.0.zip",
                        "url": "https://gitlab.avion.team/api/v4/projects/1/packages/generic/voicecraft/v1.7.0/VoiceCraft.Server.Windows.x64.v1.7.0.zip"
                      }
                    ]
                  }
                }
                """;

        VoiceCraftReleaseResolver.ReleaseAsset asset = VoiceCraftReleaseResolver.selectReleaseAsset(
                JsonParser.parseString(json), "windows", "x64");

        assertEquals("v1.7.0", asset.tagName());
        assertEquals("VoiceCraft.Server.Windows.x64.v1.7.0.zip", asset.name());
        assertEquals("https://gitlab.avion.team/api/v4/projects/1/packages/generic/voicecraft/v1.7.0/"
                + "VoiceCraft.Server.Windows.x64.v1.7.0.zip", asset.url());
    }

    @Test
    void reportsMissingPlatformAsset() {
        String json = """
                {
                  "tag_name": "v1.7.0",
                  "assets": {"links": []}
                }
                """;

        assertThrows(IOException.class, () -> VoiceCraftReleaseResolver.selectReleaseAsset(
                JsonParser.parseString(json), "linux", "arm64"));
    }

    @Test
    void buildsEncodedGitLabReleaseApiUrl() {
        assertEquals(
                "https://gitlab.avion.team/api/v4/projects/voicecraft%2FVoiceCraft/releases/permalink/latest",
                VoiceCraftReleaseResolver.buildReleaseApiUrl(
                        "https://gitlab.avion.team/", "voicecraft/VoiceCraft", "latest")
        );
    }
}
