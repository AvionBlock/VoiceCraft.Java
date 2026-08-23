package team.avion.proxy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import team.avion.common.utils.BaseLogger;
import team.avion.protocol.McApiTcpClient;
import team.avion.protocol.McPackets.AcceptResponsePacket;
import team.avion.protocol.McPackets.ClearEffectsRequestPacket;
import team.avion.protocol.McPackets.DenyResponsePacket;
import team.avion.protocol.McPackets.McApiPacket;
import team.avion.protocol.McPackets.OnEntityDestroyedPacket;
import team.avion.protocol.McPackets.OnNetworkEntityCreatedPacket;
import team.avion.protocol.McPackets.PingRequestPacket;
import team.avion.protocol.McPackets.ProximityEffect;
import team.avion.protocol.McPackets.SetEffectRequestPacket;
import team.avion.protocol.McPackets.SetEntityDescriptionRequestPacket;
import team.avion.protocol.McPackets.SetEntityEffectBitmaskRequestPacket;
import team.avion.protocol.McPackets.SetEntityNameRequestPacket;
import team.avion.protocol.McPackets.SetEntityPositionRequestPacket;
import team.avion.protocol.McPackets.SetEntityPropertyRequestPacket;
import team.avion.protocol.McPackets.SetEntityRotationRequestPacket;
import team.avion.protocol.McPackets.SetEntityWorldIdRequestPacket;
import team.avion.protocol.McProtocolTypes.Vector2;
import team.avion.protocol.McProtocolTypes.Vector3;
import team.avion.protocol.McProtocolEnums.PropertyType;

public final class VoiceCraftProxySessionManager {
    private static final int EFFECT_BITMASK_PROXIMITY = 1;
    private static final String PROXIMITY_ECHO_FACTOR_PROPERTY = "ProximityEchoEffect:Factor";
    private static final String PROXIMITY_MUFFLE_FACTOR_PROPERTY = "ProximityMuffleEffect:Factor";

    private final McApiTcpClient client;
    private final SecureRandom random = new SecureRandom();

    private final Map<Integer, PendingEntity> unboundEntitiesByKey = new HashMap<>();
    private final Map<Integer, PendingEntity> unboundEntitiesById = new HashMap<>();
    private final Map<String, BoundEntity> boundEntitiesByPlayerId = new HashMap<>();
    private final Map<String, ProxyPlayerSnapshot> snapshotsByPlayerId = new HashMap<>();

    private String host = "";
    private int port;
    private String loginKey = "";
    private int proximityDistance = 30;
    private boolean proximityToggle = true;
    private boolean voiceEffects = true;

    public VoiceCraftProxySessionManager(BaseLogger logger) {
        this.client = new McApiTcpClient(logger);
    }

    public synchronized void configure(String host, int port, String loginKey, int proximityDistance,
            boolean proximityToggle, boolean voiceEffects) {
        this.host = host == null ? "" : host;
        this.port = port;
        this.loginKey = loginKey == null ? "" : loginKey;
        this.proximityDistance = proximityDistance;
        this.proximityToggle = proximityToggle;
        this.voiceEffects = voiceEffects;
    }

    public synchronized boolean connect() {
        if (host.isBlank() || loginKey.isBlank() || port <= 0) {
            return false;
        }

        clearEntityState();
        if (!client.connect(host, port, loginKey)) {
            return false;
        }

        applyGlobalSettings();
        processPackets(client.exchange(List.of(new PingRequestPacket())));
        return client.isConnected();
    }

    public synchronized void disconnect() {
        client.logout();
        clearSessionState();
    }

    public synchronized void close() {
        client.close();
        clearSessionState();
    }

    public synchronized boolean isConnected() {
        return client.isConnected();
    }

