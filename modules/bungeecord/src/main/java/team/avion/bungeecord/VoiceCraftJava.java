package team.avion.bungeecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import team.avion.bungeecord.commands.VoiceCommand;
import team.avion.bungeecord.listeners.PlayerJoinHandler;
import team.avion.bungeecord.listeners.PlayerQuitHandler;
import team.avion.bungeecord.listeners.PluginMessageHandler;
import team.avion.bungeecord.tasks.PositionsTask;
import team.avion.bungeecord.utils.BungeecordLogger;
import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.config.ConfigTemplateWriter;
import team.avion.common.localization.PluginLocalization;
import team.avion.common.localization.Language;
import team.avion.proxy.VoiceCraftProxySessionManager;
import team.avion.proxy.VoiceProxyUdpRelayManager;

public class VoiceCraftJava extends Plugin implements BaseVoiceCraftJava {
    private static final String CONFIG_VERSION_PATH = "config-version";
    private static final int CURRENT_CONFIG_VERSION = 3;
    private static final String[] REMOVED_PLAYER_NOTIFICATION_PATHS = {
            "config.voice.send-bind-message",
            "config.voice.send-disconnect-message"
    };
    private static final String TRANSPORT_HOST_PATH = "config.voicecraft.transport.host";
    private static final String TRANSPORT_PORT_PATH = "config.voicecraft.transport.port";
    private static final String TRANSPORT_LOGIN_TOKEN_PATH = "config.voicecraft.transport.login-token";
    private static final String LEGACY_HOST_PATH = "config.voicecraft.host";
    private static final String LEGACY_PORT_PATH = "config.voicecraft.port";
    private static final String LEGACY_LOGIN_TOKEN_PATH = "config.voicecraft.login-token";

    private static @Getter Configuration config;
    private static @Getter VoiceCraftJava instance;

    private @Getter boolean isConnected = false;
    private @Getter String host = "";
    private @Getter int port = 0;
    private @Getter String loginToken = "";
    private final @Getter Map<String, Boolean> playerBinds = new HashMap<>();
    private @Getter String token = "";
    private String lang;

    public String getLanguage() {
        return lang;
    }
    private final @Getter PluginMessageHandler messageHandler = new PluginMessageHandler(this);
    private final @Getter VoiceCraftProxySessionManager sessionManager;
    private final @Getter VoiceProxyUdpRelayManager udpRelayManager;

    private @Getter ScheduledTask taskRunner;

    public final BungeecordLogger Logger = new BungeecordLogger();

    public VoiceCraftJava() {
        this.sessionManager = new VoiceCraftProxySessionManager(Logger);
        this.udpRelayManager = new VoiceProxyUdpRelayManager(Logger);
    }

    @Override
    public void onEnable() {
        instance = this;

        ensureLocalizedConfigExists();
        reloadConfig();
        if (!migrateConfigIfNeeded()) {
            return;
        }
        reloadConfig();
        lang = resolveConfiguredLanguage();

        getProxy().registerChannel(PluginMessageHandler.CHANNEL);
        getProxy().getPluginManager().registerListener(this, messageHandler);
        getProxy().getPluginManager().registerCommand(this, new VoiceCommand(this, lang));
        getProxy().getPluginManager().registerListener(this, new PlayerJoinHandler(this, lang));
        getProxy().getPluginManager().registerListener(this, new PlayerQuitHandler(this, lang));

        reload();
    }

    @Override
    public void onDisable() {
        getProxy().unregisterChannel(PluginMessageHandler.CHANNEL);
        if (taskRunner != null) {
            taskRunner.cancel();
        }
        udpRelayManager.close();
        sessionManager.close();
    }

    @Override
    public void reload() {
        reloadConfig();
        if (!migrateConfigIfNeeded()) {
            return;
        }
        reloadConfig();
        lang = resolveConfiguredLanguage();
        Logger.info(Language.getMessage(lang, "plugin-config-loaded"));
        Logger.info(Language.getMessage(lang, "plugin-command-executor"));

        host = getTransportHost();
        port = getTransportPort();
        loginToken = getTransportLoginToken();
        int proximityDistance = getConfig().getInt("config.voice.proximity-distance", 30);
        boolean proximityToggle = getConfig().getBoolean("config.voice.proximity-toggle", true);
        boolean voiceEffects = getConfig().getBoolean("config.voice.voice-effects", true);
        sessionManager.configure(host, port, loginToken, proximityDistance, proximityToggle, voiceEffects);

        if (getConfig().getBoolean("config.auto-reconnect", true)) {
            isConnected = reconnect(true);
        }

        int positionTaskInterval = getConfig().getInt("config.voice.position-update-interval-ticks", 1);
        if (taskRunner != null) {
            taskRunner.cancel();
        }
        taskRunner = getProxy().getScheduler().schedule(this, new PositionsTask(this, lang), 1,
                50L * positionTaskInterval, TimeUnit.MILLISECONDS);

        updateSettings(proximityDistance, proximityToggle, voiceEffects);
        reloadUdpRelays();
    }

