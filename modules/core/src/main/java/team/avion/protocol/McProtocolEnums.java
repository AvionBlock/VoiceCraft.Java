package team.avion.protocol;

public final class McProtocolEnums {
    private McProtocolEnums() {
    }

    public enum McApiConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    public enum McApiPacketType {
        LOGIN_REQUEST,
        LOGOUT_REQUEST,
        PING_REQUEST,
        ACCEPT_RESPONSE,
        DENY_RESPONSE,
        PING_RESPONSE,
        EVENT_REQUEST,
        RESET_REQUEST,
        SET_EFFECT_REQUEST,
        CLEAR_EFFECTS_REQUEST,
        CREATE_ENTITY_REQUEST,
        DESTROY_ENTITY_REQUEST,
        ENTITY_AUDIO_REQUEST,
        SET_ENTITY_TITLE_REQUEST,
        SET_ENTITY_DESCRIPTION_REQUEST,
        SET_ENTITY_WORLD_ID_REQUEST,
        SET_ENTITY_NAME_REQUEST,
        SET_ENTITY_MUTE_REQUEST,
        SET_ENTITY_DEAFEN_REQUEST,
        SET_ENTITY_TALK_BITMASK_REQUEST,
        SET_ENTITY_LISTEN_BITMASK_REQUEST,
        SET_ENTITY_EFFECT_BITMASK_REQUEST,
        SET_ENTITY_POSITION_REQUEST,
        SET_ENTITY_ROTATION_REQUEST,
        SET_ENTITY_PROPERTY_REQUEST,
        RESET_RESPONSE,
        CREATE_ENTITY_RESPONSE,
        DESTROY_ENTITY_RESPONSE,

        // Obsolete top-level event packet ids kept only for legacy Java model classes.
        SET_ENTITY_CAVE_FACTOR_REQUEST,
        SET_ENTITY_MUFFLE_FACTOR_REQUEST,
        ON_EFFECT_UPDATED,
        ON_ENTITY_CREATED,
        ON_NETWORK_ENTITY_CREATED,
        ON_ENTITY_DESTROYED,
        ON_ENTITY_VISIBILITY_UPDATED,
        ON_ENTITY_WORLD_ID_UPDATED,
        ON_ENTITY_NAME_UPDATED,
        ON_ENTITY_MUTE_UPDATED,
        ON_ENTITY_DEAFEN_UPDATED,
        ON_ENTITY_SERVER_MUTE_UPDATED,
        ON_ENTITY_SERVER_DEAFEN_UPDATED,
        ON_ENTITY_TALK_BITMASK_UPDATED,
        ON_ENTITY_LISTEN_BITMASK_UPDATED,
        ON_ENTITY_EFFECT_BITMASK_UPDATED,
        ON_ENTITY_POSITION_UPDATED,
        ON_ENTITY_ROTATION_UPDATED,
        ON_ENTITY_CAVE_FACTOR_UPDATED,
        ON_ENTITY_MUFFLE_FACTOR_UPDATED,
        ON_ENTITY_AUDIO_RECEIVED;

        public static McApiPacketType fromByte(int value) {
            McApiPacketType[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Unknown packet type id: " + value);
            }
            return values[value];
        }
    }

    public enum EventType {
        NONE,
        ON_EFFECT_UPDATED,
        ON_ENTITY_CREATED,
        ON_NETWORK_ENTITY_CREATED,
        ON_ENTITY_DESTROYED,
        ON_ENTITY_VISIBILITY_UPDATED,
        ON_ENTITY_WORLD_ID_UPDATED,
        ON_ENTITY_NAME_UPDATED,
        ON_ENTITY_MUTE_UPDATED,
        ON_ENTITY_DEAFEN_UPDATED,
        ON_ENTITY_SERVER_MUTE_UPDATED,
        ON_ENTITY_SERVER_DEAFEN_UPDATED,
        ON_ENTITY_TALK_BITMASK_UPDATED,
        ON_ENTITY_LISTEN_BITMASK_UPDATED,
        ON_ENTITY_EFFECT_BITMASK_UPDATED,
        ON_ENTITY_POSITION_UPDATED,
        ON_ENTITY_ROTATION_UPDATED,
        ON_ENTITY_PROPERTY_UPDATED,
        ON_ENTITY_AUDIO_RECEIVED,
        ON_ENTITY_AUDIO_DATA_RECEIVED;

        public static EventType fromByte(int value) {
            EventType[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Unknown event type id: " + value);
            }
            return values[value];
        }
    }

    public enum PositioningType {
        SERVER,
        CLIENT;

        public static PositioningType fromByte(int value) {
            PositioningType[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Unknown positioning type id: " + value);
            }
            return values[value];
        }
    }

    public enum EffectType {
        NONE,
        VISIBILITY,
        PROXIMITY,
        DIRECTIONAL,
        PROXIMITY_ECHO,
        ECHO,
        PROXIMITY_MUFFLE,
        MUFFLE;

        public static EffectType fromByte(int value) {
            EffectType[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Unknown effect type id: " + value);
            }
            return values[value];
        }
    }

    public enum CreateEntityResponseCode {
        OK(0),
        FAILURE(-1);

        private final int code;

        CreateEntityResponseCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static CreateEntityResponseCode fromCode(int code) {
            for (CreateEntityResponseCode value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown create entity response code: " + code);
        }
    }

    public enum DestroyEntityResponseCode {
        OK(0),
        NOT_FOUND(-1);

        private final int code;

        DestroyEntityResponseCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static DestroyEntityResponseCode fromCode(int code) {
            for (DestroyEntityResponseCode value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown destroy entity response code: " + code);
        }
    }

    public enum ResetResponseCode {
        OK(0),
        FAILURE(-1);

        private final int code;

        ResetResponseCode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static ResetResponseCode fromCode(int code) {
            for (ResetResponseCode value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown reset response code: " + code);
        }
    }
}
