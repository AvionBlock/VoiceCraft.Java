package team.avion.paper;

import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.config.ConfigTemplateWriter;
import team.avion.common.localization.PluginLocalization;
import team.avion.common.localization.Language;
import team.avion.adapter.AdapterContext;
import team.avion.paper.adapters.plasmo.PaperPlasmoVoiceAdapter;
import team.avion.paper.adapters.simplevoicechat.PaperSimpleVoiceChatAdapter;
import team.avion.paper.commands.VoiceCommand;
import team.avion.paper.listeners.*;
import team.avion.paper.tasks.PositionsTask;
import team.avion.paper.utils.*;
import team.avion.paper.voicecraft.PaperVoiceCraftSessionManager;
import team.avion.paper.voicecraft.PlayerVoiceStatusOverlay;
import team.avion.paper.voicecraft.VoiceCraftProcessManager;
import team.avion.proxy.ProxyMessageCodec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.Objects;
import java.util.Map;

/**
 * Main plugin class for VoiceCraft.Java.
 */
public class VoiceCraftJava extends JavaPlugin implements BaseVoiceCraftJava {
    private static final String CONFIG_VERSION_PATH = "config-version";
    private static final int CURRENT_CONFIG_VERSION = 7;
    private static final String PLASMO_PUBLIC_HOST_PATH = "config.adapters.plasmo.public.host";
    private static final String[] REMOVED_VOICE_UI_PATHS = {
            "config.voice.status-icons",
            "config.voice.not-in-voice-symbol",
            "config.voice.in-voice-symbol",
            "config.voice.send-bind-message",
            "config.voice.send-disconnect-message"
    };
    private static final String TRANSPORT_HOST_PATH = "config.voicecraft.transport.host";
    private static final String TRANSPORT_PORT_PATH = "config.voicecraft.transport.port";
    private static final String TRANSPORT_LOGIN_TOKEN_PATH = "config.voicecraft.transport.login-token";
    private static final String LEGACY_HOST_PATH = "config.voicecraft.host";
    private static final String LEGACY_PORT_PATH = "config.voicecraft.port";
    private static final String LEGACY_LOGIN_TOKEN_PATH = "config.voicecraft.login-token";
    private static final String MANAGED_VOICE_PORT_PATH = "config.voicecraft.voice.port";

    private static @Getter VoiceCraftJava instance;
    private @Getter volatile boolean isConnected = false;
    private @Getter volatile String host = "";
    private @Getter volatile int port = 0;
    private @Getter volatile String loginToken = "";
    private @Getter Map<String, Boolean> playerBinds = new java.util.concurrent.ConcurrentHashMap<>();
    private @Getter volatile String token = "";
    private String lang;

    public String getLanguage() {
        return lang;
    }
    public volatile boolean usesProxy = false;
    private final @Getter PluginMessageHandler messageHandler = new PluginMessageHandler(this);
    private @Getter VoiceCraftProcessManager voiceCraftProcessManager;
    private @Getter PaperVoiceCraftSessionManager sessionManager;
    private PlayerVoiceStatusOverlay voiceStatusOverlay;
    private @Getter PositionsTask positionsTask;
    private PaperPlasmoVoiceAdapter plasmoVoiceAdapter;
    private PaperSimpleVoiceChatAdapter simpleVoiceChatAdapter;
    private volatile boolean reloadInProgress;

    private BukkitTask taskRunner;

    public PaperLogger Logger = new PaperLogger();

