package team.avion.fabric.adapters.simplevoicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.*;
import net.minecraft.server.level.ServerPlayer;
import team.avion.adapter.*;
import team.avion.fabric.VoiceCraftJava;
import team.avion.fabric.utils.FabricMinecraftCompat;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fabric entrypoint for the official Simple Voice Chat server API. */
@ForgeVoicechatPlugin
public final class FabricSimpleVoiceChatBridge implements VoicechatPlugin, VoiceCraftAudioSink,
        VoiceEndpointListener, VoiceCraftPlayerStateListener, VoiceCraftIdentityListener {
    private static final String ID = "simple-voice-chat";
    private final Map<UUID, AtomicLong> fallbackSequences = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> outputSequences = new ConcurrentHashMap<>();
    private final AtomicBoolean sinkRegistered = new AtomicBoolean();
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final VoiceAudioEndDebouncer<UUID> audioEnds =
            new VoiceAudioEndDebouncer<>("VoiceCraft-SVC-AudioEnd");
    private final VoiceAudioDebugMetrics audioDebug = new VoiceAudioDebugMetrics(
            FabricSimpleVoiceChatBridge::debugEnabled, FabricSimpleVoiceChatBridge::debugLog);
    private VoicechatServerApi api;

    @Override
    public String getPluginId() {
        return "voicecraft_java_bridge";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            this.api = serverApi;
            VoiceCraftJava plugin = VoiceCraftJava.getInstance();
            ensureSink(plugin);
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(PlayerConnectedEvent.class, this::connected, -10_000);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::disconnected, -10_000);
        registration.registerEvent(PlayerStateChangedEvent.class, this::playerStateChanged, -10_000);
        registration.registerEvent(MicrophonePacketEvent.class, this::microphone, -10_000);
        registration.registerEvent(EntitySoundPacketEvent.class, this::entitySound, -10_000);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::locationalSound, -10_000);
        registration.registerEvent(StaticSoundPacketEvent.class, this::staticSound, -10_000);
    }

    private void connected(PlayerConnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        ensureSink(plugin);
        if (!enabled(plugin)) return;
        UUID id = event.getConnection().getPlayer().getUuid();
        runServer(plugin, () -> player(plugin, id).ifPresent(p -> plugin.getVoiceEndpointRegistry().connect(ID, p)));
    }

    private void disconnected(PlayerDisconnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        ensureSink(plugin);
        UUID playerId = event.getPlayerUuid();
        audioEnds.cancel(playerId);
        fallbackSequences.remove(playerId);
        outputSequences.remove(playerId);
        if (enabled(plugin)) plugin.getVoiceEndpointRegistry().disconnect(ID, playerId);
    }

    private void playerStateChanged(PlayerStateChangedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || !plugin.getVoiceEndpointRegistry().isConnected(event.getPlayerUuid(), ID)) return;
        plugin.getSessionManager().updatePlayerVoiceState(new VoiceCraftPlayerState(
                event.getPlayerUuid(), event.isDisabled(), false));
    }

    private void microphone(MicrophonePacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || event.isCancelled() || injecting.get() || event.getSenderConnection() == null) return;
        UUID playerId = event.getSenderConnection().getPlayer().getUuid();
        if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, ID)) {
            event.cancel();
            return;
        }
        Optional<ServerPlayer> player = player(plugin, playerId);
        OptionalInt entity = player.isEmpty() ? OptionalInt.empty()
                : plugin.getSessionManager().getBoundEntityId(player.get().getName().getString());
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (entity.isEmpty()) {
            event.cancel();
            return;
        }
        long seq = microphoneSequence(playerId, event.getPacket());
        if (opus == null || opus.length == 0) {
            audioDebug.recordEnd("svc-in", "scheduled");
            audioEnds.schedule(playerId, () -> {
                VoiceCraftJava current = VoiceCraftJava.getInstance();
                if (enabled(current)) {
                    current.getSessionManager().sendEntityAudioEnd(entity.getAsInt(), seq);
                    audioDebug.recordEnd("svc-in", "fired");
                }
            });
            event.cancel();
            return;
        }
        if (audioEnds.cancel(playerId)) {
            audioDebug.recordEnd("svc-in", "cancelled");
        }
        audioDebug.recordFrame("svc-in", seq, opus.length);
        VoiceAudioSource source = VoiceAudioSource.player(entity.getAsInt(), playerId,
                plugin.getConfig().getInt("config.voice.proximity-distance", 30));
        plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, source,
                VoiceAudioAudience.PROXIMITY, seq, (int) (seq & 0xFFFF), 1F, opus));
        event.cancel();
    }

    private void entitySound(EntitySoundPacketEvent event) {
        EntitySoundPacket packet = event.getPacket();
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!captureAllowed(plugin, event.isCancelled())) return;
        UUID receiver = receiver(event.getReceiverConnection());
        VoiceAudioSource source = player(plugin, packet.getEntityUuid())
                .flatMap(p -> {
                    OptionalInt id = plugin.getSessionManager().getBoundEntityId(p.getName().getString());
                    return id.isPresent() ? Optional.of(VoiceAudioSource.player(id.getAsInt(), p.getUUID(), packet.getDistance()))
                            : Optional.empty();
                }).orElse(new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.ENTITY, 0, null,
                        packet.getEntityUuid(), "", 0, 0, 0, packet.getDistance(), packet.getCategory(), false));
        capture(plugin, packet, source, receiver);
        event.cancel();
    }

    private void locationalSound(LocationalSoundPacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!captureAllowed(plugin, event.isCancelled())) return;
        LocationalSoundPacket packet = event.getPacket();
        UUID receiver = receiver(event.getReceiverConnection());
        String world = player(plugin, receiver).map(FabricMinecraftCompat::dimensionId).orElse("");
        capture(plugin, packet, new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.POSITION, 0, null,
                null, world, packet.getPosition().getX(), packet.getPosition().getY(), packet.getPosition().getZ(),
                packet.getDistance(), packet.getCategory(), false), receiver);
        event.cancel();
    }

    private void staticSound(StaticSoundPacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!captureAllowed(plugin, event.isCancelled())) return;
        SoundPacket packet = event.getPacket();
        capture(plugin, packet, new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.DIRECT, 0,
                packet.getSender(), null, "", 0, 0, 0, 0, packet.getCategory(), false),
                receiver(event.getReceiverConnection()));
        event.cancel();
    }

    private void capture(VoiceCraftJava plugin, SoundPacket packet, VoiceAudioSource source, UUID receiver) {
        byte[] opus = packet.getOpusEncodedData();
        if (receiver == null || opus == null || opus.length == 0) return;
        plugin.getSessionManager().getOrCreateVirtualEntity(source, ID).ifPresent(routed ->
                plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, routed,
                        new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE, Set.of(receiver)),
                        packet.getSequenceNumber(), (int) (packet.getSequenceNumber() & 0xFFFF), 0F, opus)));
    }

    @Override
    public void receiveAudioFrame(VoiceAudioFrame frame) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || api == null || frame.opusFrame().length == 0) return;
        VoiceAudioFrame outputFrame = withOutputSequence(frame);
        audioDebug.recordFrame("svc-out/from-" + frame.originAdapter(), outputFrame.sequenceNumber(),
                outputFrame.opusFrame().length);
        injecting.set(true);
        try {
            for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
                UUID id = player.getUUID();
                if (!plugin.getVoiceEndpointRegistry().isPrimary(id, ID) || !outputFrame.audience().allows(id)
                        || outputFrame.source().playerIdentity().map(id::equals).orElse(false)) continue;
                VoicechatConnection connection = api.getConnectionOf(id);
                if (connection == null || !connection.isConnected() || connection.isDisabled()) continue;
                send(connection, outputFrame);
            }
        } finally {
            injecting.remove();
        }
    }

    @Override public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) { }

    @Override
    public void receiveEntityAudioEnd(int entityId, long sequenceNumber) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || api == null) return;
        plugin.getSessionManager().entityIdentity(entityId).ifPresent(identity -> {
            audioDebug.recordEnd("svc-out", "fired");
            long outputSequence = nextOutputSequence(identity.playerId());
            VoiceAudioFrame end = new VoiceAudioFrame(UUID.randomUUID(), "voicecraft",
                    VoiceAudioSource.player(entityId, identity.playerId(),
                            plugin.getConfig().getInt("config.voice.proximity-distance", 30)),
                    VoiceAudioAudience.PROXIMITY, outputSequence, (int) outputSequence, 0F, new byte[0]);
            for (ServerPlayer player : plugin.getServer().getPlayerList().getPlayers()) {
                UUID receiverId = player.getUUID();
                if (receiverId.equals(identity.playerId())
                        || !plugin.getVoiceEndpointRegistry().isPrimary(receiverId, ID)) continue;
                VoicechatConnection connection = api.getConnectionOf(receiverId);
                if (connection != null && connection.isConnected() && !connection.isDisabled()) send(connection, end);
            }
        });
    }

    private VoiceAudioFrame withOutputSequence(VoiceAudioFrame frame) {
        long sequence = nextOutputSequence(frame.source().streamId());
        return new VoiceAudioFrame(frame.routeId(), frame.originAdapter(), frame.source(), frame.audience(),
                sequence, (int) (sequence & 0xFFFF), frame.loudness(), frame.opusFrame());
    }

    private long nextOutputSequence(UUID streamId) {
        return outputSequences.computeIfAbsent(streamId, ignored -> new AtomicLong()).getAndIncrement();
    }

    private long microphoneSequence(UUID playerId, MicrophonePacket packet) {
        try {
            return packet.entitySoundPacketBuilder().build().getSequenceNumber();
        } catch (RuntimeException ignored) {
            return fallbackSequences.computeIfAbsent(playerId, key -> new AtomicLong()).getAndIncrement();
        }
    }

    @Override
    public void endpointUpdated(VoiceEndpointState endpoint) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null) runServer(plugin, () -> syncProjectedConnection(plugin, endpoint));
    }

    @Override
    public void endpointRemoved(UUID playerId, String playerName) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null) runServer(plugin, () -> setProjectedConnection(playerId, false, false));
    }

    @Override
    public void playerVoiceStateUpdated(VoiceCraftPlayerState state) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin == null) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(state.playerId());
        if ((endpoint == null || !endpoint.isConnectedThrough(ID)) && hasIdentity(plugin, state.playerId())) {
            runServer(plugin, () -> setProjectedConnection(state.playerId(), true, state.voiceDisabled()));
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin == null) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || !endpoint.isConnectedThrough(ID)) {
            runServer(plugin, () -> {
                boolean disabled = plugin.getSessionManager().playerVoiceState(identity.playerId())
                        .map(VoiceCraftPlayerState::voiceDisabled).orElse(false);
                setProjectedConnection(identity.playerId(), true, disabled);
            });
        }
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin == null) return;
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || endpoint.transports().isEmpty()) {
            runServer(plugin, () -> setProjectedConnection(identity.playerId(), false, false));
        }
    }

    private void syncProjectedConnection(VoiceCraftJava plugin, VoiceEndpointState endpoint) {
        if (endpoint.isConnectedThrough(ID)) return;
        boolean disabled = plugin.getSessionManager().playerVoiceState(endpoint.playerId())
                .map(VoiceCraftPlayerState::voiceDisabled).orElse(false);
        setProjectedConnection(endpoint.playerId(), true, disabled);
    }

    private void setProjectedConnection(UUID playerId, boolean connected, boolean disabled) {
        if (api == null) return;
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null || connection.isInstalled()) return;
        connection.setConnected(connected);
        connection.setDisabled(disabled);
    }

    private void send(VoicechatConnection connection, VoiceAudioFrame frame) {
        VoiceAudioSource source = frame.source();
        UUID sender = source.playerId() == null ? source.streamId() : source.playerId();
        String category = source.category().isBlank() ? null : source.category();
        switch (source.type()) {
            case PLAYER, ENTITY -> api.sendEntitySoundPacketTo(connection, api.createPacket().entitySoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).entityUuid(source.minecraftEntityId() == null ? sender : source.minecraftEntityId())
                    .opusEncodedData(frame.opusFrame()).sequenceNumber(frame.sequenceNumber()).category(category)
                    .whispering(false).distance(source.distance()).build());
            case POSITION -> api.sendLocationalSoundPacketTo(connection, api.createPacket().locationalSoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).position(api.createPosition(source.x(), source.y(), source.z()))
                    .opusEncodedData(frame.opusFrame()).sequenceNumber(frame.sequenceNumber()).category(category)
                    .distance(source.distance()).build());
            case DIRECT, BROADCAST -> api.sendStaticSoundPacketTo(connection, api.createPacket().staticSoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).opusEncodedData(frame.opusFrame())
                    .sequenceNumber(frame.sequenceNumber()).category(category).build());
        }
    }

    private boolean captureAllowed(VoiceCraftJava plugin, boolean cancelled) {
        return enabled(plugin) && !cancelled && !injecting.get();
    }
    private static boolean enabled(VoiceCraftJava plugin) {
        return plugin != null
                && plugin.getTargetCapabilities().simpleVoiceChat()
                && plugin.getConfig().getBoolean("config.adapters.simple-voice-chat.enabled", false);
    }

    private static boolean debugEnabled() {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("config.debug", false);
    }

    private static void debugLog(String message) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null) plugin.Logger.info(message);
    }
    private static Optional<ServerPlayer> player(VoiceCraftJava plugin, UUID id) {
        return plugin == null || plugin.getServer() == null || id == null ? Optional.empty()
                : Optional.ofNullable(plugin.getServer().getPlayerList().getPlayer(id));
    }
    private static UUID receiver(VoicechatConnection connection) {
        return connection == null ? null : connection.getPlayer().getUuid();
    }
    private static void runServer(VoiceCraftJava plugin, Runnable task) {
        if (plugin.getServer() != null) plugin.getServer().execute(task);
    }

    private void ensureSink(VoiceCraftJava plugin) {
        if (plugin != null && plugin.getSessionManager() != null && sinkRegistered.compareAndSet(false, true)) {
            plugin.getSessionManager().registerAudioSink(this);
            plugin.getSessionManager().registerPlayerStateListener(this);
            plugin.getSessionManager().registerIdentityListener(this);
            plugin.getVoiceEndpointRegistry().registerListener(this);
            plugin.getSessionManager().entityIdentities().forEach(this::entityIdentityRegistered);
        }
    }

    private boolean hasIdentity(VoiceCraftJava plugin, UUID playerId) {
        return plugin.getSessionManager().entityIdentities().stream()
                .anyMatch(identity -> identity.playerId().equals(playerId));
    }
}
