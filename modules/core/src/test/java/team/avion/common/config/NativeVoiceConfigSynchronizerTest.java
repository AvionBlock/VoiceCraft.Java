package team.avion.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import team.avion.common.config.NativeVoiceConfigSynchronizer.Endpoint;
import team.avion.common.config.NativeVoiceConfigSynchronizer.Owner;
import team.avion.common.config.NativeVoiceConfigSynchronizer.Settings;

class NativeVoiceConfigSynchronizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void updatesOnlySvcTransportPropertiesAndKeepsComments() throws Exception {
        Path svc = temporaryDirectory.resolve("voicechat-server.properties");
        Files.writeString(svc, "# native comment\nport=1000\ncodec=VOIP\nvoice_host=old.example:1000\n");

        NativeVoiceConfigSynchronizer.Result result = NativeVoiceConfigSynchronizer.synchronize(svc,
                temporaryDirectory.resolve("plasmo.toml"), settings(
                        new Endpoint(true, "*", 24454, "voice.example", 30000), disabled()));

        String updated = Files.readString(svc);
        assertTrue(result.simpleVoiceChatChanged());
        assertTrue(updated.contains("# native comment"));
        assertTrue(updated.contains("port=24454"));
        assertTrue(updated.contains("bind_address=*"));
        assertTrue(updated.contains("voice_host=voice.example:30000"));
        assertTrue(updated.contains("codec=VOIP"));
    }

    @Test
    void updatesPlasmoHostTablesWithoutTouchingVoiceSettings() throws Exception {
        Path plasmo = temporaryDirectory.resolve("config.toml");
        Files.writeString(plasmo, "[host]\nip = \"127.0.0.1\"\nport = 1\n\n[voice]\nmtu_size = 1024\n");

        NativeVoiceConfigSynchronizer.Result result = NativeVoiceConfigSynchronizer.synchronize(
                temporaryDirectory.resolve("svc.properties"), plasmo,
                settings(disabled(), new Endpoint(true, "0.0.0.0", 60606, "voice.example", 60607)));

        String updated = Files.readString(plasmo);
        assertTrue(result.plasmoVoiceChanged());
        assertTrue(updated.contains("[host]\nip = \"0.0.0.0\"\nport = 60606"));
        assertTrue(updated.contains("[host.public]\nip = \"voice.example\"\nport = 60607"));
        assertTrue(updated.contains("[voice]\nmtu_size = 1024"));
    }

    @Test
    void nativeOwnershipDoesNotCreateOrEditFiles() throws Exception {
        Path svc = temporaryDirectory.resolve("svc.properties");
        NativeVoiceConfigSynchronizer.Result result = NativeVoiceConfigSynchronizer.synchronize(svc,
                temporaryDirectory.resolve("plasmo.toml"),
                new Settings(Owner.NATIVE, new Endpoint(true, "", 24454, "", 24454), disabled()));

        assertFalse(result.changed());
        assertFalse(Files.exists(svc));
    }

    @Test
    void synchronizationIsIdempotent() throws Exception {
        Path svc = temporaryDirectory.resolve("svc.properties");
        Settings settings = settings(new Endpoint(true, "", 24454, "", 24454), disabled());
        assertTrue(NativeVoiceConfigSynchronizer.synchronize(svc,
                temporaryDirectory.resolve("plasmo.toml"), settings).changed());
        assertFalse(NativeVoiceConfigSynchronizer.synchronize(svc,
                temporaryDirectory.resolve("plasmo.toml"), settings).changed());
        assertEquals("port=24454\nbind_address=\nvoice_host=24454\n", Files.readString(svc));
    }

    @Test
    void rejectsUnknownOwnerAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> Owner.parse("both"));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint(true, "", 65536, "", 0));
    }

    private static Settings settings(Endpoint svc, Endpoint plasmo) {
        return new Settings(Owner.VOICECRAFT, svc, plasmo);
    }

    private static Endpoint disabled() {
        return new Endpoint(false, "", 0, "", 0);
    }
}
