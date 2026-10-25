package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/** Verifies weekly membership, bounds and structural generation cursors. */
class RecurringTaskTest {
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Test
    void constructor_validBounds_normalizesAndChecksWeeklyMembership() {
        RecurringTask task = new RecurringTask(2, " Quiz ", FIRST, Optional.of(FIRST.plusDays(10).toLocalDate()),
                60, Optional.of(FIRST.plusWeeks(1)));
        assertEquals("Quiz", task.name());
        assertEquals("R2", task.displayId());
        assertTrue(task.includes(FIRST));
        assertTrue(task.includes(FIRST.plusWeeks(1)));
        assertFalse(task.includes(FIRST.plusDays(1)));
        assertFalse(task.includes(FIRST.plusWeeks(2)));
        assertFalse(task.includes(FIRST.plusMinutes(1)));
        assertThrows(NullPointerException.class, () -> task.includes(null));
    }

    @Test
    void constructor_invalidStructure_rejects() {
        for (LocalDateTime cursor : new LocalDateTime[] {FIRST.minusWeeks(1), FIRST.plusDays(1),
                FIRST.plusSeconds(1), FIRST.withYear(10000)}) {
            assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                    Optional.empty(), 30, Optional.of(cursor)));
        }
        assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                Optional.of(FIRST.minusDays(1).toLocalDate()), 30, Optional.empty()));
        assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                Optional.of(FIRST.toLocalDate()), 30, Optional.of(FIRST.plusWeeks(1))));
        assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                Optional.empty(), 45, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                null, 30, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                Optional.empty(), 30, null));
    }
}
