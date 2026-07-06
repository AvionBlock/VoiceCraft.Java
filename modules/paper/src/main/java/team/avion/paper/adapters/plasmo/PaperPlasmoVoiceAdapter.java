package team.avion.paper.adapters.plasmo;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.adapter.plasmo.PlasmoChannels;
import team.avion.adapter.plasmo.PlasmoPacket;
import team.avion.adapter.plasmo.data.McGameProfile;
import team.avion.adapter.plasmo.data.PlayerSourceInfo;
import team.avion.adapter.plasmo.data.VoicePlayerInfo;
import team.avion.adapter.plasmo.data.VoiceSourceLine;
import team.avion.adapter.plasmo.session.PlasmoClientSession;
import team.avion.adapter.plasmo.session.PlasmoSessionManager;
import team.avion.adapter.plasmo.tcp.PlasmoTcpCodec;
import team.avion.adapter.plasmo.tcp.PlasmoTcpCodecs;
import team.avion.adapter.plasmo.tcp.packet.ConfigPacket;
import team.avion.adapter.plasmo.tcp.packet.ConnectionPacket;
import team.avion.adapter.plasmo.tcp.packet.LanguagePacket;
import team.avion.adapter.plasmo.tcp.packet.LanguageRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerActivationDistancesPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerDisconnectPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoUpdatePacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerInfoRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerListPacket;
import team.avion.adapter.plasmo.tcp.packet.PlayerStatePacket;
import team.avion.adapter.plasmo.tcp.packet.SourceAudioEndPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceInfoPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceInfoRequestPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceLinePlayersListPacket;
import team.avion.adapter.plasmo.tcp.packet.SourceLineRegisterPacket;
import team.avion.adapter.plasmo.udp.PlasmoUdpCodec;
import team.avion.adapter.plasmo.udp.PlasmoUdpCodecs;
import team.avion.adapter.plasmo.udp.PlasmoUdpEnvelope;
import team.avion.adapter.plasmo.udp.packet.PingPacket;
import team.avion.adapter.plasmo.udp.packet.SourceAudioPacket;
import team.avion.paper.VoiceCraftJava;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperPlasmoVoiceAdapter implements VoiceAdapter, VoiceCraftAudioSink, VoiceCraftIdentityListener,
        PluginMessageListener, Listener {
    private static final long KEEP_ALIVE_INTERVAL_MS = 1000L;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 30000L;
    private static final byte SOURCE_STATE = 1;
    private static final String SOURCE_LINE_TRANSLATION = "pv.activation.proximity";

    private final VoiceCraftJava plugin;
    private final PlasmoTcpCodec tcpCodec = PlasmoTcpCodecs.createBaseCodec();
    private final PlasmoUdpCodec udpCodec = PlasmoUdpCodecs.createBaseCodec();
    private final UUID serverId = UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<UUID, PlayerSourceInfo> sourcesById = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerSourceInfo> sourcesByEntityId = new ConcurrentHashMap<>();
    private final Map<Integer, Long> sourceSequencesByEntity = new ConcurrentHashMap<>();
    private final Map<UUID, PlasmoPlayerState> playerStatesById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> sentSourcesByReceiver = new ConcurrentHashMap<>();

    private AdapterState state = AdapterState.NEW;
    private PlasmoSessionManager sessions;
    private AdapterContext context;
    private DatagramSocket udpSocket;
    private Thread udpThread;
    private String bindHost;
    private String publicHost;
    private int udpPort;
    private int publicPort;
    private int proximityDistance;
    private boolean debug;
    private long audioDebugWindowMillis;
    private int receivedAudioFrames;
    private int receivedAudioBytes;
    private int forwardedAudioFrames;
    private int forwardedAudioBytes;

    public PaperPlasmoVoiceAdapter(VoiceCraftJava plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "plasmo";
    }

    @Override
    public String displayName() {
        return "Plasmo Voice";
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
        this.sessions = new PlasmoSessionManager(context.audioBridge());
        this.bindHost = plugin.getConfig().getString("config.adapters.plasmo.bind-host", "0.0.0.0");
        this.udpPort = plugin.getConfig().getInt("config.adapters.plasmo.port", 24454);
        this.publicHost = plugin.getConfig().getString("config.adapters.plasmo.public.host",
                plugin.getConfig().getString("config.adapters.plasmo.advertised-host", "0.0.0.0"));
        int configuredPublicPort = plugin.getConfig().getInt("config.adapters.plasmo.public.port", 0);
        this.publicPort = configuredPublicPort > 0 ? configuredPublicPort : udpPort;
        this.proximityDistance = plugin.getConfig().getInt("config.voice.proximity-distance", 30);
        this.debug = plugin.getConfig().getBoolean("config.debug", false);

        udpSocket = new DatagramSocket(new InetSocketAddress(bindHost, udpPort));
        udpSocket.setSoTimeout(1000);
        running.set(true);
        udpThread = new Thread(this::runUdpLoop, "VoiceCraft.Java-Plasmo-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlasmoChannels.MAIN);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlasmoChannels.FLAG);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, PlasmoChannels.MAIN, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        context.audioBridge().registerAudioSink(this);
        context.audioBridge().registerIdentityListener(this);
        state = AdapterState.RUNNING;
        plugin.Logger.info("Plasmo Voice adapter listening on " + bindHost + ":" + udpPort
                + " and advertising " + publicHost + ":" + publicPort + ".");
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            state = AdapterState.STOPPED;
            return;
        }

        state = AdapterState.STOPPING;
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, PlasmoChannels.MAIN, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, PlasmoChannels.MAIN);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, PlasmoChannels.FLAG);
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
        state = AdapterState.STOPPED;
    }

    @Override
    public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        if (!running.get() || opusFrame == null || opusFrame.length == 0) {
            return;
        }

        PlasmoClientSession sourceSession = sessions.byEntityId(entityId).orElse(null);
        PlayerSourceInfo sourceInfo = sourceInfoForEntity(entityId, sourceSession);
        SourceAudioPacket packet = new SourceAudioPacket(
                sourceInfo.sourceId(),
                sourceInfo.state(),
                (short) Math.max(0, Math.min(Short.MAX_VALUE, proximityDistance)),
                nextPlasmoSequence(entityId),
                opusFrame
        );

        for (PlasmoClientSession receiver : sessions.sessions()) {
            if (receiver.remoteAddress() == null) {
                continue;
            }
            if (sourceSession != null && receiver.playerId().equals(sourceSession.playerId())) {
                continue;
            }
            try {
                sendSourceInfoIfNeeded(receiver, sourceInfo);
                sendUdpPacket(packet, receiver.udpSecret(), receiver.remoteAddress());
                debugForwardedAudio(opusFrame.length);
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to forward Plasmo audio to " + receiver.playerName()
                        + ": " + exception.getMessage());
            }
        }
    }

    @Override
    public void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
        if (!running.get()) {
            return;
        }

        PlasmoClientSession sourceSession = sessions.byEntityId(entityId).orElse(null);
        PlayerSourceInfo sourceInfo = sourceInfoForEntity(entityId, sourceSession);
        SourceAudioEndPacket packet = new SourceAudioEndPacket(sourceInfo.sourceId(), nextPlasmoSequence(entityId));

        for (PlasmoClientSession receiver : sessions.sessions()) {
            if (receiver.playerId().equals(sourceInfo.sourceId())) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(receiver.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            sendTcpPacket(player, packet);
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        if (!running.get() || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PlayerSourceInfo sourceInfo = sourceInfoForEntity(identity.entityId(),
                    sessions.byPlayerId(identity.playerId()).orElse(null));
            broadcastPlayerInfo(voicePlayerInfo(identity));
            sendSourceInfoToConfiguredPlayers(sourceInfo);
        });
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        if (!running.get() || !plugin.isEnabled()) {
            return;
        }
        sourcesByEntityId.remove(identity.entityId());
        sourcesById.remove(identity.playerId());
        sourceSequencesByEntity.remove(identity.entityId());
        playerStatesById.remove(identity.playerId());
        sentSourcesByReceiver.values().forEach(sourceIds -> sourceIds.remove(identity.playerId()));
        plugin.getServer().getScheduler().runTask(plugin, () -> broadcastPlayerDisconnect(identity.playerId()));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!PlasmoChannels.MAIN.equals(channel)) {
            return;
        }

        try {
            PlasmoPacket packet = tcpCodec.decode(message);
            if (packet instanceof PlayerInfoPacket infoPacket) {
                handlePlayerInfo(player, infoPacket);
            } else if (packet instanceof LanguageRequestPacket languageRequest) {
                sendTcpPacket(player, new LanguagePacket(languageRequest.language(), Map.of(
                        "pv.activation.proximity", "Proximity"
                )));
            } else if (packet instanceof PlayerActivationDistancesPacket) {
                // Client-side distance preferences are accepted but currently mapped to VoiceCraft proximity settings.
            } else if (packet instanceof PlayerAudioEndPacket audioEndPacket) {
                debug("Plasmo client " + player.getName() + " ended audio sequence="
                        + audioEndPacket.sequenceNumber() + ".");
                sessions.forwardAudioEnd(player.getUniqueId(), audioEndPacket.sequenceNumber());
            } else if (packet instanceof PlayerStatePacket statePacket) {
                debug("Plasmo client " + player.getName() + " state voiceDisabled="
                        + statePacket.voiceDisabled() + " microphoneMuted=" + statePacket.microphoneMuted() + ".");
                playerStatesById.put(player.getUniqueId(), new PlasmoPlayerState(
                        statePacket.voiceDisabled(),
                        statePacket.microphoneMuted()
                ));
                sessions.byPlayerId(player.getUniqueId())
                        .flatMap(session -> context.audioBridge().entityIdentity(session.voiceCraftEntityId()))
                        .ifPresent(identity -> broadcastPlayerInfo(voicePlayerInfo(identity)));
            } else if (packet instanceof SourceInfoRequestPacket sourceInfoRequest) {
                debug("Plasmo client " + player.getName() + " requested source info "
                        + sourceInfoRequest.sourceId() + ".");
                PlayerSourceInfo sourceInfo = sourcesById.get(sourceInfoRequest.sourceId());
                if (sourceInfo != null) {
                    sendTcpPacket(player, new SourceInfoPacket(sourceInfo));
                }
            }
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to decode Plasmo packet from " + player.getName() + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!PlasmoChannels.MAIN.equals(event.getChannel())) {
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> requestPlayerInfo(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (sessions.byPlayerId(event.getPlayer().getUniqueId()).isPresent()) {
            PlasmoClientSession session = sessions.byPlayerId(event.getPlayer().getUniqueId()).orElse(null);
            sessions.remove(event.getPlayer().getUniqueId());
            playerStatesById.remove(event.getPlayer().getUniqueId());
            sentSourcesByReceiver.remove(event.getPlayer().getUniqueId());
            if (session != null) {
                context.audioBridge().unregisterEntityIdentity(session.voiceCraftEntityId());
            }
            plugin.getSessionManager().destroyEntityForPlayer(event.getPlayer().getName());
        }
    }

    private void requestPlayerInfo(Player player) {
        if (!running.get() || !player.isOnline()) {
            return;
        }
        sendTcpPacket(player, new PlayerInfoRequestPacket());
    }

    private void handlePlayerInfo(Player player, PlayerInfoPacket packet) {
        if (packet.voiceDisabled()) {
            return;
        }
        if (!plugin.isConnected()) {
            plugin.Logger.warn("Plasmo client " + player.getName() + " joined before VoiceCraft was connected.");
            return;
        }

        OptionalInt entityId = plugin.getSessionManager().createEntityForPlayer(player);
        if (entityId.isEmpty()) {
            String reason = plugin.getSessionManager().getLastCreateEntityFailure();
            plugin.Logger.warn("Failed to create VoiceCraft entity for Plasmo client " + player.getName()
                    + (reason.isBlank() ? "." : ": " + reason + "."));
            return;
        }

        PlasmoClientSession session = sessions.createOrReplace(player.getUniqueId(), player.getName(), entityId.getAsInt());
        playerStatesById.put(player.getUniqueId(), new PlasmoPlayerState(packet.voiceDisabled(), packet.microphoneMuted()));
        context.audioBridge().registerEntityIdentity(new VoiceCraftEntityIdentity(entityId.getAsInt(),
                player.getUniqueId(), player.getName(), id()));
        PlayerSourceInfo sourceInfo = sourceInfoForEntity(entityId.getAsInt(), session);
        debug("Created Plasmo session for " + player.getName() + " entity=" + entityId.getAsInt()
                + " secret=" + session.udpSecret() + " endpoint=" + publicHost + ":" + publicPort + ".");
        sendTcpPacket(player, new ConnectionPacket(session.udpSecret(), publicHost, publicPort));
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
                sendKeepAlivePackets();
            } catch (SocketTimeoutException ignored) {
                sendKeepAlivePackets();
            } catch (SocketException exception) {
                if (running.get()) {
                    plugin.Logger.warn("Plasmo UDP socket failed: " + exception.getMessage());
                }
            } catch (Exception exception) {
                plugin.Logger.warn("Failed to handle Plasmo UDP packet: " + exception.getMessage());
            }
        }
    }

    private void handleUdpDatagram(byte[] payload, java.net.SocketAddress remoteAddress) throws IOException {
        PlasmoUdpEnvelope envelope = udpCodec.decode(payload);
        if (!sessions.handleUdpPacket(envelope, remoteAddress)) {
            debug("Ignored Plasmo UDP packet with unknown secret " + envelope.secret() + ".");
            return;
        }

        if (envelope.packet() instanceof PingPacket) {
            debug("Decoded Plasmo UDP packet type=" + envelope.type() + " secret=" + envelope.secret()
                    + " from=" + remoteAddress + ".");
            sessions.bySecret(envelope.secret()).ifPresent(session ->
                    runPluginTask(() -> {
                        if (session.configSent()) {
                            return;
                        }
                        Player player = plugin.getServer().getPlayer(session.playerId());
                        if (player == null || !player.isOnline()) {
                            return;
                        }
                        sendTcpPacket(player, new ConfigPacket(serverId, proximityDistance));
                        sendTcpPacket(player, new SourceLineRegisterPacket(proximitySourceLine()));
                        sendTcpPacket(player, new PlayerListPacket(voicePlayers()));
                        sendTcpPacket(player, new SourceLinePlayersListPacket(ConfigPacket.PROXIMITY_SOURCE_LINE_ID, gameProfiles()));
                        sourcesById.values().forEach(sourceInfo -> {
                            markSourceInfoSent(session.playerId(), sourceInfo.sourceId());
                            sendTcpPacket(player, new SourceInfoPacket(sourceInfo));
                        });
                        session.markConfigSent();
                        debug("Sent Plasmo config/player list to " + player.getName() + ".");
                    })
            );
        } else {
            debugReceivedAudio(payload.length);
        }
    }

    private void sendKeepAlivePackets() {
        long now = System.currentTimeMillis();
        for (PlasmoClientSession session : sessions.sessions()) {
            java.net.SocketAddress remoteAddress = session.remoteAddress();
            if (remoteAddress == null) {
                continue;
            }
            if (now - session.lastSeenMillis() > KEEP_ALIVE_TIMEOUT_MS) {
                debug("Plasmo UDP session timed out for " + session.playerName() + ".");
                sessions.remove(session.playerId());
                continue;
            }
            if (now - session.lastKeepAliveSentMillis() < KEEP_ALIVE_INTERVAL_MS) {
                continue;
            }

            try {
                sendUdpPacket(new PingPacket(), session.udpSecret(), remoteAddress);
                session.markKeepAliveSent();
                debug("Sent Plasmo UDP keepalive secret=" + session.udpSecret() + " to=" + remoteAddress + ".");
            } catch (IOException exception) {
                plugin.Logger.warn("Failed to send Plasmo UDP keepalive to " + session.playerName() + ": "
                        + exception.getMessage());
            }
        }
    }

    private void sendUdpPacket(PlasmoPacket packet, UUID secret, java.net.SocketAddress remoteAddress) throws IOException {
        byte[] encoded = udpCodec.encode(packet, secret);
        DatagramPacket datagram = new DatagramPacket(encoded, encoded.length);
        datagram.setSocketAddress(remoteAddress);
        udpSocket.send(datagram);
    }

    private PlayerSourceInfo sourceInfoForEntity(int entityId, PlasmoClientSession session) {
        VoiceCraftEntityIdentity identity = context.audioBridge().entityIdentity(entityId)
                .orElseGet(() -> session != null
                        ? new VoiceCraftEntityIdentity(entityId, session.playerId(), session.playerName(), id())
                        : new VoiceCraftEntityIdentity(entityId, syntheticSourceId(entityId),
                                "VoiceCraft-" + entityId, "voicecraft"));
        VoicePlayerInfo playerInfo = voicePlayerInfo(identity);
        PlayerSourceInfo existing = sourcesByEntityId.get(entityId);
        if (existing != null
                && existing.sourceId().equals(identity.playerId())
                && existing.playerInfo().equals(playerInfo)) {
            return existing;
        }

        PlayerSourceInfo sourceInfo = new PlayerSourceInfo(
                "voicecraft",
                identity.playerId(),
                ConfigPacket.PROXIMITY_SOURCE_LINE_ID,
                identity.playerName(),
                SOURCE_STATE,
                false,
                true,
                0,
                playerInfo
        );
        sourcesByEntityId.put(entityId, sourceInfo);
        sourcesById.put(sourceInfo.sourceId(), sourceInfo);
        return sourceInfo;
    }

    private Collection<VoicePlayerInfo> voicePlayers() {
        return context.audioBridge().entityIdentities()
                .stream()
                .map(this::voicePlayerInfo)
                .toList();
    }

    private VoicePlayerInfo voicePlayerInfo(VoiceCraftEntityIdentity identity) {
        PlasmoPlayerState state = playerStatesById.getOrDefault(identity.playerId(),
                new PlasmoPlayerState(false, false));
        return new VoicePlayerInfo(identity.playerId(), identity.playerName(), false,
                state.voiceDisabled(), state.microphoneMuted());
    }

    private List<McGameProfile> gameProfiles() {
        return context.audioBridge().entityIdentities()
                .stream()
                .map(identity -> new McGameProfile(identity.playerId(), identity.playerName()))
                .toList();
    }

    private VoiceSourceLine proximitySourceLine() {
        return new VoiceSourceLine(
                ConfigPacket.PROXIMITY_NAME,
                SOURCE_LINE_TRANSLATION,
                "plasmovoice:textures/icons/speaker.png",
                1.0D,
                0,
                gameProfiles()
        );
    }

    private UUID syntheticSourceId(int entityId) {
        return UUID.nameUUIDFromBytes(("voicecraft-entity:" + entityId).getBytes(StandardCharsets.UTF_8));
    }

    private long nextPlasmoSequence(int entityId) {
        return sourceSequencesByEntity.merge(entityId, 1L, Long::sum);
    }

    private void sendSourceInfoToConfiguredPlayers(PlayerSourceInfo sourceInfo) {
        for (PlasmoClientSession receiver : sessions.sessions()) {
            if (!receiver.configSent()) {
                continue;
            }
            sendSourceInfoIfNeeded(receiver, sourceInfo);
            sendLinePlayers(receiver);
        }
    }

    private void broadcastPlayerInfo(VoicePlayerInfo playerInfo) {
        for (PlasmoClientSession receiver : sessions.sessions()) {
            Player player = plugin.getServer().getPlayer(receiver.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            sendTcpPacket(player, new PlayerInfoUpdatePacket(playerInfo));
            if (receiver.configSent()) {
                sendLinePlayers(receiver);
            }
        }
    }

    private void broadcastPlayerDisconnect(UUID playerId) {
        playerStatesById.remove(playerId);
        for (PlasmoClientSession receiver : sessions.sessions()) {
            Player player = plugin.getServer().getPlayer(receiver.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            sendTcpPacket(player, new PlayerDisconnectPacket(playerId));
            if (receiver.configSent()) {
                sendLinePlayers(receiver);
            }
        }
    }

    private void sendLinePlayers(PlasmoClientSession receiver) {
        Player player = plugin.getServer().getPlayer(receiver.playerId());
        if (player == null || !player.isOnline()) {
            return;
        }
        sendTcpPacket(player, new SourceLinePlayersListPacket(ConfigPacket.PROXIMITY_SOURCE_LINE_ID, gameProfiles()));
    }

    private void sendSourceInfoIfNeeded(PlasmoClientSession receiver, PlayerSourceInfo sourceInfo) {
        if (!markSourceInfoSent(receiver.playerId(), sourceInfo.sourceId())) {
            return;
        }
        Player player = plugin.getServer().getPlayer(receiver.playerId());
        if (player == null || !player.isOnline()) {
            return;
        }
        runPluginTask(() -> sendTcpPacket(player, new SourceInfoPacket(sourceInfo)));
    }

    private void runPluginTask(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private boolean markSourceInfoSent(UUID receiverId, UUID sourceId) {
        return sentSourcesByReceiver.computeIfAbsent(receiverId, ignored -> ConcurrentHashMap.newKeySet()).add(sourceId);
    }

    private void sendTcpPacket(Player player, PlasmoPacket packet) {
        try {
            player.sendPluginMessage(plugin, PlasmoChannels.MAIN, tcpCodec.encode(packet));
            debug("Sent Plasmo TCP packet " + packet.getClass().getSimpleName() + " to " + player.getName() + ".");
        } catch (Exception exception) {
            plugin.Logger.warn("Failed to send Plasmo packet to " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void debug(String message) {
        if (debug) {
            plugin.Logger.info("[PlasmoAdapter] " + message);
        }
    }

    private void debugReceivedAudio(int bytes) {
        if (!debug) {
            return;
        }
        receivedAudioFrames++;
        receivedAudioBytes += bytes;
        flushAudioDebugIfNeeded();
    }

    private void debugForwardedAudio(int bytes) {
        if (!debug) {
            return;
        }
        forwardedAudioFrames++;
        forwardedAudioBytes += bytes;
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
        plugin.Logger.info("[PlasmoAdapter] Audio frames last " + (now - audioDebugWindowMillis)
                + "ms: received=" + receivedAudioFrames + " (" + receivedAudioBytes + " bytes), forwarded="
                + forwardedAudioFrames + " (" + forwardedAudioBytes + " bytes).");
        audioDebugWindowMillis = now;
        receivedAudioFrames = 0;
        receivedAudioBytes = 0;
        forwardedAudioFrames = 0;
        forwardedAudioBytes = 0;
    }

    private record PlasmoPlayerState(boolean voiceDisabled, boolean microphoneMuted) {
    }
}
