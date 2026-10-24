package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/** Verifies immutable provenance and supported release boundaries. */
class TaskOccurrenceTest {
    @Test
    void constructor_boundaries_requiresSupportedDeadlineAndRelease() {
        LocalDateTime first = LocalDateTime.of(1, 1, 8, 0, 0);
        assertEquals(first.minusWeeks(1), new TaskOccurrence(1, first).release());
        for (LocalDateTime invalid : new LocalDateTime[] {null, first.minusMinutes(1),
                first.withYear(0), first.withYear(10000), first.plusSeconds(1), first.plusNanos(1)}) {
            assertThrows(ValidationException.class, () -> new TaskOccurrence(1, invalid));
        }
        assertThrows(ValidationException.class, () -> new TaskOccurrence(0, first));
        assertThrows(NullPointerException.class, () -> new Task(1, "Quiz", first, 30, null));
        assertEquals(Optional.empty(), new Task(1, "Quiz", first, 30).occurrence());
    }
}
