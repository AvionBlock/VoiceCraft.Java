package team.avion.paper.voicecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceCraftReleaseArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stripsSingleArchiveRootDirectory() throws Exception {
        Path installDirectory = temporaryDirectory.resolve("voicecraft-runtime");
        byte[] archive = zip(Map.of(
                "VoiceCraft.Server.Windows.x64/VoiceCraft.Server.exe", "binary",
                "VoiceCraft.Server.Windows.x64/config/ServerProperties.json", "{}"
        ));

        VoiceCraftReleaseArchive.extract(new ByteArrayInputStream(archive), installDirectory);

        assertEquals("binary", Files.readString(installDirectory.resolve("VoiceCraft.Server.exe")));
        assertTrue(Files.exists(installDirectory.resolve("config/ServerProperties.json")));
        assertFalse(Files.exists(installDirectory.resolve("VoiceCraft.Server.Windows.x64")));
    }

    @Test
    void preservesAlreadyFlatArchiveLayout() throws Exception {
        Path installDirectory = temporaryDirectory.resolve("voicecraft-runtime");
        byte[] archive = zip(Map.of(
                "VoiceCraft.Server", "binary",
                "config/ServerProperties.json", "{}"
        ));

        VoiceCraftReleaseArchive.extract(new ByteArrayInputStream(archive), installDirectory);

        assertEquals("binary", Files.readString(installDirectory.resolve("VoiceCraft.Server")));
        assertTrue(Files.exists(installDirectory.resolve("config/ServerProperties.json")));
    }

    @Test
    void rejectsEntriesOutsideExtractionDirectory() throws Exception {
        Path installDirectory = temporaryDirectory.resolve("voicecraft-runtime");
        byte[] archive = zip(Map.of("../outside.txt", "bad"));

        assertThrows(IOException.class, () -> VoiceCraftReleaseArchive.extract(
                new ByteArrayInputStream(archive), installDirectory));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside.txt")));
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
