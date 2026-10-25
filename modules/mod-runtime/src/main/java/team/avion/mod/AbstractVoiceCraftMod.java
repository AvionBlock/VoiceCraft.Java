package team.avion.mod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.config.ConfigTemplateWriter;
import team.avion.common.config.NativeVoiceConfigSynchronizer;
import team.avion.common.localization.Language;
import team.avion.common.localization.PluginLocalization;
import team.avion.common.platform.TargetCapabilities;
import team.avion.fabric.config.FabricConfig;
import team.avion.fabric.tasks.FabricPositionsTask;
import team.avion.fabric.utils.FabricLogger;
import team.avion.fabric.voicecraft.FabricVoiceCraftProcessManager;
import team.avion.fabric.voicecraft.FabricVoiceCraftSessionManager;
import team.avion.fabric.voicecraft.FabricVoiceEndpointRegistry;
import team.avion.proxy.ProxyDimensionIds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared server-side runtime used by every modloader artifact. */
public abstract class AbstractVoiceCraftMod implements BaseVoiceCraftJava {
    private static final String TRANSPORT_HOST_PATH = "config.voicecraft.transport.host";
    private static final String TRANSPORT_PORT_PATH = "config.voicecraft.transport.port";
    private static final String TRANSPORT_LOGIN_TOKEN_PATH = "config.voicecraft.transport.login-token";
    private static final String MANAGED_VOICE_PORT_PATH = "config.voicecraft.voice.port";

    private final Path dataFolder;
    private final FabricConfig config;
    private final Map<String, Boolean> playerBinds = new ConcurrentHashMap<>();
    private final TargetCapabilities targetCapabilities;

    public final FabricLogger Logger;

    private volatile boolean connected;
    private volatile boolean proxyMode;
    private ModProxyBridge proxyBridge;
    private volatile String host = "";
    private volatile int port;
    private volatile String loginToken = "";
    private volatile String token = "";
    private String lang = "en";
    private MinecraftServer server;
    private FabricVoiceCraftSessionManager sessionManager;
    private FabricVoiceEndpointRegistry voiceEndpointRegistry;
    private FabricVoiceCraftProcessManager voiceCraftProcessManager;
    private FabricPositionsTask positionsTask;
    private volatile boolean reloadInProgress;

    protected AbstractVoiceCraftMod(Path dataFolder, FabricLogger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.config = new FabricConfig(dataFolder.resolve("config.yml"));
        this.Logger = Objects.requireNonNull(logger, "logger");
        this.targetCapabilities = TargetCapabilities.load(getClass().getClassLoader());
    }

    protected final void initializeRuntime() {
        ensureLocalizedConfigExists();
        reloadConfig();
        migrateConfig();
        synchronizeNativeVoiceConfigs(false);
        installMissingDependencies();
        sessionManager = createSessionManager();
        voiceEndpointRegistry = createVoiceEndpointRegistry();
        if (targetCapabilities.plasmoVoice() && isModLoaded("plasmovoice")
                && config.getBoolean("config.adapters.plasmo.enabled", false)) {
            try {
                Class.forName("team.avion.fabric.adapters.plasmo.FabricPlasmoBootstrap")
                        .getMethod("load").invoke(null);
            } catch (ReflectiveOperationException exception) {
                Logger.error("Failed to load Plasmo Voice bridge: " + exception.getMessage());
            }
        }
        voiceCraftProcessManager = createProcessManager();
        positionsTask = createPositionsTask();
        proxyBridge = createProxyBridge();
    }

    protected abstract boolean isModLoaded(String modId);

    protected abstract void installMissingDependencies();

    protected abstract FabricVoiceCraftSessionManager createSessionManager();

    protected abstract FabricVoiceEndpointRegistry createVoiceEndpointRegistry();

    protected abstract FabricVoiceCraftProcessManager createProcessManager();

    protected abstract FabricPositionsTask createPositionsTask();

    protected ModProxyBridge createProxyBridge() {
        return null;
    }

    protected final void serverStarted(MinecraftServer server) {
        this.server = server;
        reload();
    }

    protected final void serverStopping() {
        disconnect("Server stopping");
        if (voiceCraftProcessManager != null) {
            voiceCraftProcessManager.shutdown();
        }
        server = null;
    }

    protected final void serverTick(MinecraftServer server) {
        if (positionsTask != null) {
            positionsTask.tick(server);
        }
    }

    public final MinecraftServer getServer() {
        return server;
    }

