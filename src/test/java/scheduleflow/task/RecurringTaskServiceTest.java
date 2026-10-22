package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.common.ValidationException;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.TimeRules;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;

// @@author nickpatt
/** Verifies deterministic, atomic weekly generation and persisted no-regeneration history. */
class RecurringTaskServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private final RecurringTaskService service = new RecurringTaskService();

    @Test
    void materialize_mixedTemplates_assignsChronologicalIdsThenNumericTemplateIds() {
        RecurringTask laterId = template(10, NOW.plusDays(1), Optional.empty());
        RecurringTask earlierId = template(2, NOW.plusDays(1), Optional.empty());
        RecurringTask earliest = template(3, NOW.plusHours(1), Optional.empty());
        Snapshot source = state(List.of(laterId, earlierId, earliest), 7, 11);
        Snapshot candidate = service.materialize(source, NOW);
        assertEquals(List.of(3, 2, 10, 3, 2, 10), candidate.tasks().stream().limit(6)
                .map(task -> task.occurrence().orElseThrow().templateId()).toList());
        assertEquals(List.of(7, 8, 9, 10, 11, 12), candidate.tasks().stream().limit(6).map(Task::id).toList());
        assertTrue(source.tasks().isEmpty());
        assertSame(candidate, service.materialize(candidate, NOW));
        assertEquals(candidate, service.materialize(source, NOW));
        assertEquals(List.of(2, 3, 10), service.list(candidate).stream().map(RecurringTask::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> service.list(candidate).clear());
    }

    @Test
    void add_horizonAndInclusiveUntil_materializesOnlyEligibleWeeklyDates() {
        LocalDateTime horizon = TimeRules.horizonEnd(NOW);
        Snapshot atBoundary = service.add(Snapshot.empty(), "Quiz", horizon, 45, Optional.of(horizon.toLocalDate()),
                TaskPriority.HIGH, NOW);
        assertEquals(Optional.of(horizon), atBoundary.tasks().getFirst().deadline());
        Snapshot beyond = service.add(Snapshot.empty(), "Future", horizon.plusMinutes(1), 30, Optional.empty(),
                TaskPriority.NORMAL, NOW);
        assertTrue(beyond.tasks().isEmpty());
        assertTrue(beyond.recurringTasks().getFirst().generatedThrough().isEmpty());
        Snapshot bounded = service.add(Snapshot.empty(), "Quiz", NOW.plusDays(1), 45,
                Optional.of(NOW.plusDays(8).toLocalDate()), TaskPriority.LOW, NOW);
        assertEquals(2, bounded.tasks().size());
        assertEquals(45, bounded.tasks().getLast().remainingMinutes());
        assertEquals(TaskPriority.LOW, bounded.tasks().getLast().priority());
        assertSame(bounded, service.materialize(bounded, NOW.plusYears(3)));
    }

    @Test
    void materialize_millenniaMissed_jumpsPastWithoutBackfillAndUsesRealSequenceCursor() {
        LocalDateTime first = LocalDateTime.of(1, 1, 8, 12, 0);
        Snapshot source = state(List.of(template(1, first, Optional.empty())), 1, 2);
        Snapshot candidate = org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(2),
                () -> service.materialize(source, NOW));
        assertTrue(candidate.tasks().size() <= 53);
        assertTrue(candidate.tasks().stream().allMatch(task -> task.deadline().orElseThrow().isAfter(NOW)));
        assertTrue(candidate.recurringTasks().getFirst().includes(candidate.recurringTasks().getFirst()
                .generatedThrough().orElseThrow()));
        Snapshot expired = state(List.of(template(1, first, Optional.of(first.plusWeeks(2).toLocalDate()))), 1, 2);
        Snapshot skipped = service.materialize(expired, NOW);
        assertTrue(skipped.tasks().isEmpty());
        assertEquals(Optional.of(first.plusWeeks(2)), skipped.recurringTasks().getFirst().generatedThrough());
        assertSame(skipped, service.materialize(skipped, NOW.minusYears(1)));
    }

    @Test
    void materialize_dueExactlyNow_skipsAndRetainsPreviouslyGeneratedOverdue() {
        Snapshot source = state(List.of(template(1, NOW, Optional.empty())), 1, 2);
        Snapshot candidate = service.materialize(source, NOW);
        assertEquals(Optional.of(NOW.plusWeeks(1)), candidate.tasks().getFirst().deadline());
        Snapshot later = service.materialize(candidate, NOW.plusWeeks(3));
        assertEquals(candidate.tasks(), later.tasks().subList(0, candidate.tasks().size()));
    }

    @Test
    void materialize_leapDayAndOffWeekUntil_usesCalendarHorizonAndLastRealDeadline() {
        LocalDateTime leapNow = LocalDateTime.of(2028, 2, 29, 7, 0);
        LocalDateTime boundary = TimeRules.horizonEnd(leapNow);
        Snapshot source = state(List.of(template(1, boundary.minusWeeks(1), Optional.empty())), 1, 2);
        Snapshot candidate = service.materialize(source, leapNow);
        assertEquals(List.of(Optional.of(boundary.minusWeeks(1)), Optional.of(boundary)),
                candidate.tasks().stream().map(Task::deadline).toList());
        LocalDateTime first = NOW.plusDays(1);
        Snapshot bounded = state(List.of(template(1, first, Optional.of(first.plusDays(10).toLocalDate()))), 1, 2);
        Snapshot result = service.materialize(bounded, NOW);
        assertEquals(2, result.tasks().size());
        assertEquals(Optional.of(first.plusWeeks(1)), result.recurringTasks().getFirst().generatedThrough());
    }

    @Test
    void materialize_exhaustedTaskIdsWithSkippedOnlyHistory_advancesCursorWithoutAllocating() {
        LocalDateTime first = NOW.minusYears(10);
        Snapshot source = state(List.of(template(1, first, Optional.of(first.plusDays(10).toLocalDate()))),
                Integer.MAX_VALUE, 2);
        Snapshot candidate = service.materialize(source, NOW);
        assertTrue(candidate.tasks().isEmpty());
        assertEquals(Integer.MAX_VALUE, candidate.nextTaskId());
        assertEquals(Optional.of(first.plusWeeks(1)), candidate.recurringTasks().getFirst().generatedThrough());
        assertSame(candidate, service.materialize(candidate, NOW));
    }

    @Test
    void materialize_deletedAndCompletedOccurrences_neverRecreatesAfterCodecAndFileRestart(@TempDir Path folder)
            throws Exception {
        Snapshot generated = service.add(Snapshot.empty(), "Quiz|café", NOW.plusDays(1), 45,
                Optional.empty(), TaskPriority.HIGH, NOW);
        TaskService tasks = new TaskService();
        Snapshot changed = tasks.complete(tasks.delete(generated, 1), 2);
        StateCodec codec = new StateCodec();
        Snapshot decoded = codec.decode(codec.encode(changed));
        assertSame(decoded, service.materialize(decoded, NOW));
        FileStorage file = new FileStorage(folder.resolve("saved.txt"), codec);
        file.save(decoded);
        Snapshot restarted = new FileStorage(folder.resolve("saved.txt"), new StateCodec()).load();
        assertSame(restarted, service.materialize(restarted, NOW));
        Snapshot advanced = service.materialize(restarted, NOW.plusWeeks(4));
        assertTrue(advanced.tasks().stream().noneMatch(task -> task.id() == 1));
        assertEquals(0, advanced.tasks().getFirst().remainingMinutes());
        assertEquals(changed.tasks(), advanced.tasks().subList(0, changed.tasks().size()));
        Snapshot removed = service.delete(advanced, 1);
        assertEquals(advanced.tasks(), removed.tasks());
        assertEquals(2, removed.nextRecurringTaskId());
        assertSame(removed, service.materialize(removed, NOW.plusYears(3)));
        assertEquals(removed, codec.decode(codec.encode(removed)));
    }

    @Test
    void materialize_exhaustion_rejectsWholeBatchAndLeavesSourceCountersAndCursors() {
        Snapshot source = state(List.of(template(1, NOW.plusDays(1), Optional.empty())), Integer.MAX_VALUE - 1, 2);
        assertThrows(ValidationException.class, () -> service.materialize(source, NOW));
        assertEquals(Integer.MAX_VALUE - 1, source.nextTaskId());
        assertTrue(source.recurringTasks().getFirst().generatedThrough().isEmpty());
        Snapshot one = state(List.of(template(1, NOW.plusDays(1), Optional.of(NOW.plusDays(1).toLocalDate()))),
                Integer.MAX_VALUE - 1, 2);
        Snapshot finalId = service.materialize(one, NOW);
        assertEquals(Integer.MAX_VALUE - 1, finalId.tasks().getFirst().id());
        assertEquals(Integer.MAX_VALUE, finalId.nextTaskId());
        assertSame(finalId, service.materialize(finalId, NOW));
        assertThrows(ValidationException.class, () -> service.add(state(List.of(), 1, Integer.MAX_VALUE),
                "Quiz", NOW.plusDays(1), 30, Optional.empty(), TaskPriority.NORMAL, NOW));
        Snapshot exhausted = state(List.of(), Integer.MAX_VALUE, 1);
        assertThrows(ValidationException.class, () -> service.add(exhausted, "Quiz", NOW.plusDays(1), 30,
                Optional.empty(), TaskPriority.NORMAL, NOW));
        assertTrue(exhausted.recurringTasks().isEmpty());
        assertEquals(Integer.MAX_VALUE - 1, service.add(state(List.of(), 1, Integer.MAX_VALUE - 1), "Future",
                NOW.plusYears(2), 30, Optional.empty(), TaskPriority.NORMAL, NOW).recurringTasks().getFirst().id());
    }

    @Test
    void materialize_combinedBatchExhaustion_preservesAllCursorsAndAllowsValidRetry() {
        RecurringTask first = template(1, NOW.plusDays(1), Optional.of(NOW.plusDays(1).toLocalDate()));
        RecurringTask second = template(2, NOW.plusDays(2), Optional.of(NOW.plusDays(2).toLocalDate()));
        int lastId = Integer.MAX_VALUE - 1;
        for (RecurringTask template : List.of(first, second)) {
            assertEquals(lastId, service.materialize(state(List.of(template), lastId, 3), NOW)
                    .tasks().getFirst().id());
        }
        Snapshot source = state(List.of(second, first), lastId, 3);
        assertThrows(ValidationException.class, () -> service.materialize(source, NOW));
        assertEquals(List.of(second, first), source.recurringTasks());
        assertTrue(source.recurringTasks().stream().allMatch(template -> template.generatedThrough().isEmpty()));
        assertTrue(source.tasks().isEmpty());
        assertEquals(lastId, source.nextTaskId());
        assertEquals(3, source.nextRecurringTaskId());

        Snapshot retry = service.materialize(source, first.firstDeadline());
        assertEquals(List.of(lastId), retry.tasks().stream().map(Task::id).toList());
        assertEquals(2, retry.tasks().getFirst().occurrence().orElseThrow().templateId());
        assertEquals(Integer.MAX_VALUE, retry.nextTaskId());
        assertEquals(Optional.of(second.firstDeadline()), retry.recurringTasks().getFirst().generatedThrough());
        assertEquals(Optional.of(first.firstDeadline()), retry.recurringTasks().getLast().generatedThrough());
    }

    @Test
    void add_pendingOldAndNewOccurrences_rejectsCombinedExhaustionWithoutConsumingEitherId() {
        LocalDateTime oldDue = NOW.plusDays(1);
        LocalDateTime newDue = NOW.plusDays(3);
        RecurringTask old = template(1, oldDue, Optional.of(oldDue.toLocalDate()));
        Snapshot source = state(List.of(old), Integer.MAX_VALUE - 1, 2);
        assertThrows(ValidationException.class, () -> service.add(source, "New", newDue, 45,
                Optional.of(newDue.toLocalDate()), TaskPriority.HIGH, NOW));
        assertEquals(List.of(old), source.recurringTasks());
        assertTrue(source.tasks().isEmpty());
        assertEquals(Integer.MAX_VALUE - 1, source.nextTaskId());
        assertEquals(2, source.nextRecurringTaskId());

        Snapshot retry = service.add(source, "New", newDue, 45, Optional.of(newDue.toLocalDate()),
                TaskPriority.HIGH, NOW.plusDays(2));
        assertEquals(List.of(Integer.MAX_VALUE - 1), retry.tasks().stream().map(Task::id).toList());
        assertEquals(2, retry.tasks().getFirst().occurrence().orElseThrow().templateId());
        assertEquals(Optional.of(oldDue), retry.recurringTasks().getFirst().generatedThrough());
        assertEquals(Optional.of(newDue), retry.recurringTasks().getLast().generatedThrough());
        assertEquals(Integer.MAX_VALUE, retry.nextTaskId());
        assertEquals(3, retry.nextRecurringTaskId());
    }

    @Test
    void materialize_year9999_doesNotGenerateOutsideSupportedYears() {
        LocalDateTime first = LocalDateTime.of(9999, 12, 24, 23, 59);
        Snapshot result = service.materialize(state(List.of(template(1, first, Optional.empty())), 1, 2),
                first.minusDays(1));
        assertEquals(2, result.tasks().size());
        assertEquals(Optional.of(first.plusWeeks(1)), result.recurringTasks().getFirst().generatedThrough());
        assertSame(result, service.materialize(result, first.plusDays(7)));
    }

    @Test
    void methods_invalidAndNullInputs_rejectWithoutCandidates() {
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Quiz", NOW, 30,
                Optional.empty(), TaskPriority.NORMAL, NOW));
        assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), 1));
        assertThrows(NullPointerException.class, () -> service.materialize(null, NOW));
        assertThrows(NullPointerException.class, () -> service.materialize(Snapshot.empty(), null));
        assertThrows(NullPointerException.class, () -> service.list(null));
        assertThrows(NullPointerException.class, () -> service.delete(null, 1));
    }

    /** Builds an unprocessed fixed-time template for boundary and ordering scenarios. */
    private RecurringTask template(int id, LocalDateTime first, Optional<LocalDate> until) {
        return new RecurringTask(id, "Quiz", first, until, 30, TaskPriority.NORMAL, Optional.empty());
    }

    /** Builds an isolated recurrence snapshot with explicit counters. */
    private Snapshot state(List<RecurringTask> templates, int nextTask, int nextRecurring) {
        return new Snapshot(List.of(), List.of(), nextTask, 1, Snapshot.empty().studyHours(), templates, nextRecurring);
    }
}
// @@author
