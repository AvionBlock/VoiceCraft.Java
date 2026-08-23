package team.avion.fabric.adapters.plasmo;

import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.voice.api.addon.*;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.*;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.connection.*;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.proto.data.player.VoicePlayerInfo;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerDisconnectPacket;
import su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoUpdatePacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerStatePacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;
import team.avion.adapter.*;
import team.avion.fabric.VoiceCraftJava;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Native Plasmo Voice add-on used by the Fabric server mod. */
@Addon(id = "voicecraft-java", name = "VoiceCraft.Java Bridge", version = "2.0.0", authors = {"lil-jon-crunk"})
public final class FabricPlasmoVoiceBridge implements AddonInitializer, VoiceCraftAudioSink,
        VoiceEndpointListener, VoiceCraftPlayerStateListener, VoiceCraftIdentityListener {
    private static final String ID = "plasmo";
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final Map<UUID, ServerAudioSource<?>> nativeSources = new ConcurrentHashMap<>();
    private final Map<Integer, ServerAudioSource<?>> nativeSourcesByEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> outputSequences = new ConcurrentHashMap<>();
    private final VoiceAudioEndDebouncer<UUID> audioEnds =
            new VoiceAudioEndDebouncer<>("VoiceCraft-Plasmo-AudioEnd");
    private final VoiceAudioDebugMetrics audioDebug = new VoiceAudioDebugMetrics(
            FabricPlasmoVoiceBridge::debugEnabled, FabricPlasmoVoiceBridge::debugLog);

    @InjectPlasmoVoice private PlasmoVoiceServer voiceServer;

    @Override
    public void onAddonInitialize() {
        voiceServer.getEventBus().register(this, UdpClientConnectedEvent.class, EventPriority.HIGHEST, this::connected);
        voiceServer.getEventBus().register(this, UdpClientDisconnectedEvent.class, EventPriority.HIGHEST, this::disconnected);
        voiceServer.getEventBus().register(this, UdpPacketReceivedEvent.class, EventPriority.HIGHEST, this::udpPacket);
        voiceServer.getEventBus().register(this, TcpPacketReceivedEvent.class, EventPriority.HIGHEST, this::tcpPacket);
        voiceServer.getEventBus().register(this, ServerSourceAudioPacketEvent.class, EventPriority.HIGHEST, this::sourcePacket);
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (enabled(plugin)) {
            plugin.getSessionManager().registerAudioSink(this);
            plugin.getSessionManager().registerPlayerStateListener(this);
            plugin.getSessionManager().registerIdentityListener(this);
            plugin.getVoiceEndpointRegistry().registerListener(this);
            plugin.getSessionManager().entityIdentities().forEach(this::entityIdentityRegistered);
        }
    }

    @Override
    public void onAddonShutdown() {
        if (voiceServer != null) voiceServer.getEventBus().unregister(this);
        audioEnds.close();
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null && plugin.getSessionManager() != null) {
            clearProjectedPlayers(plugin);
            plugin.getSessionManager().unregisterAudioSink(this);
            plugin.getSessionManager().unregisterPlayerStateListener(this);
            plugin.getSessionManager().unregisterIdentityListener(this);
            plugin.getVoiceEndpointRegistry().unregisterListener(this);
        }
        nativeSources.clear();
        nativeSourcesByEntityId.clear();
        outputSequences.clear();
    }

    private void connected(UdpClientConnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        UUID id = event.getConnection().getPlayer().getInstance().getUuid();
        runServer(plugin, () -> Optional.ofNullable(plugin.getServer().getPlayerList().getPlayer(id))
                .ifPresent(p -> {
                    plugin.getVoiceEndpointRegistry().connect(ID, p);
                    sendProjectedPlayersTo(plugin, (VoiceServerPlayer) event.getConnection().getPlayer());
                }));
    }

    private void disconnected(UdpClientDisconnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        if (audioEnds.cancel(playerId)) {
            audioDebug.recordEnd("plasmo-in", "cancelled");
        }
        if (enabled(plugin)) plugin.getVoiceEndpointRegistry().disconnect(ID, playerId);
    }

    private void udpPacket(UdpPacketReceivedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || event.isCancelled() || injecting.get()
                || !(event.getPacket() instanceof PlayerAudioPacket packet)) return;
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, ID)) {
            event.setCancelled(true);
            return;
        }
        String name = event.getConnection().getPlayer().getInstance().getName();
        OptionalInt entity = plugin.getSessionManager().getBoundEntityId(name);
        if (entity.isEmpty() || packet.getData() == null || packet.getData().length == 0) {
            event.setCancelled(true);
            return;
        }
        byte[] opus = decryptFrame(plugin, packet.getData(), "player " + playerId);
        if (opus == null || opus.length == 0) {
            event.setCancelled(true);
            return;
        }
        if (audioEnds.cancel(playerId)) {
            audioDebug.recordEnd("plasmo-in", "cancelled");
        }
        audioDebug.recordFrame("plasmo-in", packet.getSequenceNumber(), opus.length);
        VoiceAudioSource source = VoiceAudioSource.player(entity.getAsInt(), playerId, packet.getDistance());
        plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, source,
                VoiceAudioAudience.PROXIMITY, packet.getSequenceNumber(),
                (int) (packet.getSequenceNumber() & 0xFFFF), 1F, opus));
        event.setCancelled(true);
    }

    private void tcpPacket(TcpPacketReceivedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        UUID playerId = event.getPlayer().getInstance().getUuid();
        if (event.getPacket() instanceof PlayerStatePacket statePacket
                && plugin.getVoiceEndpointRegistry().isConnected(playerId, ID)) {
            plugin.getSessionManager().updatePlayerVoiceState(new VoiceCraftPlayerState(playerId,
                    statePacket.isVoiceDisabled(), statePacket.isMicrophoneMuted()));
            return;
        }
        if (event.getPacket() instanceof PlayerAudioEndPacket endPacket
                && plugin.getVoiceEndpointRegistry().isPrimary(playerId, ID)) {
            plugin.getSessionManager().getBoundEntityId(event.getPlayer().getInstance().getName())
                    .ifPresent(entityId -> {
                        audioDebug.recordEnd("plasmo-in", "scheduled");
                        audioEnds.schedule(playerId, () -> {
                            VoiceCraftJava current = VoiceCraftJava.getInstance();
                            if (enabled(current)) {
                                current.getSessionManager().sendEntityAudioEnd(entityId,
                                        endPacket.getSequenceNumber());
                                audioDebug.recordEnd("plasmo-in", "fired");
                            }
                        });
                    });
        }
    }

    private void sourcePacket(ServerSourceAudioPacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || event.isCancelled() || injecting.get()) return;
        byte[] encrypted = event.getPacket().getData();
        if (encrypted == null || encrypted.length == 0) return;
        byte[] opus = decryptFrame(plugin, encrypted, "source " + event.getSource().getId());
        if (opus == null || opus.length == 0) return;
        ServerAudioSource<?> nativeSource = event.getSource();
        nativeSources.put(nativeSource.getId(), nativeSource);
        VoiceAudioSource source = describe(nativeSource, event.getDistance());
        plugin.getSessionManager().getOrCreateVirtualEntity(source, ID).ifPresent(routed -> {
            plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, routed,
                    audience(nativeSource), event.getPacket().getSequenceNumber(),
                    (int) (event.getPacket().getSequenceNumber() & 0xFFFF), 0F, opus));
            event.setResult(ServerSourceAudioPacketEvent.Result.HANDLED);
            event.setCancelled(true);
        });
    }

    @Override
    public void receiveAudioFrame(VoiceAudioFrame frame) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || frame.opusFrame().length == 0) return;
        ServerAudioSource<?> source = nativeSources.computeIfAbsent(frame.source().streamId(),
                ignored -> createSource(frame.source(), frame.audience()));
        if (source == null) return;
        byte[] encrypted = encryptFrame(plugin, frame.opusFrame(), "source " + frame.source().streamId());
        if (encrypted == null) return;
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

    @Override public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) { }

    @Override
    public void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
        ServerAudioSource<?> source = nativeSourcesByEntityId.get(entityId);
        if (source == null) return;
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        injecting.set(true);
        try {
            audioDebug.recordEnd("plasmo-out", "fired");
            long outputSequence = nextOutputSequence(source.getId());
            if (source instanceof ServerProximitySource<?> proximity) {
                proximity.sendAudioEnd(outputSequence, (short) Math.min(Short.MAX_VALUE,
                        plugin.getConfig().getInt("config.voice.proximity-distance", 30)));
            } else if (source instanceof BaseServerDirectSource direct) {
                direct.sendAudioEnd(outputSequence);
            }
        } finally {
            injecting.remove();
        }
    }

    private ServerAudioSource<?> createSource(VoiceAudioSource source, VoiceAudioAudience audience) {
        ServerSourceLine line = voiceServer.getSourceLineManager().getLineByName("proximity").orElse(null);
        if (line == null) return null;
        return switch (source.type()) {
            case PLAYER -> voiceServer.getPlayerManager().getPlayerById(source.playerId())
                    .map(p -> p.hasVoiceChat()
                            ? line.createPlayerSource(p, source.stereo())
                            : line.createEntitySource(p.getInstance(), source.stereo()))
                    .orElse(null);
            case ENTITY -> {
                var player = voiceServer.getPlayerManager().getPlayerById(source.minecraftEntityId());
                yield player.isPresent() ? line.createEntitySource(player.get().getInstance(), source.stereo())
                        : staticSource(line, source);
            }
            case POSITION -> staticSource(line, source);
            case DIRECT -> audience.playerIds().stream().findFirst().flatMap(voiceServer.getPlayerManager()::getPlayerById)
                    .map(p -> line.createDirectSource(p, source.stereo())).orElse(null);
            case BROADCAST -> {
                ServerBroadcastSource broadcast = line.createBroadcastSource(source.stereo());
                if (audience.mode() == VoiceAudioAudience.Mode.INCLUDE) {
                    Set<VoicePlayer> players = ConcurrentHashMap.newKeySet();
                    audience.playerIds().forEach(id -> voiceServer.getPlayerManager().getPlayerById(id).ifPresent(players::add));
                    broadcast.setPlayers(players);
                }
                yield broadcast;
            }
        };
    }

    private ServerAudioSource<?> staticSource(ServerSourceLine line, VoiceAudioSource source) {
        var world = voiceServer.getMinecraftServer().getWorlds().stream()
                .filter(w -> w.getName().equals(source.worldId()) || w.getName().endsWith(source.worldId()))
                .findFirst().orElse(null);
        return world == null ? null : line.createStaticSource(new ServerPos3d(world, source.x(), source.y(), source.z()), source.stereo());
    }

    private VoiceAudioSource describe(ServerAudioSource<?> source, short distance) {
        String category = source.getLine().getName();
        boolean stereo = stereo(source);
        if (source instanceof ServerPlayerSource playerSource) {
            UUID id = playerSource.getPlayer().getInstance().getUuid();
            VoiceCraftJava plugin = VoiceCraftJava.getInstance();
            OptionalInt entity = plugin.getSessionManager().getBoundEntityId(playerSource.getPlayer().getInstance().getName());
            if (entity.isPresent()) return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.PLAYER,
                    entity.getAsInt(), id, id, "", 0, 0, 0, distance, category, stereo);
        }
        if (source instanceof ServerEntitySource entity) {
            return positioned(source, VoiceAudioSourceType.ENTITY, entity.getEntity().getUuid(), entity.getPosition(), distance, category, stereo);
        }
        if (source instanceof ServerProximitySource<?> proximity) {
            return positioned(source, VoiceAudioSourceType.POSITION, null, proximity.getPosition(), distance, category, stereo);
        }
        if (source instanceof ServerDirectSource direct) {
            UUID id = direct.getPlayer().getInstance().getUuid();
            return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.DIRECT, 0, id, null, "", 0, 0, 0, 0, category, stereo);
        }
        return new VoiceAudioSource(source.getId(), VoiceAudioSourceType.BROADCAST, 0, null, null, "", 0, 0, 0, 0, category, stereo);
    }

    private VoiceAudioSource positioned(ServerAudioSource<?> source, VoiceAudioSourceType type, UUID entity,
            ServerPos3d pos, short distance, String category, boolean stereo) {
        return new VoiceAudioSource(source.getId(), type, 0, null, entity,
                pos.getWorld() == null ? "" : pos.getWorld().getName(), pos.getX(), pos.getY(), pos.getZ(), distance, category, stereo);
    }

    private VoiceAudioAudience audience(ServerAudioSource<?> source) {
        if (source instanceof ServerDirectSource direct) return new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE,
                Set.of(direct.getPlayer().getInstance().getUuid()));
        if (source instanceof ServerBroadcastSource broadcast && !broadcast.getPlayers().isEmpty()) {
            Set<UUID> ids = ConcurrentHashMap.newKeySet();
            broadcast.getPlayers().forEach(p -> ids.add(p.getInstance().getUuid()));
            return new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE, ids);
        }
        return source instanceof ServerBroadcastSource ? VoiceAudioAudience.EVERYONE : VoiceAudioAudience.PROXIMITY;
    }

    private boolean stereo(ServerAudioSource<?> source) {
        try { var info = source.resolveSourceInfo().getNow(null); return info != null && info.isStereo(); }
        catch (RuntimeException ignored) { return false; }
    }

    private byte[] decryptFrame(VoiceCraftJava plugin, byte[] encrypted, String description) {
        try {
            return voiceServer.getDefaultEncryption().decrypt(encrypted);
        } catch (EncryptionException exception) {
            plugin.Logger.warn("Failed to decrypt Plasmo Voice audio from " + description + ": "
                    + exception.getMessage());
            return null;
        }
    }

    private byte[] encryptFrame(VoiceCraftJava plugin, byte[] opus, String description) {
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
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (enabled(plugin) && !endpoint.isConnectedThrough(ID)) broadcastProjectedPlayer(plugin, endpoint);
    }

    @Override
    public void endpointRemoved(UUID playerId, String playerName) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (enabled(plugin)) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerDisconnectPacket(playerId));
            nativeSources.remove(playerId);
        }
    }

    @Override
    public void playerVoiceStateUpdated(VoiceCraftPlayerState state) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(state.playerId());
        if (endpoint != null && endpoint.isConnectedThrough(ID)) return;
        plugin.getSessionManager().entityIdentities().stream()
                .filter(identity -> identity.playerId().equals(state.playerId())).findFirst()
                .ifPresent(identity -> broadcastProjectedPlayer(plugin, identity));
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || !endpoint.isConnectedThrough(ID)) broadcastProjectedPlayer(plugin, identity);
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || endpoint.transports().isEmpty()) {
            voiceServer.getTcpPacketManager().broadcast(new PlayerDisconnectPacket(identity.playerId()));
        }
    }

    private void sendProjectedPlayersTo(VoiceCraftJava plugin, VoiceServerPlayer receiver) {
        plugin.getSessionManager().entityIdentities().stream()
                .filter(identity -> {
                    VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
                    return endpoint == null || !endpoint.isConnectedThrough(ID);
                })
                .map(identity -> projectedPlayerInfo(plugin, identity))
                .forEach(info -> receiver.sendPacket(new PlayerInfoUpdatePacket(info)));
    }

    private void broadcastProjectedPlayer(VoiceCraftJava plugin, VoiceEndpointState endpoint) {
        voiceServer.getTcpPacketManager().broadcast(
                new PlayerInfoUpdatePacket(projectedPlayerInfo(plugin, endpoint)));
    }

    private void broadcastProjectedPlayer(VoiceCraftJava plugin, VoiceCraftEntityIdentity identity) {
        voiceServer.getTcpPacketManager().broadcast(
                new PlayerInfoUpdatePacket(projectedPlayerInfo(plugin, identity)));
    }

    private VoicePlayerInfo projectedPlayerInfo(VoiceCraftJava plugin, VoiceEndpointState endpoint) {
        VoiceCraftPlayerState state = plugin.getSessionManager().playerVoiceState(endpoint.playerId()).orElse(null);
        return new VoicePlayerInfo(endpoint.playerId(), endpoint.playerName(), false,
                state != null && state.voiceDisabled(), state != null && state.microphoneMuted());
    }

    private VoicePlayerInfo projectedPlayerInfo(VoiceCraftJava plugin, VoiceCraftEntityIdentity identity) {
        VoiceCraftPlayerState state = plugin.getSessionManager().playerVoiceState(identity.playerId()).orElse(null);
        return new VoicePlayerInfo(identity.playerId(), identity.playerName(), false,
                state != null && state.voiceDisabled(), state != null && state.microphoneMuted());
    }

    private void clearProjectedPlayers(VoiceCraftJava plugin) {
        if (voiceServer == null) return;
        plugin.getSessionManager().entityIdentities().stream()
                .map(VoiceCraftEntityIdentity::playerId).distinct()
                .forEach(playerId -> voiceServer.getTcpPacketManager()
                        .broadcast(new PlayerDisconnectPacket(playerId)));
    }

    private static boolean enabled(VoiceCraftJava plugin) {
        return plugin != null
                && plugin.getTargetCapabilities().plasmoVoice()
                && plugin.getConfig().getBoolean("config.adapters.plasmo.enabled", false);
    }

    private static boolean debugEnabled() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("config.debug", false);
    }

    private static void debugLog(String message) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null) plugin.Logger.info(message);
    }
    private static void runServer(VoiceCraftJava plugin, Runnable task) {
        if (plugin.getServer() != null) plugin.getServer().execute(task);
    }
}
