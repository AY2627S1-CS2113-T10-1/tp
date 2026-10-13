package scheduleflow.storage;

/**
 * Reports a load or atomic-save failure without publishing partial state.
 */
public final class StorageException extends Exception {
    /**
     * Creates a storage failure with a user-facing explanation.
     *
     * @param message explanation suitable for the caller's error output
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Creates a storage failure preserving its underlying cause.
     * Additional cleanup failures may be attached with {@link #addSuppressed(Throwable)}.
     *
     * @param message explanation suitable for the caller's error output
     * @param cause original I/O, parsing or validation failure
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
