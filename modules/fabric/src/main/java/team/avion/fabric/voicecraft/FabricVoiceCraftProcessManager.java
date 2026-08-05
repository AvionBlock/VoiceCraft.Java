package team.avion.fabric.voicecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import team.avion.fabric.VoiceCraftJava;
import team.avion.protocol.VoiceCraftProtocol;
import team.avion.protocol.VoiceCraftRuntimeArguments;
import team.avion.protocol.VoiceCraftRuntimeDefaults;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FabricVoiceCraftProcessManager {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final VoiceCraftJava plugin;
    private final ExecutorService launcherExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "VoiceCraft.Java-Fabric-Runtime-Launcher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean starting = new AtomicBoolean(false);

    private Process process;
    private Thread stdoutLogPump;
    private Thread stderrLogPump;
    private volatile Future<?> ensureFuture;
    private volatile boolean closed;

    public FabricVoiceCraftProcessManager(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    public void ensureRunning() {
        if (!plugin.getConfig().getBoolean("config.voicecraft.auto-start", true)) {
            return;
        }
        if (closed || !starting.compareAndSet(false, true)) {
            return;
        }

        try {
            ensureFuture = launcherExecutor.submit(() -> {
                try {
                    ensureRunningBlocking();
                } finally {
                    starting.set(false);
                    if (!plugin.isConnected() && plugin.getConfig().getBoolean("config.auto-reconnect", true)) {
                        try {
                            plugin.reconnect(true);
                        } catch (Exception exception) {
                            plugin.Logger.warnLocalized("runtime.reconnect-failed", "$error", exception.getMessage());
                        }
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            starting.set(false);
        }
    }

    public boolean isStarting() {
        return starting.get();
    }

    public void shutdown() {
        closed = true;
        Future<?> pendingEnsure = ensureFuture;
        if (pendingEnsure != null) {
            pendingEnsure.cancel(true);
        }
        launcherExecutor.shutdownNow();

        synchronized (this) {
            if (process == null) {
                return;
            }
            if (plugin.getConfig().getBoolean("config.voicecraft.shutdown-on-disable", true)) {
                plugin.Logger.infoLocalized("runtime.stopping");
                stopProcess();
            }
            process = null;
        }
    }

    private synchronized void ensureRunningBlocking() {
        LaunchTarget launchTarget = null;
        try {
            launchTarget = resolveReleaseLaunchTarget();
            if (launchTarget == null) {
                process = null;
                return;
            }
            if (process != null && process.isAlive()) {
                ensureLogPumps(process);
                return;
            }
            if (isEndpointReachable()) {
                plugin.Logger.warnLocalized("runtime.external-process");
                return;
            }
            startManagedProcess(launchTarget);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.Logger.errorLocalized("runtime.start-interrupted", "$error", exception.getMessage());
            process = null;
        } catch (IOException exception) {
            plugin.Logger.errorLocalized("runtime.start-failed", "$error", exception.getMessage());
            plugin.Logger.errorLocalized("runtime.executable-path", "$path",
                    launchTarget == null ? "<unresolved>" : launchTarget.command());
            process = null;
        }
    }

    private void startManagedProcess(LaunchTarget launchTarget) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(launchTarget.command());
        command.addAll(launchTarget.arguments());
        command.addAll(VoiceCraftRuntimeArguments.tcp(plugin.getHost(), plugin.getPort(), plugin.getLoginToken(),
                plugin.getManagedVoicePort(), plugin.getLanguage()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(launchTarget.workingDirectory().toFile());
        if (plugin.getConfig().getBoolean("config.voicecraft.invariant-globalization", true)) {
            builder.environment().put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
        }

        plugin.Logger.infoLocalized("runtime.starting", "$path", launchTarget.command());
        process = builder.start();
        startLogPumps(process);
        waitUntilReachable();
    }

    private void stopProcess() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        stdoutLogPump = null;
        stderrLogPump = null;
    }

    private void startLogPumps(Process managedProcess) {
        stdoutLogPump = startLogPump(managedProcess.getInputStream(), false);
        stderrLogPump = startLogPump(managedProcess.getErrorStream(), true);
        plugin.Logger.infoLocalized("runtime.forwarding-output");
    }

    private void ensureLogPumps(Process managedProcess) {
        if (stdoutLogPump == null || !stdoutLogPump.isAlive()) {
            stdoutLogPump = startLogPump(managedProcess.getInputStream(), false);
        }
        if (stderrLogPump == null || !stderrLogPump.isAlive()) {
            stderrLogPump = startLogPump(managedProcess.getErrorStream(), true);
        }
    }

    private Thread startLogPump(InputStream stream, boolean errorStream) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        if (errorStream) {
                            plugin.Logger.warn("[VoiceCraft] " + line);
                        } else {
                            plugin.Logger.info("[VoiceCraft] " + line);
                        }
                    }
                }
            } catch (IOException exception) {
                plugin.Logger.debugLocalized("runtime.log-pump-stopped", "$error", exception.getMessage());
            }
        }, errorStream ? "voicecraft-fabric-stderr" : "voicecraft-fabric-stdout");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void waitUntilReachable() {
        long timeoutMs = plugin.getConfig().getLong("config.voicecraft.ready-timeout-ms", 20000L);
        Instant deadline = Instant.now().plus(Duration.ofMillis(timeoutMs));
        while (Instant.now().isBefore(deadline)) {
            if (process == null || !process.isAlive()) {
                plugin.Logger.errorLocalized("runtime.exited-before-ready");
                process = null;
                return;
            }
            if (isEndpointReachable()) {
                plugin.Logger.infoLocalized("runtime.reachable");
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        plugin.Logger.warnLocalized("runtime.endpoint-not-ready");
    }

    private boolean isEndpointReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(plugin.getHost(), plugin.getPort()), 500);
            return true;
        } catch (IOException exception) {
            plugin.Logger.debugLocalized("runtime.endpoint-check-failed", "$error", exception.getMessage());
            return false;
        }
    }

    private LaunchTarget resolveReleaseLaunchTarget() throws IOException, InterruptedException {
        Path installDirectory = resolveInstallDirectory();
        Files.createDirectories(installDirectory);

        String platform = defaultPlatform();
        String architecture = defaultArchitecture();
        Path releaseMarker = installDirectory.resolve(".voicecraft-java-release");
        Path executablePath = resolveManagedExecutable(installDirectory, platform);
        VoiceCraftReleaseResolver.ReleaseAsset releaseAsset;
        try {
            releaseAsset = resolveReleaseAsset(platform, architecture);
        } catch (IOException exception) {
            if (!Files.exists(executablePath)) {
                throw exception;
            }
            plugin.Logger.warnLocalized("runtime.release-check-failed", "$error", exception.getMessage());
            Path absoluteExecutablePath = executablePath.toAbsolutePath().normalize();
            return new LaunchTarget(absoluteExecutablePath.getParent(), absoluteExecutablePath.toString(), List.of());
        }
        String releaseId = VoiceCraftRuntimeDefaults.GITLAB_BASE_URL + "|" + VoiceCraftRuntimeDefaults.GITLAB_PROJECT_PATH
                + "|" + releaseAsset.tagName() + "|" + VoiceCraftProtocol.VERSION_STRING + "|" + releaseAsset.name();

        if (!Files.exists(executablePath) || !releaseId.equals(readReleaseMarker(releaseMarker))) {
            plugin.Logger.infoLocalized("runtime.preparing", "$release", releaseAsset.tagName(), "$protocol",
                    VoiceCraftProtocol.VERSION_STRING, "$platform", platform, "$architecture", architecture);
            downloadAndExtractRelease(installDirectory, releaseAsset);
            writeReleaseMarker(releaseMarker, releaseId);
        }

        if (!Files.exists(executablePath)) {
            plugin.Logger.errorLocalized("runtime.executable-not-found", "$path", executablePath.toString());
            return null;
        }

        executablePath.toFile().setExecutable(true);
        Path absoluteExecutablePath = executablePath.toAbsolutePath().normalize();
        return new LaunchTarget(absoluteExecutablePath.getParent(), absoluteExecutablePath.toString(), List.of());
    }

    private Path resolveInstallDirectory() {
        String installDirectory = plugin.getConfig().getString("config.voicecraft.install-directory",
                VoiceCraftRuntimeDefaults.INSTALL_DIRECTORY);
        if (installDirectory == null || installDirectory.isBlank()) {
            installDirectory = VoiceCraftRuntimeDefaults.INSTALL_DIRECTORY;
        }

        Path path = Path.of(installDirectory);
        if (path.isAbsolute()) {
            return path;
        }
        return plugin.getDataFolder().resolve(path).toAbsolutePath().normalize();
    }

    private VoiceCraftReleaseResolver.ReleaseAsset resolveReleaseAsset(String platform, String architecture)
            throws IOException, InterruptedException {
        String apiUrl = VoiceCraftReleaseResolver.buildReleaseApiUrl(VoiceCraftRuntimeDefaults.GITLAB_BASE_URL,
                VoiceCraftRuntimeDefaults.GITLAB_PROJECT_PATH, VoiceCraftRuntimeDefaults.RELEASE);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected HTTP status " + response.statusCode()
                    + " while reading release metadata " + apiUrl);
        }

        JsonElement releaseJson;
        try {
            releaseJson = JsonParser.parseString(response.body());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid VoiceCraft release metadata from " + apiUrl, exception);
        }
        return VoiceCraftReleaseResolver.selectReleaseAsset(releaseJson, platform, architecture);
    }

    private Path resolveManagedExecutable(Path installDirectory, String platform) {
        String fileName = "windows".equalsIgnoreCase(platform)
                ? VoiceCraftRuntimeDefaults.EXECUTABLE_BASE_NAME + ".exe"
                : VoiceCraftRuntimeDefaults.EXECUTABLE_BASE_NAME;
        return installDirectory.resolve(fileName).normalize();
    }

    private void downloadAndExtractRelease(Path installDirectory, VoiceCraftReleaseResolver.ReleaseAsset releaseAsset)
            throws IOException, InterruptedException {
        String downloadUrl = releaseAsset.url();
        plugin.Logger.infoLocalized("runtime.downloading", "$asset", releaseAsset.name());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected HTTP status " + response.statusCode() + " while downloading " + downloadUrl);
        }
        try (InputStream body = response.body()) {
            VoiceCraftReleaseArchive.extract(body, installDirectory);
        }
    }

    private static String readReleaseMarker(Path releaseMarker) throws IOException {
        if (!Files.exists(releaseMarker)) {
            return "";
        }
        return Files.readString(releaseMarker, StandardCharsets.UTF_8).trim();
    }

    private static void writeReleaseMarker(Path releaseMarker, String releaseId) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(releaseMarker, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write(releaseId);
        }
    }

    private static String defaultPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        return "linux";
    }

    private static String defaultArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("arm")) {
            return "arm";
        }
        if (arch.contains("86") && !arch.contains("64")) {
            return "x86";
        }
        return "x64";
    }

    private record LaunchTarget(Path workingDirectory, String command, List<String> arguments) {
    }
}
