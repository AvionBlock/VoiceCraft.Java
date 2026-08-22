package team.avion.fabric.adapters.simplevoicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.*;
import de.maxhenkel.voicechat.api.packets.*;
import net.minecraft.server.network.ServerPlayerEntity;
import team.avion.adapter.*;
import team.avion.fabric.VoiceCraftJava;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fabric entrypoint for the official Simple Voice Chat server API. */
public final class FabricSimpleVoiceChatBridge implements VoicechatPlugin, VoiceCraftAudioSink {
    private static final String ID = "simple-voice-chat";
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean sinkRegistered = new AtomicBoolean();
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
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
        if (enabled(plugin)) plugin.getVoiceEndpointRegistry().disconnect(ID, event.getPlayerUuid());
    }

    private void microphone(MicrophonePacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || event.isCancelled() || injecting.get() || event.getSenderConnection() == null) return;
        UUID playerId = event.getSenderConnection().getPlayer().getUuid();
        if (!plugin.getVoiceEndpointRegistry().isPrimary(playerId, ID)) {
            event.cancel();
            return;
        }
        Optional<ServerPlayerEntity> player = player(plugin, playerId);
        OptionalInt entity = player.isEmpty() ? OptionalInt.empty()
                : plugin.getSessionManager().getBoundEntityId(player.get().getName().getString());
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (entity.isEmpty() || opus == null || opus.length == 0) {
            event.cancel();
            return;
        }
        long seq = sequence.incrementAndGet();
        VoiceAudioSource source = VoiceAudioSource.player(entity.getAsInt(), playerId,
                plugin.getConfig().getInt("config.voice.proximity-distance", 30));
        plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, source,
                VoiceAudioAudience.PROXIMITY, seq, (int) (seq & 0xFFFF), 0F, opus));
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
                    return id.isPresent() ? Optional.of(VoiceAudioSource.player(id.getAsInt(), p.getUuid(), packet.getDistance()))
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
        String world = player(plugin, receiver).map(p -> p.getEntityWorld().getRegistryKey().getValue().toString()).orElse("");
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
        if (!enabled(plugin) || api == null) return;
        injecting.set(true);
        try {
            for (ServerPlayerEntity player : plugin.getServer().getPlayerManager().getPlayerList()) {
                UUID id = player.getUuid();
                if (!plugin.getVoiceEndpointRegistry().isPrimary(id, ID) || !frame.audience().allows(id)
                        || frame.source().playerIdentity().map(id::equals).orElse(false)) continue;
                VoicechatConnection connection = api.getConnectionOf(id);
                if (connection == null || !connection.isConnected() || connection.isDisabled()) continue;
                send(connection, frame);
            }
        } finally {
            injecting.remove();
        }
    }

    @Override public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) { }

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
        return plugin != null && plugin.getConfig().getBoolean("config.adapters.simple-voice-chat.enabled", false);
    }
    private static Optional<ServerPlayerEntity> player(VoiceCraftJava plugin, UUID id) {
        return plugin == null || plugin.getServer() == null || id == null ? Optional.empty()
                : Optional.ofNullable(plugin.getServer().getPlayerManager().getPlayer(id));
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
        }
    }
}
