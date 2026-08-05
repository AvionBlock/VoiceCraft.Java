package team.avion.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import team.avion.common.BaseVoiceCraftJava;
import team.avion.common.config.ConfigTemplateWriter;
import team.avion.common.localization.Language;
import team.avion.common.localization.PluginLocalization;
import team.avion.fabric.config.FabricConfig;
import team.avion.fabric.tasks.FabricPositionsTask;
import team.avion.fabric.utils.FabricLogger;
import team.avion.fabric.voicecraft.FabricVoiceCraftProcessManager;
import team.avion.fabric.voicecraft.FabricVoiceCraftSessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceCraftJava implements ModInitializer, BaseVoiceCraftJava {
    private static final String TRANSPORT_HOST_PATH = "config.voicecraft.transport.host";
    private static final String TRANSPORT_PORT_PATH = "config.voicecraft.transport.port";
    private static final String TRANSPORT_LOGIN_TOKEN_PATH = "config.voicecraft.transport.login-token";
    private static final String MANAGED_VOICE_PORT_PATH = "config.voicecraft.voice.port";

    private static VoiceCraftJava instance;

    private final Path dataFolder = FabricLoader.getInstance().getConfigDir().resolve("voicecraft-java");
    private final FabricConfig config = new FabricConfig(dataFolder.resolve("config.yml"));
    private final Map<String, Boolean> playerBinds = new ConcurrentHashMap<>();

    public final FabricLogger Logger = new FabricLogger();

    private volatile boolean connected;
    private volatile String host = "";
    private volatile int port;
    private volatile String loginToken = "";
    private volatile String token = "";
    private String lang = "en";
    private MinecraftServer server;
    private FabricVoiceCraftSessionManager sessionManager;
    private FabricVoiceCraftProcessManager voiceCraftProcessManager;
    private FabricPositionsTask positionsTask;
    private volatile boolean reloadInProgress;

    public static VoiceCraftJava getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        instance = this;
        ensureLocalizedConfigExists();
        reloadConfig();
        sessionManager = new FabricVoiceCraftSessionManager(this);
        voiceCraftProcessManager = new FabricVoiceCraftProcessManager(this);
        positionsTask = new FabricPositionsTask(this);

        registerCommands();
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        reload();
    }

    private void onServerStopping(MinecraftServer ignored) {
        disconnect("Server stopping");
        if (voiceCraftProcessManager != null) {
            voiceCraftProcessManager.shutdown();
        }
        server = null;
    }

    private void onServerTick(MinecraftServer server) {
        if (positionsTask != null) {
            positionsTask.tick(server);
        }
    }

    @Override
    public void reload() {
        reloadInProgress = true;
        try {
            ensureLocalizedConfigExists();
            reloadConfig();
            lang = PluginLocalization.resolveConfiguredLanguage(config.getString("config.lang", "system"));

            String newHost = getTransportHost();
            int newPort = getTransportPort();
            String newLoginToken = getTransportLoginToken();
            boolean connectionChanged = !Objects.equals(host, newHost)
                    || port != newPort
                    || !Objects.equals(loginToken, newLoginToken);
            if (sessionManager.isConnected() && connectionChanged) {
                sessionManager.disconnect();
                connected = false;
                token = "";
            }

            host = newHost;
            port = newPort;
            loginToken = newLoginToken;
            int proximityDistance = config.getInt("config.voice.proximity-distance", 30);
            boolean proximityToggle = config.getBoolean("config.voice.proximity-toggle", true);
            boolean voiceEffects = config.getBoolean("config.voice.voice-effects", true);
            sessionManager.configure(host, port, loginToken, proximityDistance, proximityToggle, voiceEffects);

            if (voiceCraftProcessManager != null) {
                voiceCraftProcessManager.ensureRunning();
            }

            if (!connectionChanged && sessionManager.isConnected()) {
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
    public Boolean connect(String host, int port, String loginToken) {
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
    public synchronized Boolean reconnect(Boolean force) {
        if (connected && !force) {
            return true;
        }
        if (connected) {
            disconnect("Reconnecting to another server.");
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
    public void disconnect(String reason) {
        if (!connected) {
            return;
        }
        if (sessionManager.isConnected()) {
            sessionManager.disconnect();
        }
        connected = false;
        token = "";
        Logger.info(Language.getMessage(lang, "plugin-connection-disconnect").replace("$reason", reason));
    }

    @Override
    public void disconnect() {
        disconnect("N.A.");
    }

    public Boolean bind(int playerKey, ServerPlayerEntity player) {
        if (!connected) {
            return false;
        }
        if (playerBinds.getOrDefault(player.getName().getString(), false)) {
            return true;
        }
        boolean bound = sessionManager.bindPlayer(playerKey, player);
        if (!bound && !sessionManager.isConnected()) {
            connected = reconnect(true);
            return sessionManager.bindPlayer(playerKey, player);
        }
        return bound;
    }

    @Override
    public Boolean bindFake(int bindKey, String name, int tries) {
        if (!connected) {
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
    public Boolean bindFake(int bindKey, String name) {
        return bindFake(bindKey, name, 0);
    }

    @Override
    public Boolean updateSettings(int proximityDistance, Boolean proximityToggle, Boolean voiceEffects) {
        if (!connected) {
            return false;
        }
        return sessionManager.updateSettings(proximityDistance, proximityToggle, voiceEffects);
    }

    @Override
    public void setNotConnected() {
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
    public void saveResource(String resourcePath) {
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
    public void saveConfig() {
        config.save();
    }

    @Override
    public void reloadConfig() {
        config.load();
    }

    public Boolean isConnected() {
        return connected;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getLoginToken() {
        return loginToken;
    }

    public Map<String, Boolean> getPlayerBinds() {
        return playerBinds;
    }

    public String getLanguage() {
        return lang;
    }

    public Path getDataFolder() {
        return dataFolder;
    }

    public FabricConfig getConfig() {
        return config;
    }

    public FabricVoiceCraftSessionManager getSessionManager() {
        return sessionManager;
    }

    public int getManagedVoicePort() {
        return config.getInt(MANAGED_VOICE_PORT_PATH, 1111);
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
                throw new IOException("Missing embedded Fabric config template");
            }
            ConfigTemplateWriter.write(template, configFile,
                    Map.of("__GENERATED_LOGIN_TOKEN__", UUID.randomUUID().toString()));
        } catch (IOException exception) {
            Logger.error("Failed to create Fabric config: " + exception.getMessage());
        }
    }

    private InputStream openResource(String resourcePath) {
        return VoiceCraftJava.class.getClassLoader().getResourceAsStream(resourcePath);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("voice")
                        .requires(source -> source.hasPermissionLevel(0))
                        .then(CommandManager.literal("reload")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> {
                                    reload();
                                    send(context.getSource(), "commands.reload");
                                    return 1;
                                }))
                        .then(CommandManager.literal("disconnect")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> {
                                    disconnect("Disconnection Request.");
                                    send(context.getSource(), "commands.disconnect.disconnecting");
                                    return 1;
                                }))
                        .then(CommandManager.literal("reconnect")
                                .requires(source -> source.hasPermissionLevel(2))
                                .executes(context -> reconnectCommand(context.getSource(), false))
                                .then(CommandManager.argument("force", StringArgumentType.word())
                                        .executes(context -> reconnectCommand(context.getSource(),
                                                Boolean.parseBoolean(StringArgumentType.getString(context, "force"))))))
                        .then(CommandManager.literal("connect")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument("host", StringArgumentType.word())
                                        .then(CommandManager.argument("port", IntegerArgumentType.integer(1, 65535))
                                                .then(CommandManager.argument("token", StringArgumentType.word())
                                                        .executes(context -> {
                                                            boolean active = connect(
                                                                    StringArgumentType.getString(context, "host"),
                                                                    IntegerArgumentType.getInteger(context, "port"),
                                                                    StringArgumentType.getString(context, "token"));
                                                            send(context.getSource(), active
                                                                    ? "plugin-connect-connected"
                                                                    : "plugin-connect-failed");
                                                            return active ? 1 : 0;
                                                        })))))
                        .then(CommandManager.literal("bind")
                                .then(CommandManager.argument("key", IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            boolean bound = bind(IntegerArgumentType.getInteger(context, "key"), player);
                                            send(context.getSource(), bound ? "commands.bind.binded" : "commands.bind.failed");
                                            return bound ? 1 : 0;
                                        })))
                        .then(CommandManager.literal("bindfake")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument("key", IntegerArgumentType.integer(0))
                                        .then(CommandManager.argument("name", StringArgumentType.word())
                                                .executes(context -> {
                                                    boolean bound = bindFake(
                                                            IntegerArgumentType.getInteger(context, "key"),
                                                            StringArgumentType.getString(context, "name"));
                                                    send(context.getSource(), bound
                                                            ? "commands.bind.binded"
                                                            : "commands.bind.failed");
                                                    return bound ? 1 : 0;
                                                }))))));
    }

    private int reconnectCommand(ServerCommandSource source, boolean force) {
        send(source, "plugin-connect-connecting");
        boolean active = reconnect(force);
        send(source, active ? "plugin-connect-connected" : "plugin-connect-failed");
        return active ? 1 : 0;
    }

    private void send(ServerCommandSource source, String key) {
        source.sendFeedback(() -> Text.literal(Language.getMessage(lang, key)), false);
    }
}
