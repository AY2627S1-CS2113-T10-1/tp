package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

// @@author nickpatt
/** Checks clock-free weekly template contracts, including saved cursor boundaries. */
class RecurringTaskTest {
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 9, 22, 18, 0);

    @Test
    void constructor_boundedCursor_normalizesAndAcceptsInclusiveLastDate() {
        RecurringTask template = new RecurringTask(2, " Quiz ", FIRST, Optional.of(FIRST.plusWeeks(2).toLocalDate()),
                1, TaskPriority.HIGH, Optional.of(FIRST.plusWeeks(2)));
        assertEquals("Quiz", template.name());
        assertEquals("R2", template.displayId());
        assertEquals(Optional.of(FIRST.plusWeeks(2)), template.generatedThrough());
    }

    @Test
    void constructor_invalidSequenceCursor_rejects() {
        for (LocalDateTime cursor : new LocalDateTime[] {FIRST.minusWeeks(1), FIRST.plusDays(1),
                FIRST.plusMinutes(1), FIRST.plusSeconds(1), FIRST.plusWeeks(3)}) {
            assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST,
                    Optional.of(FIRST.plusWeeks(2).toLocalDate()), 30, TaskPriority.NORMAL, Optional.of(cursor)));
        }
        for (LocalDate until : new LocalDate[] {FIRST.toLocalDate().minusDays(1), LocalDate.of(10000, 1, 1)}) {
            assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST, Optional.of(until),
                    30, TaskPriority.NORMAL, Optional.empty()));
        }
        assertThrows(ValidationException.class, () -> new RecurringTask(0, "Quiz", FIRST, Optional.empty(),
                30, TaskPriority.NORMAL, Optional.empty()));
        assertThrows(ValidationException.class, () -> new RecurringTask(1, "Quiz", FIRST, Optional.empty(),
                0, TaskPriority.NORMAL, Optional.empty()));
        assertThrows(ValidationException.class, () -> new RecurringTask(1, "Bad/name", FIRST, Optional.empty(),
                30, TaskPriority.NORMAL, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RecurringTask(1, "Quiz", FIRST, null,
                30, TaskPriority.NORMAL, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RecurringTask(1, "Quiz", FIRST, Optional.empty(),
                30, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RecurringTask(1, "Quiz", FIRST, Optional.empty(),
                30, TaskPriority.NORMAL, null));
    }
}
// @@author
