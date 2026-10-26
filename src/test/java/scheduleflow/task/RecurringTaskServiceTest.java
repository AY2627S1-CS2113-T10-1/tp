// @@author GabrielYuSP
package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.StudyPolicy;
import scheduleflow.planning.TimeRules;

/** Verifies deterministic bounded generation, atomic exhaustion and persistent deletion history. */
class RecurringTaskServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private final RecurringTaskService service = new RecurringTaskService();

    @Test
    void mutations_storedPolicy_preservesSettingThroughAddMaterializationAndDeletion() {
        Optional<StudyPolicy> policy = Optional.of(new StudyPolicy(90, 30));
        Snapshot source = Snapshot.empty().withStudyPolicy(policy);
        Snapshot added = service.add(source, "Weekly", NOW.plusDays(1), 60,
                Optional.of(NOW.plusDays(8).toLocalDate()), NOW);
        assertEquals(policy, added.studyPolicy());
        assertEquals(policy, service.delete(added, 1).studyPolicy());
        RecurringTask pending = new RecurringTask(1, "Pending", NOW.plusDays(1),
                Optional.of(NOW.plusDays(1).toLocalDate()), 30, Optional.empty());
        Snapshot unprocessed = new Snapshot(List.of(), List.of(), 1, 1, source.studyHours(), List.of(pending), 2,
                policy);
        Snapshot generated = service.materialize(unprocessed, NOW);
        assertEquals(policy, generated.studyPolicy());
        assertEquals(1, generated.tasks().size());
        assertSame(generated, service.materialize(generated, NOW));
    }

    @Test
    void materialize_ties_assignsChronologicalThenNumericTemplateIds() {
        Snapshot source = state(List.of(template(10, NOW.plusDays(1), Optional.empty()),
                template(2, NOW.plusDays(1), Optional.empty()),
                template(3, NOW.plusHours(1), Optional.empty())), 7, 11);
        Snapshot result = service.materialize(source, NOW);
        assertEquals(List.of(3, 2, 10, 3, 2, 10), result.tasks().stream().limit(6)
                .map(task -> task.occurrence().orElseThrow().templateId()).toList());
        assertEquals(List.of(7, 8, 9, 10, 11, 12), result.tasks().stream().limit(6).map(Task::id).toList());
        assertEquals(result, service.materialize(source, NOW));
        assertSame(result, service.materialize(result, NOW));
        assertTrue(source.tasks().isEmpty());
        assertEquals(List.of(2, 3, 10), service.list(result).stream().map(RecurringTask::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> service.list(result).clear());
    }

    @Test
    void add_horizonAndUntil_includesBoundaryAndDefersFutureRelease() {
        LocalDateTime horizon = TimeRules.horizonEnd(NOW);
        Snapshot boundary = service.add(Snapshot.empty(), " Quiz ", horizon, 60,
                Optional.of(horizon.toLocalDate()), NOW);
        assertEquals(Optional.of(horizon), boundary.tasks().getFirst().deadline());
        assertEquals(horizon.minusWeeks(1), boundary.tasks().getFirst().occurrence().orElseThrow().release());
        assertEquals("Quiz", boundary.tasks().getFirst().name());
        Snapshot beyond = service.add(Snapshot.empty(), "Later", horizon.plusMinutes(1), 30,
                Optional.empty(), NOW);
        assertTrue(beyond.tasks().isEmpty());
        assertTrue(beyond.recurringTasks().getFirst().generatedThrough().isEmpty());
        Snapshot bounded = service.add(Snapshot.empty(), "Quiz", NOW.plusDays(1), 60,
                Optional.of(NOW.plusDays(11).toLocalDate()), NOW);
        assertEquals(2, bounded.tasks().size());
        assertEquals(Optional.of(NOW.plusDays(8)), bounded.recurringTasks().getFirst().generatedThrough());
        assertSame(bounded, service.materialize(bounded, NOW.plusYears(3)));
    }

    @Test
    void materialize_millenniaMissed_skipsArithmeticallyWithoutBackfill() {
        LocalDateTime first = LocalDateTime.of(1, 1, 8, 12, 0);
        Snapshot result = assertTimeout(Duration.ofSeconds(2), () -> service.materialize(
                state(List.of(template(1, first, Optional.empty())), 1, 2), NOW));
        assertTrue(result.tasks().size() <= 53);
        assertTrue(result.tasks().stream().allMatch(task -> task.deadline().orElseThrow().isAfter(NOW)));
        Snapshot expired = service.materialize(state(List.of(template(1, first,
                Optional.of(first.plusDays(16).toLocalDate()))), Integer.MAX_VALUE, 2), NOW);
        assertTrue(expired.tasks().isEmpty());
        assertEquals(Optional.of(first.plusWeeks(2)), expired.recurringTasks().getFirst().generatedThrough());
        assertSame(expired, service.materialize(expired, first.minusDays(1)));
    }

    @Test
    void materialize_dueNowAndDeletion_neverRecreatesProcessedOccurrences() {
        Snapshot generated = service.materialize(state(List.of(template(1, NOW, Optional.empty())), 1, 2), NOW);
        assertEquals(Optional.of(NOW.plusWeeks(1)), generated.tasks().getFirst().deadline());
        Snapshot deleted = new TaskService().delete(generated, 1);
        assertSame(deleted, service.materialize(deleted, NOW));
        Snapshot advanced = service.materialize(deleted, NOW.plusWeeks(4));
        assertEquals(deleted.tasks(), advanced.tasks().subList(0, deleted.tasks().size()));
        Snapshot removed = service.delete(advanced, 1);
        assertEquals(advanced.tasks(), removed.tasks());
        assertEquals(2, removed.nextRecurringTaskId());
        assertSame(removed, service.materialize(removed, NOW.plusYears(3)));
    }

    @Test
    void materialize_combinedExhaustion_isAtomicAndAllowsRetry() {
        RecurringTask first = template(1, NOW.plusDays(1), Optional.of(NOW.plusDays(1).toLocalDate()));
        RecurringTask second = template(2, NOW.plusDays(2), Optional.of(NOW.plusDays(2).toLocalDate()));
        Snapshot source = state(List.of(second, first), Integer.MAX_VALUE - 1, 3);
        assertThrows(ValidationException.class, () -> service.materialize(source, NOW));
        assertTrue(source.tasks().isEmpty());
        assertEquals(List.of(second, first), source.recurringTasks());
        Snapshot retry = service.materialize(source, first.firstDeadline());
        assertEquals(Integer.MAX_VALUE, retry.nextTaskId());
        assertEquals(2, retry.tasks().getFirst().occurrence().orElseThrow().templateId());
        assertSame(retry, service.materialize(retry, NOW));
        assertThrows(ValidationException.class, () -> service.add(state(List.of(first),
                Integer.MAX_VALUE - 1, 2), "New", NOW.plusDays(2), 30,
                Optional.of(NOW.plusDays(2).toLocalDate()), NOW));
        assertThrows(ValidationException.class, () -> service.add(state(List.of(), 1, Integer.MAX_VALUE),
                "New", NOW.plusDays(1), 30, Optional.empty(), NOW));
    }

    @Test
    void materialize_leapAndLastYear_respectsSupportedCalendarBoundaries() {
        LocalDateTime leap = LocalDateTime.of(2028, 2, 29, 7, 0);
        LocalDateTime horizon = TimeRules.horizonEnd(leap);
        Snapshot result = service.materialize(state(List.of(template(1, horizon.minusWeeks(1),
                Optional.empty())), 1, 2), leap);
        assertEquals(List.of(Optional.of(horizon.minusWeeks(1)), Optional.of(horizon)),
                result.tasks().stream().map(Task::deadline).toList());
        LocalDateTime first = LocalDateTime.of(9999, 12, 24, 23, 59);
        Snapshot last = service.materialize(state(List.of(template(1, first, Optional.empty())), 1, 2),
                first.minusDays(1));
        assertEquals(2, last.tasks().size());
        assertSame(last, service.materialize(last, first.plusWeeks(1)));
    }

    @Test
    void methods_invalidInputs_reject() {
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Quiz", NOW, 30,
                Optional.empty(), NOW));
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Quiz", NOW.plusDays(1), 45,
                Optional.empty(), NOW));
        assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), 0));
        assertThrows(ValidationException.class, () -> service.materialize(Snapshot.empty(), NOW.withYear(0)));
        assertThrows(NullPointerException.class, () -> service.materialize(null, NOW));
        assertThrows(NullPointerException.class, () -> service.materialize(Snapshot.empty(), null));
        assertThrows(NullPointerException.class, () -> service.add(Snapshot.empty(), "Quiz", NOW.plusDays(1), 30,
                null, NOW));
        assertThrows(NullPointerException.class, () -> service.list(null));
        assertThrows(NullPointerException.class, () -> service.delete(null, 1));
    }

    /** Builds an unprocessed template with a fixed estimate. */
    private RecurringTask template(int id, LocalDateTime first, Optional<LocalDate> until) {
        return new RecurringTask(id, "Quiz", first, until, 30, Optional.empty());
    }

    /** Builds a recurrence snapshot with explicit counters. */
    private Snapshot state(List<RecurringTask> templates, int nextTask, int nextRecurring) {
        return new Snapshot(List.of(), List.of(), nextTask, 1, Snapshot.empty().studyHours(), templates, nextRecurring);
    }
}
// @@author
