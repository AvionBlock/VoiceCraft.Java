package team.avion.paper.voicecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class VoiceCraftReleaseArchive {
    private VoiceCraftReleaseArchive() {
    }

    static void extract(InputStream archive, Path installDirectory) throws IOException {
        Path absoluteInstallDirectory = installDirectory.toAbsolutePath().normalize();
        Path parent = absoluteInstallDirectory.getParent();
        if (parent == null) {
            throw new IOException("VoiceCraft install directory has no parent: " + absoluteInstallDirectory);
        }
        Files.createDirectories(parent);
        Path stagingDirectory = Files.createTempDirectory(parent, ".voicecraft-extract-");

        try {
            extractIntoStaging(archive, stagingDirectory);
            Path contentRoot = resolveContentRoot(stagingDirectory);

            Files.createDirectories(absoluteInstallDirectory);
            deleteDirectoryContents(absoluteInstallDirectory);
            try (var children = Files.list(contentRoot)) {
                for (Path child : children.toList()) {
                    Files.move(child, absoluteInstallDirectory.resolve(child.getFileName()));
                }
            }
        } finally {
            deleteRecursively(stagingDirectory);
        }
    }

    private static void extractIntoStaging(InputStream archive, Path stagingDirectory) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path target = stagingDirectory.resolve(entry.getName()).normalize();
                if (!target.startsWith(stagingDirectory)) {
                    throw new IOException("Refusing to extract outside of staging directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (OutputStream outputStream = Files.newOutputStream(target, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    zipInputStream.transferTo(outputStream);
                }
            }
        }
    }

    private static Path resolveContentRoot(Path stagingDirectory) throws IOException {
        List<Path> children;
        try (var stream = Files.list(stagingDirectory)) {
            children = stream.toList();
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst())) {
            return children.getFirst();
        }
        return stagingDirectory;
    }

    private static void deleteDirectoryContents(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path current : paths) {
                Files.deleteIfExists(current);
            }
        }
    }
}
