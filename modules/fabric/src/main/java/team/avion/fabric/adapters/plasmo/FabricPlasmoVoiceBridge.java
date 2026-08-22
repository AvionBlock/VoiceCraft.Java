package team.avion.fabric.adapters.plasmo;

import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.voice.api.addon.*;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.*;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.connection.*;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;
import team.avion.adapter.*;
import team.avion.fabric.VoiceCraftJava;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native Plasmo Voice add-on used by the Fabric server mod. */
@Addon(id = "voicecraft-java", name = "VoiceCraft.Java Bridge", version = "2.0.0", authors = {"lil-jon-crunk"})
public final class FabricPlasmoVoiceBridge implements AddonInitializer, VoiceCraftAudioSink {
    private static final String ID = "plasmo";
    private final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);
    private final Map<UUID, ServerAudioSource<?>> nativeSources = new ConcurrentHashMap<>();

    @InjectPlasmoVoice private PlasmoVoiceServer voiceServer;

    @Override
    public void onAddonInitialize() {
        voiceServer.getEventBus().register(this, UdpClientConnectedEvent.class, EventPriority.HIGHEST, this::connected);
        voiceServer.getEventBus().register(this, UdpClientDisconnectedEvent.class, EventPriority.HIGHEST, this::disconnected);
        voiceServer.getEventBus().register(this, UdpPacketReceivedEvent.class, EventPriority.HIGHEST, this::udpPacket);
        voiceServer.getEventBus().register(this, ServerSourceAudioPacketEvent.class, EventPriority.HIGHEST, this::sourcePacket);
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (enabled(plugin)) plugin.getSessionManager().registerAudioSink(this);
    }

    @Override
    public void onAddonShutdown() {
        if (voiceServer != null) voiceServer.getEventBus().unregister(this);
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (plugin != null && plugin.getSessionManager() != null) plugin.getSessionManager().unregisterAudioSink(this);
        nativeSources.clear();
    }

    private void connected(UdpClientConnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin)) return;
        UUID id = event.getConnection().getPlayer().getInstance().getUuid();
        runServer(plugin, () -> Optional.ofNullable(plugin.getServer().getPlayerManager().getPlayer(id))
                .ifPresent(p -> plugin.getVoiceEndpointRegistry().connect(ID, p)));
    }

    private void disconnected(UdpClientDisconnectedEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (enabled(plugin)) plugin.getVoiceEndpointRegistry().disconnect(ID,
                event.getConnection().getPlayer().getInstance().getUuid());
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
        VoiceAudioSource source = VoiceAudioSource.player(entity.getAsInt(), playerId, packet.getDistance());
        plugin.getSessionManager().sendAudioFrame(new VoiceAudioFrame(UUID.randomUUID(), ID, source,
                VoiceAudioAudience.PROXIMITY, packet.getSequenceNumber(),
                (int) (packet.getSequenceNumber() & 0xFFFF), 0F, packet.getData()));
        event.setCancelled(true);
    }

    private void sourcePacket(ServerSourceAudioPacketEvent event) {
        VoiceCraftJava plugin = VoiceCraftJava.getInstance();
        if (!enabled(plugin) || event.isCancelled() || injecting.get()) return;
        byte[] opus = event.getPacket().getData();
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
        injecting.set(true);
        try {
            if (source instanceof ServerProximitySource<?> proximity) {
                proximity.sendAudioFrame(frame.opusFrame(), frame.sequenceNumber(),
                        (short) Math.min(Short.MAX_VALUE, Math.round(frame.source().distance())));
            } else if (source instanceof BaseServerDirectSource direct) {
                direct.sendAudioFrame(frame.opusFrame(), frame.sequenceNumber());
            }
        } finally {
            injecting.remove();
        }
    }

    @Override public void receiveEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) { }

    private ServerAudioSource<?> createSource(VoiceAudioSource source, VoiceAudioAudience audience) {
        ServerSourceLine line = voiceServer.getSourceLineManager().getLineByName("proximity").orElse(null);
        if (line == null) return null;
        return switch (source.type()) {
            case PLAYER -> voiceServer.getPlayerManager().getPlayerById(source.playerId())
                    .map(p -> line.createPlayerSource(p, source.stereo())).orElse(null);
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

    private static boolean enabled(VoiceCraftJava plugin) {
        return plugin != null && plugin.getConfig().getBoolean("config.adapters.plasmo.enabled", false);
    }
    private static void runServer(VoiceCraftJava plugin, Runnable task) {
        if (plugin.getServer() != null) plugin.getServer().execute(task);
    }
}
