package scheduleflow.common;

import java.util.Objects;

/**
 * Reports a rejected domain value without changing application state.
 */
public final class ValidationException extends RuntimeException {
    /**
     * Creates a validation failure with a user-facing explanation.
     * The application decides how to display the explanation and report session severity.
     *
     * @param message explanation of the rejected domain value
     * @throws NullPointerException if message is null
     */
    public ValidationException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }
}
