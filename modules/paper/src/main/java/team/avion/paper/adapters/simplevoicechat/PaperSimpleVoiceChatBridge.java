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
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import team.avion.adapter.AdapterContext;
import team.avion.adapter.AdapterState;
import team.avion.adapter.VoiceAdapter;
import team.avion.adapter.VoiceAudioAudience;
import team.avion.adapter.VoiceAudioFrame;
import team.avion.adapter.VoiceAudioSource;
import team.avion.adapter.VoiceAudioSourceType;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.paper.VoiceCraftJava;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Native Simple Voice Chat API bridge. SVC owns transport; VoiceCraft owns server-side routing. */
public final class PaperSimpleVoiceChatBridge implements VoiceAdapter, VoiceCraftAudioSink, VoicechatPlugin {
    private static final int FINAL_EVENT_PRIORITY = -10_000;

    private final VoiceCraftJava plugin;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong fallbackSequence = new AtomicLong();
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

    private volatile AdapterState state = AdapterState.NEW;
    private volatile AdapterContext context;
    private volatile VoicechatServerApi voicechat;

    public PaperSimpleVoiceChatBridge(VoiceCraftJava plugin) {
        this.plugin = plugin;
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
        state = AdapterState.RUNNING;
        bootstrapConnectedPlayers();
    }

    @Override
    public void stop() {
        active.set(false);
        if (context != null) {
            context.audioBridge().unregisterAudioSink(this);
        }
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
        state = AdapterState.STOPPED;
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getConnection().getPlayer().getUuid();
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                plugin.getVoiceEndpointRegistry().connect(id(), player);
            }
        });
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (!active.get()) {
            return;
        }
        UUID playerId = event.getPlayerUuid();
        closeDecoder(playerId);
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            plugin.getVoiceEndpointRegistry().disconnect(id(), playerId,
                    player == null ? playerId.toString() : player.getName());
        });
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
        if (entityId.isEmpty() || opus == null || opus.length == 0) {
            event.cancel();
            return;
        }
        long sequence = fallbackSequence.incrementAndGet();
        int timestamp = (int) (sequence & 0xFFFF);
        VoiceAudioSource source = VoiceAudioSource.player(entityId.getAsInt(), playerId,
                (float) plugin.getConfig().getDouble("config.voice.proximity-distance", 30D));
        context.audioBridge().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), id(), source,
                VoiceAudioAudience.PROXIMITY, sequence, timestamp, loudness(playerId, opus), opus));
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
        Player receiverPlayer = plugin.getServer().getPlayer(receiver);
        String world = receiverPlayer == null ? "" : receiverPlayer.getWorld().getKey().toString();
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
        injecting.set(true);
        try {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!plugin.getVoiceEndpointRegistry().isPrimary(player.getUniqueId(), id())
                        || !frame.audience().allows(player.getUniqueId())
                        || frame.source().playerIdentity().map(player.getUniqueId()::equals).orElse(false)) {
                    continue;
                }
                VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
                if (connection == null || !connection.isConnected() || connection.isDisabled()) {
                    continue;
                }
                sendFrameTo(api, connection, frame);
            }
        } finally {
            injecting.remove();
        }
    }

    @Override
    public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        // Rich frames are delivered through receiveAudioFrame.
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

    private float loudness(UUID playerId, byte[] opus) {
        VoicechatServerApi api = voicechat;
        if (api == null) {
            return 0F;
        }
        try {
            OpusDecoder decoder = decoders.computeIfAbsent(playerId, ignored -> api.createDecoder());
            short[] samples = decoder.decode(opus);
            int peak = 0;
            for (short sample : samples) {
                peak = Math.max(peak, Math.abs((int) sample));
            }
            return peak / 32768F;
        } catch (RuntimeException ignored) {
            closeDecoder(playerId);
            return 0F;
        }
    }

    private void closeDecoder(UUID playerId) {
        OpusDecoder decoder = decoders.remove(playerId);
        if (decoder != null) {
            decoder.close();
        }
    }

    private void bootstrapConnectedPlayers() {
        VoicechatServerApi api = voicechat;
        if (api == null) {
            return;
        }
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
            if (connection != null && connection.isConnected()) {
                plugin.getVoiceEndpointRegistry().connect(id(), player);
            }
        });
    }

    private UUID receiverId(VoicechatConnection connection) {
        return connection == null ? null : connection.getPlayer().getUuid();
    }

    private String playerName(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player == null ? playerId.toString() : player.getName();
    }

    private void runMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
