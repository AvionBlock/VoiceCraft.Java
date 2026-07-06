package team.avion.adapter;

import java.util.UUID;

public record VoiceCraftEntityIdentity(int entityId, UUID playerId, String playerName, String adapterId) {
}
