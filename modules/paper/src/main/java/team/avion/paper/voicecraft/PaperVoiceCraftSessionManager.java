package team.avion.paper.voicecraft;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;

import team.avion.adapter.VoiceCraftAudioBridge;
import team.avion.adapter.VoiceCraftAudioSink;
import team.avion.adapter.VoiceAudioAudience;
import team.avion.adapter.VoiceAudioDebugMetrics;
import team.avion.adapter.VoiceAudioFrame;
import team.avion.adapter.VoiceAudioReturnOrderer;
import team.avion.adapter.VoiceAudioSource;
import team.avion.adapter.VoiceAudioSourceType;
import team.avion.adapter.VoiceCraftEntityIdentity;
import team.avion.adapter.VoiceCraftIdentityListener;
import team.avion.adapter.VoiceCraftPlayerState;
import team.avion.adapter.VoiceCraftPlayerStateListener;
import team.avion.paper.VoiceCraftJava;
import team.avion.proxy.ProxyPlayerSnapshot;
import team.avion.protocol.McApiTcpClient;
import team.avion.protocol.McPackets.AcceptResponsePacket;
import team.avion.protocol.McPackets.ClearEffectsRequestPacket;
import team.avion.protocol.McPackets.CreateEntityRequestPacket;
import team.avion.protocol.McPackets.CreateEntityResponsePacket;
import team.avion.protocol.McPackets.DenyResponsePacket;
import team.avion.protocol.McPackets.DestroyEntityRequestPacket;
import team.avion.protocol.McPackets.EntityAudioRequestPacket;
import team.avion.protocol.McPackets.EventRequestPacket;
import team.avion.protocol.McPackets.McApiPacket;
import team.avion.protocol.McPackets.OnEntityAudioDataReceivedPacket;
import team.avion.protocol.McPackets.OnEntityDestroyedPacket;
import team.avion.protocol.McPackets.OnEntityDeafenUpdatedPacket;
import team.avion.protocol.McPackets.OnEntityMuteUpdatedPacket;
import team.avion.protocol.McPackets.OnEntityServerDeafenUpdatedPacket;
import team.avion.protocol.McPackets.OnEntityServerMuteUpdatedPacket;
import team.avion.protocol.McPackets.OnNetworkEntityCreatedPacket;
import team.avion.protocol.McPackets.PingRequestPacket;
import team.avion.protocol.McPackets.ProximityEffect;
import team.avion.protocol.McPackets.SetEntityDeafenRequestPacket;
import team.avion.protocol.McPackets.SetEffectRequestPacket;
import team.avion.protocol.McPackets.SetEntityDescriptionRequestPacket;
import team.avion.protocol.McPackets.SetEntityEffectBitmaskRequestPacket;
import team.avion.protocol.McPackets.SetEntityMuteRequestPacket;
import team.avion.protocol.McPackets.SetEntityNameRequestPacket;
import team.avion.protocol.McPackets.SetEntityPositionRequestPacket;
import team.avion.protocol.McPackets.SetEntityRotationRequestPacket;
import team.avion.protocol.McPackets.SetEntityWorldIdRequestPacket;
import team.avion.protocol.McProtocolEnums.CreateEntityResponseCode;
import team.avion.protocol.McProtocolTypes.Vector2;
import team.avion.protocol.McProtocolTypes.Vector3;

/**
 * Paper-side VoiceCraft session manager and audio bridge implementation.
 *
 * <p>This class owns the TCP session to the VoiceCraft backend, keeps Minecraft players bound to
 * VoiceCraft entity ids, and fans audio frames out to protocol adapters.</p>
 */
public final class PaperVoiceCraftSessionManager implements VoiceCraftAudioBridge {
    private static final int EFFECT_BITMASK_PROXIMITY = 1;

    private final VoiceCraftJava plugin;
    private final McApiTcpClient client;
    private final SecureRandom random = new SecureRandom();

    private final Map<Integer, PendingEntity> unboundEntitiesByKey = new HashMap<>();
    private final Map<Integer, PendingEntity> unboundEntitiesById = new HashMap<>();
    private final Map<String, BoundEntity> boundEntitiesByPlayerName = new ConcurrentHashMap<>();
    private final Map<Integer, VoiceCraftEntityIdentity> entityIdentitiesById = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceCraftPlayerState> playerVoiceStatesById = new HashMap<>();
    private final Map<Integer, NativeVoiceState> nativeVoiceStatesByEntityId = new HashMap<>();
    private final Map<UUID, Integer> virtualEntitiesByStreamId = new HashMap<>();

    private String host = "";
    private int port;
    private String loginKey = "";
    private int proximityDistance = 30;
    private boolean proximityToggle = true;
    private boolean voiceEffects = true;
    private String lastCreateEntityFailure = "";
    private final List<VoiceCraftAudioSink> audioSinks = new CopyOnWriteArrayList<>();
    private final List<VoiceCraftIdentityListener> identityListeners = new CopyOnWriteArrayList<>();
    private final List<VoiceCraftPlayerStateListener> playerStateListeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<PendingAudioFrame> audioQueue = new LinkedBlockingQueue<>();
    private final Map<AudioFrameKey, ConcurrentLinkedQueue<RoutedFrameMetadata>> routedFrameMetadata =
            new ConcurrentHashMap<>();
    private final VoiceAudioDebugMetrics audioDebug;
    private final VoiceAudioReturnOrderer returnOrderer = new VoiceAudioReturnOrderer();
    private final AtomicBoolean audioWorkerRunning = new AtomicBoolean(false);
    private Thread audioWorkerThread;

