package team.avion.adapter;

import java.util.UUID;

/**
 * Stable Minecraft player identity associated with a VoiceCraft entity.
 *
 * @param entityId VoiceCraft entity id
 * @param playerId Minecraft player UUID used by voice client protocols
 * @param playerName visible Minecraft player name
 * @param adapterId adapter that owns the entity
 */
public record VoiceCraftEntityIdentity(int entityId, UUID playerId, String playerName, String adapterId) {
}
