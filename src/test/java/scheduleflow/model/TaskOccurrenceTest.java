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

    @Test
    void task_unifiedConstructors_preserveOptionalDeadlineExactWorkAndProvenance() {
        LocalDateTime due = LocalDateTime.of(2026, 10, 2, 12, 0);
        Optional<TaskOccurrence> occurrence = Optional.of(new TaskOccurrence(2, due));
        Task undatedCompleted = new Task(1, "Quiz", Optional.empty(), 0, TaskPriority.HIGH, occurrence);
        assertEquals(Optional.empty(), undatedCompleted.deadline());
        assertEquals(occurrence, undatedCompleted.occurrence());
        assertEquals(TaskPriority.HIGH, undatedCompleted.priority());
        Task legacy = new Task(1, "Quiz", due, 17, occurrence);
        assertEquals(Optional.of(due), legacy.deadline());
        assertEquals(TaskPriority.NORMAL, legacy.priority());
        assertEquals(17, legacy.remainingMinutes());
        assertEquals(Optional.empty(), new Task(1, "Quiz", Optional.of(due), 17,
                TaskPriority.LOW).occurrence());
        assertThrows(NullPointerException.class, () -> new Task(1, "Quiz", null, 0,
                TaskPriority.NORMAL, occurrence));
        assertThrows(NullPointerException.class, () -> new Task(1, "Quiz", Optional.empty(), 0,
                null, occurrence));
        assertThrows(NullPointerException.class, () -> new Task(1, "Quiz", Optional.empty(), 0,
                TaskPriority.NORMAL, null));
        assertThrows(ValidationException.class, () -> new Task(1, "Quiz", Optional.empty(), -1,
                TaskPriority.NORMAL, occurrence));
    }
}