    public final FabricVoiceCraftSessionManager getSessionManager() {
        return sessionManager;
    }

    public final FabricConfig getConfig() {
        return config;
    }

    public final FabricVoiceEndpointRegistry getVoiceEndpointRegistry() {
        return voiceEndpointRegistry;
    }

    public final TargetCapabilities getTargetCapabilities() {
        return targetCapabilities;
    }

    public final boolean usesProxy() {
        return proxyMode;
    }

    public final ModProxyBridge getProxyBridge() {
        return proxyBridge;
    }

    public final String qualifyWorldId(String dimensionId) {
        return proxyMode
                ? ProxyDimensionIds.qualify(config.getString("config.proxy.server-name", ""), dimensionId)
                : dimensionId;
    }

    public final void syncPlayerBind(String playerName, boolean bound) {
        boolean previous = playerBinds.getOrDefault(playerName, false);
        playerBinds.put(playerName, bound);
        if (previous != bound) {
            Logger.infoLocalized(bound ? "voicechat.joined" : "voicechat.left", "$player", playerName);
        }
    }

    @Override
    public final void reload() {
        reloadInProgress = true;
        try {
            ensureLocalizedConfigExists();
            reloadConfig();
            warnAboutUnavailableAdapters();
            synchronizeNativeVoiceConfigs(true);
            lang = PluginLocalization.resolveConfiguredLanguage(config.getString("config.lang", "system"));

            String newHost = getTransportHost();
            int newPort = getTransportPort();
            String newLoginToken = getTransportLoginToken();
            boolean requestedProxyMode = config.getBoolean("config.proxy.enabled", false);
            String proxyServerName = config.getString("config.proxy.server-name", "");
            boolean newProxyMode = requestedProxyMode && proxyBridge != null && !proxyServerName.isBlank();
            if (requestedProxyMode && !newProxyMode) {
                Logger.error("Proxy mode requires a supported Fabric target and config.proxy.server-name matching Velocity.");
                if (sessionManager.isConnected()) {
                    sessionManager.disconnect();
                }
                connected = false;
                token = "";
                return;
            }
            boolean connectionChanged = !Objects.equals(host, newHost)
                    || port != newPort
                    || !Objects.equals(loginToken, newLoginToken)
                    || proxyMode != newProxyMode;
            if (sessionManager.isConnected() && connectionChanged) {
                sessionManager.disconnect();
                connected = false;
                token = "";
            }

            host = newHost;
            port = newPort;
            loginToken = newLoginToken;
            proxyMode = newProxyMode;
            int proximityDistance = config.getInt("config.voice.proximity-distance", 30);
            boolean proximityToggle = config.getBoolean("config.voice.proximity-toggle", true);
            boolean voiceEffects = config.getBoolean("config.voice.voice-effects", true);
            sessionManager.configure(host, port, loginToken, proximityDistance, proximityToggle, voiceEffects);

            if (voiceCraftProcessManager != null) {
                voiceCraftProcessManager.ensureRunning();
            }

            if (proxyMode) {
                if (!sessionManager.isConnected() && !sessionManager.connect()) {
                    Logger.warn("Proxy mode is active, but the auxiliary native audio connection is unavailable.");
                }
                connected = true;
                token = "proxy";
            } else if (!connectionChanged && sessionManager.isConnected()) {
                connected = true;
                token = sessionManager.getSessionToken();
                sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
            } else if (voiceCraftProcessManager != null && voiceCraftProcessManager.isStarting()) {
                connected = false;
                token = "";
            } else if (config.getBoolean("config.auto-reconnect", true)) {
                connected = reconnect(true);
            } else {
                connected = false;
                token = "";
            }
        } finally {
            reloadInProgress = false;
        }
    }

    @Override
    public final Boolean connect(String host, int port, String loginToken) {
        if (host == null || loginToken == null) {
            Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
            return false;
        }
        config.set(TRANSPORT_HOST_PATH, host);
        config.set(TRANSPORT_PORT_PATH, port);
        config.set(TRANSPORT_LOGIN_TOKEN_PATH, loginToken);
        saveConfig();
        reloadConfig();
        reload();
        return connected || (voiceCraftProcessManager != null && voiceCraftProcessManager.isStarting());
    }

