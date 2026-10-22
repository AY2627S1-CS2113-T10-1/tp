package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/**
 * Verifies persisted identity integrity and immutable ownership of lists.
 */
class SnapshotTest {
    // @@author nickpatt
    @Test
    void constructor_recurrence_validatesRetainedReferencesAndCursorCoverage() {
        LocalDateTime first = LocalDateTime.of(2026, 9, 22, 18, 0);
        RecurringTask template = new RecurringTask(1, "Quiz", first, Optional.empty(), 30,
                TaskPriority.NORMAL, Optional.of(first));
        Task task = new Task(1, "Edited", Optional.empty(), 0, TaskPriority.LOW,
                Optional.of(new TaskOccurrence(1, first)));
        Snapshot valid = new Snapshot(List.of(task), List.of(), 2, 1, Snapshot.empty().studyHours(),
                List.of(template), 2);
        assertEquals(1, valid.recurringTasks().size());
        assertThrows(UnsupportedOperationException.class, () -> valid.recurringTasks().clear());
        assertEquals(List.of(task), new Snapshot(List.of(task), List.of(), 2, 1, valid.studyHours(), List.of(), 2)
                .tasks());
        for (int counter : new int[] {0, 1}) {
            assertThrows(ValidationException.class, () -> new Snapshot(List.of(task), List.of(), 2, 1,
                    valid.studyHours(), List.of(), counter));
        }
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(), List.of(), 1, 1,
                valid.studyHours(), List.of(template, template), 2));
        Task duplicate = new Task(2, "Other", Optional.of(first), 30, TaskPriority.HIGH, task.occurrence());
        assertThrows(ValidationException.class, () -> valid.withTasks(List.of(task, duplicate), 3));
        for (LocalDateTime original : new LocalDateTime[] {first.plusMinutes(1), first.plusDays(1),
                first.plusWeeks(1), first.minusWeeks(1)}) {
            Task invalid = new Task(2, "Other", Optional.empty(), 30, TaskPriority.NORMAL,
                    Optional.of(new TaskOccurrence(1, original)));
            assertThrows(ValidationException.class, () -> valid.withTasks(List.of(invalid), 3));
        }
        RecurringTask unprocessed = new RecurringTask(1, "Quiz", first, Optional.empty(), 30,
                TaskPriority.NORMAL, Optional.empty());
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(task), List.of(), 2, 1,
                valid.studyHours(), List.of(unprocessed), 2));
    }
    // @@author

    @Test
    void empty_initialCountersAndLists() {
        Snapshot snapshot = Snapshot.empty();
        assertEquals(1, snapshot.nextTaskId());
        assertEquals(1, snapshot.nextCommitmentId());
        assertTrue(snapshot.tasks().isEmpty());
        assertTrue(snapshot.commitments().isEmpty());
    }

    @Test
    void constructor_copiesLists_preservesOrderAndSeparateIds() {
        List<Task> tasks = new ArrayList<>(List.of(task(10), task(1)));
        List<Commitment> commitments = new ArrayList<>(List.of(commitment(1, 10)));
        Snapshot snapshot = new Snapshot(tasks, commitments, 11, 2);
        tasks.clear();
        commitments.clear();
        assertEquals(List.of(task(10), task(1)), snapshot.tasks());
        assertEquals(List.of(commitment(1, 10)), snapshot.commitments());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tasks().add(task(2)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.commitments().clear());
    }

    @Test
    void constructor_duplicateIdsAndInvalidCounters_rejects() {
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(task(1), task(1)), List.of(), 2, 1));
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(),
                List.of(commitment(1, 10), commitment(1, 11)), 1, 2));
        for (int counter : new int[] {-1, 0, 1}) {
            assertThrows(ValidationException.class, () -> new Snapshot(List.of(task(1)), List.of(), counter, 1));
            assertThrows(ValidationException.class,
                    () -> new Snapshot(List.of(), List.of(commitment(1, 10)), 1, counter));
        }
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(), List.of(), 0, 1));
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(), List.of(), 1, 0));
    }

    @Test
    void constructor_overlaps_rejectsButAllowsAdjacency() {
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(),
                List.of(commitment(1, 10), commitment(2, 10)), 1, 3));
        assertEquals(2, new Snapshot(List.of(), List.of(commitment(1, 10), commitment(2, 11)), 1, 3)
                .commitments().size());
    }

    @Test
    void constructor_nullListsOrElements_rejects() {
        assertThrows(ValidationException.class, () -> new Snapshot(null, List.of(), 1, 1));
        assertThrows(ValidationException.class, () -> new Snapshot(List.of(), null, 1, 1));
        assertThrows(ValidationException.class, () -> new Snapshot(Arrays.asList((Task) null), List.of(), 1, 1));
        assertThrows(ValidationException.class,
                () -> new Snapshot(List.of(), Arrays.asList((Commitment) null), 1, 1));
    }

    @Test
    void constructor_emptyAfterDeletion_preservesHighCounters() {
        Snapshot snapshot = new Snapshot(List.of(), List.of(), Integer.MAX_VALUE, 17);
        assertEquals(Integer.MAX_VALUE, snapshot.nextTaskId());
        assertEquals(17, snapshot.nextCommitmentId());
    }

    /**
     * Creates a fixed overdue task so snapshot validation cannot depend on the current date.
     */
    private Task task(int id) {
        return new Task(id, "Repeated", LocalDateTime.of(2000, 1, 1, 12, 0), 30);
    }

    /**
     * Creates a one-hour Monday series for deterministic identity and overlap checks.
     */
    private Commitment commitment(int id, int hour) {
        return new Commitment(id, "Repeated", DayOfWeek.MONDAY, LocalTime.of(hour, 0), 60);
    }
}
