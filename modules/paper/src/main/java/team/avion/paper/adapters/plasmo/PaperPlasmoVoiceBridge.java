package team.avion.paper.adapters.plasmo;

import org.bukkit.entity.Player;
import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.BaseServerDirectSource;
import su.plo.voice.api.server.audio.source.ServerAudioSource;
import su.plo.voice.api.server.audio.source.ServerBroadcastSource;
import su.plo.voice.api.server.audio.source.ServerDirectSource;
import su.plo.voice.api.server.audio.source.ServerEntitySource;
import su.plo.voice.api.server.audio.source.ServerPlayerSource;
import su.plo.voice.api.server.audio.source.ServerProximitySource;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.connection.UdpClientConnectedEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.event.connection.UdpPacketReceivedEvent;
import su.plo.voice.api.server.event.connection.TcpPacketReceivedEvent;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerDisconnectPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoUpdatePacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.VoiceAudioAudience;
import team.avion.adapter.VoiceAudioDebugMetrics;
import team.avion.adapter.VoiceAudioEndDebouncer;
import team.avion.adapter.VoiceAudioFrame;
import team.avion.adapter.VoiceAudioSource;
import team.avion.adapter.VoiceAudioSourceType;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceCraftPlayerState;
import team.avion.adapter.VoiceCraftPlayerStateListener;
import team.avion.adapter.VoiceEndpointListener;
import team.avion.adapter.VoiceEndpointState;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.paper.VoiceCraftJava;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Native Plasmo Voice add-on bridge. Plasmo owns transport; VoiceCraft owns server-side routing. */
@Addon(id = "voicecraft-java", name = "VoiceCraft.Java Bridge", version = "2.0.0", authors = {"lil-jon-crunk"})
public final class PaperPlasmoVoiceBridge implements VoiceAdapter, VoiceCraftAudioSink, AddonInitializer,
        VoiceEndpointListener, VoiceCraftPlayerStateListener, VoiceCraftIdentityListener {
    private final VoiceCraftJava plugin;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final Map<UUID, ServerAudioSource<?>> nativeSources = new ConcurrentHashMap<>();
    private final Map<Integer, ServerAudioSource<?>> nativeSourcesByEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> outputSequences = new ConcurrentHashMap<>();
    private final VoiceAudioDebugMetrics audioDebug;
    private final VoiceAudioEndDebouncer<UUID> audioEnds =
            new VoiceAudioEndDebouncer<>("VoiceCraft-Plasmo-AudioEnd");

    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;

    private volatile AdapterState state = AdapterState.NEW;
    private volatile AdapterContext context;

    public PaperPlasmoVoiceBridge(VoiceCraftJava plugin) {
        this.plugin = plugin;
        this.audioDebug = new VoiceAudioDebugMetrics(
                () -> plugin.getConfig().getBoolean("config.debug", false), plugin.Logger::info);
    }

    @Override
    public void onAddonInitialize() {
        voiceServer.getEventBus().register(this, UdpClientConnectedEvent.class, EventPriority.HIGHEST,
                this::onClientConnected);
        voiceServer.getEventBus().register(this, UdpClientDisconnectedEvent.class, EventPriority.HIGHEST,
                this::onClientDisconnected);
        voiceServer.getEventBus().register(this, UdpPacketReceivedEvent.class, EventPriority.HIGHEST,
                this::onUdpPacketReceived);
        voiceServer.getEventBus().register(this, TcpPacketReceivedEvent.class, EventPriority.HIGHEST,
                this::onTcpPacketReceived);
        voiceServer.getEventBus().register(this, ServerSourceAudioPacketEvent.class, EventPriority.HIGHEST,
                this::onSourceAudioPacket);
        initialized.set(true);
        if (active.get()) {
            bootstrapConnectedPlayers();
        }
    }

    @Override
    public void onAddonShutdown() {
        active.set(false);
        if (voiceServer != null) {
            voiceServer.getEventBus().unregister(this);
        }
        audioEnds.close();
        nativeSources.clear();
        nativeSourcesByEntityId.clear();
        outputSequences.clear();
        initialized.set(false);
    }

    @Override
    public String id() {
        return "plasmo";
    }

    @Override
    public String displayName() {
        return "Plasmo Voice (native)";
    }

    @Override
    public AdapterState state() {
        return state;
    }

    @Override
    public void start(AdapterContext context) {
        if (!initialized.get()) {
            state = AdapterState.FAILED;
            throw new IllegalStateException("Plasmo Voice add-on API is unavailable");
        }
        this.context = context;
        context.audioBridge().registerAudioSink(this);
        active.set(true);
        context.audioBridge().registerPlayerStateListener(this);
        context.audioBridge().registerIdentityListener(this);
        plugin.getVoiceEndpointRegistry().registerListener(this);
        context.audioBridge().entityIdentities().forEach(this::entityIdentityRegistered);
        state = AdapterState.RUNNING;
        bootstrapConnectedPlayers();
    }

    @Override
    public void stop() {
        active.set(false);
        clearProjectedPlayers();
        plugin.getVoiceEndpointRegistry().unregisterListener(this);
        if (context != null) {
            context.audioBridge().unregisterAudioSink(this);
            context.audioBridge().unregisterPlayerStateListener(this);
            context.audioBridge().unregisterIdentityListener(this);
        }
        audioEnds.clear();
        nativeSourcesByEntityId.clear();
        outputSequences.clear();
        state = AdapterState.STOPPED;
    }

    private void onClientConnected(UdpClientConnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                plugin.getVoiceEndpointRegistry().connect(id(), player);
                sendProjectedPlayersTo((VoiceServerPlayer) event.getConnection().getPlayer());
            }
        });
    }

    private void onClientDisconnected(UdpClientDisconnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        if (audioEnds.cancel(playerId)) {
            audioDebug.recordEnd("plasmo-in", "cancelled");
        }
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            plugin.getVoiceEndpointRegistry().disconnect(id(), playerId,
                    player == null ? playerId.toString() : player.getName());
        });
    }

    private void onUdpPacketReceived(UdpPacketReceivedEvent event) {
        if (!active.get() || event.isCancelled() || injecting.get()
                || !(event.getPacket() instanceof PlayerAudioPacket packet)) {
            return;
        }
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, id())) {
            event.setCancelled(true);
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        OptionalInt entityId = player == null ? OptionalInt.empty()
                : plugin.getSessionManager().getBoundEntityId(player.getName());
        if (entityId.isEmpty() || packet.getData() == null || packet.getData().length == 0) {
            event.setCancelled(true);
            return;
        }
        byte[] opus = decryptFrame(packet.getData(), "player " + playerId);
        if (opus == null || opus.length == 0) {
            event.setCancelled(true);
            return;
        }
        if (audioEnds.cancel(playerId)) {
            audioDebug.recordEnd("plasmo-in", "cancelled");
        }
        audioDebug.recordFrame("plasmo-in", packet.getSequenceNumber(), opus.length);
        VoiceAudioSource source = VoiceAudioSource.player(entityId.getAsInt(), playerId, packet.getDistance());
        context.audioBridge().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), id(), source,
                VoiceAudioAudience.PROXIMITY, packet.getSequenceNumber(),
                (int) (packet.getSequenceNumber() & 0xFFFF), 1F, opus));
        event.setCancelled(true);
    }

    private void onTcpPacketReceived(TcpPacketReceivedEvent event) {
        if (!active.get() || context == null) {
            return;
        }
        UUID playerId = event.getPlayer().getInstance().getUuid();
        if (event.getPacket() instanceof PlayerStatePacket statePacket
                && plugin.getVoiceEndpointRegistry().isConnected(playerId, id())) {
            context.audioBridge().updatePlayerVoiceState(new VoiceCraftPlayerState(playerId,
                    statePacket.isVoiceDisabled(), statePacket.isMicrophoneMuted()));
            return;
        }
        if (event.getPacket() instanceof PlayerAudioEndPacket endPacket
                && plugin.getVoiceEndpointRegistry().isPrimary(playerId, id())) {
            plugin.getSessionManager().getBoundEntityId(event.getPlayer().getInstance().getName())
                    .ifPresent(entityId -> {
                        audioDebug.recordEnd("plasmo-in", "scheduled");
                        audioEnds.schedule(playerId, () -> {
                            if (active.get() && context != null) {
                                context.audioBridge().sendEntityAudioEnd(entityId, endPacket.getSequenceNumber());
                                audioDebug.recordEnd("plasmo-in", "fired");
                            }
                        });
                    });
        }
    }

    private void onSourceAudioPacket(ServerSourceAudioPacketEvent event) {
        if (!active.get() || event.isCancelled() || injecting.get()) {
            return;
        }
        byte[] encrypted = event.getPacket().getData();
        if (encrypted == null || encrypted.length == 0) {
            return;
        }
        byte[] opus = decryptFrame(encrypted, "source " + event.getSource().getId());
        if (opus == null || opus.length == 0) {
            return;
        }
        ServerAudioSource<?> nativeSource = event.getSource();
        nativeSources.put(nativeSource.getId(), nativeSource);
        VoiceAudioSource source = describeSource(nativeSource, event.getDistance());
        Optional<VoiceAudioSource> routed = source.voiceCraftEntityId() > 0
                ? Optional.of(source)
                : plugin.getSessionManager().getOrCreateVirtualEntity(source, id());
        if (routed.isEmpty()) {
            return;
        }
        context.audioBridge().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), id(), routed.get(),
                audience(nativeSource), event.getPacket().getSequenceNumber(),
                (int) (event.getPacket().getSequenceNumber() & 0xFFFF), 0F, opus));
        event.setResult(ServerSourceAudioPacketEvent.Result.HANDLED);
        event.setCancelled(true);
    }

    @Override
    public void receiveAudioFrame(VoiceAudioFrame frame) {
        if (!active.get() || frame.opusFrame().length == 0) {
            return;
        }
        ServerAudioSource<?> source = nativeSources.computeIfAbsent(frame.source().streamId(),
                ignored -> createNativeSource(frame.source(), frame.audience()));
        if (source == null) {
            return;
        }
        byte[] encrypted = encryptFrame(frame.opusFrame(), "source " + frame.source().streamId());
        if (encrypted == null) {
            return;
        }
        nativeSourcesByEntityId.put(frame.source().voiceCraftEntityId(), source);
        long outputSequence = nextOutputSequence(source.getId());
        audioDebug.recordFrame("plasmo-out/from-" + frame.originAdapter(), outputSequence,
                frame.opusFrame().length);
        injecting.set(true);
        try {
            if (source instanceof ServerProximitySource<?> proximity) {
                proximity.sendAudioFrame(encrypted, outputSequence,
                        (short) Math.min(Short.MAX_VALUE, Math.round(frame.source().distance())));
            } else if (source instanceof BaseServerDirectSource direct) {
                direct.sendAudioFrame(encrypted, outputSequence);
            }
        } finally {
            injecting.remove();
        }
    }

    @Override
    public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        // Rich frames are delivered through receiveAudioFrame.
    }

    @Override
    public void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
        ServerAudioSource<?> source = nativeSourcesByEntityId.get(entityId);
        if (!active.get() || source == null) {
            return;
        }
        injecting.set(true);
        try {
            audioDebug.recordEnd("plasmo-out", "fired");
            long outputSequence = nextOutputSequence(source.getId());
            if (source instanceof ServerProximitySource<?> proximity) {
                proximity.sendAudioEnd(outputSequence,
                        (short) Math.min(Short.MAX_VALUE, Math.round(
                                plugin.getConfig().getDouble("config.voice.proximity-distance", 30D))));
            } else if (source instanceof BaseServerDirectSource direct) {
                direct.sendAudioEnd(outputSequence);
            }
        } finally {
            injecting.remove();
        }
    }

    private ServerAudioSource<?> createNativeSource(VoiceAudioSource source, VoiceAudioAudience audience) {
        ServerSourceLine line = voiceServer.getSourceLineManager().getLineByName("proximity").orElse(null);
        if (line == null) {
            return null;
        }
        return switch (source.type()) {
            case PLAYER -> voiceServer.getPlayerManager().getPlayerById(source.playerId())
                    .map(player -> player.hasVoiceChat()
                            ? line.createPlayerSource(player, source.stereo())
                            : line.createEntitySource(player.getInstance(), source.stereo()))
                    .orElse(null);
            case ENTITY -> {
                Player player = source.minecraftEntityId() == null ? null
                        : plugin.getServer().getPlayer(source.minecraftEntityId());
                yield player == null ? createStaticSource(line, source)
                        : line.createEntitySource(voiceServer.getMinecraftServer().getEntityByInstance(player),
                                source.stereo());
            }
            case POSITION -> createStaticSource(line, source);
            case DIRECT -> audience.playerIds().stream().findFirst()
                    .flatMap(voiceServer.getPlayerManager()::getPlayerById)
                    .map(player -> line.createDirectSource(player, source.stereo())).orElse(null);
            case BROADCAST -> {
                ServerBroadcastSource broadcast = line.createBroadcastSource(source.stereo());
                if (audience.mode() == VoiceAudioAudience.Mode.INCLUDE) {
                    Set<VoicePlayer> players = ConcurrentHashMap.newKeySet();
                    audience.playerIds().forEach(id -> voiceServer.getPlayerManager().getPlayerById(id)
                            .ifPresent(players::add));
                    broadcast.setPlayers(players);
                }
                yield broadcast;
            }
        };
    }

    private ServerAudioSource<?> createStaticSource(ServerSourceLine line, VoiceAudioSource source) {
        Player carrier = plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player.getWorld().getKey().toString().equals(source.worldId())).findFirst().orElse(null);
        if (carrier == null) {
            return null;
        }
        var world = voiceServer.getMinecraftServer().getWorld(carrier.getWorld());
        return line.createStaticSource(new ServerPos3d(world, source.x(), source.y(), source.z()), source.stereo());
    }

    private VoiceAudioSource describeSource(ServerAudioSource<?> source, short distance) {
        String category = source.getLine().getName();
        if (source instanceof ServerPlayerSource playerSource) {
            UUID playerId = playerSource.getPlayer().getInstance().getUuid();
            Player player = plugin.getServer().getPlayer(playerId);
            OptionalInt entityId = player == null ? OptionalInt.empty()
                    : plugin.getSessionManager().getBoundEntityId(player.getName());
            if (entityId.isPresent()) {
                return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.PLAYER, entityId.getAsInt(), playerId,
                        playerId, "", 0D, 0D, 0D, distance, category, isStereo(source));
            }
        }
        if (source instanceof ServerEntitySource entitySource) {
            ServerPos3d pos = entitySource.getPosition();
            return positioned(source, VoiceAudioSourceType.ENTITY, entitySource.getEntity().getUuid(), pos, distance,
                    category);
        }
        if (source instanceof ServerProximitySource<?> proximity) {
            return positioned(source, VoiceAudioSourceType.POSITION, null, proximity.getPosition(), distance, category);
        }
        if (source instanceof ServerDirectSource direct) {
            UUID playerId = direct.getPlayer().getInstance().getUuid();
            return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.DIRECT, 0, playerId, null, "",
                    0D, 0D, 0D, 0F, category, isStereo(source));
        }
        return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.BROADCAST, 0, null, null, "",
                0D, 0D, 0D, 0F, category, isStereo(source));
    }

    private VoiceAudioSource positioned(ServerAudioSource<?> source, VoiceAudioSourceType type, UUID entityId,
            ServerPos3d pos, short distance, String category) {
        return new VoiceAudioSource(source.getId(), type, 0, null, entityId,
                pos.getWorld() == null ? "" : pos.getWorld().getName(), pos.getX(), pos.getY(), pos.getZ(), distance,
                category, isStereo(source));
    }

    private boolean isStereo(ServerAudioSource<?> source) {
        try {
            var info = source.resolveSourceInfo().getNow(null);
            return info != null && info.isStereo();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private VoiceAudioAudience audience(ServerAudioSource<?> source) {
        if (source instanceof ServerDirectSource direct) {
            return new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE,
                    Set.of(direct.getPlayer().getInstance().getUuid()));
        }
        if (source instanceof ServerBroadcastSource broadcast && !broadcast.getPlayers().isEmpty()) {
            Set<UUID> players = ConcurrentHashMap.newKeySet();
            broadcast.getPlayers().forEach(player -> players.add(player.getInstance().getUuid()));
            return new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE, players);
        }
        return source instanceof ServerBroadcastSource ? VoiceAudioAudience.EVERYONE : VoiceAudioAudience.PROXIMITY;
    }

    private byte[] decryptFrame(byte[] encrypted, String description) {
        try {
            return voiceServer.getDefaultEncryption().decrypt(encrypted);
        } catch (EncryptionException exception) {
            plugin.Logger.warn("Failed to decrypt Plasmo Voice audio from " + description + ": "
                    + exception.getMessage());
            return null;
        }
    }

    private byte[] encryptFrame(byte[] opus, String description) {
        try {
            return voiceServer.getDefaultEncryption().encrypt(opus);
        } catch (EncryptionException exception) {
            plugin.Logger.warn("Failed to encrypt Plasmo Voice audio for " + description + ": "
                    + exception.getMessage());
            return null;
        }
    }

    private long nextOutputSequence(UUID sourceId) {
        return outputSequences.computeIfAbsent(sourceId, ignored -> new AtomicLong()).getAndIncrement();
    }

    @Override
    public void endpointUpdated(VoiceEndpointState endpoint) {
        if (active.get() && !endpoint.isConnectedThrough(id())) {
            broadcastProjectedPlayer(endpoint);
        }
    }

    @Override
    public void endpointRemoved(UUID playerId, String playerName) {
        if (active.get() && voiceServer != null) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerDisconnectPacket(playerId));
            nativeSources.remove(playerId);
        }
    }

    @Override
    public void playerVoiceStateUpdated(VoiceCraftPlayerState playerState) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(playerState.playerId());
        if (!active.get() || (endpoint != null && endpoint.isConnectedThrough(id()))) {
            return;
        }
        context.audioBridge().entityIdentities().stream()
                .filter(identity -> identity.playerId().equals(playerState.playerId())).findFirst()
                .ifPresent(this::broadcastProjectedPlayer);
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (active.get() && (endpoint == null || !endpoint.isConnectedThrough(id()))) {
            broadcastProjectedPlayer(identity);
        }
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (active.get() && (endpoint == null || endpoint.transports().isEmpty())) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerDisconnectPacket(identity.playerId()));
        }
    }

    private void sendProjectedPlayersTo(VoiceServerPlayer receiver) {
        context.audioBridge().entityIdentities().stream()
                .filter(identity -> {
                    VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
                    return endpoint == null || !endpoint.isConnectedThrough(id());
                })
                .map(this::projectedPlayerInfo)
                .forEach(info -> receiver.sendPacket(new PlayerInfoUpdatePacket(info)));
    }

    private void broadcastProjectedPlayer(VoiceEndpointState endpoint) {
        if (voiceServer != null) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerInfoUpdatePacket(projectedPlayerInfo(endpoint)));
        }
    }

    private void broadcastProjectedPlayer(VoiceCraftEntityIdentity identity) {
        if (voiceServer != null) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerInfoUpdatePacket(projectedPlayerInfo(identity)));
        }
    }

    private VoicePlayerInfo projectedPlayerInfo(VoiceEndpointState endpoint) {
        VoiceCraftPlayerState playerState = context == null ? null
                : context.audioBridge().playerVoiceState(endpoint.playerId()).orElse(null);
        return new VoicePlayerInfo(endpoint.playerId(), endpoint.playerName(), false,
                playerState != null && playerState.voiceDisabled(),
                playerState != null && playerState.microphoneMuted());
    }

    private VoicePlayerInfo projectedPlayerInfo(VoiceCraftEntityIdentity identity) {
        VoiceCraftPlayerState playerState = context == null ? null
                : context.audioBridge().playerVoiceState(identity.playerId()).orElse(null);
        return new VoicePlayerInfo(identity.playerId(), identity.playerName(), false,
                playerState != null && playerState.voiceDisabled(),
                playerState != null && playerState.microphoneMuted());
    }

    private void clearProjectedPlayers() {
        if (voiceServer == null) {
            return;
        }
        if (context != null) {
            context.audioBridge().entityIdentities().stream()
                    .map(VoiceCraftEntityIdentity::playerId).distinct()
                    .forEach(playerId -> voiceServer.getTcpPacketManager()
                            .broadcast(new PlayerDisconnectPacket(playerId)));
        }
    }

    private void bootstrapConnectedPlayers() {
        if (!active.get() || voiceServer == null) {
            return;
        }
        voiceServer.getUdpConnectionManager().getConnections().forEach(connection -> {
            UUID playerId = connection.getPlayer().getInstance().getUuid();
            runMain(() -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    plugin.getVoiceEndpointRegistry().connect(id(), player);
                }
            });
        });
    }

    private void runMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
