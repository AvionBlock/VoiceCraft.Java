package team.avion.paper.adapters.simplevoicechat;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.adapter.simplevoicechat.SimpleVoiceChatChannels;
import team.avion.adapter.simplevoicechat.net.AuthenticatePacket;
import team.avion.adapter.simplevoicechat.net.MicPacket;
import team.avion.adapter.simplevoicechat.net.PlayerSoundPacket;
import team.avion.adapter.simplevoicechat.net.SimplePacket;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatPacketType;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatTcpCodec;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatUdpCodec;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatUdpEnvelope;
import team.avion.adapter.simplevoicechat.net.SimpleVoiceChatUdpPacket;
import team.avion.adapter.simplevoicechat.session.SimpleVoiceChatSessionManager.AudioEndDebugMetrics;
import team.avion.adapter.simplevoicechat.session.SimpleVoiceChatClientSession;
import team.avion.adapter.simplevoicechat.session.SimpleVoiceChatSessionManager;
import team.avion.paper.VoiceCraftJava;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper adapter that speaks the Simple Voice Chat plugin-message and UDP protocols.
 *
 * <p>The adapter performs the SVC secret handshake, tracks UDP sessions, publishes SVC player
 * states, and forwards Opus frames between Simple Voice Chat clients and the shared VoiceCraft
 * audio bridge.</p>
 */
