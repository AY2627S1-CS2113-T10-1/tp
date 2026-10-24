package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;

/** Verifies task mutations preserve recurrence data and reject inconsistent snapshot fixtures. */
class TaskRecurrenceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 7, 0);

    @Test
    void mutations_generatedTask_preserveOriginalReleaseAndAllRecurrence() {
        Snapshot source = new RecurringTaskService().add(Snapshot.empty(), "Quiz", NOW.plusDays(1), 30,
                Optional.empty(), NOW);
        TaskService service = new TaskService();
        TaskOccurrence occurrence = source.tasks().getFirst().occurrence().orElseThrow();
        Snapshot edited = service.edit(source, 1, new TaskEdit(Optional.of("Renamed"),
                Optional.of(NOW.plusDays(3)), false), NOW);
        Snapshot revised = service.reviseRemaining(edited, 1, 60);
        Snapshot added = service.add(revised, "Ordinary", NOW.plusDays(2), 30, NOW);
        Snapshot deleted = service.delete(added, added.tasks().getLast().id());
        for (Snapshot candidate : List.of(edited, revised, added, deleted,
                source.withStudyHours(source.studyHours()), source.withCommitments(List.of(), 1))) {
            assertEquals(source.recurringTasks(), candidate.recurringTasks());
            assertEquals(source.nextRecurringTaskId(), candidate.nextRecurringTaskId());
            assertEquals(occurrence, candidate.tasks().getFirst().occurrence().orElseThrow());
            assertEquals(NOW.plusDays(1).minusWeeks(1), occurrence.release());
        }
        assertSame(revised, service.reviseRemaining(revised, 1, 60));
        assertSame(edited, service.edit(edited, 1,
                new TaskEdit(Optional.of("Renamed"), Optional.empty(), false), NOW));
    }

    @Test
    void snapshot_invalidRecurrence_rejectsButAllowsDeletedTemplateProvenance() {
        LocalDateTime due = NOW.plusDays(1);
        Task task = new Task(1, "Quiz", due, 30, Optional.of(new TaskOccurrence(1, due)));
        RecurringTask processed = new RecurringTask(1, "Quiz", due, Optional.empty(), 30, Optional.of(due));
        Snapshot valid = state(List.of(task), List.of(processed), 2);
        assertEquals(List.of(task), state(List.of(task), List.of(), 2).tasks());
        assertThrows(UnsupportedOperationException.class, () -> valid.recurringTasks().clear());
        assertThrows(ValidationException.class, () -> state(List.of(task), List.of(processed), 1));
        assertThrows(ValidationException.class, () -> state(List.of(), List.of(processed, processed), 2));
        assertThrows(ValidationException.class, () -> state(List.of(), null, 2));
        assertThrows(ValidationException.class, () -> state(List.of(), Arrays.asList((RecurringTask) null), 2));
        assertThrows(ValidationException.class, () -> state(List.of(task,
                new Task(2, "Duplicate", due.plusDays(1), 30, task.occurrence())), List.of(processed), 2));
        assertThrows(ValidationException.class, () -> state(List.of(task), List.of(new RecurringTask(1,
                "Quiz", due, Optional.empty(), 30, Optional.empty())), 2));
        assertThrows(ValidationException.class, () -> state(List.of(new Task(1, "Wrong week", due, 30,
                Optional.of(new TaskOccurrence(1, due.plusDays(1))))), List.of(processed), 2));
        assertThrows(ValidationException.class, () -> state(List.of(new Task(1, "Ahead", due, 30,
                Optional.of(new TaskOccurrence(1, due.plusWeeks(1))))), List.of(processed), 2));
        assertThrows(ValidationException.class, () -> state(List.of(), List.of(), 0));
    }

    /** Builds structural fixtures with room for two task IDs. */
    private Snapshot state(List<Task> tasks, List<RecurringTask> templates, int nextRecurring) {
        return new Snapshot(tasks, List.of(), 3, 1, Snapshot.empty().studyHours(), templates, nextRecurring);
    }
}
