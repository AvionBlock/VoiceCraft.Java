package team.avion.fabric.voicecraft;

import net.minecraft.server.network.ServerPlayerEntity;
import team.avion.proxy.ProxyPlayerSnapshot;
import team.avion.protocol.McApiTcpClient;
import team.avion.protocol.McPackets.AcceptResponsePacket;
import team.avion.protocol.McPackets.ClearEffectsRequestPacket;
import team.avion.protocol.McPackets.CreateEntityRequestPacket;
import team.avion.protocol.McPackets.CreateEntityResponsePacket;
import team.avion.protocol.McPackets.DenyResponsePacket;
import team.avion.protocol.McPackets.DestroyEntityRequestPacket;
import team.avion.protocol.McPackets.EventRequestPacket;
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
import team.avion.protocol.McPackets.SetEntityRotationRequestPacket;
import team.avion.protocol.McPackets.SetEntityWorldIdRequestPacket;
import team.avion.protocol.McProtocolEnums.CreateEntityResponseCode;
import team.avion.protocol.McProtocolTypes.Vector2;
import team.avion.protocol.McProtocolTypes.Vector3;
import team.avion.fabric.VoiceCraftJava;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

public final class FabricVoiceCraftSessionManager {
    private static final int EFFECT_BITMASK_PROXIMITY = 1;

    private final VoiceCraftJava plugin;
    private final McApiTcpClient client;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, PendingEntity> unboundEntitiesByKey = new HashMap<>();
    private final Map<Integer, PendingEntity> unboundEntitiesById = new HashMap<>();
    private final Map<String, BoundEntity> boundEntitiesByPlayerName = new HashMap<>();

    private String host = "";
    private int port;
    private String loginKey = "";
    private int proximityDistance = 30;
    private boolean proximityToggle = true;
    private boolean voiceEffects = true;

    public FabricVoiceCraftSessionManager(VoiceCraftJava plugin) {
        this.plugin = plugin;
        this.client = new McApiTcpClient(plugin.Logger);
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

        clearSessionState();
        if (!client.connect(host, port, loginKey)) {
            return false;
        }

        applyGlobalSettings();
        processPackets(client.exchange(List.of(new PingRequestPacket())));
        return client.isConnected();
    }