    public PaperVoiceCraftSessionManager(VoiceCraftJava plugin) {
        this.plugin = plugin;
        this.client = new McApiTcpClient(plugin.Logger);
        this.audioDebug = new VoiceAudioDebugMetrics(
                () -> plugin.getConfig().getBoolean("config.debug", false), plugin.Logger::info);
    }

    /**
     * Updates the backend connection and voice effect settings used by the next connection.
     *
     * @param host VoiceCraft TCP host
     * @param port VoiceCraft TCP port
     * @param loginKey VoiceCraft login key
     * @param proximityDistance proximity effect distance
     * @param proximityToggle whether proximity attenuation is enabled
     * @param voiceEffects whether voice effects are enabled
     */
    public synchronized void configure(String host, int port, String loginKey, int proximityDistance,
            boolean proximityToggle, boolean voiceEffects) {
        this.host = host == null ? "" : host;
        this.port = port;
        this.loginKey = loginKey == null ? "" : loginKey;
        this.proximityDistance = proximityDistance;
        this.proximityToggle = proximityToggle;
        this.voiceEffects = voiceEffects;
    }

    /**
     * Opens the VoiceCraft TCP session and starts the audio worker.
     *
     * @return {@code true} when the backend connection is active
     */
    public synchronized boolean connect() {
        stopAudioWorker();
        if (host.isBlank() || loginKey.isBlank() || port <= 0) {
            return false;
        }

        clearSessionState();
        if (!client.connect(host, port, loginKey)) {
            return false;
        }

        applyGlobalSettings();
        processPackets(client.exchange(List.of(new PingRequestPacket())));
        if (client.isConnected()) {
            startAudioWorker();
        }
        return client.isConnected();
    }

    /**
     * Logs out from the VoiceCraft backend and clears local entity state.
     */
    public synchronized void disconnect() {
        stopAudioWorker();
        destroyManagedEntities();
        client.logout();
        clearSessionState();
    }

    /**
     * Closes the VoiceCraft backend client and clears local entity state.
     */
    public synchronized void close() {
        stopAudioWorker();
        client.close();
        clearSessionState();
    }

    public synchronized boolean isConnected() {
        return client.isConnected();
    }

    public synchronized String getSessionToken() {
        return client.getSessionToken();
    }

    public OptionalInt getBoundEntityId(String playerName) {
        BoundEntity boundEntity = boundEntitiesByPlayerName.get(playerName);
        return boundEntity == null ? OptionalInt.empty() : OptionalInt.of(boundEntity.entityId());
    }

    public synchronized String getLastCreateEntityFailure() {
        return lastCreateEntityFailure;
    }

