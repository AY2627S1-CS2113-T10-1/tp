package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/**
 * Verifies structural task validation without imposing service deadline rules.
 */
class TaskTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2020, 1, 1, 23, 59);

    @Test
    void constructor_pastDeadline_preservesEstimateAndNormalizesName() {
        Task task = new Task(10, "  Draft  ", DEADLINE, 90);
        assertEquals("Draft", task.name());
        assertEquals(DEADLINE, task.deadline());
        assertEquals(90, task.remainingMinutes());
        assertEquals("T10", task.displayId());
        assertEquals("T2147483647", new Task(Integer.MAX_VALUE, "A", DEADLINE, 30).displayId());
    }

    @Test
    void constructor_invalidFields_rejects() {
        assertThrows(ValidationException.class, () -> new Task(0, "A", DEADLINE, 30));
        assertThrows(ValidationException.class, () -> new Task(-1, "A", DEADLINE, 30));
        assertThrows(ValidationException.class, () -> new Task(1, null, DEADLINE, 30));
        assertThrows(ValidationException.class, () -> new Task(1, "A", DEADLINE, 45));
        for (LocalDateTime deadline : new LocalDateTime[] {null, DEADLINE.withSecond(1),
                DEADLINE.withNano(1), DEADLINE.withYear(0), DEADLINE.withYear(10000)}) {
            assertThrows(ValidationException.class, () -> new Task(1, "A", deadline, 30));
        }
    }

    @Test
    void constructor_yearBoundaries_accepts() {
        assertEquals(1, new Task(1, "A", DEADLINE.withYear(1), 30).deadline().getYear());
        assertEquals(9999, new Task(1, "A", DEADLINE.withYear(9999), 30).deadline().getYear());
    }

    // @@author nickpatt
    @Test
    void constructor_invalidNamesAndDurations_usesSharedValidation() {
        for (String name : new String[] {"", "   ", "Bad/name", "Bad\tname", "Bad\nname"}) {
            assertThrows(ValidationException.class, () -> new Task(1, name, DEADLINE, 30));
        }
        for (int minutes : new int[] {0, -30, 45, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> new Task(1, "Draft", DEADLINE, minutes));
        }
    }
    // @@author
}