public final class PaperSimpleVoiceChatAdapter implements VoiceAdapter, VoiceCraftAudioSink, VoiceCraftIdentityListener,
        PluginMessageListener, Listener {
    private static final long KEEP_ALIVE_TIMEOUT_FACTOR = 10L;

    private final VoiceCraftJava plugin;
    private final SimpleVoiceChatTcpCodec tcpCodec = new SimpleVoiceChatTcpCodec();
    private final SimpleVoiceChatUdpCodec udpCodec = new SimpleVoiceChatUdpCodec();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AdapterState state = AdapterState.NEW;
    private SimpleVoiceChatSessionManager sessions;
    private DatagramSocket udpSocket;
    private Thread udpThread;
    private String bindHost;
    private String voiceHost;
    private int udpPort;
    private int publicPort;
    private int compatibilityVersion;
    private int mtuSize;
    private int keepAliveMillis;
    private double voiceDistance;
    private boolean allowRecording;
    private boolean debug;
    private AdapterContext context;
    private long audioDebugWindowMillis;
    private int receivedAudioFrames;
    private int receivedAudioBytes;
    private int forwardedAudioFrames;
    private int forwardedAudioBytes;
    private int svcEmptyMicPackets;
    private int svcAudioEndsSent;
    private int svcAudioEndsCancelledByAudio;
    private final Set<String> sentStates = ConcurrentHashMap.newKeySet();

    public PaperSimpleVoiceChatAdapter(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "simple-voice-chat";
    }

    @Override
    public String displayName() {
        return "Simple Voice Chat";
    }

    @Override
    public AdapterState state() {
        return state;
    }

    @Override
    public void start(AdapterContext context) throws Exception {
        if (running.get()) {
            return;
        }

        state = AdapterState.STARTING;
        this.context = context;
        this.sessions = new SimpleVoiceChatSessionManager(context.audioBridge(), new AudioEndDebugMetrics() {
            @Override
            public void svcEmptyMicReceived() {
                debugSvcEmptyMicReceived();
            }

            @Override
            public void svcAudioEndSent() {
                debugSvcAudioEndSent();
            }

            @Override
            public void svcAudioEndCancelledByAudio() {
                debugSvcAudioEndCancelledByAudio();
            }
        });
        this.bindHost = plugin.getConfig().getString("config.adapters.simple-voice-chat.bind-host", "0.0.0.0");
        this.udpPort = plugin.getConfig().getInt("config.adapters.simple-voice-chat.port", 24455);
        this.voiceHost = plugin.getConfig().getString("config.adapters.simple-voice-chat.public.host", "");
        int configuredPublicPort = plugin.getConfig().getInt("config.adapters.simple-voice-chat.public.port", 0);
        this.publicPort = configuredPublicPort > 0 ? configuredPublicPort : udpPort;
        this.compatibilityVersion = plugin.getConfig().getInt("config.adapters.simple-voice-chat.compatibility-version", 20);
        this.mtuSize = plugin.getConfig().getInt("config.adapters.simple-voice-chat.mtu-size", 1024);
        this.keepAliveMillis = plugin.getConfig().getInt("config.adapters.simple-voice-chat.keep-alive-ms", 1000);
        this.voiceDistance = plugin.getConfig().getDouble("config.voice.proximity-distance", 30D);
        this.allowRecording = plugin.getConfig().getBoolean("config.adapters.simple-voice-chat.allow-recording", true);
        this.debug = plugin.getConfig().getBoolean("config.debug", false);

        udpSocket = new DatagramSocket(new InetSocketAddress(bindHost, udpPort));
        udpSocket.setSoTimeout(1000);
        running.set(true);
        udpThread = new Thread(this::runUdpLoop, "VoiceCraft.Java-SimpleVoiceChat-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SimpleVoiceChatChannels.REQUEST_SECRET, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.SECRET);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.STATE);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.STATES);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.REMOVE_STATE);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        context.audioBridge().registerAudioSink(this);
        context.audioBridge().registerIdentityListener(this);
        state = AdapterState.RUNNING;
        plugin.Logger.info("Simple Voice Chat adapter listening on " + bindHost + ":" + udpPort
                + " and advertising " + (voiceHost.isBlank() ? "<minecraft host>" : voiceHost) + ":" + publicPort + ".");
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            state = AdapterState.STOPPED;
            return;
        }

        state = AdapterState.STOPPING;
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SimpleVoiceChatChannels.REQUEST_SECRET, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.SECRET);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.STATE);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.STATES);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SimpleVoiceChatChannels.REMOVE_STATE);
        if (context != null) {
            context.audioBridge().unregisterAudioSink(this);
            context.audioBridge().unregisterIdentityListener(this);
        }

        if (udpSocket != null) {
            udpSocket.close();
        }
        if (udpThread != null) {
            try {
                udpThread.join(2000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (sessions != null) {
            sessions.close();
        }
        state = AdapterState.STOPPED;
    }

    @Override
    public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        if (!running.get() || opusFrame == null || opusFrame.length == 0) {
            return;
        }

        Optional<SimpleVoiceChatClientSession> sourceSession = sessions.byEntityId(entityId);
        VoiceCraftEntityIdentity sourceIdentity = context.audioBridge().entityIdentity(entityId)
                .orElseGet(() -> sourceSession
                        .map(session -> new VoiceCraftEntityIdentity(entityId, session.playerId(), session.playerName(), id()))
                        .orElseGet(() -> new VoiceCraftEntityIdentity(entityId, syntheticSourceId(entityId),
                                "VoiceCraft-" + entityId, "voicecraft")));
        UUID sourceId = sourceIdentity.playerId();
        PlayerSoundPacket packet = new PlayerSoundPacket(
                sourceId,
                sourceId,
                opusFrame,
                Integer.toUnsignedLong(timestamp),
                false,
                (float) voiceDistance
        );
        for (SimpleVoiceChatClientSession receiver : sessions.sessions()) {
            if (!receiver.connected() || receiver.remoteAddress() == null
                    || sourceSession.map(source -> receiver.playerId().equals(source.playerId())).orElse(false)) {
                continue;
            }
            try {
                sendStateIfNeeded(receiver, sourceIdentity);
                sendUdpPacket(packet, receiver);
                debugForwardedAudio(opusFrame.length);
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to forward Simple Voice Chat audio to " + receiver.playerName()
                        + ": " + exception.getMessage());
            }
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        if (!running.get() || !plugin.isEnabled()) {
            return;
        }
        runPluginTask(() -> broadcastState(identity));
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        if (!running.get() || !plugin.isEnabled()) {
            return;
        }
        sentStates.removeIf(key -> key.startsWith(identity.playerId() + ":")
                || key.endsWith(":" + identity.playerId()));
        runPluginTask(() -> broadcastRemoveState(identity.playerId()));
    }

    private UUID syntheticSourceId(int entityId) {
        return UUID.nameUUIDFromBytes(("voicecraft-entity:" + entityId).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SimpleVoiceChatChannels.REQUEST_SECRET.equals(channel)) {
            return;
        }

        try {
            int clientCompatibilityVersion = tcpCodec.decodeRequestSecret(message);
            if (clientCompatibilityVersion != compatibilityVersion) {
                plugin.Logger.warn("Simple Voice Chat client " + player.getName()
                        + " has incompatible compatibility version " + clientCompatibilityVersion
                        + " (server=" + compatibilityVersion + ").");
                return;
            }
            handleRequestSecret(player);
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to decode Simple Voice Chat packet from " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (sessions.byPlayerId(event.getPlayer().getUniqueId()).isPresent()) {
            SimpleVoiceChatClientSession session = sessions.byPlayerId(event.getPlayer().getUniqueId()).orElse(null);
            sessions.remove(event.getPlayer().getUniqueId());
            if (session != null) {
                unregisterProxyUdpRoute(event.getPlayer(), session);
                context.audioBridge().unregisterEntityIdentity(session.voiceCraftEntityId());
            }
            plugin.getSessionManager().destroyEntityForPlayer(event.getPlayer().getName());
        }
    }

    private void handleRequestSecret(Player player) {
        if (!plugin.isConnected()) {
            plugin.Logger.warn("Simple Voice Chat client " + player.getName() + " joined before VoiceCraft was connected.");
            return;
        }

        Optional<SimpleVoiceChatClientSession> existingSession = sessions.byPlayerId(player.getUniqueId());
        if (existingSession.isPresent()) {
            registerProxyUdpRoute(player, existingSession.get());
            sendSecret(player, existingSession.get());
            debug("Resent Simple Voice Chat secret to " + player.getName()
                    + " entity=" + existingSession.get().voiceCraftEntityId() + ".");
            return;
        }

        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            String reason = plugin.getSessionManager().getLastCreateEntityFailure();
            plugin.Logger.warn("Failed to create VoiceCraft entity for Simple Voice Chat client " + player.getName()
                    + (reason.isBlank() ? "." : ": " + reason + "."));
            return;
        }

        SimpleVoiceChatClientSession session = sessions.createOrReplace(
                player.getUniqueId(),
                player.getName(),
                entityId.getAsInt()
        );
        VoiceCraftEntityIdentity identity = new VoiceCraftEntityIdentity(entityId.getAsInt(), player.getUniqueId(),
                player.getName(), id());
        context.audioBridge().registerEntityIdentity(identity);
        registerProxyUdpRoute(player, session);
        sendSecret(player, session);
        debug("Sent Simple Voice Chat secret to " + player.getName() + " entity=" + entityId.getAsInt() + ".");
    }

    private void registerProxyUdpRoute(Player player, SimpleVoiceChatClientSession session) {
        plugin.getMessageHandler().sendUdpRouteRegister(player, id(), session.playerId().toString(), udpPort);
    }

    private void unregisterProxyUdpRoute(Player player, SimpleVoiceChatClientSession session) {
        plugin.getMessageHandler().sendUdpRouteUnregister(player, id(), session.playerId().toString());
    }

    private void sendSecret(Player player, SimpleVoiceChatClientSession session) {
        byte[] payload = tcpCodec.encodeSecret(
                session.secret(),
                publicPort,
                player.getUniqueId(),
                mtuSize,
                voiceDistance,
                keepAliveMillis,
                voiceHost == null ? "" : voiceHost,
                allowRecording
        );
        sendSecretPacket(player, payload);
    }

    private void sendSecretPacket(Player player, byte[] payload) {
        if (sendNmsCustomPayload(player, SimpleVoiceChatChannels.SECRET, payload)) {
            debug("Sent Simple Voice Chat secret via NMS custom payload to " + player.getName() + ".");
            return;
        }

        player.sendPluginMessage(plugin, SimpleVoiceChatChannels.SECRET, payload);
        debug("Sent Simple Voice Chat secret via Bukkit plugin message to " + player.getName() + ".");
    }

    private void sendInitialStates(Player player) {
        byte[] payload = tcpCodec.encodePlayerStates(context.audioBridge().entityIdentities());
        player.sendPluginMessage(plugin, SimpleVoiceChatChannels.STATES, payload);
    }

    private void sendStateIfNeeded(SimpleVoiceChatClientSession receiver, VoiceCraftEntityIdentity identity) {
        if (receiver.playerId().equals(identity.playerId())) {
            return;
        }
        String key = receiver.playerId() + ":" + identity.playerId();
        if (!sentStates.add(key)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(receiver.playerId());
        if (player == null || !player.isOnline()) {
            return;
        }
        sendState(player, identity);
    }

    private void broadcastState(VoiceCraftEntityIdentity identity) {
        for (SimpleVoiceChatClientSession receiver : sessions.sessions()) {
            Player player = plugin.getServer().getPlayer(receiver.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            sendState(player, identity);
        }
    }

    private void sendState(Player player, VoiceCraftEntityIdentity identity) {
        player.sendPluginMessage(plugin, SimpleVoiceChatChannels.STATE,
                tcpCodec.encodePlayerState(identity, false, false));
    }

    private void broadcastRemoveState(UUID playerId) {
        byte[] payload = tcpCodec.encodeRemovePlayerState(playerId);
        for (SimpleVoiceChatClientSession receiver : sessions.sessions()) {
            Player player = plugin.getServer().getPlayer(receiver.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendPluginMessage(plugin, SimpleVoiceChatChannels.REMOVE_STATE, payload);
        }
    }

    private boolean sendNmsCustomPayload(Player player, String channel, byte[] payload) {
        try {
            Object identifier = createIdentifier(channel);
            Class<?> discardedPayloadClass = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
            Constructor<?> discardedPayloadConstructor = discardedPayloadClass.getConstructor(identifier.getClass(), byte[].class);
            Object discardedPayload = discardedPayloadConstructor.newInstance(identifier, payload);

            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
            Class<?> customPayloadClass = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
            Object packet = packetClass.getConstructor(customPayloadClass).newInstance(discardedPayload);

            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = handle.getClass().getField("connection").get(handle);
            Method send = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            send.invoke(connection, packet);
            return true;
        } catch (Exception exception) {
            debug("Falling back to Bukkit plugin message for Simple Voice Chat secret: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ".");
            return false;
        }
    }

    private Object createIdentifier(String channel) throws Exception {
        String[] parts = channel.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid channel " + channel);
        }

        Class<?> identifierClass;
        try {
            identifierClass = Class.forName("net.minecraft.resources.Identifier");
        } catch (ClassNotFoundException exception) {
            identifierClass = Class.forName("net.minecraft.resources.ResourceLocation");
        }
        Method factory = identifierClass.getMethod("fromNamespaceAndPath", String.class, String.class);
        return factory.invoke(null, parts[0], parts[1]);
    }

    private void runUdpLoop() {
        byte[] buffer = new byte[4096];
        while (running.get()) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(datagram);
                byte[] payload = new byte[datagram.getLength()];
                System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());
                handleUdpDatagram(payload, datagram.getSocketAddress());
                sendKeepAlives();
            } catch (SocketTimeoutException ignored) {
                sendKeepAlives();
            } catch (SocketException exception) {
                if (running.get()) {
                    plugin.Logger.warn("Simple Voice Chat UDP socket failed: " + exception.getMessage());
                }
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to handle Simple Voice Chat UDP packet: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }
    }

    private void handleUdpDatagram(byte[] payload, java.net.SocketAddress remoteAddress) throws Exception {
        SimpleVoiceChatUdpEnvelope envelope = udpCodec.decodeClient(payload, remoteAddress,
                playerId -> sessions.byPlayerId(playerId).map(SimpleVoiceChatClientSession::secret).orElse(null));
        Optional<SimpleVoiceChatClientSession> optionalSession = sessions.byPlayerId(envelope.playerId());
        if (optionalSession.isEmpty()) {
            debug("Ignored Simple Voice Chat UDP packet for unknown player " + envelope.playerId() + ".");
            return;
        }

        SimpleVoiceChatClientSession session = optionalSession.get();
        session.touch(remoteAddress);
        if (envelope.packet() instanceof AuthenticatePacket authenticatePacket) {
            debug("Decoded Simple Voice Chat UDP packet " + envelope.packet().type()
                    + " from " + session.playerName() + " at " + remoteAddress + ".");
            if (!authenticatePacket.playerId().equals(session.playerId())) {
                debug("Ignored Simple Voice Chat auth with mismatched player id " + authenticatePacket.playerId() + ".");
                return;
            }
            if (!authenticatePacket.secret().equals(session.secret())) {
                debug("Ignored Simple Voice Chat auth with mismatched secret from " + session.playerName() + ".");
                return;
            }
            sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.AUTHENTICATE_ACK), session);
            debug("Authenticated Simple Voice Chat UDP session for " + session.playerName() + ".");
        } else if (envelope.packet() instanceof SimplePacket simplePacket
                && simplePacket.type() == SimpleVoiceChatPacketType.CONNECTION_CHECK) {
            debug("Decoded Simple Voice Chat UDP packet " + envelope.packet().type()
                    + " from " + session.playerName() + " at " + remoteAddress + ".");
            session.markConnected();
            sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.CONNECTION_CHECK_ACK), session);
            Player player = plugin.getServer().getPlayer(session.playerId());
            if (player != null && player.isOnline()) {
                runPluginTask(() -> sendInitialStates(player));
            }
            debug("Validated Simple Voice Chat UDP session for " + session.playerName() + ".");
        } else if (envelope.packet() instanceof SimplePacket simplePacket
                && simplePacket.type() == SimpleVoiceChatPacketType.KEEP_ALIVE) {
            debug("Received Simple Voice Chat keepalive from " + session.playerName() + ".");
        } else if (envelope.packet() instanceof MicPacket micPacket) {
            debugReceivedAudio(micPacket.data().length);
            sessions.forwardAudio(session, micPacket);
        }
    }

    private void sendKeepAlives() {
        long now = System.currentTimeMillis();
        for (SimpleVoiceChatClientSession session : sessions.sessions()) {
            if (session.remoteAddress() == null || !session.connected()) {
                continue;
            }
            if (now - session.lastSeenMillis() >= keepAliveMillis * KEEP_ALIVE_TIMEOUT_FACTOR) {
                debug("Simple Voice Chat UDP session timed out for " + session.playerName() + ".");
                sessions.remove(session.playerId());
                continue;
            }
            if (now - session.lastKeepAliveSentMillis() < keepAliveMillis) {
                continue;
            }

            try {
                sendUdpPacket(new SimplePacket(SimpleVoiceChatPacketType.KEEP_ALIVE), session);
                session.markKeepAliveSent();
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to send Simple Voice Chat keepalive to " + session.playerName()
                        + ": " + exception.getMessage());
            }
        }
    }

    private void sendUdpPacket(SimpleVoiceChatUdpPacket packet, SimpleVoiceChatClientSession session) throws Exception {
        byte[] encoded = udpCodec.encodeServer(packet, session.secret());
        DatagramPacket datagram = new DatagramPacket(encoded, encoded.length);
        datagram.setSocketAddress(session.remoteAddress());
        udpSocket.send(datagram);
    }

    private void runPluginTask(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // The server may disable the plugin between the enabled check and scheduler registration.
        }
    }

    private void debug(String message) {
        if (debug) {
            plugin.Logger.info("[SimpleVoiceChatAdapter] " + message);
        }
    }

    private synchronized void debugReceivedAudio(int bytes) {
        if (!debug) {
            return;
        }
        receivedAudioFrames++;
        receivedAudioBytes += bytes;
        flushAudioDebugIfNeeded();
    }

    private synchronized void debugForwardedAudio(int bytes) {
        if (!debug) {
            return;
        }
        forwardedAudioFrames++;
        forwardedAudioBytes += bytes;
        flushAudioDebugIfNeeded();
    }

    private synchronized void debugSvcEmptyMicReceived() {
        if (!debug) {
            return;
        }
        svcEmptyMicPackets++;
        flushAudioDebugIfNeeded();
    }

    private synchronized void debugSvcAudioEndSent() {
        if (!debug) {
            return;
        }
        svcAudioEndsSent++;
        flushAudioDebugIfNeeded();
    }

    private synchronized void debugSvcAudioEndCancelledByAudio() {
        if (!debug) {
            return;
        }
        svcAudioEndsCancelledByAudio++;
        flushAudioDebugIfNeeded();
    }

    private void flushAudioDebugIfNeeded() {
        long now = System.currentTimeMillis();
        if (audioDebugWindowMillis == 0L) {
            audioDebugWindowMillis = now;
            return;
        }
        if (now - audioDebugWindowMillis < 1000L) {
            return;
        }
        plugin.Logger.info("[SimpleVoiceChatAdapter] Audio frames last " + (now - audioDebugWindowMillis)
                + "ms: received=" + receivedAudioFrames + " (" + receivedAudioBytes + " bytes), forwarded="
                + forwardedAudioFrames + " (" + forwardedAudioBytes + " bytes), svcEmptyMic=" + svcEmptyMicPackets
                + ", svcAudioEndSent=" + svcAudioEndsSent + ", svcAudioEndCancelledByAudio="
                + svcAudioEndsCancelledByAudio + ".");
        audioDebugWindowMillis = now;
        receivedAudioFrames = 0;
        receivedAudioBytes = 0;
        forwardedAudioFrames = 0;
        forwardedAudioBytes = 0;
        svcEmptyMicPackets = 0;
        svcAudioEndsSent = 0;
        svcAudioEndsCancelledByAudio = 0;
    }
}
