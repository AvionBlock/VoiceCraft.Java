package team.avion.adapter.simplevoicechat.net;

public enum SimpleVoiceChatPacketType {
    MIC(0x1),
    PLAYER_SOUND(0x2),
    AUTHENTICATE(0x5),
    AUTHENTICATE_ACK(0x6),
    KEEP_ALIVE(0x8),
    CONNECTION_CHECK(0x9),
    CONNECTION_CHECK_ACK(0xA);

    private final int id;

    SimpleVoiceChatPacketType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static SimpleVoiceChatPacketType byId(int id) {
        for (SimpleVoiceChatPacketType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Simple Voice Chat UDP packet id " + id);
    }
}