    @Override
    public Boolean connect(String host, int port, String loginToken) {
        if (host == null || loginToken == null) {
            Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
            return false;
        }

        getConfig().set(TRANSPORT_HOST_PATH, host);
        getConfig().set(TRANSPORT_PORT_PATH, port);
        getConfig().set(TRANSPORT_LOGIN_TOKEN_PATH, loginToken);
        saveConfig();
        reload();
        return isConnected;
    }

    @Override
    public Boolean reconnect(Boolean force) {
        if (isConnected && !force) {
            return true;
        }
        if (isConnected) {
            disconnect("Reconnecting to another server.");
        }

        if (Objects.nonNull(host) && Objects.nonNull(loginToken)) {
            boolean connected = sessionManager.connect();
            String sessionToken = connected ? sessionManager.getSessionToken() : null;
            if (sessionToken != null && !sessionToken.isBlank()) {
                Logger.info(Language.getMessage(lang, "plugin-connect-connected"));
                isConnected = true;
                token = sessionToken;
            } else {
                Logger.warn(Language.getMessage(lang, "plugin-connect-failed"));
            }
            return isConnected;
        }

        Logger.warn(Language.getMessage(lang, "plugin-connect-invalid-data"));
        return false;
    }

    @Override
    public void disconnect(String reason) {
        if (!isConnected) {
            return;
        }

        if (sessionManager.isConnected()) {
            sessionManager.disconnect();
        }
        isConnected = false;
        token = "";
        playerBinds.clear();

        String disconnectMessage = Language.getMessage(lang, "plugin-connection-disconnect").replace("$reason", reason);
        Logger.info(disconnectMessage);
        if (getConfig().getBoolean("config.voice.send-voicecraft-disconnect-message", true)) {
            getProxy().broadcast(new ComponentBuilder(disconnectMessage).color(ChatColor.YELLOW).create());
        }
    }

    @Override
    public void disconnect() {
        disconnect("N.A.");
    }

    public Boolean bind(int playerKey, ProxiedPlayer player, int tries) {
        if (!isConnected) {
            return false;
        }
        if (playerBinds.getOrDefault(player.getName(), false)) {
            return true;
        }

        boolean bound = sessionManager.bindPlayer(playerKey, player.getUniqueId().toString(), player.getName());
        if (!bound && tries == 0 && !sessionManager.isConnected()) {
            isConnected = reconnect(true);
            return bind(playerKey, player, 1);
        }
        if (!bound) {
            messageHandler.sendPlayerBindSync(player);
            return false;
        }

        playerBinds.put(player.getName(), true);
        messageHandler.sendPlayerBindSync(player);
        Logger.info(Language.getMessage(lang, "player-binded").replace("$player", player.getName()));
        return true;
    }

    public Boolean bind(int playerKey, ProxiedPlayer player) {
        return bind(playerKey, player, 0);
    }

    @Override
    public Boolean bindFake(int playerKey, String name, int tries) {
        if (!isConnected) {
            return false;
        }
        boolean bound = sessionManager.bindFakePlayer(playerKey, "fake:" + playerKey, name);
        if (!bound && tries == 0 && !sessionManager.isConnected()) {
            isConnected = reconnect(true);
            return bindFake(playerKey, name, 1);
        }
        if (bound) {
            playerBinds.put(name, true);
        }
        return bound;
    }

    @Override
    public Boolean bindFake(int playerKey, String name) {
        return bindFake(playerKey, name, 0);
    }

    public Boolean disconnectPlayer(ProxiedPlayer player, int tries) {
        if (!isConnected) {
            return false;
        }

        boolean disconnected = sessionManager.unbindPlayer(player.getUniqueId().toString());
        if (!disconnected && tries == 0 && !sessionManager.isConnected()) {
            isConnected = reconnect(true);
            return disconnectPlayer(player, 1);
        }
        if (disconnected) {
            playerBinds.remove(player.getName());
            messageHandler.sendBindSync(player.getName(), false);
        }
        return disconnected;
    }

    public Boolean disconnectPlayer(ProxiedPlayer player) {
        return disconnectPlayer(player, 0);
    }

    public boolean handleProxyBindRequest(int bindingKey, String playerId, String playerName) {
        boolean bound = sessionManager.bindPlayer(bindingKey, playerId, playerName);
        if (bound) {
            playerBinds.put(playerName, true);
        }
        return bound;
    }