    public synchronized String getSessionToken() {
        return client.getSessionToken();
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

    public synchronized void updatePlayerSnapshot(ProxyPlayerSnapshot snapshot) {
        snapshotsByPlayerId.put(snapshot.playerId(), snapshot);
    }

    public synchronized void removePlayerSnapshot(String playerId) {
        snapshotsByPlayerId.remove(playerId);
    }

    public synchronized boolean bindPlayer(int bindingKey, String playerId, String playerName) {
        if (!client.isConnected()) {
            return false;
        }
        if (boundEntitiesByPlayerId.containsKey(playerId)) {
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

        ProxyPlayerSnapshot snapshot = snapshotsByPlayerId.getOrDefault(playerId,
                new ProxyPlayerSnapshot(playerId, playerName, "", 0.0, 0.0, 0.0, 0.0, 0.0, false, false));
        return bindEntityToPlayer(pendingEntity, snapshot);
    }

    public synchronized boolean bindFakePlayer(int bindingKey, String playerId, String playerName) {
        return bindPlayer(bindingKey, playerId, playerName);
    }

    public synchronized boolean unbindPlayer(String playerId) {
        BoundEntity boundEntity = boundEntitiesByPlayerId.remove(playerId);
        if (boundEntity == null) {
            return false;
        }

        PendingEntity pendingEntity = new PendingEntity(boundEntity.entityId(), boundEntity.userGuid(),
                boundEntity.serverUserGuid(), boundEntity.locale());
        assignBindingKey(pendingEntity);
        return client.isConnected();
    }

    public synchronized boolean tick() {
        if (!client.isConnected()) {
            return false;
        }

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(new PingRequestPacket());

        for (BoundEntity boundEntity : boundEntitiesByPlayerId.values()) {
            ProxyPlayerSnapshot snapshot = snapshotsByPlayerId.get(boundEntity.playerId());
            if (snapshot == null) {
                continue;
            }

            if (!Objects.equals(boundEntity.playerName(), snapshot.playerName())) {
                packets.add(
                        stringPacket(new SetEntityNameRequestPacket(), boundEntity.entityId(), snapshot.playerName()));
                boundEntity.playerName = snapshot.playerName();
            }

            if (!Objects.equals(boundEntity.worldId(), snapshot.dimensionId())) {
                packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), boundEntity.entityId(),
                        snapshot.dimensionId()));
                boundEntity.worldId = snapshot.dimensionId();
            }

            int currentEffectBitmask = getCurrentEffectBitmask();
            if (boundEntity.effectBitmask() != currentEffectBitmask) {
                packets.add(
                        intPacket(new SetEntityEffectBitmaskRequestPacket(), boundEntity.entityId(),
                                currentEffectBitmask));
                boundEntity.effectBitmask = currentEffectBitmask;
            }

            packets.add(vector3Packet(new SetEntityPositionRequestPacket(), boundEntity.entityId(),
                    new Vector3((float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z())));
            packets.add(vector2Packet(new SetEntityRotationRequestPacket(), boundEntity.entityId(),
                    new Vector2(0.0f, (float) snapshot.rotation())));
            packets.add(floatPropertyPacket(boundEntity.entityId(), PROXIMITY_ECHO_FACTOR_PROPERTY,
                    voiceEffects ? (float) snapshot.echoFactor() : 0.0f));
            packets.add(floatPropertyPacket(boundEntity.entityId(), PROXIMITY_MUFFLE_FACTOR_PROPERTY,
                    voiceEffects && snapshot.muffled() ? 1.0f : 0.0f));
        }

        processPackets(client.exchange(packets));
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

        for (BoundEntity boundEntity : boundEntitiesByPlayerId.values()) {
            packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), boundEntity.entityId(),
                    getCurrentEffectBitmask()));
            boundEntity.effectBitmask = getCurrentEffectBitmask();
        }

        processPackets(client.exchange(packets));
    }

    private void processPackets(List<McApiPacket> packets) {
        for (McApiPacket packet : packets) {
            if (packet instanceof AcceptResponsePacket || packet instanceof DenyResponsePacket) {
                continue;
            }
            if (packet instanceof OnNetworkEntityCreatedPacket createdPacket) {
                handleNetworkEntityCreated(createdPacket);
                continue;
            }
            if (packet instanceof OnEntityDestroyedPacket destroyedPacket) {
                handleEntityDestroyed(destroyedPacket.Id);
            }
        }
    }

    private void handleNetworkEntityCreated(OnNetworkEntityCreatedPacket packet) {
        assignBindingKey(new PendingEntity(packet.Id, packet.UserGuid.toString(), packet.ServerUserGuid.toString(),
                packet.Locale));
    }

    private void handleEntityDestroyed(int entityId) {
        PendingEntity pendingEntity = unboundEntitiesById.remove(entityId);
        if (pendingEntity != null) {
            unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        }

        BoundEntity removed = null;
        for (BoundEntity boundEntity : boundEntitiesByPlayerId.values()) {
            if (boundEntity.entityId() == entityId) {
                removed = boundEntity;
                break;
            }
        }

        if (removed != null) {
            boundEntitiesByPlayerId.remove(removed.playerId());
        }
    }

    private boolean bindEntityToPlayer(PendingEntity pendingEntity, ProxyPlayerSnapshot snapshot) {
        unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        unboundEntitiesById.remove(pendingEntity.entityId());

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), snapshot.playerName()));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Bound to player " + snapshot.playerName()));
        packets.add(
                stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), snapshot.dimensionId()));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), pendingEntity.entityId(),
                getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), pendingEntity.entityId(),
                new Vector3((float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z())));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), pendingEntity.entityId(),
                new Vector2(0.0f, (float) snapshot.rotation())));
        packets.add(floatPropertyPacket(pendingEntity.entityId(), PROXIMITY_ECHO_FACTOR_PROPERTY,
                voiceEffects ? (float) snapshot.echoFactor() : 0.0f));
        packets.add(floatPropertyPacket(pendingEntity.entityId(), PROXIMITY_MUFFLE_FACTOR_PROPERTY,
                voiceEffects && snapshot.muffled() ? 1.0f : 0.0f));

        processPackets(client.exchange(packets));
        if (!client.isConnected()) {
            return false;
        }

        boundEntitiesByPlayerId.put(snapshot.playerId(), new BoundEntity(snapshot.playerId(), snapshot.playerName(),
                pendingEntity.entityId(), pendingEntity.userGuid(), pendingEntity.serverUserGuid(),
                pendingEntity.locale(), snapshot.dimensionId(), getCurrentEffectBitmask()));
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

    private void clearSessionState() {
        clearEntityState();
        snapshotsByPlayerId.clear();
    }

    private void clearEntityState() {
        unboundEntitiesByKey.clear();
        unboundEntitiesById.clear();
        boundEntitiesByPlayerId.clear();
    }

    private static SetEntityNameRequestPacket stringPacket(SetEntityNameRequestPacket packet, int entityId,
            String value) {
        packet.Id = entityId;
        packet.Value = value;
        return packet;
    }

    private static SetEntityDescriptionRequestPacket stringPacket(SetEntityDescriptionRequestPacket packet,
            int entityId,
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

    private static SetEntityEffectBitmaskRequestPacket intPacket(SetEntityEffectBitmaskRequestPacket packet,
            int entityId,
            int value) {
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

    private static SetEntityPropertyRequestPacket floatPropertyPacket(int entityId, String key, float value) {
        SetEntityPropertyRequestPacket packet = new SetEntityPropertyRequestPacket();
        packet.Id = entityId;
        packet.Key = key;
        packet.PropertyTypeValue = PropertyType.FLOAT;
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

    private static final class BoundEntity {
        private final String playerId;
        private String playerName;
        private final int entityId;
        private final String userGuid;
        private final String serverUserGuid;
        private final String locale;
        private String worldId;
        private int effectBitmask;

        private BoundEntity(String playerId, String playerName, int entityId, String userGuid, String serverUserGuid,
                String locale, String worldId, int effectBitmask) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.entityId = entityId;
            this.userGuid = userGuid;
            this.serverUserGuid = serverUserGuid;
            this.locale = locale;
            this.worldId = worldId;
            this.effectBitmask = effectBitmask;
        }

        private String playerId() {
            return playerId;
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
    }
}