    public synchronized void disconnect() {
        destroyManagedEntities();
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

    public synchronized boolean tick(List<ProxyPlayerSnapshot> snapshots) {
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

    public synchronized OptionalInt createEntityForPlayer(ServerPlayerEntity player) {
        BoundEntity existing = boundEntitiesByPlayerName.get(player.getName().getString());
        if (existing != null) {
            return OptionalInt.of(existing.entityId());
        }
        if (!client.isConnected()) {
            return OptionalInt.empty();
        }

        String requestId = java.util.UUID.randomUUID().toString();
        CreateEntityRequestPacket request = new CreateEntityRequestPacket();
        request.RequestId = requestId;
        request.WorldId = getDimensionId(player);
        request.Name = player.getName().getString();
        request.Muted = false;
        request.Deafened = false;
        request.TalkBitmask = 0xFFFF;
        request.ListenBitmask = 0xFFFF;
        request.EffectBitmask = getCurrentEffectBitmask();
        request.Position = new Vector3((float) player.getX(), (float) player.getEyeY(), (float) player.getZ());
        request.Rotation = new Vector2(player.getPitch(), player.getYaw());

        for (McApiPacket response : client.exchange(List.of(request))) {
            if (response instanceof CreateEntityResponsePacket created && requestId.equals(created.RequestId)) {
                if (created.ResponseCode == CreateEntityResponseCode.OK) {
                    initializeCreatedEntity(created.Id, player);
                    boundEntitiesByPlayerName.put(player.getName().getString(), new BoundEntity(
                            player.getName().getString(), created.Id, getDimensionId(player), getCurrentEffectBitmask(),
                            false));
                    plugin.getPlayerBinds().put(player.getName().getString(), true);
                    return OptionalInt.of(created.Id);
                }
                continue;
            }
            processPackets(List.of(response));
        }

        return OptionalInt.empty();
    }

    public synchronized boolean bindPlayer(int bindingKey, ServerPlayerEntity player) {
        if (!client.isConnected()) {
            return false;
        }
        String playerName = player.getName().getString();
        if (plugin.getPlayerBinds().getOrDefault(playerName, false)) {
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

        unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        unboundEntitiesById.remove(pendingEntity.entityId());

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), playerName));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Bound to player " + playerName));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), getDimensionId(player)));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), pendingEntity.entityId(), getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), pendingEntity.entityId(),
                new Vector3((float) player.getX(), (float) player.getEyeY(), (float) player.getZ())));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), pendingEntity.entityId(),
                new Vector2(player.getPitch(), player.getYaw())));
        processPackets(client.exchange(packets));
        if (!client.isConnected()) {
            return false;
        }

        boundEntitiesByPlayerName.put(playerName, new BoundEntity(playerName, pendingEntity.entityId(),
                getDimensionId(player), getCurrentEffectBitmask(), true));
        plugin.getPlayerBinds().put(playerName, true);
        return true;
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

        unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        unboundEntitiesById.remove(pendingEntity.entityId());

        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), pendingEntity.entityId(), name));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), pendingEntity.entityId(),
                "Bound to fake player " + name));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), pendingEntity.entityId(), ""));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), pendingEntity.entityId(), getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), pendingEntity.entityId(),
                new Vector3(0.0f, 0.0f, 0.0f)));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), pendingEntity.entityId(),
                new Vector2(0.0f, 0.0f)));
        processPackets(client.exchange(packets));
        if (!client.isConnected()) {
            return false;
        }

        boundEntitiesByPlayerName.put(name, new BoundEntity(name, pendingEntity.entityId(), "",
                getCurrentEffectBitmask(), true));
        plugin.getPlayerBinds().put(name, true);
        return true;
    }

    public synchronized void unbindPlayer(ServerPlayerEntity player) {
        BoundEntity boundEntity = boundEntitiesByPlayerName.remove(player.getName().getString());
        plugin.getPlayerBinds().remove(player.getName().getString());
        if (boundEntity == null || !client.isConnected()) {
            return;
        }

        DestroyEntityRequestPacket packet = new DestroyEntityRequestPacket();
        packet.RequestId = java.util.UUID.randomUUID().toString();
        packet.Id = boundEntity.entityId();
        processPackets(client.exchange(List.of(packet)));
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
            }
        }
    }

    private void handleNetworkEntityCreated(OnNetworkEntityCreatedPacket packet) {
        PendingEntity pendingEntity = new PendingEntity(packet.Id);
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

    private void handleEntityDestroyed(int entityId) {
        PendingEntity pendingEntity = unboundEntitiesById.remove(entityId);
        if (pendingEntity != null) {
            unboundEntitiesByKey.remove(pendingEntity.bindingKey());
        }
        boundEntitiesByPlayerName.values().removeIf(bound -> {
            boolean remove = bound.entityId() == entityId;
            if (remove) {
                plugin.getPlayerBinds().remove(bound.playerName());
            }
            return remove;
        });
    }

    private void initializeCreatedEntity(int entityId, ServerPlayerEntity player) {
        List<McApiPacket> packets = new ArrayList<>();
        packets.add(stringPacket(new SetEntityNameRequestPacket(), entityId, player.getName().getString()));
        packets.add(stringPacket(new SetEntityDescriptionRequestPacket(), entityId,
                "Fabric client " + player.getName().getString()));
        packets.add(stringPacket(new SetEntityWorldIdRequestPacket(), entityId, getDimensionId(player)));
        packets.add(intPacket(new SetEntityEffectBitmaskRequestPacket(), entityId, getCurrentEffectBitmask()));
        packets.add(vector3Packet(new SetEntityPositionRequestPacket(), entityId,
                new Vector3((float) player.getX(), (float) player.getEyeY(), (float) player.getZ())));
        packets.add(vector2Packet(new SetEntityRotationRequestPacket(), entityId,
                new Vector2(player.getPitch(), player.getYaw())));
        processPackets(client.exchange(packets));
    }

    private void applyGlobalSettings() {
        ClearEffectsRequestPacket clear = new ClearEffectsRequestPacket();
        ProximityEffect effect = new ProximityEffect();
        effect.MinRange = 0.0f;
        effect.MaxRange = proximityDistance;
        effect.WetDry = voiceEffects ? 1.0f : 0.0f;
        SetEffectRequestPacket proximity = new SetEffectRequestPacket();
        proximity.Bitmask = EFFECT_BITMASK_PROXIMITY;
        proximity.Effect = effect;
        processPackets(client.exchange(List.of(clear, proximity)));
    }

    private void clearSessionState() {
        unboundEntitiesByKey.clear();
        unboundEntitiesById.clear();
        boundEntitiesByPlayerName.clear();
        plugin.getPlayerBinds().clear();
    }

    private void destroyManagedEntities() {
        if (!client.isConnected()) {
            return;
        }
        List<McApiPacket> packets = new ArrayList<>();
        for (BoundEntity boundEntity : boundEntitiesByPlayerName.values()) {
            if (!boundEntity.networkEntity()) {
                DestroyEntityRequestPacket packet = new DestroyEntityRequestPacket();
                packet.RequestId = java.util.UUID.randomUUID().toString();
                packet.Id = boundEntity.entityId();
                packets.add(packet);
            }
        }
        if (!packets.isEmpty()) {
            processPackets(client.exchange(packets));
        }
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

    private String getDimensionId(ServerPlayerEntity player) {
        return player.getWorld().getRegistryKey().getValue().toString();
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
        private int bindingKey;

        private PendingEntity(int entityId) {
            this.entityId = entityId;
        }

        private int entityId() {
            return entityId;
        }

        private int bindingKey() {
            return bindingKey;
        }
    }

    private static final class BoundEntity {
        private final String playerName;
        private final int entityId;
        private String worldId;
        private int effectBitmask;
        private final boolean networkEntity;

        private BoundEntity(String playerName, int entityId, String worldId, int effectBitmask, boolean networkEntity) {
            this.playerName = playerName;
            this.entityId = entityId;
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
