package scheduleflow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies that rejected domain values retain an explanation for the application.
 */
class ValidationExceptionTest {
    @Test
    void constructor_explanation_preservesMessage() {
        assertEquals("Invalid duration", new ValidationException("Invalid duration").getMessage());
    }

    @Test
    void constructor_nullMessage_reportsNamedPrecondition() {
        assertEquals("message", assertThrows(NullPointerException.class,
                () -> new ValidationException(null)).getMessage());
    }
}
