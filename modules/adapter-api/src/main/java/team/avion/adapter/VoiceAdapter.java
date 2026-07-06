package team.avion.adapter;

/**
 * Lifecycle contract implemented by external voice protocol adapters.
 */
public interface VoiceAdapter extends AutoCloseable {
    /**
     * Returns the stable adapter id used in config and identity metadata.
     *
     * @return adapter id
     */
    String id();

    /**
     * Returns a human-readable adapter name for logs and diagnostics.
     *
     * @return display name
     */
    String displayName();

    /**
     * Returns the current adapter lifecycle state.
     *
     * @return adapter state
     */
    AdapterState state();

    /**
     * Starts the adapter using the provided host context.
     *
     * @param context adapter runtime context
     * @throws Exception when the adapter cannot start
     */
    void start(AdapterContext context) throws Exception;

    /**
     * Stops the adapter and releases protocol resources.
     *
     * @throws Exception when the adapter cannot stop cleanly
     */
    void stop() throws Exception;

    @Override
    default void close() throws Exception {
        stop();
    }
}