    /**
     * Creates and initializes a VoiceCraft entity for a Minecraft player.
     *
     * @param player Bukkit player to bind
     * @return created or existing VoiceCraft entity id
     */
    public synchronized OptionalInt createEntityForPlayer(Player player) {
        lastCreateEntityFailure = "";

        BoundEntity existing = boundEntitiesByPlayerName.get(player.getName());
        if (existing != null) {
            return OptionalInt.of(existing.entityId());
        }
        if (!client.isConnected()) {
            lastCreateEntityFailure = "VoiceCraft TCP client is not connected";
            return OptionalInt.empty();
        }

        String requestId = java.util.UUID.randomUUID().toString();
        CreateEntityRequestPacket request = new CreateEntityRequestPacket();
        request.RequestId = requestId;
        request.WorldId = getDimensionId(player);
        request.Name = player.getName();
        request.Muted = false;
        request.Deafened = false;
        request.TalkBitmask = 0xFFFF;
        request.ListenBitmask = 0xFFFF;
        request.EffectBitmask = getCurrentEffectBitmask();
        request.Position = new Vector3((float) player.getLocation().getX(), (float) player.getLocation().getY(),
                (float) player.getLocation().getZ());
        request.Rotation = new Vector2(player.getLocation().getPitch(), player.getLocation().getYaw());

        boolean sawCreateResponse = false;
        List<String> unexpectedResponses = new ArrayList<>();
        for (McApiPacket response : client.exchange(List.of(request))) {
            if (response instanceof CreateEntityResponsePacket created) {
                if (!requestId.equals(created.RequestId)) {
                    unexpectedResponses.add("CREATE_ENTITY_RESPONSE with mismatched request id " + created.RequestId);
                    processPackets(List.of(response));
                    continue;
                }

                sawCreateResponse = true;
                if (created.ResponseCode == CreateEntityResponseCode.OK) {
                    initializeCreatedEntity(created.Id, player);
                    boundEntitiesByPlayerName.put(player.getName(), new BoundEntity(player.getName(), created.Id,
                            player.getUniqueId().toString(), player.getUniqueId().toString(), "java",
                            getDimensionId(player), getCurrentEffectBitmask(), false));
                    plugin.getPlayerBinds().put(player.getName(), true);
                    return OptionalInt.of(created.Id);
                }

                lastCreateEntityFailure = "VoiceCraft returned " + created.ResponseCode + " for request " + requestId;
                continue;
            }
            unexpectedResponses.add(response.packetType().name());
            processPackets(List.of(response));
        }

        if (lastCreateEntityFailure.isBlank()) {
            if (!client.isConnected()) {
                lastCreateEntityFailure = "VoiceCraft TCP client disconnected while creating entity";
            } else if (!sawCreateResponse) {
                lastCreateEntityFailure = unexpectedResponses.isEmpty()
                        ? "VoiceCraft returned no packets for create entity request " + requestId
                        : "VoiceCraft returned no matching create response for request " + requestId
                                + "; received " + String.join(", ", unexpectedResponses);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Destroys the VoiceCraft entity bound to a player name.
     *
     * @param playerName Minecraft player name
     */
    public synchronized void destroyEntityForPlayer(String playerName) {
        BoundEntity boundEntity = boundEntitiesByPlayerName.remove(playerName);
        if (boundEntity == null) {
            plugin.getPlayerBinds().remove(playerName);
            return;
        }

        unregisterEntityIdentity(boundEntity.entityId());
        plugin.getPlayerBinds().remove(playerName);
        if (!client.isConnected()) {
            return;
        }

        DestroyEntityRequestPacket packet = new DestroyEntityRequestPacket();
        packet.RequestId = java.util.UUID.randomUUID().toString();
        packet.Id = boundEntity.entityId();
        processPackets(client.exchange(List.of(packet)));
    }

    @Override
    public void sendAudioFrame(VoiceAudioFrame frame) {
        if (!audioWorkerRunning.get() || frame.opusFrame().length == 0) {
            return;
        }
        AudioFrameKey key = new AudioFrameKey(frame.source().voiceCraftEntityId(), frame.timestamp());
        returnOrderer.queued(frame);
        routedFrameMetadata.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>())
                .offer(new RoutedFrameMetadata(frame, System.nanoTime()));
        audioDebug.recordFrame("vc-in/from-" + frame.originAdapter(), frame.sequenceNumber(),
                frame.opusFrame().length);
        sendEntityAudio(frame.source().voiceCraftEntityId(), frame.timestamp(), frame.loudness(), frame.opusFrame());
    }

    /** Creates a backend entity for an add-on source so protocol 1.7 can route it like a positioned entity. */
    public synchronized Optional<VoiceAudioSource> getOrCreateVirtualEntity(VoiceAudioSource source,
            String originAdapter) {
        if (source.voiceCraftEntityId() > 0) {
            return Optional.of(source);
        }
        Integer existing = virtualEntitiesByStreamId.get(source.streamId());
        if (existing != null) {
            return Optional.of(source.withVoiceCraftEntityId(existing));
        }
        if (!client.isConnected()) {
            return Optional.empty();
        }

        String requestId = UUID.randomUUID().toString();
        CreateEntityRequestPacket request = new CreateEntityRequestPacket();
        request.RequestId = requestId;
        for (McApiPacket response : client.exchange(List.of(request))) {
            if (!(response instanceof CreateEntityResponsePacket created) || !requestId.equals(created.RequestId)) {
                processPackets(List.of(response));
                continue;
            }
            if (created.ResponseCode != CreateEntityResponseCode.OK) {
                return Optional.empty();
            }
            int entityId = created.Id;
            List<McApiPacket> packets = new ArrayList<>();
            packets.add(stringPacket(new SetEntityNameRequestPacket(), entityId,
                    source.category().isBlank() ? "Voice add-on" : source.category()));
            packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), entityId,
                    "Bridged from " + originAdapter));
            packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), entityId, source.worldId()));
            packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), entityId,
                    source.type() == VoiceAudioSourceType.DIRECT || source.type() == VoiceAudioSourceType.BROADCAST
                            ? 0 : getCurrentEffectBitmask()));
            packets.add(vector3Packet(new SetEntityPositionRequestPacket(), entityId,
                    new Vector3((float) source.x(), (float) source.y(), (float) source.z())));
            processPackets(client.exchange(packets));
            virtualEntitiesByStreamId.put(source.streamId(), entityId);
            registerEntityIdentity(new VoiceCraftEntityIdentity(entityId, source.streamId(),
                    source.category().isBlank() ? "Voice add-on" : source.category(), originAdapter));
            return Optional.of(source.withVoiceCraftEntityId(entityId));
        }
        return Optional.empty();
    }

    /** Destroys only entities created for native transports, never an externally bound VoiceCraft client. */
    public synchronized void destroyManagedEntityForPlayer(String playerName) {
        BoundEntity boundEntity = boundEntitiesByPlayerName.get(playerName);
        if (boundEntity == null || boundEntity.networkEntity()) {
            return;
        }
        destroyEntityForPlayer(playerName);
    }

    @Override
    public void sendEntityAudio(int entityId, int timestamp, float loudness, byte[] opusFrame) {
        if (!audioWorkerRunning.get() || opusFrame == null || opusFrame.length == 0) {
            return;
        }

        audioQueue.offer(new PendingAudioFrame(entityId, timestamp, loudness, opusFrame.clone()));
    }

    @Override
    public void sendEntityAudioEnd(int entityId, long sequenceNumber) {
        returnOrderer.flushEntity(entityId).forEach(this::deliverAudioFrame);
        for (VoiceCraftAudioSink sink : audioSinks) {
            sink.receiveEntityAudioEnd(entityId, sequenceNumber);
        }
        audioDebug.flushAll();
    }

    @Override
    public void registerAudioSink(VoiceCraftAudioSink sink) {
        if (sink != null && !audioSinks.contains(sink)) {
            audioSinks.add(sink);
        }
    }

    @Override
    public void unregisterAudioSink(VoiceCraftAudioSink sink) {
        audioSinks.remove(sink);
    }

    @Override
    public void registerIdentityListener(VoiceCraftIdentityListener listener) {
        if (listener != null && !identityListeners.contains(listener)) {
            identityListeners.add(listener);
        }
    }

    @Override
    public void unregisterIdentityListener(VoiceCraftIdentityListener listener) {
        identityListeners.remove(listener);
    }

    @Override
    public void registerPlayerStateListener(VoiceCraftPlayerStateListener listener) {
        if (listener != null && !playerStateListeners.contains(listener)) {
            playerStateListeners.add(listener);
        }
    }

    @Override
    public void unregisterPlayerStateListener(VoiceCraftPlayerStateListener listener) {
        playerStateListeners.remove(listener);
    }

    @Override
    public synchronized void updatePlayerVoiceState(VoiceCraftPlayerState state) {
        if (state == null || state.equals(playerVoiceStatesById.put(state.playerId(), state))) {
            return;
        }
        for (VoiceCraftPlayerStateListener listener : playerStateListeners) {
            listener.playerVoiceStateUpdated(state);
        }
    }

    @Override
    public synchronized Optional<VoiceCraftPlayerState> playerVoiceState(UUID playerId) {
        return Optional.ofNullable(playerVoiceStatesById.get(playerId));
    }

    @Override
    public synchronized void registerEntityIdentity(VoiceCraftEntityIdentity identity) {
        if (identity != null) {
            VoiceCraftEntityIdentity previous = entityIdentitiesById.put(identity.entityId(), identity);
            NativeVoiceState nativeState = nativeVoiceStatesByEntityId.get(identity.entityId());
            if (nativeState != null && "voicecraft".equals(identity.adapterId())) {
                updatePlayerVoiceState(nativeState.toPlayerState(identity.playerId()));
            }
            if (!identity.equals(previous)) {
                notifyIdentityRegistered(identity);
            }
        }
    }

    @Override
    public synchronized void unregisterEntityIdentity(int entityId) {
        VoiceCraftEntityIdentity removed = entityIdentitiesById.remove(entityId);
        nativeVoiceStatesByEntityId.remove(entityId);
        if (removed != null) {
            boolean playerStillRegistered = entityIdentitiesById.values().stream()
                    .anyMatch(identity -> identity.playerId().equals(removed.playerId()));
            if (!playerStillRegistered) {
                playerVoiceStatesById.remove(removed.playerId());
            }
            notifyIdentityUnregistered(removed);
        }
    }

    @Override
    public synchronized java.util.Optional<VoiceCraftEntityIdentity> entityIdentity(int entityId) {
        return java.util.Optional.ofNullable(entityIdentitiesById.get(entityId));
    }

    @Override
    public synchronized Collection<VoiceCraftEntityIdentity> entityIdentities() {
        return List.copyOf(entityIdentitiesById.values());
    }

    public synchronized boolean updateSettings(int proximityDistance, boolean proximityToggle, boolean voiceEffects) {
        this.proximityDistance = proximityDistance;
        this.proximityToggle = proximityToggle;
        this.voiceEffects = voiceEffects;

        if (!client.isConnected()) {
            return false;
        }

        applyGlobalSettings();
        return client.isConnected();
    }

    public synchronized boolean tick(Collection<ProxyPlayerSnapshot> snapshots) {
        if (!client.isConnected()) {
            return false;
        }

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(new PingRequestPacket());

        for (ProxyPlayerSnapshot snapshot : snapshots) {
            BoundEntity boundEntity = boundEntitiesByPlayerName.get(snapshot.playerName());
            if (boundEntity == null) {
                continue;
            }

            if (!Objects.equals(boundEntity.worldId(), snapshot.dimensionId())) {
                packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), boundEntity.entityId(),
                        snapshot.dimensionId()));
                boundEntity.worldId = snapshot.dimensionId();
            }

            int currentEffectBitmask = getCurrentEffectBitmask();
            if (boundEntity.effectBitmask() != currentEffectBitmask) {
                packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), boundEntity.entityId(),
                        currentEffectBitmask));
                boundEntity.effectBitmask = currentEffectBitmask;
            }

            packets.add(vector3Packet(new SetEntityPositionRequestPacket(), boundEntity.entityId(),
                    new Vector3((float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z())));
            packets.add(vector2Packet(new SetEntityRotationRequestPacket(), boundEntity.entityId(),
                    new Vector2(0.0f, (float) snapshot.rotation())));
        }

        processPackets(client.exchange(packets));
        return client.isConnected();
    }

    public synchronized boolean bindPlayer(int bindingKey, Player player) {
        if (!client.isConnected()) {
            return false;
        }
        if (plugin.getPlayerBinds().getOrDefault(player.getName(), false)) {
            return true;
        }

        PendingEntity pendingEntity = unboundEntitiesByKey.get(bindingKey);
        if (pendingEntity == null) {
            processPackets(client.exchange(List.of(new PingRequestPacket())));
            pendingEntity = unboundEntitiesByKey.get(bindingKey);
        }
        if (pendingEntity == null) {
            return false;
        }

        return bindEntityToPlayer(pendingEntity, player);
    }

    public synchronized boolean bindFakePlayer(int bindingKey, String name) {
        if (!client.isConnected()) {
            return false;
        }
        if (plugin.getPlayerBinds().getOrDefault(name, false)) {
            return true;
        }

        PendingEntity pendingEntity = unboundEntitiesByKey.get(bindingKey);
        if (pendingEntity == null) {
            processPackets(client.exchange(List.of(new PingRequestPacket())));
            pendingEntity = unboundEntitiesByKey.get(bindingKey);
        }
        if (pendingEntity == null) {
            return false;
        }

        return bindEntityToFakePlayer(pendingEntity, name);
    }

    public synchronized boolean unbindPlayer(Player player) {
        BoundEntity boundEntity = boundEntitiesByPlayerName.remove(player.getName());
        if (boundEntity == null) {
            plugin.getPlayerBinds().remove(player.getName());
            return false;
        }

        unregisterEntityIdentity(boundEntity.entityId());
        plugin.getPlayerBinds().remove(player.getName());
        if (!client.isConnected()) {
            return false;
        }

        DestroyEntityRequestPacket packet = new DestroyEntityRequestPacket();
        packet.RequestId = java.util.UUID.randomUUID().toString();
        packet.Id = boundEntity.entityId();
        processPackets(client.exchange(List.of(packet)));
        return client.isConnected();
    }

    private void applyGlobalSettings() {
        List<McApiPacket> packets = new ArrayList<>();
        packets.add(new ClearEffectsRequestPacket());

        if (proximityToggle) {
            ProximityEffect effect = new ProximityEffect();
            effect.MinRange = 0.0f;
            effect.MaxRange = proximityDistance;
            effect.WetDry = 1.0f;

            SetEffectRequestPacket packet = new SetEffectRequestPacket();
            packet.Bitmask = EFFECT_BITMASK_PROXIMITY;
            packet.Effect = effect;
            packets.add(packet);
        }

        for (BoundEntity boundEntity : boundEntitiesByPlayerName.values()) {
            packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), boundEntity.entityId(), getCurrentEffectBitmask()));
            boundEntity.effectBitmask = getCurrentEffectBitmask();
        }

        processPackets(client.exchange(packets));
    }

    private void processPackets(List<McApiPacket> packets) {
        for (McApiPacket packet : packets) {
            if (packet instanceof EventRequestPacket eventRequest) {
                if (eventRequest.Event != null) {
                    processPackets(List.of(eventRequest.Event));
                }
                continue;
            }
            if (packet instanceof AcceptResponsePacket || packet instanceof DenyResponsePacket) {
                continue;
            }
            if (packet instanceof OnNetworkEntityCreatedPacket createdPacket) {
                handleNetworkEntityCreated(createdPacket);
                continue;
            }
            if (packet instanceof OnEntityDestroyedPacket destroyedPacket) {
                handleEntityDestroyed(destroyedPacket.Id);
                continue;
            }
            if (packet instanceof OnEntityAudioDataReceivedPacket audioPacket) {
                handleEntityAudio(audioPacket);
                continue;
            }
            if (packet instanceof OnEntityMuteUpdatedPacket mutePacket) {
                updateNativeVoiceState(mutePacket.Id, state -> state.withMuted(mutePacket.Value));
                continue;
            }
            if (packet instanceof OnEntityDeafenUpdatedPacket deafenPacket) {
                updateNativeVoiceState(deafenPacket.Id, state -> state.withDeafened(deafenPacket.Value));
                continue;
            }
            if (packet instanceof OnEntityServerMuteUpdatedPacket mutePacket) {
                updateNativeVoiceState(mutePacket.Id, state -> state.withServerMuted(mutePacket.Value));
                continue;
            }
            if (packet instanceof OnEntityServerDeafenUpdatedPacket deafenPacket) {
                updateNativeVoiceState(deafenPacket.Id, state -> state.withServerDeafened(deafenPacket.Value));
            }
        }
    }

    private synchronized void updateNativeVoiceState(int entityId,
            java.util.function.UnaryOperator<NativeVoiceState> updater) {
        NativeVoiceState updated = updater.apply(nativeVoiceStatesByEntityId.getOrDefault(entityId,
                NativeVoiceState.DEFAULT));
        nativeVoiceStatesByEntityId.put(entityId, updated);
        VoiceCraftEntityIdentity identity = entityIdentitiesById.get(entityId);
        if (identity != null && "voicecraft".equals(identity.adapterId())) {
            updatePlayerVoiceState(updated.toPlayerState(identity.playerId()));
        }
    }

    private void startAudioWorker() {
        if (!audioWorkerRunning.compareAndSet(false, true)) {
            return;
        }
        audioWorkerThread = new Thread(this::runAudioWorker, "VoiceCraft.Java-AudioBridge");
        audioWorkerThread.setDaemon(true);
        audioWorkerThread.start();
    }

    private void stopAudioWorker() {
        if (!audioWorkerRunning.getAndSet(false)) {
            audioQueue.clear();
            routedFrameMetadata.clear();
            returnOrderer.clear();
            return;
        }
        if (audioWorkerThread != null) {
            audioWorkerThread.interrupt();
            audioWorkerThread = null;
        }
        audioQueue.clear();
        routedFrameMetadata.clear();
        returnOrderer.clear();
    }

    private void runAudioWorker() {
        while (audioWorkerRunning.get()) {
            try {
                PendingAudioFrame first = audioQueue.poll(20, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }

                List<PendingAudioFrame> frames = new ArrayList<>();
                frames.add(first);
                audioQueue.drainTo(frames, 63);
                sendAudioBatch(frames);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception exception) {
                plugin.Logger.warnLocalized("session.audio-worker-failed", "$error", exception.getMessage());
            }
        }
    }

    private void sendAudioBatch(List<PendingAudioFrame> frames) {
        if (!client.isConnected() || frames.isEmpty()) {
            return;
        }

        List<EntityAudioRequestPacket> packets = new ArrayList<>(frames.size());
        for (PendingAudioFrame frame : frames) {
            EntityAudioRequestPacket packet = new EntityAudioRequestPacket();
            packet.Id = frame.entityId();
            packet.Timestamp = frame.timestamp();
            packet.FrameLoudness = frame.loudness();
            packet.Length = frame.opusFrame().length;
            packet.Data = frame.opusFrame();
            packets.add(packet);
        }
        long startedNanos = System.nanoTime();
        List<McApiPacket> responses = client.exchange(packets);
        audioDebug.recordBatch("vc-exchange", frames.size(), audioQueue.size(),
                System.nanoTime() - startedNanos, countAudioResponses(responses));
        synchronized (this) {
            processPackets(responses);
        }
    }

    private void handleEntityAudio(OnEntityAudioDataReceivedPacket packet) {
        if (packet.Data == null || packet.Data.length == 0) {
            return;
        }
        AudioFrameKey key = new AudioFrameKey(packet.Id, packet.Timestamp);
        ConcurrentLinkedQueue<RoutedFrameMetadata> queued = routedFrameMetadata.get(key);
        RoutedFrameMetadata metadata = queued == null ? null : queued.poll();
        VoiceAudioFrame frame = metadata == null ? null : metadata.frame();
        if (queued != null && queued.isEmpty()) {
            routedFrameMetadata.remove(key, queued);
        }
        if (frame == null) {
            audioDebug.recordCorrelation("vc-return/unmatched", false, 0L);
            VoiceCraftEntityIdentity identity = entityIdentitiesById.get(packet.Id);
            UUID playerId = identity == null ? null : identity.playerId();
            VoiceAudioSource source = playerId == null
                    ? new VoiceAudioSource(UUID.randomUUID(), VoiceAudioSourceType.ENTITY, packet.Id, null, null,
                            "", 0D, 0D, 0D, proximityDistance, "", false)
                    : VoiceAudioSource.player(packet.Id, playerId, proximityDistance);
            frame = new VoiceAudioFrame(UUID.randomUUID(), "voicecraft", source, VoiceAudioAudience.PROXIMITY,
                    Integer.toUnsignedLong(packet.Timestamp), packet.Timestamp, packet.FrameLoudness, packet.Data);
        } else {
            audioDebug.recordCorrelation("vc-return/to-" + frame.originAdapter(), true,
                    System.nanoTime() - metadata.queuedNanos());
            frame = new VoiceAudioFrame(frame.routeId(), frame.originAdapter(), frame.source(), frame.audience(),
                    frame.sequenceNumber(), packet.Timestamp, packet.FrameLoudness, packet.Data);
        }
        audioDebug.recordFrame("vc-return/to-" + frame.originAdapter(), frame.sequenceNumber(), packet.Data.length);
        if (metadata == null) {
            deliverAudioFrame(frame);
            return;
        }
        returnOrderer.returned(frame).forEach(this::deliverAudioFrame);
    }

    private void deliverAudioFrame(VoiceAudioFrame frame) {
        audioDebug.recordFrame("vc-ordered/to-" + frame.originAdapter(), frame.sequenceNumber(),
                frame.opusFrame().length);
        for (VoiceCraftAudioSink sink : audioSinks) {
            sink.receiveAudioFrame(frame);
        }
    }

    private static int countAudioResponses(List<McApiPacket> packets) {
        int count = 0;
        for (McApiPacket packet : packets) {
            if (packet instanceof OnEntityAudioDataReceivedPacket) {
                count++;
            } else if (packet instanceof EventRequestPacket event && event.Event != null) {
                count += countAudioResponses(List.of(event.Event));
            }
        }
        return count;
    }

    private void handleNetworkEntityCreated(OnNetworkEntityCreatedPacket packet) {
        PendingEntity pendingEntity = new PendingEntity(packet.Id, packet.UserGuid.toString(),
                packet.ServerUserGuid.toString(), packet.Locale);

        assignBindingKey(pendingEntity);
    }

    private void handleEntityDestroyed(int entityId) {
        PendingEntity pendingEntity = unboundEntitiesById.remove(entityId);
        if (pendingEntity != null) {
            unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        }

        BoundEntity removed = null;
        for (BoundEntity boundEntity : boundEntitiesByPlayerName.values()) {
            if (boundEntity.entityId() == entityId) {
                removed = boundEntity;
                break;
            }
        }

        if (removed != null) {
            boundEntitiesByPlayerName.remove(removed.playerName());
            plugin.getPlayerBinds().remove(removed.playerName());
        }
        unregisterEntityIdentity(entityId);
    }

    private boolean bindEntityToPlayer(PendingEntity pendingEntity, Player player) {
        if (plugin.getPlayerBinds().getOrDefault(player.getName(), false)) {
            return true;
        }

        unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        unboundEntitiesById.remove(pendingEntity.entityId());

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), player.getName()));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Bound to player " + player.getName()));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), getDimensionId(player)));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), pendingEntity.entityId(), getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), pendingEntity.entityId(),
                new Vector3((float) player.getLocation().getX(), (float) player.getLocation().getY(),
                        (float) player.getLocation().getZ())));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), pendingEntity.entityId(),
                new Vector2(player.getLocation().getPitch(), player.getLocation().getYaw())));

        processPackets(client.exchange(packets));
        if (!client.isConnected()) {
            return false;
        }

        boundEntitiesByPlayerName.put(player.getName(), new BoundEntity(player.getName(), pendingEntity.entityId(),
                pendingEntity.userGuid(), pendingEntity.serverUserGuid(), pendingEntity.locale(), getDimensionId(player),
                getCurrentEffectBitmask(), true));
        registerEntityIdentity(new VoiceCraftEntityIdentity(pendingEntity.entityId(), player.getUniqueId(),
                player.getName(), "voicecraft"));
        plugin.getPlayerBinds().put(player.getName(), true);
        return true;
    }

    private boolean bindEntityToFakePlayer(PendingEntity pendingEntity, String name) {
        unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        unboundEntitiesById.remove(pendingEntity.entityId());

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), name));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Bound to fake player " + name));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), ""));
        packets.add(boolPacket(new SetEntityMuteRequestPacket(), pendingEntity.entityId(), false));
        packets.add(boolPacket(new SetEntityDeafenRequestPacket(), pendingEntity.entityId(), false));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), pendingEntity.entityId(), getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), pendingEntity.entityId(),
                new Vector3(0.0f, 0.0f, 0.0f)));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), pendingEntity.entityId(),
                new Vector2(0.0f, 0.0f)));

        processPackets(client.exchange(packets));
        if (!client.isConnected()) {
            return false;
        }

        boundEntitiesByPlayerName.put(name, new BoundEntity(name, pendingEntity.entityId(),
                pendingEntity.userGuid(), pendingEntity.serverUserGuid(), pendingEntity.locale(), "",
                getCurrentEffectBitmask(), true));
        plugin.getPlayerBinds().put(name, true);
        return true;
    }

    private void assignBindingKey(PendingEntity pendingEntity) {
        int bindingKey = nextBindingKey();
        pendingEntity.bindingKey = bindingKey;
        unboundEntitiesByKey.put(bindingKey, pendingEntity);
        unboundEntitiesById.put(pendingEntity.entityId(), pendingEntity);

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), "New Client"));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), ""));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Welcome! Your binding key is " + bindingKey));
        processPackets(client.exchange(packets));
    }

    private void initializeCreatedEntity(int entityId, Player player) {
        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), entityId, player.getName()));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), entityId,
                "Native voice client " + player.getName()));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), entityId, getDimensionId(player)));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), entityId, getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), entityId,
                new Vector3((float) player.getLocation().getX(), (float) player.getLocation().getY(),
                        (float) player.getLocation().getZ())));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), entityId,
                new Vector2(player.getLocation().getPitch(), player.getLocation().getYaw())));
        processPackets(client.exchange(packets));
    }

    private int nextBindingKey() {
        int bindingKey = 10000 + random.nextInt(90000);
        while (unboundEntitiesByKey.containsKey(bindingKey)) {
            bindingKey = 10000 + random.nextInt(90000);
        }
        return bindingKey;
    }

    private int getCurrentEffectBitmask() {
        return proximityToggle ? EFFECT_BITMASK_PROXIMITY : 0;
    }

    private String getDimensionId(Player player) {
        String worldName = player.getWorld().getName();
        return switch (worldName) {
            case "world" -> "minecraft:overworld";
            case "world_nether" -> "minecraft:nether";
            case "world_the_end" -> "minecraft:the_end";
            default -> worldName;
        };
    }

    private void clearSessionState() {
        List<VoiceCraftEntityIdentity> identities = List.copyOf(entityIdentitiesById.values());
        unboundEntitiesByKey.clear();
        unboundEntitiesById.clear();
        boundEntitiesByPlayerName.clear();
        entityIdentitiesById.clear();
        playerVoiceStatesById.clear();
        nativeVoiceStatesByEntityId.clear();
        virtualEntitiesByStreamId.clear();
        identities.forEach(this::notifyIdentityUnregistered);
        plugin.getPlayerBinds().clear();
    }

    private void destroyManagedEntities() {
        if (!client.isConnected()) {
            return;
        }

        List<McApiPacket> packets = new ArrayList<>();
        for (BoundEntity boundEntity : boundEntitiesByPlayerName.values()) {
            if (boundEntity.networkEntity()) {
                continue;
            }
            DestroyEntityRequestPacket packet = new DestroyEntityRequestPacket();
            packet.RequestId = java.util.UUID.randomUUID().toString();
            packet.Id = boundEntity.entityId();
            packets.add(packet);
        }
        if (!packets.isEmpty()) {
            processPackets(client.exchange(packets));
        }
    }

    private void notifyIdentityRegistered(VoiceCraftEntityIdentity identity) {
        for (VoiceCraftIdentityListener listener : identityListeners) {
            listener.entityIdentityRegistered(identity);
        }
    }

    private void notifyIdentityUnregistered(VoiceCraftEntityIdentity identity) {
        for (VoiceCraftIdentityListener listener : identityListeners) {
            listener.entityIdentityUnregistered(identity);
        }
    }

    private static SetEntityNameRequestPacket stringPacket(SetEntityNameRequestPacket packet, int entityId, String value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityDescriptionRequestPacket stringPacket(SetEntityDescriptionRequestPacket packet, int entityId,
            String value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityWorldIdRequestPacket stringPacket(SetEntityWorldIdRequestPacket packet, int entityId,
            String value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityEffectBitmaskRequestPacket intPacket(SetEntityEffectBitmaskRequestPacket packet, int entityId,
            int value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityMuteRequestPacket boolPacket(SetEntityMuteRequestPacket packet, int entityId,
            boolean value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityDeafenRequestPacket boolPacket(SetEntityDeafenRequestPacket packet, int entityId,
            boolean value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityPositionRequestPacket vector3Packet(SetEntityPositionRequestPacket packet, int entityId,
            Vector3 value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityRotationRequestPacket vector2Packet(SetEntityRotationRequestPacket packet, int entityId,
            Vector2 value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static final class PendingEntity {
        private final int entityId;
        private final String userGuid;
        private final String serverUserGuid;
        private final String locale;
        private int bindingKey;

        private PendingEntity(int entityId, String userGuid, String serverUserGuid, String locale) {
            this.entityId = entityId;
            this.userGuid = userGuid;
            this.serverUserGuid = serverUserGuid;
            this.locale = locale;
        }

        private int entityId() {
            return entityId;
        }

        private String userGuid() {
            return userGuid;
        }

        private String serverUserGuid() {
            return serverUserGuid;
        }

        private String locale() {
            return locale;
        }

        private int bindingKey() {
            return bindingKey;
        }
    }

    private record PendingAudioFrame(int entityId, int timestamp, float loudness, byte[] opusFrame) {
    }

    private record AudioFrameKey(int entityId, int timestamp) {
    }

    private record RoutedFrameMetadata(VoiceAudioFrame frame, long queuedNanos) {
    }

    private record NativeVoiceState(boolean muted, boolean deafened, boolean serverMuted, boolean serverDeafened) {
        private static final NativeVoiceState DEFAULT = new NativeVoiceState(false, false, false, false);

        private NativeVoiceState withMuted(boolean value) {
            return new NativeVoiceState(value, deafened, serverMuted, serverDeafened);
        }

        private NativeVoiceState withDeafened(boolean value) {
            return new NativeVoiceState(muted, value, serverMuted, serverDeafened);
        }

        private NativeVoiceState withServerMuted(boolean value) {
            return new NativeVoiceState(muted, deafened, value, serverDeafened);
        }

        private NativeVoiceState withServerDeafened(boolean value) {
            return new NativeVoiceState(muted, deafened, serverMuted, value);
        }

        private VoiceCraftPlayerState toPlayerState(UUID playerId) {
            return new VoiceCraftPlayerState(playerId, deafened || serverDeafened, muted || serverMuted);
        }
    }

    private static final class BoundEntity {
        private final String playerName;
        private final int entityId;
        private final String userGuid;
        private final String serverUserGuid;
        private final String locale;
        private String worldId;
        private int effectBitmask;
        private final boolean networkEntity;

        private BoundEntity(String playerName, int entityId, String userGuid, String serverUserGuid, String locale,
                String worldId, int effectBitmask, boolean networkEntity) {
            this.playerName = playerName;
            this.entityId = entityId;
            this.userGuid = userGuid;
            this.serverUserGuid = serverUserGuid;
            this.locale = locale;
            this.worldId = worldId;
            this.effectBitmask = effectBitmask;
            this.networkEntity = networkEntity;
        }

        private String playerName() {
            return playerName;
        }

        private int entityId() {
            return entityId;
        }

        private String userGuid() {
            return userGuid;
        }

        private String serverUserGuid() {
            return serverUserGuid;
        }

        private String locale() {
            return locale;
        }

        private String worldId() {
            return worldId;
        }

        private int effectBitmask() {
            return effectBitmask;
        }

        private boolean networkEntity() {
            return networkEntity;
        }
    }
}