    @Override
    public final synchronized Boolean reconnect(Boolean force) {
        if (connected && !force) {
            return true;
        }
        if (connected) {
            disconnect("Reconnecting to another server.");
        }
        if (proxyMode) {
            if (!sessionManager.isConnected() && !sessionManager.connect()) {
                Logger.warn("Proxy mode reconnected; the auxiliary native audio connection is still unavailable.");
            }
            connected = true;
            token = "proxy";
            return true;
        }
        if (host != null && loginToken != null) {
            sessionManager.configure(host, port, loginToken, config.getInt("config.voice.proximity-distance", 30),
                    config.getBoolean("config.voice.proximity-toggle", true),
                    config.getBoolean("config.voice.voice-effects", true));
            boolean active = sessionManager.connect();
            String newToken = active ? sessionManager.getSessionToken() : null;
            if (newToken != null) {
                Logger.info(Language.getMessage(lang, "plugin-connect-connected"));
                connected = true;
                token = newToken;
            } else {
                connected = false;
                token = "";
                Logger.warn(Language.getMessage(lang, "plugin-connect-failed"));
            }
            return connected;
        }
        Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
        return false;
    }

    @Override
    public final void disconnect(String reason) {
        if (!connected) {
            return;
        }
        if (sessionManager.isConnected()) {
            sessionManager.disconnect();
        }
        connected = false;
        token = "";
        playerBinds.clear();
        Logger.info(Language.getMessage(lang, "plugin-connection-disconnect").replace("$reason", reason));
    }

    @Override
    public final void disconnect() {
        disconnect("N.A.");
    }

    public final Boolean bind(int playerKey, ServerPlayer player) {
        if (!connected) {
            return false;
        }
        if (playerBinds.getOrDefault(player.getName().getString(), false)) {
            return true;
        }
        if (proxyMode) {
            return proxyBridge.sendBindRequest(player, playerKey);
        }
        boolean bound = sessionManager.bindPlayer(playerKey, player);
        if (!bound && !sessionManager.isConnected()) {
            connected = reconnect(true);
            return sessionManager.bindPlayer(playerKey, player);
        }
        return bound;
    }

    @Override
    public final Boolean bindFake(int bindKey, String name, int tries) {
        if (!connected) {
            return false;
        }
        if (proxyMode) {
            Logger.warn("BindFake is unavailable on a proxied backend; run it on the proxy.");
            return false;
        }
        boolean bound = sessionManager.bindFakePlayer(bindKey, name);
        if (!bound && tries == 0 && !sessionManager.isConnected()) {
            connected = reconnect(true);
            return bindFake(bindKey, name, 1);
        }
        return bound;
    }

    @Override
    public final Boolean bindFake(int bindKey, String name) {
        return bindFake(bindKey, name, 0);
    }

    @Override
    public final Boolean updateSettings(int proximityDistance, Boolean proximityToggle, Boolean voiceEffects) {
        if (!connected) {
            return false;
        }
        return sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
    }

    @Override
    public final void setNotConnected() {
        if (!connected) {
            return;
        }
        connected = false;
        token = "";
        if (sessionManager.isConnected()) {
            sessionManager.close();
        }
    }