    public void handleProxyUnbindRequest(String playerId, String playerName) {
        sessionManager.unbindPlayer(playerId);
        playerBinds.remove(playerName);
    }

    @Override
    public Boolean updateSettings(int proximityDistance, Boolean proximityToggle, Boolean voiceEffects) {
        if (!isConnected) {
            return false;
        }
        return sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
    }

    @Override
    public void setNotConnected() {
        if (!isConnected) {
            return;
        }
        isConnected = false;
        token = "";
        playerBinds.clear();
        sessionManager.close();
    }

    public void reloadConfig() {
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(getDataFolder(), "config.yml"));
        } catch (IOException exception) {
            Logger.errorLocalized("config.reload-failed", "$error", exception.getMessage());
        }
    }

    private void reloadUdpRelays() {
        udpRelayManager.configure(VoiceProxyUdpRelayManager.ADAPTER_PLASMO,
                getConfig().getBoolean("config.adapters.plasmo.proxy.enabled", false),
                getConfig().getString("config.adapters.plasmo.proxy.bind-host", "0.0.0.0"),
                getConfig().getInt("config.adapters.plasmo.proxy.port", 24454));
        udpRelayManager.configure(VoiceProxyUdpRelayManager.ADAPTER_SIMPLE_VOICE_CHAT,
                getConfig().getBoolean("config.adapters.simple-voice-chat.proxy.enabled", false),
                getConfig().getString("config.adapters.simple-voice-chat.proxy.bind-host", "0.0.0.0"),
                getConfig().getInt("config.adapters.simple-voice-chat.proxy.port", 24455));
    }

    @Override
    public void saveConfig() {
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, new File(getDataFolder(), "config.yml"));
        } catch (IOException exception) {
            Logger.errorLocalized("config.save-failed", "$error", exception.getMessage());
        }
    }

    @Override
    public void saveResource(String resourcePath) {
        saveResource(resourcePath, false);
    }

    public void saveResource(String resourcePath, boolean replace) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }

        resourcePath = resourcePath.replace("\\", "/");
        try (InputStream in = getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("The embedded resource '" + resourcePath + "' cannot be found");
            }

            File outFile = new File(getDataFolder(), resourcePath);
            File outDir = outFile.getParentFile();
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            if (!outFile.exists() || replace) {
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
        } catch (IOException ex) {
            Logger.errorLocalized("config.resource-save-path-failed", "$resource", resourcePath, "$path",
                    new File(getDataFolder(), resourcePath).toString());
        }
    }

    private String resolveConfiguredLanguage() {
        return PluginLocalization.resolveConfiguredLanguage(getConfig().getString("config.lang", "system"));
    }

    private void ensureLocalizedConfigExists() {
        File target = new File(getDataFolder(), "config.yml");
        if (target.exists()) {
            return;
        }

        String language = PluginLocalization.resolveSystemLanguage();
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (InputStream input = openConfigTemplate(language)) {
            if (input == null) {
                throw new IOException("Missing embedded config template for language " + language);
            }
            ConfigTemplateWriter.write(input, target.toPath(),
                    Map.of("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString()));
        } catch (IOException exception) {
            Logger.errorLocalized("config.localized-create-failed", "$error", exception.getMessage());
        }
    }

    private InputStream openConfigTemplate(String language) {
        InputStream input = getResourceAsStream("config/" + language + ".yml");
        return input != null ? input : getResourceAsStream("config/en.yml");
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

        String language = PluginLocalization.resolveConfiguredLanguage(getConfig().getString("config.lang", "system"));
        try (InputStream input = openConfigTemplate(language)) {
            if (input == null) {
                throw new IOException("Missing embedded config template for language " + language);
            }

            String template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString());
            Configuration defaultConfig = ConfigurationProvider.getProvider(YamlConfiguration.class)
                    .load(new StringReader(template));
            mergeMissingConfig(config, defaultConfig, "");
            for (String removedPath : REMOVED_PLAYER_NOTIFICATION_PATHS) {
                config.set(removedPath, null);
            }
            config.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION);
            saveConfig();
            Logger.infoLocalized("config.updated", "$from", Integer.toString(configVersion), "$to",
                    Integer.toString(CURRENT_CONFIG_VERSION));
            return true;
        } catch (Exception exception) {
            Logger.errorLocalized("config.migrate-failed", "$error", exception.getMessage());
            return false;
        }
    }

    private static void mergeMissingConfig(Configuration target, Configuration defaults, String prefix) {
        for (String key : defaults.getKeys()) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = defaults.get(key);
            if (value instanceof Configuration section) {
                mergeMissingConfig(target, section, path);
            } else if (!target.contains(path)) {
                target.set(path, value);
            }
        }
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
}
