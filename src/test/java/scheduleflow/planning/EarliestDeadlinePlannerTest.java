package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Checks deterministic slot allocation, deadline handling and minute conservation.
 */
class EarliestDeadlinePlannerTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime EARLY_MORNING = MONDAY.atTime(7, 0);
    private final EarliestDeadlinePlanner planner = new EarliestDeadlinePlanner();

    @Test
    void generate_emptySnapshot_returnsCompletePlanWithSource() {
        Commitment commitment = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60);
        Snapshot source = snapshot(List.of(), List.of(commitment));

        Plan result = planner.generate(source, EARLY_MORNING);

        assertSame(source, result.source());
        assertEquals(TimeRules.horizonEnd(EARLY_MORNING), result.horizonEnd());
        assertTrue(result.sessions().isEmpty());
        assertTrue(result.unallocated().isEmpty());
        assertTrue(result.isComplete());
        assertEquals(List.of(commitment), result.source().commitments());
    }

    @Test
    void generate_allocatesEarliestSlotsAndMergesAdjacentSlots() {
        Task task = task(1, "Essay", MONDAY.atTime(12, 0), 60);
        Snapshot source = snapshot(List.of(task), List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(9, 0))), result.sessions());
        assertTrue(result.unallocated().isEmpty());
        assertEquals(60, task.remainingMinutes());
    }

    @Test
    void generate_skipsCommitmentSlotsAndMergesAcrossNoBlockedTime() {
        Task task = task(1, "Essay", MONDAY.atTime(11, 0), 90);
        Commitment commitment = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 30), 60);
        Snapshot source = snapshot(List.of(task), List.of(commitment));

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(
                new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30)),
                new StudySession(1, MONDAY.atTime(9, 30), MONDAY.atTime(10, 30))), result.sessions());
        assertTrue(result.unallocated().isEmpty());
    }

    @Test
    void generate_equalDeadlines_usesNumericTaskIdOrder() {
        LocalDateTime deadline = MONDAY.atTime(9, 0);
        Snapshot source = snapshot(List.of(task(10, "Later ID", deadline, 30), task(2, "Earlier ID", deadline, 30)),
                List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(
                new StudySession(2, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30)),
                new StudySession(10, MONDAY.atTime(8, 30), MONDAY.atTime(9, 0))), result.sessions());
    }

    @Test
    void generate_deadlineAtSlotEnd_allowsSlotButPreservesPartialRemainder() {
        Task task = task(1, "Essay", MONDAY.atTime(8, 30), 90);
        Snapshot source = snapshot(List.of(task), List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30))), result.sessions());
        assertEquals(List.of(new UnallocatedWork(1, "Essay", task.deadline(), 60,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(90, task.remainingMinutes());
    }

    @Test
    void generate_cutoffRoundsUpAndClassifiesOverdueAndOutOfHorizonTasks() {
        LocalDateTime now = MONDAY.atTime(10, 0, 1);
        Task overdue = task(1, "Overdue", now.withSecond(0), 30);
        Task afterHorizon = task(2, "Future", TimeRules.horizonEnd(now).plusMinutes(1), 60);
        Task today = task(3, "Today", MONDAY.atTime(11, 0), 30);
        Snapshot source = snapshot(List.of(today, afterHorizon, overdue), List.of());

        Plan result = planner.generate(source, now);

        assertEquals(List.of(new StudySession(3, MONDAY.atTime(10, 30), MONDAY.atTime(11, 0))), result.sessions());
        assertEquals(List.of(
                new UnallocatedWork(1, "Overdue", overdue.deadline(), 30, UnallocatedReason.DEADLINE_PASSED),
                new UnallocatedWork(2, "Future", afterHorizon.deadline(), 60, UnallocatedReason.OUTSIDE_HORIZON)),
                result.unallocated());
    }

    @Test
    void generate_isDeterministicAndConservesEveryTaskMinute() {
        Task first = task(1, "First", MONDAY.atTime(9, 0), 90);
        Task second = task(2, "Second", MONDAY.atTime(9, 0), 60);
        Snapshot source = snapshot(List.of(first, second), List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(result, planner.generate(source, EARLY_MORNING));
        for (Task task : source.tasks()) {
            int allocated = result.sessions().stream().filter(session -> session.taskId() == task.id())
                    .mapToInt(StudySession::minutes).sum();
            int remaining = result.unallocated().stream().filter(work -> work.taskId() == task.id())
                    .mapToInt(UnallocatedWork::minutes).sum();
            assertEquals(task.remainingMinutes(), allocated + remaining);
        }
    }

    private static Task task(int id, String name, LocalDateTime deadline, int minutes) {
        return new Task(id, name, deadline, minutes);
    }

    private static Snapshot snapshot(List<Task> tasks, List<Commitment> commitments) {
        return new Snapshot(tasks, commitments, tasks.stream().mapToInt(Task::id).max().orElse(0) + 1,
                commitments.stream().mapToInt(Commitment::id).max().orElse(0) + 1);
    }
}
