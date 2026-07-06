package team.avion.adapter;

public interface VoiceCraftIdentityListener {
    void entityIdentityRegistered(VoiceCraftEntityIdentity identity);

    void entityIdentityUnregistered(VoiceCraftEntityIdentity identity);
}
