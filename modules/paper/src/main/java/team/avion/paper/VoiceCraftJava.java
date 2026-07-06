package team.avion.paper;

import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.config.ConfigTemplateWriter;
import team.avion.common.localization.PluginLocalization;
import team.avion.adapter.AdapterContext;
import team.avion.paper.adapters.plasmo.PaperPlasmoVoiceAdapter;
import team.avion.paper.adapters.simplevoicechat.PaperSimpleVoiceChatAdapter;
import team.avion.paper.commands.VoiceCommand;
import team.avion.paper.listeners.*;
import team.avion.paper.tasks.PositionsTask;
import team.avion.paper.utils.*;
import team.avion.paper.voicecraft.PaperVoiceCraftSessionManager;
import team.avion.paper.voicecraft.VoiceCraftProcessManager;
import team.avion.proxy.ProxyMessageCodec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

/**
 * Main plugin class for VoiceCraft.Java.
 */
public class VoiceCraftJava extends JavaPlugin implements BaseVoiceCraftJava {
    private static final String TRANSPORT_HOST_PATH = "config.voicecraft.transport.host";
    private static final String TRANSPORT_PORT_PATH = "config.voicecraft.transport.port";
    private static final String TRANSPORT_LOGIN_TOKEN_PATH = "config.voicecraft.transport.login-token";
    private static final String LEGACY_HOST_PATH = "config.voicecraft.host";
    private static final String LEGACY_PORT_PATH = "config.voicecraft.port";
    private static final String LEGACY_LOGIN_TOKEN_PATH = "config.voicecraft.login-token";
    private static final String MANAGED_VOICE_PORT_PATH = "config.voicecraft.voice.port";

    private static @Getter VoiceCraftJava instance;
    private @Getter boolean isConnected = false;
    private @Getter String host = "";
    private @Getter int port = 0;
    private @Getter String loginToken = "";
    private @Getter Map<String, Boolean> playerBinds = new HashMap<>();
    private @Getter String token = "";
    private String lang;
    public boolean usesProxy = false;
    private final @Getter PluginMessageHandler messageHandler = new PluginMessageHandler(this);
    private @Getter VoiceCraftProcessManager voiceCraftProcessManager;
    private @Getter PaperVoiceCraftSessionManager sessionManager;
    private @Getter PositionsTask positionsTask;
    private PaperPlasmoVoiceAdapter plasmoVoiceAdapter;
    private PaperSimpleVoiceChatAdapter simpleVoiceChatAdapter;

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
        voiceCraftProcessManager = new VoiceCraftProcessManager(this);
        sessionManager = new PaperVoiceCraftSessionManager(this);
        plasmoVoiceAdapter = new PaperPlasmoVoiceAdapter(this);
        simpleVoiceChatAdapter = new PaperSimpleVoiceChatAdapter(this);

        lang = resolveConfiguredLanguage();
        int positionTaskInterval = getConfig().getInt("config.voice.position-update-interval-ticks", 1);
        Language.init(this);

        getServer().getMessenger().registerOutgoingPluginChannel(this, ProxyMessageCodec.CHANNEL_NAME);
        getServer().getMessenger().registerIncomingPluginChannel(this, ProxyMessageCodec.CHANNEL_NAME, messageHandler);

        VoiceCommand voiceCommand = new VoiceCommand(this, lang);
        getCommand("voice").setExecutor(voiceCommand);
        getCommand("voice").setTabCompleter(voiceCommand);
        positionsTask = new PositionsTask(this, lang);
        taskRunner = positionsTask.runTaskTimer(this, 1, positionTaskInterval);
        getServer().getPluginManager().registerEvents(new PlayerQuitHandler(this, lang), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new Placeholder(this).register();
        }

        this.reload();
    }

    @Override
    public void onDisable() {
        disconnect("Plugin disabled");
        if (voiceCraftProcessManager != null) {
            voiceCraftProcessManager.shutdown();
        }
        stopPlasmoAdapter();
        stopSimpleVoiceChatAdapter();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    /**
     * Reloads the plugin configuration and initializes connections.
     */
    public void reload() {
        ensureLocalizedConfigExists();
        reloadConfig();
        lang = resolveConfiguredLanguage();
        Logger.info(Language.getMessage(lang, "plugin-config-loaded"));
        Logger.info(Language.getMessage(lang, "plugin-command-executor"));

        boolean newUsesProxy = getConfig().getBoolean("config.proxy.enabled", false);
        if (sessionManager.isConnected() && usesProxy != newUsesProxy) {
            sessionManager.disconnect();
        }
        usesProxy = newUsesProxy;

        host = getTransportHost();
        port = getTransportPort();
        loginToken = getTransportLoginToken();
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

        updateSettings(proximityDistance, proximityToggle, voiceEffects);
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

            return isConnected;
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
    public Boolean reconnect(Boolean force) {
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
            } else {
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
            Logger.info("VoiceCraft session dropped during bind, reconnecting...");
            isConnected = reconnect(true);
            return bind(playerKey, player, 1);
        }
        if (!bound) {
            return false;
        }

        Logger.info(Language.getMessage(lang, "player-binded").replace("$player", player.getName()));

        boolean sendBindedMessage = getConfig().getBoolean("config.voice.send-bind-message");
        if (sendBindedMessage) {
            Bukkit.broadcast(
                    Component.text(player.getName()).decorate(TextDecoration.BOLD)
                            .append(Component.text(
                                    Language.getMessage(lang, "player-binded").replace("$player", ""))
                                    .color(NamedTextColor.DARK_GREEN)));
        }
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
            Logger.info("VoiceCraft session dropped during fake bind, reconnecting...");
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
            Logger.info("VoiceCraft session dropped during unbind, reconnecting...");
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
            Logger.error("Could not create localized config.yml: " + exception.getMessage());
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
            Logger.error("Failed to start Plasmo Voice adapter: " + exception.getMessage());
        }
    }

    private void stopPlasmoAdapter() {
        if (plasmoVoiceAdapter == null) {
            return;
        }
        try {
            plasmoVoiceAdapter.stop();
        } catch (Exception exception) {
            Logger.warn("Failed to stop Plasmo Voice adapter: " + exception.getMessage());
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
            Logger.error("Failed to start Simple Voice Chat adapter: " + exception.getMessage());
        }
    }

    private void stopSimpleVoiceChatAdapter() {
        if (simpleVoiceChatAdapter == null) {
            return;
        }
        try {
            simpleVoiceChatAdapter.stop();
        } catch (Exception exception) {
            Logger.warn("Failed to stop Simple Voice Chat adapter: " + exception.getMessage());
        }
    }
}
