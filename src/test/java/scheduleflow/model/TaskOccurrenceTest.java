package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

// @@author nickpatt
/** Checks release arithmetic at both supported calendar boundaries. */
class TaskOccurrenceTest {
    @Test
    void constructor_supportedRelease_handlesYearsOneAnd9999() {
        LocalDateTime first = LocalDateTime.of(1, 1, 8, 0, 0);
        assertEquals(first.minusWeeks(1), new TaskOccurrence(1, first).release());
        LocalDateTime last = LocalDateTime.of(9999, 12, 31, 23, 59);
        assertEquals(last.minusWeeks(1), new TaskOccurrence(1, last).release());
    }

    @Test
    void constructor_invalidOriginalDeadlineOrId_rejects() {
        for (LocalDateTime due : new LocalDateTime[] {null, LocalDateTime.of(1, 1, 7, 23, 59),
                LocalDateTime.of(0, 12, 31, 0, 0), LocalDateTime.of(10000, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0, 1)}) {
            assertThrows(ValidationException.class, () -> new TaskOccurrence(1, due));
        }
        assertThrows(ValidationException.class, () -> new TaskOccurrence(0, LocalDateTime.of(2026, 1, 1, 0, 0)));
    }
}
// @@author