    /**
     * Executes upon enabling the plugin.
     */
    @Override
    public void onEnable() {
        instance = this;
        ensureLocalizedConfigExists();
        reloadConfig();
        if (!migrateConfigIfNeeded()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadConfig();
        voiceCraftProcessManager = new VoiceCraftProcessManager(this);
        sessionManager = new PaperVoiceCraftSessionManager(this);
        voiceStatusOverlay = new PlayerVoiceStatusOverlay(this);
        plasmoVoiceAdapter = new PaperPlasmoVoiceAdapter(this);
        simpleVoiceChatAdapter = new PaperSimpleVoiceChatAdapter(this);

        lang = resolveConfiguredLanguage();
        int positionTaskInterval = getConfig().getInt("config.voice.position-update-interval-ticks", 1);

        getServer().getMessenger().registerOutgoingPluginChannel(this, ProxyMessageCodec.CHANNEL_NAME);
        getServer().getMessenger().registerIncomingPluginChannel(this, ProxyMessageCodec.CHANNEL_NAME, messageHandler);

        VoiceCommand voiceCommand = new VoiceCommand(this, lang);
        getCommand("voice").setExecutor(voiceCommand);
        getCommand("voice").setTabCompleter(voiceCommand);
        positionsTask = new PositionsTask(this, lang);
        taskRunner = positionsTask.runTaskTimer(this, 1, positionTaskInterval);
        getServer().getPluginManager().registerEvents(new PlayerQuitHandler(this, lang), this);
        voiceStatusOverlay.start();

        this.reload();
    }

    @Override
    public void onDisable() {
        if (voiceStatusOverlay != null) {
            voiceStatusOverlay.stop();
        }
        stopPlasmoAdapter();
        stopSimpleVoiceChatAdapter();
        disconnect("Plugin disabled");
        if (taskRunner != null && !taskRunner.isCancelled()) {
            taskRunner.cancel();
        }
        if (voiceCraftProcessManager != null) {
            voiceCraftProcessManager.shutdown();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    /**
     * Reloads the plugin configuration and initializes connections.
     */
    public void reload() {
        reloadInProgress = true;
        try {
            reloadInternal();
        } finally {
            reloadInProgress = false;
        }
    }

    private void reloadInternal() {
        ensureLocalizedConfigExists();
        reloadConfig();
        if (!migrateConfigIfNeeded()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadConfig();
        lang = resolveConfiguredLanguage();
        Logger.info(Language.getMessage(lang, "plugin-config-loaded"));
        Logger.info(Language.getMessage(lang, "plugin-command-executor"));

        boolean newUsesProxy = getConfig().getBoolean("config.proxy.enabled", false);
        String newHost = getTransportHost();
        int newPort = getTransportPort();
        String newLoginToken = getTransportLoginToken();
        boolean connectionChanged = usesProxy != newUsesProxy
                || !Objects.equals(host, newHost)
                || port != newPort
                || !Objects.equals(loginToken, newLoginToken);
        if (sessionManager.isConnected() && connectionChanged) {
            sessionManager.disconnect();
            isConnected = false;
            token = "";
        }
        usesProxy = newUsesProxy;

        host = newHost;
        port = newPort;
        loginToken = newLoginToken;
        int proximityDistance = getConfig().getInt("config.voice.proximity-distance");
        boolean proximityToggle = getConfig().getBoolean("config.voice.proximity-toggle");
        boolean voiceEffects = getConfig().getBoolean("config.voice.voice-effects");
        sessionManager.configure(host, port, loginToken, proximityDistance, proximityToggle, voiceEffects);

        if (voiceCraftProcessManager != null) {
            voiceCraftProcessManager.ensureRunning();
        }

        if (usesProxy) {
            isConnected = true;
            token = "proxy";
        } else if (!connectionChanged && sessionManager.isConnected()) {
            isConnected = true;
            token = sessionManager.getSessionToken();
            sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
        } else if (voiceCraftProcessManager != null && voiceCraftProcessManager.isStarting()) {
            isConnected = false;
            token = "";
        } else if (getConfig().getBoolean("config.auto-reconnect")) {
            isConnected = reconnect(true);
        } else {
            isConnected = false;
            token = "";
        }

        int positionTaskInterval = getConfig().getInt("config.voice.position-update-interval-ticks", 1);
        if (!taskRunner.isCancelled())
            taskRunner.cancel();
        positionsTask = new PositionsTask(this, lang);
        taskRunner = positionsTask.runTaskTimer(this, 1, positionTaskInterval);
        if (voiceStatusOverlay != null) {
            voiceStatusOverlay.reload();
        }

        reloadPlasmoAdapter();
        reloadSimpleVoiceChatAdapter();
    }

    /**
     * Connects to a new server.
     *
     * @param host      The host to connect to.
     * @param port      The port to connect to.
     * @param loginToken The VoiceCraft login token.
     * @return True if connected successfully, otherwise false.
     */
    public Boolean connect(String host, int port, String loginToken) {
        if (Objects.nonNull(host) && Objects.nonNull(loginToken)) {
            getConfig().set(TRANSPORT_HOST_PATH, host);
            getConfig().set(TRANSPORT_PORT_PATH, port);
            getConfig().set(TRANSPORT_LOGIN_TOKEN_PATH, loginToken);
            saveConfig();
            reloadConfig();
            reload();

            return isConnected || (voiceCraftProcessManager != null && voiceCraftProcessManager.isStarting());
        } else {
            Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
            return false;
        }
    }

    /**
     * Connects to the server.
     *
     * @param force Indicates whether to force a connection.
     * @return True if connected successfully, otherwise false.
     */
    public synchronized Boolean reconnect(Boolean force) {
        if (isConnected && !force)
            return true;
        if (isConnected) {
            disconnect("Reconnecting to another server.");
        }

        if (usesProxy) {
            Logger.info(Language.getMessage(lang, "plugin-connect-proxy"));
            isConnected = true;
            token = "proxy";
            return true;
        }

        if (Objects.nonNull(host) && Objects.nonNull(loginToken)) {
            sessionManager.configure(host, port, loginToken, getConfig().getInt("config.voice.proximity-distance"),
                    getConfig().getBoolean("config.voice.proximity-toggle"),
                    getConfig().getBoolean("config.voice.voice-effects"));
            boolean connected = sessionManager.connect();
            String Token = connected ? sessionManager.getSessionToken() : null;
            if (Objects.nonNull(Token)) {
                Logger.info(Language.getMessage(lang, "plugin-connect-connected"));
                isConnected = true;
                token = Token;
                if (!reloadInProgress) {
                    scheduleAdapterReload();
                }
            } else {
                isConnected = false;
                token = "";
                Logger.warn(Language.getMessage(lang, "plugin-connect-failed"));
            }
            return isConnected;
        } else {
            Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
            return false;
        }
    }

    /**
     * Disconnects from the server.
     *
     * @param reason The reason why we disconnected
     */
    public void disconnect(String reason) {
        if (!isConnected)
            return;

        if (sessionManager.isConnected()) {
            sessionManager.disconnect();
        }

        isConnected = false;
        token = "";

        String disconnectMessage = Language.getMessage(lang, "plugin-connection-disconnect").replace("$reason",
                reason);
        Logger.info(disconnectMessage);

        boolean sendVoipDisconnectMessage = getConfig().getBoolean("config.voice.send-voicecraft-disconnect-message");
        if (sendVoipDisconnectMessage) {
            Bukkit.broadcast(Component.text(disconnectMessage).color(NamedTextColor.YELLOW));
        }
    }

    /**
     * Disconnects from the server.
     */
    public void disconnect() {
        disconnect("N.A.");
    }

    /**
     * Binds a player to the voice chat server.
     *
     * @param playerKey The key associated with the player.
     * @param player    The player to bind.
     * @return True if the binding was successful, otherwise false.
     */
    public Boolean bind(int playerKey, Player player, int tries) {
        if (!isConnected)
            return false;

        if (playerBinds.containsKey(player.getName()) && playerBinds.get(player.getName())) {
            return true;
        }
        if (usesProxy) {
            return messageHandler.sendBindRequest(player, playerKey);
        }
        boolean bound = sessionManager.bindPlayer(playerKey, player);
        if (!bound && tries == 0 && !sessionManager.isConnected()) {
            Logger.infoLocalized("session.bind-reconnect");
            isConnected = reconnect(true);
            return bind(playerKey, player, 1);
        }
        if (!bound) {
            return false;
        }

        Logger.info(Language.getMessage(lang, "player-binded").replace("$player", player.getName()));
        return true;
    }

    public Boolean bind(int playerKey, Player player) {
        return bind(playerKey, player, 0);
    }

    /**
     * Bind a fake player
     * 
     * @param bindKey
     * @param name
     * @return
     */
    public Boolean bindFake(int playerKey, String name, int tries) {
        if (!isConnected)
            return false;

        boolean bound = sessionManager.bindFakePlayer(playerKey, name);
        if (!bound && tries == 0 && !sessionManager.isConnected()) {
            Logger.infoLocalized("session.fake-bind-reconnect");
            isConnected = reconnect(true);
            return bindFake(playerKey, name, 1);
        }
        return bound;
    }

    public Boolean bindFake(int playerKey, String name) {
        return bindFake(playerKey, name, 0);
    }

    /**
     * Disconnects a player from the voice chat server.
     *
     * @param player The player to disconnect.
     * @return True if the disconnection was successful, otherwise false.
     */
    public Boolean disconnectPlayer(Player player, int tries) {
        if (!isConnected)
            return false;

        if (usesProxy) {
            return messageHandler.sendUnbindRequest(player);
        }

        boolean disconnected = sessionManager.unbindPlayer(player);
        if (!disconnected && tries == 0 && !sessionManager.isConnected()) {
            Logger.infoLocalized("session.unbind-reconnect");
            isConnected = reconnect(true);
            return disconnectPlayer(player, 1);
        }
        return disconnected;
    }

    public Boolean disconnectPlayer(Player player) {
        return disconnectPlayer(player, 0);
    }

    /**
     * Updates the voice chat settings.
     *
     * @param proximityDistance Proximity distance setting.
     * @param proximityToggle   Proximity toggle setting.
     * @param voiceEffects      Voice effects setting.
     * @return True if settings were updated successfully, otherwise false.
     */
    public Boolean updateSettings(int proximityDistance, Boolean proximityToggle, Boolean voiceEffects) {
        if (!isConnected || usesProxy)
            return false;

        return sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
    }

    public void setNotConnected() {
        if (!isConnected)
            return;
        isConnected = false;
        token = "";
        if (sessionManager.isConnected()) {
            sessionManager.close();
        }
    }

    public void saveResource(String resourcePath) {
        File outFile = new File(getDataFolder(), resourcePath);
        // Default Spigot saveResource gives a warning when the file already exists and
        // when you don't override
        // Now just skip it if the file exists
        if (!outFile.exists()) {
            saveResource(resourcePath, false);
        }
    }

    private String resolveConfiguredLanguage() {
        return PluginLocalization.resolveConfiguredLanguage(getConfig().getString("config.lang", "system"));
    }

    private void ensureLocalizedConfigExists() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            return;
        }

        String language = PluginLocalization.resolveSystemLanguage();
        String templatePath = "config/" + language + ".yml";

        getDataFolder().mkdirs();
        try (InputStream input = openConfigTemplate(templatePath)) {
            if (input == null) {
                throw new IOException("Missing embedded config template for language " + language);
            }

            ConfigTemplateWriter.write(input, configFile.toPath(),
                    Map.of("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString()));
        } catch (IOException exception) {
            Logger.errorLocalized("config.localized-create-failed", "$error", exception.getMessage());
        }
    }

    private boolean migrateConfigIfNeeded() {
        int configVersion = getConfig().getInt(CONFIG_VERSION_PATH, 0);
        if (configVersion > CURRENT_CONFIG_VERSION) {
            Logger.errorLocalized("config.version-too-new", "$version", Integer.toString(configVersion),
                    "$supported", Integer.toString(CURRENT_CONFIG_VERSION));
            Logger.errorLocalized("config.start-aborted");
            return false;
        }
        if (configVersion == CURRENT_CONFIG_VERSION) {
            return true;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);
        String language = PluginLocalization.resolveConfiguredLanguage(userConfig.getString("config.lang", "system"));
        String templatePath = "config/" + language + ".yml";

        try (InputStream input = openConfigTemplate(templatePath)) {
            if (input == null) {
                throw new IOException("Missing embedded config template for language " + language);
            }

            String template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString());
            YamlConfiguration defaultConfig = new YamlConfiguration();
            defaultConfig.loadFromString(template);

            for (String key : defaultConfig.getKeys(true)) {
                if (!defaultConfig.isConfigurationSection(key) && !userConfig.contains(key)) {
                    userConfig.set(key, defaultConfig.get(key));
                }
            }

            for (String removedPath : REMOVED_VOICE_UI_PATHS) {
                userConfig.set(removedPath, null);
            }
            String plasmoPublicHost = userConfig.getString(PLASMO_PUBLIC_HOST_PATH, "");
            if (plasmoPublicHost.isBlank()) {
                userConfig.set(PLASMO_PUBLIC_HOST_PATH, "0.0.0.0");
            }
            userConfig.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION);
            userConfig.save(configFile);
            Logger.infoLocalized("config.updated", "$from", Integer.toString(configVersion), "$to",
                    Integer.toString(CURRENT_CONFIG_VERSION));
            return true;
        } catch (Exception exception) {
            Logger.errorLocalized("config.migrate-failed", "$error", exception.getMessage());
            return false;
        }
    }

    private InputStream openConfigTemplate(String templatePath) {
        InputStream input = getResource(templatePath);
        return input != null ? input : getResource("config/en.yml");
    }

    public int getManagedVoicePort() {
        return getConfig().getInt(MANAGED_VOICE_PORT_PATH, 1111);
    }

    private String getTransportHost() {
        String value = getConfig().getString(TRANSPORT_HOST_PATH);
        return value != null ? value : getConfig().getString(LEGACY_HOST_PATH);
    }

    private int getTransportPort() {
        int value = getConfig().getInt(TRANSPORT_PORT_PATH, -1);
        return value > 0 ? value : getConfig().getInt(LEGACY_PORT_PATH);
    }

    private String getTransportLoginToken() {
        String value = getConfig().getString(TRANSPORT_LOGIN_TOKEN_PATH);
        return value != null ? value : getConfig().getString(LEGACY_LOGIN_TOKEN_PATH);
    }

    private void reloadPlasmoAdapter() {
        stopPlasmoAdapter();
        if (!getConfig().getBoolean("config.adapters.plasmo.enabled", false)) {
            return;
        }

        try {
            plasmoVoiceAdapter.start(new AdapterContext(Logger, getDataFolder().toPath(), sessionManager));
        } catch (Exception exception) {
            Logger.errorLocalized("adapter.plasmo-start-failed", "$error", exception.getMessage());
        }
    }

    private void stopPlasmoAdapter() {
        if (plasmoVoiceAdapter == null) {
            return;
        }
        try {
            plasmoVoiceAdapter.stop();
        } catch (Exception exception) {
            Logger.warnLocalized("adapter.plasmo-stop-failed", "$error", exception.getMessage());
        }
    }

    private void reloadSimpleVoiceChatAdapter() {
        stopSimpleVoiceChatAdapter();
        if (!getConfig().getBoolean("config.adapters.simple-voice-chat.enabled", false)) {
            return;
        }

        try {
            simpleVoiceChatAdapter.start(new AdapterContext(Logger, getDataFolder().toPath(), sessionManager));
        } catch (Exception exception) {
            Logger.errorLocalized("adapter.svc-start-failed", "$error", exception.getMessage());
        }
    }

    private void scheduleAdapterReload() {
        if (!isEnabled()) {
            return;
        }
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled() || !isConnected) {
                    return;
                }
                reloadPlasmoAdapter();
                reloadSimpleVoiceChatAdapter();
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Plugin shutdown raced with reconnect completion.
        }
    }

    private void stopSimpleVoiceChatAdapter() {
        if (simpleVoiceChatAdapter == null) {
            return;
        }
        try {
            simpleVoiceChatAdapter.stop();
        } catch (Exception exception) {
            Logger.warnLocalized("adapter.svc-stop-failed", "$error", exception.getMessage());
        }
    }
}
