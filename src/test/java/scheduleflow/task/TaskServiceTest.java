package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.TimeRules;

/**
 * Verifies task candidates, deadline limits and stable identity without I/O or a live clock.
 */
class TaskServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final LocalDateTime DEADLINE = NOW.plusDays(1).withHour(18);
    private static final Commitment LECTURE = new Commitment(3, "Lecture", DayOfWeek.MONDAY,
            LocalTime.of(10, 0), 120);
    private final TaskService service = new TaskService();

    @Test
    void add_validTask_appendsAndPreservesOtherRecords() {
        Task existing = new Task(2, "Existing", DEADLINE, 30);
        Snapshot original = new Snapshot(List.of(existing), List.of(LECTURE), 5, 8);

        Snapshot candidate = service.add(original, "  Draft  ", DEADLINE, 180, NOW);

        assertEquals(List.of(existing, new Task(5, "Draft", DEADLINE, 180)), candidate.tasks());
        assertEquals(original.commitments(), candidate.commitments());
        assertEquals(6, candidate.nextTaskId());
        assertEquals(8, candidate.nextCommitmentId());
        assertEquals(new Snapshot(List.of(existing), List.of(LECTURE), 5, 8), original);
        assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
    }

    @Test
    void add_duplicateNames_allocatesDistinctIds() {
        Snapshot first = service.add(Snapshot.empty(), "Draft", DEADLINE, 30, NOW);
        Snapshot second = service.add(first, "Draft", DEADLINE, 60, NOW);

        assertEquals(List.of(new Task(1, "Draft", DEADLINE, 30), new Task(2, "Draft", DEADLINE, 60)),
                second.tasks());
        assertEquals(3, second.nextTaskId());
        assertEquals(1, first.tasks().size());
    }

    @Test
    void add_invalidNames_rejectsWithoutConsumingId() {
        Snapshot original = Snapshot.empty();
        for (String name : new String[] {null, "", "   ", "A/B", "A\tB", "A\nB"}) {
            assertThrows(ValidationException.class, () -> service.add(original, name, DEADLINE, 30, NOW));
            assertEquals(Snapshot.empty(), original);
        }
        assertEquals(1, service.add(original, "Valid", DEADLINE, 30, NOW).tasks().getFirst().id());
    }

    @Test
    void add_invalidDurations_rejectsWithoutChanges() {
        Snapshot original = Snapshot.empty();
        for (int minutes : new int[] {-30, 0, 1, 45, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> service.add(original, "Draft", DEADLINE, minutes, NOW));
            assertEquals(Snapshot.empty(), original);
        }
    }

    @Test
    void add_largeSlotMultiple_preservesWholeEstimate() {
        int minutes = Integer.MAX_VALUE - Integer.MAX_VALUE % TimeRules.SLOT_MINUTES;
        Snapshot candidate = service.add(Snapshot.empty(), "Large", DEADLINE, minutes, NOW);

        assertEquals(minutes, candidate.tasks().getFirst().remainingMinutes());
    }

    @Test
    void add_invalidDeadlines_rejectsWithoutChanges() {
        Snapshot original = Snapshot.empty();
        for (LocalDateTime deadline : new LocalDateTime[] {null, NOW.minusMinutes(1), NOW,
                TimeRules.horizonEnd(NOW).plusMinutes(1), DEADLINE.withSecond(1), DEADLINE.withNano(1),
                DEADLINE.withYear(0), DEADLINE.withYear(10000)}) {
            assertThrows(ValidationException.class, () -> service.add(original, "Draft", deadline, 30, NOW));
            assertEquals(Snapshot.empty(), original);
        }
    }

    @Test
    void add_deadlineBoundaries_acceptsNextMinuteAndExactHorizon() {
        for (LocalDateTime deadline : List.of(NOW.plusMinutes(1), TimeRules.horizonEnd(NOW))) {
            assertEquals(deadline, service.add(Snapshot.empty(), "Draft", deadline, 30, NOW)
                    .tasks().getFirst().deadline());
        }
        LocalDateTime preciseNow = NOW.plusSeconds(1).plusNanos(1);
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Draft", NOW, 30, preciseNow));
        assertEquals(NOW.plusMinutes(1), service.add(Snapshot.empty(), "Draft", NOW.plusMinutes(1), 30, preciseNow)
                .tasks().getFirst().deadline());
    }

    @Test
    void add_leapDay_usesCalendarYearBoundary() {
        LocalDateTime leapNow = LocalDateTime.of(2028, 2, 29, 12, 0);
        LocalDateTime horizon = LocalDateTime.of(2029, 2, 28, 0, 0);

        assertEquals(horizon, service.add(Snapshot.empty(), "Draft", horizon, 30, leapNow)
                .tasks().getFirst().deadline());
        assertThrows(ValidationException.class,
                () -> service.add(Snapshot.empty(), "Draft", horizon.plusMinutes(1), 30, leapNow));
    }

    @Test
    void add_exhaustedCounter_rejectsWithoutOverflow() {
        Snapshot penultimate = new Snapshot(List.of(), List.of(LECTURE), Integer.MAX_VALUE - 1, 8);
        Snapshot last = service.add(penultimate, "Last", DEADLINE, 30, NOW);

        assertEquals(Integer.MAX_VALUE - 1, last.tasks().getFirst().id());
        assertEquals(Integer.MAX_VALUE, last.nextTaskId());
        assertThrows(ValidationException.class, () -> service.add(last, "Overflow", DEADLINE, 30, NOW));
        assertEquals(1, last.tasks().size());
        assertEquals(Integer.MAX_VALUE, last.nextTaskId());
        Snapshot exhaustedEmpty = service.delete(last, Integer.MAX_VALUE - 1);
        assertThrows(ValidationException.class, () -> service.add(exhaustedEmpty, "Overflow", DEADLINE, 30, NOW));
    }

    @Test
    void delete_existingId_preservesOrderCountersAndCommitments() {
        Task first = new Task(10, "First", DEADLINE, 30);
        Task removed = new Task(2, "Removed", DEADLINE, 60);
        Task last = new Task(7, "Last", DEADLINE, 90);
        Snapshot original = new Snapshot(List.of(first, removed, last), List.of(LECTURE), 11, 8);

        Snapshot candidate = service.delete(original, 2);

        assertEquals(new Snapshot(List.of(first, last), List.of(LECTURE), 11, 8), candidate);
        assertEquals(List.of(first, removed, last), original.tasks());
        assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
    }

    @Test
    void delete_unknownId_reportsIdentifierWithoutChanges() {
        Snapshot original = service.add(Snapshot.empty(), "Draft", DEADLINE, 30, NOW);
        for (int id : new int[] {-1, 0, 2, Integer.MAX_VALUE}) {
            ValidationException exception = assertThrows(ValidationException.class, () -> service.delete(original, id));
            assertEquals("Unknown task ID: T" + id, exception.getMessage());
            assertEquals(List.of(new Task(1, "Draft", DEADLINE, 30)), original.tasks());
            assertEquals(2, original.nextTaskId());
        }
        assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), 1));
    }

    @Test
    void delete_lastTask_nextAddDoesNotReuseId() {
        Snapshot first = service.add(Snapshot.empty(), "First", DEADLINE, 30, NOW);
        Snapshot deleted = service.delete(first, 1);
        Snapshot next = service.add(deleted, "Next", DEADLINE, 30, NOW);

        assertEquals(new Snapshot(List.of(), List.of(), 2, 1), deleted);
        assertEquals(2, next.tasks().getFirst().id());
        assertEquals(3, next.nextTaskId());
    }

    @Test
    void list_unsortedAndOverdueTasks_returnsImmutableNumericOrder() {
        Task ten = new Task(10, "Ten", DEADLINE, 90);
        Task two = new Task(2, "Overdue", NOW.minusYears(1), 60);
        Task seven = new Task(7, "Seven", DEADLINE, 30);
        Snapshot original = new Snapshot(List.of(ten, two, seven), List.of(LECTURE), 11, 8);

        List<Task> listed = service.list(original);

        assertEquals(List.of(two, seven, ten), listed);
        assertEquals(List.of(ten, two, seven), original.tasks());
        assertThrows(UnsupportedOperationException.class, () -> listed.clear());
        assertEquals(List.of(), service.list(Snapshot.empty()));
    }
}
