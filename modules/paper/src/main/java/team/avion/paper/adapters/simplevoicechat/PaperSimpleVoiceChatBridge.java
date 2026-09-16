package team.avion.paper.adapters.simplevoicechat;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.adapter.VoiceEndpointListener;
import team.avion.adapter.VoiceEndpointState;
import team.avion.paper.VoiceCraftJava;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Native Simple Voice Chat API bridge. SVC owns transport; VoiceCraft owns server-side routing. */
public final class PaperSimpleVoiceChatBridge implements VoiceAdapter, VoiceCraftAudioSink, VoicechatPlugin,
        VoiceEndpointListener, VoiceCraftPlayerStateListener, VoiceCraftIdentityListener {
    private static final int FINAL_EVENT_PRIORITY = -10_000;

    private final VoiceCraftJava plugin;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private final Map<UUID, AtomicLong> fallbackSequences = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> outputSequences = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final VoiceAudioDebugMetrics audioDebug;

    private volatile AdapterState state = AdapterState.NEW;
    private volatile AdapterContext context;
    private volatile VoicechatServerApi voicechat;
    private volatile VoiceAudioEndDebouncer<UUID> audioEnds;

    public PaperSimpleVoiceChatBridge(VoiceCraftJava plugin) {
        this.plugin = plugin;
        this.audioDebug = new VoiceAudioDebugMetrics(
                () -> plugin.getConfig().getBoolean("config.debug", false), plugin.Logger::info);
    }

    @Override
    public String getPluginId() {
        return "voicecraft_java_bridge";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            voicechat = serverApi;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected, FINAL_EVENT_PRIORITY);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected, FINAL_EVENT_PRIORITY);
        registration.registerEvent(PlayerStateChangedEvent.class, this::onPlayerStateChanged, FINAL_EVENT_PRIORITY);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket, FINAL_EVENT_PRIORITY);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntitySoundPacket, FINAL_EVENT_PRIORITY);
        registration.registerEvent(LocationalSoundPacketEvent.class, this::onLocationalSoundPacket, FINAL_EVENT_PRIORITY);
        registration.registerEvent(StaticSoundPacketEvent.class, this::onStaticSoundPacket, FINAL_EVENT_PRIORITY);
    }

    @Override
    public String id() {
        return "simple-voice-chat";
    }

    @Override
    public String displayName() {
        return "Simple Voice Chat (native)";
    }

    @Override
    public AdapterState state() {
        return state;
    }

    @Override
    public void start(AdapterContext context) {
        this.context = context;
        state = AdapterState.STARTING;
        VoiceAudioEndDebouncer<UUID> previousAudioEnds = audioEnds;
        if (previousAudioEnds != null) {
            previousAudioEnds.close();
        }
        audioEnds = new VoiceAudioEndDebouncer<>("VoiceCraft-SVC-AudioEnd");
        if (registered.compareAndSet(false, true)) {
            BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
            if (service == null) {
                registered.set(false);
                state = AdapterState.FAILED;
                throw new IllegalStateException("Simple Voice Chat Bukkit service is unavailable");
            }
            service.registerPlugin(this);
        }
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
        VoiceAudioEndDebouncer<UUID> pendingAudioEnds = audioEnds;
        audioEnds = null;
        if (pendingAudioEnds != null) {
            pendingAudioEnds.close();
        }
        clearProjectedConnections();
        plugin.getVoiceEndpointRegistry().unregisterListener(this);
        if (context != null) {
            context.audioBridge().unregisterAudioSink(this);
            context.audioBridge().unregisterPlayerStateListener(this);
            context.audioBridge().unregisterIdentityListener(this);
        }
        fallbackSequences.clear();
        outputSequences.clear();
        state = AdapterState.STOPPED;
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getConnection().getPlayer().getUuid();
        runForPlayer(playerId, player -> plugin.getVoiceEndpointRegistry().connect(id(), player));
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getPlayerUuid();
        VoiceAudioEndDebouncer<UUID> pendingAudioEnds = audioEnds;
        if (pendingAudioEnds != null) {
            pendingAudioEnds.cancel(playerId);
        }
        fallbackSequences.remove(playerId);
        outputSequences.remove(playerId);
        Player player = plugin.getScheduler().player(playerId);
        if (player == null) {
            runMain(() -> plugin.getVoiceEndpointRegistry().disconnect(
                    id(), playerId, plugin.getVoiceEndpointRegistry().playerName(playerId)));
        } else {
            plugin.getScheduler().runAtEntity(player, () -> plugin.getVoiceEndpointRegistry().disconnect(
                    id(), playerId, player.getName()));
        }
    }

    private void onPlayerStateChanged(PlayerStateChangedEvent event) {
        if (!active.get() || context == null
                || !plugin.getVoiceEndpointRegistry().isConnected(event.getPlayerUuid(), id())) {
            return;
        }
        context.audioBridge().updatePlayerVoiceState(new VoiceCraftPlayerState(
                event.getPlayerUuid(), event.isDisabled(), false));
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!active.get() || event.isCancelled() || injecting.get()) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID playerId = sender.getPlayer().getUuid();
        if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, id())) {
            event.cancel();
            return;
        }
        OptionalInt entityId = plugin.getSessionManager().getBoundEntityId(playerName(playerId));
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (entityId.isEmpty()) {
            event.cancel();
            return;
        }
        long sequence = microphoneSequence(playerId, event.getPacket());
        if (opus == null || opus.length == 0) {
            VoiceAudioEndDebouncer<UUID> pendingAudioEnds = audioEnds;
            if (pendingAudioEnds != null) {
                audioDebug.recordEnd("svc-in", "scheduled");
                pendingAudioEnds.schedule(playerId, () -> {
                    if (active.get() && context != null) {
                        context.audioBridge().sendEntityAudioEnd(entityId.getAsInt(), sequence);
                        audioDebug.recordEnd("svc-in", "fired");
                    }
                });
            }
            event.cancel();
            return;
        }
        VoiceAudioEndDebouncer<UUID> pendingAudioEnds = audioEnds;
        if (pendingAudioEnds != null && pendingAudioEnds.cancel(playerId)) {
            audioDebug.recordEnd("svc-in", "cancelled");
        }
        audioDebug.recordFrame("svc-in", sequence, opus.length);
        int timestamp = (int) (sequence & 0xFFFF);
        VoiceAudioSource source = VoiceAudioSource.player(entityId.getAsInt(), playerId,
                (float) plugin.getConfig().getDouble("config.voice.proximity-distance", 30D));
        context.audioBridge().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), id(), source,
                VoiceAudioAudience.PROXIMITY, sequence, timestamp, 1F, opus));
        event.cancel();
    }

    private void onEntitySoundPacket(EntitySoundPacketEvent event) {
        if (!shouldCapture(event.isCancelled())) {
            return;
        }
        EntitySoundPacket packet = event.getPacket();
        UUID receiver = receiverId(event.getReceiverConnection());
        VoiceAudioSource source = entitySource(packet);
        captureAddonPacket(packet, source, receiver, packet.getDistance());
        event.cancel();
    }

    private void onLocationalSoundPacket(LocationalSoundPacketEvent event) {
        if (!shouldCapture(event.isCancelled())) {
            return;
        }
        LocationalSoundPacket packet = event.getPacket();
        UUID receiver = receiverId(event.getReceiverConnection());
        String world = receiver == null || plugin.getPositionsTask() == null
                ? "" : plugin.getPositionsTask().worldId(receiver);
        VoiceAudioSource source = new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.POSITION, 0,
                null, null, world, packet.getPosition().getX(), packet.getPosition().getY(), packet.getPosition().getZ(),
                packet.getDistance(), packet.getCategory(), false);
        captureAddonPacket(packet, source, receiver, packet.getDistance());
        event.cancel();
    }

    private void onStaticSoundPacket(StaticSoundPacketEvent event) {
        if (!shouldCapture(event.isCancelled())) {
            return;
        }
        SoundPacket packet = event.getPacket();
        UUID receiver = receiverId(event.getReceiverConnection());
        VoiceAudioSource source = new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.DIRECT, 0,
                packet.getSender(), null, "", 0D, 0D, 0D, 0F, packet.getCategory(), false);
        captureAddonPacket(packet, source, receiver, 0F);
        event.cancel();
    }

    private boolean shouldCapture(boolean cancelled) {
        return active.get() && !cancelled && !injecting.get() && context != null;
    }

    private void captureAddonPacket(SoundPacket packet, VoiceAudioSource source, UUID receiver, float distance) {
        byte[] opus = packet.getOpusEncodedData();
        if (receiver == null || opus == null || opus.length == 0) {
            return;
        }
        Optional<VoiceAudioSource> routed = plugin.getSessionManager().getOrCreateVirtualEntity(source, id());
        if (routed.isEmpty()) {
            return;
        }
        VoiceAudioAudience audience = new VoiceAudioAudience(VoiceAudioAudience.Mode.INCLUDE, Set.of(receiver));
        context.audioBridge().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), id(), routed.get(), audience,
                packet.getSequenceNumber(), (int) (packet.getSequenceNumber() & 0xFFFF), 0F, opus));
    }

    @Override
    public void receiveAudioFrame(VoiceAudioFrame frame) {
        VoicechatServerApi api = voicechat;
        if (!active.get() || api == null || frame.opusFrame().length == 0) {
            return;
        }
        VoiceAudioFrame outputFrame = withOutputSequence(frame);
        audioDebug.recordFrame("svc-out/from-" + frame.originAdapter(), outputFrame.sequenceNumber(),
                outputFrame.opusFrame().length);
        injecting.set(true);
        try {
            for (VoiceEndpointState endpoint : plugin.getVoiceEndpointRegistry().states()) {
                UUID playerId = endpoint.playerId();
                if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, id())
                        || !frame.audience().allows(playerId)
                        || frame.source().playerIdentity().map(playerId::equals).orElse(false)) {
                    continue;
                }
                VoicechatConnection connection = api.getConnectionOf(playerId);
                if (connection == null || !connection.isConnected() || connection.isDisabled()) {
                    continue;
                }
                try {
                    sendFrameTo(api, connection, outputFrame);
                } catch (RuntimeException exception) {
                    plugin.Logger.warn("Failed to deliver bridged audio to Simple Voice Chat player "
                            + endpoint.playerName() + ": " + exception.getMessage());
                }
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
        VoicechatServerApi api = voicechat;
        if (!active.get() || api == null || context == null) {
            return;
        }
        context.audioBridge().entityIdentity(entityId).ifPresent(identity -> {
            injecting.set(true);
            try {
                audioDebug.recordEnd("svc-out", "fired");
                long outputSequence = nextOutputSequence(identity.playerId());
                VoiceAudioFrame end = new VoiceAudioFrame(UUID.randomUUID(), "voicecraft",
                        VoiceAudioSource.player(entityId, identity.playerId(),
                                (float) plugin.getConfig().getDouble("config.voice.proximity-distance", 30D)),
                        VoiceAudioAudience.PROXIMITY, outputSequence, (int) outputSequence, 0F, new byte[0]);
                for (VoiceEndpointState endpoint : plugin.getVoiceEndpointRegistry().states()) {
                    UUID playerId = endpoint.playerId();
                    if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, id())
                            || playerId.equals(identity.playerId())) {
                        continue;
                    }
                    VoicechatConnection connection = api.getConnectionOf(playerId);
                    if (connection != null && connection.isConnected() && !connection.isDisabled()) {
                        sendFrameTo(api, connection, end);
                    }
                }
            } finally {
                injecting.remove();
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
        runMain(() -> syncProjectedConnection(endpoint));
    }

    @Override
    public void endpointRemoved(UUID playerId, String playerName) {
        runMain(() -> setProjectedConnection(playerId, false, false));
    }

    @Override
    public void playerVoiceStateUpdated(VoiceCraftPlayerState playerState) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(playerState.playerId());
        if ((endpoint == null || !endpoint.isConnectedThrough(id())) && hasIdentity(playerState.playerId())) {
            runMain(() -> setProjectedConnection(playerState.playerId(), true, playerState.voiceDisabled()));
        }
    }

    @Override
    public void entityIdentityRegistered(VoiceCraftEntityIdentity identity) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || !endpoint.isConnectedThrough(id())) {
            runMain(() -> {
                boolean disabled = context.audioBridge().playerVoiceState(identity.playerId())
                        .map(VoiceCraftPlayerState::voiceDisabled).orElse(false);
                setProjectedConnection(identity.playerId(), true, disabled);
            });
        }
    }

    @Override
    public void entityIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(identity.playerId());
        if (endpoint == null || endpoint.transports().isEmpty()) {
            runMain(() -> setProjectedConnection(identity.playerId(), false, false));
        }
    }

    private void syncProjectedConnection(VoiceEndpointState endpoint) {
        if (!endpoint.isConnectedThrough(id())) {
            boolean disabled = context == null ? false : context.audioBridge().playerVoiceState(endpoint.playerId())
                    .map(VoiceCraftPlayerState::voiceDisabled).orElse(false);
            setProjectedConnection(endpoint.playerId(), true, disabled);
        }
    }

    private void setProjectedConnection(UUID playerId, boolean connected, boolean disabled) {
        VoicechatServerApi api = voicechat;
        if (api == null) {
            return;
        }
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null || connection.isInstalled()) {
            return;
        }
        connection.setConnected(connected);
        connection.setDisabled(disabled);
    }

    private void clearProjectedConnections() {
        if (context != null) {
            context.audioBridge().entityIdentities().stream()
                    .map(VoiceCraftEntityIdentity::playerId).distinct()
                    .forEach(playerId -> setProjectedConnection(playerId, false, false));
        }
    }

    private boolean hasIdentity(UUID playerId) {
        return context != null && context.audioBridge().entityIdentities().stream()
                .anyMatch(identity -> identity.playerId().equals(playerId));
    }

    private void sendFrameTo(VoicechatServerApi api, VoicechatConnection connection, VoiceAudioFrame frame) {
        VoiceAudioSource source = frame.source();
        String category = source.category().isBlank() ? null : source.category();
        UUID sender = source.playerId() == null ? source.streamId() : source.playerId();
        switch (source.type()) {
            case PLAYER, ENTITY -> api.sendEntitySoundPacketTo(connection, api.createPacket().entitySoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).opusEncodedData(frame.opusFrame())
                    .sequenceNumber(frame.sequenceNumber()).category(category)
                    .entityUuid(source.minecraftEntityId() == null ? sender : source.minecraftEntityId())
                    .whispering(false).distance(source.distance()).build());
            case POSITION -> api.sendLocationalSoundPacketTo(connection, api.createPacket().locationalSoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).opusEncodedData(frame.opusFrame())
                    .sequenceNumber(frame.sequenceNumber()).category(category)
                    .position(api.createPosition(source.x(), source.y(), source.z())).distance(source.distance()).build());
            case DIRECT, BROADCAST -> api.sendStaticSoundPacketTo(connection, api.createPacket().staticSoundPacketBuilder()
                    .channelId(source.streamId()).sender(sender).opusEncodedData(frame.opusFrame())
                    .sequenceNumber(frame.sequenceNumber()).category(category).build());
        }
    }

    private VoiceAudioSource entitySource(EntitySoundPacket packet) {
        VoiceEndpointState endpoint = plugin.getVoiceEndpointRegistry().state(packet.getEntityUuid());
        if (endpoint != null) {
            return VoiceAudioSource.player(endpoint.voiceCraftEntityId(), endpoint.playerId(), packet.getDistance());
        }
        if (plugin.getScheduler().isFolia()) {
            return new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.ENTITY, 0, null,
                    packet.getEntityUuid(), "", 0D, 0D, 0D, packet.getDistance(), packet.getCategory(), false);
        }
        Entity entity = findEntity(packet.getEntityUuid());
        if (entity instanceof Player player) {
            OptionalInt entityId = plugin.getSessionManager().getBoundEntityId(player.getName());
            if (entityId.isPresent()) {
                return VoiceAudioSource.player(entityId.getAsInt(), player.getUniqueId(), packet.getDistance());
            }
        }
        Location location = entity == null ? null : entity.getLocation();
        return new VoiceAudioSource(packet.getChannelId(), VoiceAudioSourceType.ENTITY, 0, null,
                packet.getEntityUuid(), location == null ? "" : location.getWorld().getKey().toString(),
                location == null ? 0D : location.getX(), location == null ? 0D : location.getY(),
                location == null ? 0D : location.getZ(), packet.getDistance(), packet.getCategory(), false);
    }

    private Entity findEntity(UUID entityId) {
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private void bootstrapConnectedPlayers() {
        VoicechatServerApi api = voicechat;
        if (api == null) {
            return;
        }
        plugin.getServer().getOnlinePlayers().forEach(player ->
                plugin.getScheduler().runAtEntity(player, () -> {
                    VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
                    if (connection != null && connection.isConnected()) {
                        plugin.getVoiceEndpointRegistry().connect(id(), player);
                    }
                }));
    }

    private UUID receiverId(VoicechatConnection connection) {
        return connection == null ? null : connection.getPlayer().getUuid();
    }

    private String playerName(UUID playerId) {
        return plugin.getVoiceEndpointRegistry().playerName(playerId);
    }

    private void runMain(Runnable task) {
        plugin.getScheduler().runGlobal(task);
    }

    private void runForPlayer(UUID playerId, Consumer<Player> action) {
        Player player = plugin.getScheduler().player(playerId);
        if (player != null) {
            plugin.getScheduler().runAtEntity(player, () -> action.accept(player));
        }
    }
}