    @Override
    public final void saveResource(String resourcePath) {
        Path target = dataFolder.resolve(resourcePath);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = openResource(resourcePath)) {
            if (input == null) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target);
        } catch (IOException exception) {
            Logger.error("Failed to save resource " + resourcePath + ": " + exception.getMessage());
        }
    }

    @Override
    public final void saveConfig() {
        config.save();
    }

    @Override
    public final void reloadConfig() {
        config.load();
    }

    public final Boolean isConnected() {
        return connected;
    }

    public final String getHost() {
        return host;
    }

    public final int getPort() {
        return port;
    }

    public final String getLoginToken() {
        return loginToken;
    }

    public final Map<String, Boolean> getPlayerBinds() {
        return playerBinds;
    }

    public final String getLanguage() {
        return lang;
    }

    public final Path getDataFolder() {
        return dataFolder;
    }

    public final int getManagedVoicePort() {
        return config.getInt(MANAGED_VOICE_PORT_PATH, 9050);
    }

    private String getTransportHost() {
        return config.getString(TRANSPORT_HOST_PATH, "127.0.0.1");
    }

    private int getTransportPort() {
        return config.getInt(TRANSPORT_PORT_PATH, 9050);
    }

    private String getTransportLoginToken() {
        return config.getString(TRANSPORT_LOGIN_TOKEN_PATH, "");
    }

    private void ensureLocalizedConfigExists() {
        Path configFile = dataFolder.resolve("config.yml");
        if (Files.exists(configFile)) {
            return;
        }
        String language = PluginLocalization.resolveSystemLanguage();
        String templatePath = "config/" + language + ".yml";
        try (InputStream input = openResource(templatePath)) {
            InputStream template = input != null ? input : openResource("config/en.yml");
            if (template == null) {
                throw new IOException("Missing embedded mod config template");
            }
            ConfigTemplateWriter.write(template, configFile,
                    Map.of("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString()));
        } catch (IOException exception) {
            Logger.error("Failed to create mod config: " + exception.getMessage());
        }
    }

    private void migrateConfig() {
        if (config.getInt("config-version", 0) >= 3) {
            return;
        }
        try (InputStream input = openResource("config/en.yml")) {
            if (input == null) {
                throw new IOException("Missing mod config template");
            }
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString());
            config.mergeMissingFrom(template);
            config.set("config-version", 3);
            config.save();
            reloadConfig();
        } catch (IOException exception) {
            Logger.error("Failed to migrate mod config: " + exception.getMessage());
        }
    }

    private void warnAboutUnavailableAdapters() {
        if (config.getBoolean("config.adapters.simple-voice-chat.enabled", false)
                && !targetCapabilities.simpleVoiceChat()) {
            Logger.warn(targetCapabilities.unsupportedMessage("Simple Voice Chat"));
        }
        if (config.getBoolean("config.adapters.plasmo.enabled", false)
                && !targetCapabilities.plasmoVoice()) {
            Logger.warn(targetCapabilities.unsupportedMessage("Plasmo Voice"));
        }
    }

    private void synchronizeNativeVoiceConfigs(boolean runtimeReload) {
        try {
            String root = "config.adapters.native-configs";
            NativeVoiceConfigSynchronizer.Settings settings = new NativeVoiceConfigSynchronizer.Settings(
                    NativeVoiceConfigSynchronizer.Owner.parse(config.getString(root + ".owner", "voicecraft")),
                    nativeEndpoint(root + ".simple-voice-chat", TargetCapabilities.SIMPLE_VOICE_CHAT,
                            "", 24454, "", 24454),
                    nativeEndpoint(root + ".plasmo", TargetCapabilities.PLASMO_VOICE,
                            "0.0.0.0", 0, "0.0.0.0", 0));
            Path configDirectory = dataFolder.getParent();
            NativeVoiceConfigSynchronizer.Result result = NativeVoiceConfigSynchronizer.synchronize(
                    configDirectory.resolve("voicechat/voicechat-server.properties"),
                    configDirectory.resolve("plasmovoice/server/config.toml"), settings);
            if (result.changed()) {
                String changed = (result.simpleVoiceChatChanged() ? "Simple Voice Chat" : "")
                        + (result.simpleVoiceChatChanged() && result.plasmoVoiceChanged() ? " and " : "")
                        + (result.plasmoVoiceChanged() ? "Plasmo Voice" : "");
                boolean nativeAlreadyLoaded = result.simpleVoiceChatChanged() && isModLoaded("voicechat")
                        || result.plasmoVoiceChanged() && isModLoaded("plasmovoice");
                if (runtimeReload || nativeAlreadyLoaded) {
                    Logger.warn("Updated native port configuration for " + changed
                            + ". Restart if a native UDP listener was already initialized.");
                } else {
                    Logger.info("Applied VoiceCraft-managed native port configuration for " + changed + ".");
                }
            }
        } catch (IllegalArgumentException exception) {
            Logger.error("Native voice configuration ownership is invalid: " + exception.getMessage());
        } catch (IOException exception) {
            Logger.error("Unable to synchronize native voice port configuration: " + exception.getMessage());
        }
    }

    private NativeVoiceConfigSynchronizer.Endpoint nativeEndpoint(String root, String adapter,
            String defaultBindAddress, int defaultBindPort, String defaultAdvertisedHost,
            int defaultAdvertisedPort) {
        return new NativeVoiceConfigSynchronizer.Endpoint(
                targetCapabilities.supports(adapter)
                        && config.getBoolean("config.adapters." + adapter + ".enabled", false),
                config.getString(root + ".bind-address", defaultBindAddress),
                config.getInt(root + ".bind-port", defaultBindPort),
                config.getString(root + ".advertised-host", defaultAdvertisedHost),
                config.getInt(root + ".advertised-port", defaultAdvertisedPort));
    }

    private InputStream openResource(String resourcePath) {
        return getClass().getClassLoader().getResourceAsStream(resourcePath);
    }
}
