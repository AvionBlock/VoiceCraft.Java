package team.avion.proxy;

/** Keeps player and native-audio entities in the same backend-scoped world. */
public final class ProxyDimensionIds {
    private ProxyDimensionIds() {
    }

    public static String qualify(String serverName, String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "";
        }
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("Proxy server name is required for world isolation.");
        }
        String prefix = serverName + "_";
        return dimensionId.startsWith(prefix) ? dimensionId : prefix + dimensionId;
    }
}
