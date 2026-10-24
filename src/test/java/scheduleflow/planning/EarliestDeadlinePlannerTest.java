package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskPriority;
import scheduleflow.task.TaskService;

/**
 * Checks deterministic slot allocation, deadline handling and minute conservation.
 */
class EarliestDeadlinePlannerTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime EARLY_MORNING = MONDAY.atTime(7, 0);
    /**
     * Records the fourteen-hour daily study window promised in the User Guide.
     */
    private static final int STUDY_MINUTES_PER_DAY = 14 * 60;
    private final EarliestDeadlinePlanner planner = new EarliestDeadlinePlanner();

    // @@author GabrielYuSP
    @Test
    void generate_customHours_respectsDatePriorityRecurrenceAndCommitments() {
        Snapshot initial = snapshot(List.of(task(1, "Draft", MONDAY.plusDays(8).atStartOfDay(), 180)),
                List.of(new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(6, 0), 30)));
        var hours = initial.studyHours().withWindow("monday",
                new StudyWindow(LocalTime.of(6, 0), LocalTime.of(7, 0)))
                .withWindow(MONDAY.toString(), new StudyWindow(LocalTime.of(22, 0), LocalTime.of(23, 30)));
        Plan result = planner.generate(initial.withStudyHours(hours), MONDAY.atTime(5, 0));
        assertEquals(new StudySession(1, MONDAY.atTime(22, 0), MONDAY.atTime(23, 30)),
                result.sessions().getFirst());
        assertEquals(new StudySession(1, MONDAY.plusDays(1).atTime(8, 0), MONDAY.plusDays(1).atTime(9, 30)),
                result.sessions().getLast());
        Plan followingWeek = planner.generate(initial.withStudyHours(hours), MONDAY.plusWeeks(1).atTime(5, 0));
        assertEquals(List.of(new StudySession(1, MONDAY.plusWeeks(1).atTime(6, 30),
                MONDAY.plusWeeks(1).atTime(7, 0))), followingWeek.sessions());
        assertEquals(150, followingWeek.unallocated().getFirst().minutes());
        assertEquals(180, initial.tasks().getFirst().remainingMinutes());
    }

    @Test
    void generate_customCutoff_roundsAndClampsToWindow() {
        Snapshot initial = snapshot(List.of(task(1, "Draft", MONDAY.atTime(20, 0), 60)), List.of());
        Snapshot source = initial.withStudyHours(initial.studyHours().withWindow("monday",
                new StudyWindow(LocalTime.of(14, 0), LocalTime.of(15, 0))));
        assertEquals(MONDAY.atTime(14, 0), planner.generate(source, MONDAY.atTime(13, 0))
                .sessions().getFirst().start());
        Plan rounded = planner.generate(source, MONDAY.atTime(14, 0, 1));
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(14, 30), MONDAY.atTime(15, 0))), rounded.sessions());
        assertEquals(30, rounded.unallocated().getFirst().minutes());
        assertTrue(planner.generate(source, MONDAY.atTime(15, 0)).sessions().isEmpty());
    }

    // @@author
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
        assertEquals(List.of(new UnallocatedWork(1, "Essay", task.deadline().orElseThrow(), 60,
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
                new UnallocatedWork(1, "Overdue", overdue.deadline().orElseThrow(), 30,
                        UnallocatedReason.DEADLINE_PASSED),
                new UnallocatedWork(2, "Future", afterHorizon.deadline().orElseThrow(), 60,
                        UnallocatedReason.OUTSIDE_HORIZON)),
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

    @Test
    void generate_workAcrossDays_preservesSessionsAndPartialRemainder() {
        LocalDateTime now = MONDAY.atTime(21, 0);
        Task task = task(1, "Essay", MONDAY.plusDays(1).atTime(9, 0), 150);
        Snapshot source = snapshot(List.of(task), List.of());

        Plan result = planner.generate(source, now);

        assertEquals(List.of(
                new StudySession(1, MONDAY.atTime(21, 0), MONDAY.atTime(22, 0)),
                new StudySession(1, MONDAY.plusDays(1).atTime(8, 0), MONDAY.plusDays(1).atTime(9, 0))),
                result.sessions());
        assertEquals(List.of(new UnallocatedWork(1, "Essay", task.deadline().orElseThrow(), 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(150, source.tasks().getFirst().remainingMinutes());
        assertSame(source, result.source());
    }

    @Test
    void generate_leapDayHorizon_usesEveryFutureWindowThroughFinalDate() {
        LocalDateTime now = LocalDate.of(2024, 2, 29).atTime(22, 0);
        LocalDateTime deadline = LocalDate.of(2025, 2, 28).atStartOfDay();
        int futureDays = Math.toIntExact(Duration.between(now.toLocalDate().plusDays(1).atStartOfDay(),
                deadline).toDays());
        int availableMinutes = futureDays * STUDY_MINUTES_PER_DAY;
        Task task = task(1, "Long project", deadline, availableMinutes + 30);
        Snapshot source = snapshot(List.of(task), List.of());

        Plan result = planner.generate(source, now);

        assertEquals(deadline, result.horizonEnd());
        assertEquals(futureDays, result.sessions().size());
        assertEquals(LocalDate.of(2024, 3, 1).atTime(8, 0), result.sessions().getFirst().start());
        assertEquals(LocalDate.of(2025, 2, 27).atTime(22, 0), result.sessions().getLast().end());
        assertEquals(availableMinutes, result.sessions().stream().mapToInt(StudySession::minutes).sum());
        assertTrue(result.sessions().stream().allMatch(session -> session.minutes() == STUDY_MINUTES_PER_DAY));
        assertEquals(List.of(new UnallocatedWork(1, "Long project", deadline, 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(availableMinutes + 30, source.tasks().getFirst().remainingMinutes());
    }

    // @@author nickpatt
    @Test
    void generate_lifecycleCandidates_consumesRevisedWorkWithoutChangingFrozenPlan() {
        TaskService tasks = new TaskService();
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(8, 30), 45)), List.of());
        Plan frozen = planner.generate(source, EARLY_MORNING);
        Snapshot progressed = tasks.recordWork(source, 1, 30);
        Plan revised = planner.generate(tasks.reviseRemaining(progressed, 1, 1), EARLY_MORNING);
        assertEquals(1, revised.sessions().getFirst().workMinutes());
        assertTrue(revised.unallocated().isEmpty());
        Snapshot completed = tasks.complete(progressed, 1);
        assertTrue(planner.generate(completed, EARLY_MORNING).sessions().isEmpty());
        Snapshot reopened = tasks.reopen(completed, 1, 45);
        Plan overdue = planner.generate(reopened, MONDAY.atTime(9, 0));
        assertEquals(UnallocatedReason.DEADLINE_PASSED, overdue.unallocated().getFirst().reason());
        assertEquals(45, overdue.unallocated().getFirst().minutes());
        assertSame(source, frozen.source());
        assertEquals(15, frozen.unallocated().getFirst().minutes());
    }

    @Test
    void generate_exactWork45_reserves60AndPartial30Leaves15() {
        Task task = task(1, "Draft", MONDAY.atTime(9, 0), 45);
        Snapshot source = snapshot(List.of(task), List.of());
        Plan full = planner.generate(source, EARLY_MORNING);
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(9, 0), 45)), full.sessions());
        assertTrue(full.unallocated().isEmpty());
        Snapshot partialSource = source.withStudyHours(source.studyHours().withWindow("monday",
                new StudyWindow(LocalTime.of(8, 0), LocalTime.of(8, 30))));
        Plan partial = planner.generate(partialSource, EARLY_MORNING);
        assertEquals(30, partial.sessions().getFirst().minutes());
        assertEquals(30, partial.sessions().getFirst().workMinutes());
        assertEquals(15, partial.unallocated().getFirst().minutes());
        assertEquals(30L, partial.unallocated().getFirst().capacityMinutes());
        assertEquals(45, task.remainingMinutes());
        assertSame(partialSource.studyHours(), partial.source().studyHours());
    }

    @Test
    void generate_splitExactWork_onlyFinalSessionContainsPadding() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(10, 0), 45)),
                List.of(new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(8, 30), 30)));
        Plan plan = planner.generate(source, EARLY_MORNING);
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30), 30),
                new StudySession(1, MONDAY.atTime(9, 0), MONDAY.atTime(9, 30), 15)), plan.sessions());
        assertEquals(45L, plan.sessions().stream().mapToLong(StudySession::workMinutes).sum());
        assertEquals(60L, plan.sessions().stream().mapToLong(StudySession::minutes).sum());
    }

    @Test
    void generate_mixedDeadlinesPrioritiesAndCompletion_ordersAndExcludesCorrectly() {
        LocalDateTime deadline = MONDAY.atTime(8, 30);
        Task completed = new Task(1, "Old completed", EARLY_MORNING.minusYears(1), 0);
        Task normal = new Task(2, "Normal", deadline, 30);
        Task high = new Task(10, "High", Optional.of(deadline), 30, TaskPriority.HIGH);
        Task undated = Task.withoutDeadline(3, "Undated", 1);
        Task highUndated = new Task(4, "Undated high", Optional.empty(), 1, TaskPriority.HIGH);
        Snapshot source = snapshot(List.of(undated, normal, completed, highUndated, high), List.of());
        Plan plan = planner.generate(source, EARLY_MORNING);
        assertEquals(List.of(10, 4, 3), plan.sessions().stream().map(StudySession::taskId).toList());
        assertEquals(List.of(2), plan.unallocated().stream().map(UnallocatedWork::taskId).toList());
        assertEquals(plan, planner.generate(source, EARLY_MORNING));
        assertEquals(0, completed.remainingMinutes());
        assertTrue(planner.generate(snapshot(List.of(completed), List.of()), EARLY_MORNING).sessions().isEmpty());
        assertTrue(planner.generate(snapshot(List.of(completed), List.of()), EARLY_MORNING).unallocated().isEmpty());
    }

    @Test
    void generate_largeUndatedDemand_usesLongCapacityAndNeverAssignsDeadlineReasons() {
        Task dated = task(1, "Dated", EARLY_MORNING, Integer.MAX_VALUE);
        Task undated = Task.withoutDeadline(2, "Undated", Integer.MAX_VALUE);
        Task second = Task.withoutDeadline(3, "Second", Integer.MAX_VALUE);
        Plan plan = planner.generate(snapshot(List.of(second, undated, dated), List.of()), EARLY_MORNING);
        assertEquals(List.of(1, 2, 3), plan.unallocated().stream().map(UnallocatedWork::taskId).toList());
        assertEquals(2147483670L, plan.unallocated().getFirst().capacityMinutes());
        assertEquals(UnallocatedReason.DEADLINE_PASSED, plan.unallocated().getFirst().reason());
        for (UnallocatedWork work : plan.unallocated().subList(1, 3)) {
            assertEquals(Optional.empty(), work.deadline());
            assertEquals(UnallocatedReason.INSUFFICIENT_CAPACITY, work.reason());
        }
        long allocatedCapacity = plan.sessions().stream().mapToLong(StudySession::minutes).sum();
        long allocatedWork = plan.sessions().stream().mapToLong(StudySession::workMinutes).sum();
        assertEquals(3L * Integer.MAX_VALUE,
                allocatedWork + plan.unallocated().stream().mapToLong(UnallocatedWork::minutes).sum());
        assertEquals(3L * 2147483670L,
                allocatedCapacity + plan.unallocated().stream().mapToLong(UnallocatedWork::capacityMinutes).sum());
    }
    // @@author

    private static Task task(int id, String name, LocalDateTime deadline, int minutes) {
        return new Task(id, name, deadline, minutes);
    }

    private static Snapshot snapshot(List<Task> tasks, List<Commitment> commitments) {
        return new Snapshot(tasks, commitments, tasks.stream().mapToInt(Task::id).max().orElse(0) + 1,
                commitments.stream().mapToInt(Commitment::id).max().orElse(0) + 1);
    }
}
