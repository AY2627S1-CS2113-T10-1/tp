package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;

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

    // @@author Darren50
    @Test
    void generate_oneTimeCommitment_blocksOnlyItsExactDate() {
        Commitment meeting = new Commitment(1, "Meeting", DayOfWeek.MONDAY, LocalTime.of(8, 0), 90,
                MONDAY, MONDAY);
        Task today = task(1, "Today", MONDAY.atTime(10, 0), 30);
        Plan todayPlan = planner.generate(snapshot(List.of(today), List.of(meeting)), EARLY_MORNING);
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(9, 30), MONDAY.atTime(10, 0))), todayPlan.sessions());

        LocalDate nextMonday = MONDAY.plusWeeks(1);
        Task nextWeek = task(2, "Next week", nextMonday.atTime(10, 0), 30);
        Plan nextPlan = planner.generate(snapshot(List.of(nextWeek), List.of(meeting)), nextMonday.atTime(7, 0));
        assertEquals(List.of(new StudySession(2, nextMonday.atTime(8, 0), nextMonday.atTime(8, 30))),
                nextPlan.sessions());
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
    void generate_boundedCommitment_blocksOnlyInclusiveSemesterDates() {
        Commitment semester = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60,
                MONDAY.plusWeeks(1), MONDAY.plusWeeks(2), Set.of(MONDAY.plusWeeks(1)));
        Task before = task(1, "Before", MONDAY.atTime(9, 0), 30);
        Plan beforeResult = planner.generate(snapshot(List.of(before), List.of(semester)), EARLY_MORNING);
        assertEquals(MONDAY.atTime(8, 0), beforeResult.sessions().getFirst().start());

        LocalDate insideDate = MONDAY.plusWeeks(1);
        Task inside = task(2, "Inside", insideDate.atTime(10, 0), 30);
        Plan insideResult = planner.generate(snapshot(List.of(inside), List.of(semester)), insideDate.atTime(7, 0));
        assertEquals(insideDate.atTime(8, 0), insideResult.sessions().getFirst().start());

        LocalDate blockedDate = MONDAY.plusWeeks(2);
        Task blocked = task(3, "Blocked", blockedDate.atTime(10, 0), 30);
        Plan blockedResult = planner.generate(snapshot(List.of(blocked), List.of(semester)), blockedDate.atTime(7, 0));
        assertEquals(blockedDate.atTime(9, 0), blockedResult.sessions().getFirst().start());
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
        assertEquals(List.of(new UnallocatedWork(1, "Essay", task.deadline(), 30,
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

    // @@author GabrielYuSP
    @Test
    void generate_unavailableStudyPeriod_replansAroundBlockAndDeletionRestoresTime() {
        Snapshot original = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 90)), List.of());
        Plan before = planner.generate(original, EARLY_MORNING);
        CommitmentService commitments = new CommitmentService();
        Snapshot blocked = commitments.add(original, "Unavailable", DayOfWeek.MONDAY,
                LocalTime.of(8, 30), 60, MONDAY, MONDAY);

        Plan after = planner.generate(blocked, EARLY_MORNING);

        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30)),
                new StudySession(1, MONDAY.atTime(9, 30), MONDAY.atTime(10, 30))), after.sessions());
        assertTrue(after.isComplete());
        assertEquals(original.tasks(), blocked.tasks());
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(9, 30))), before.sessions());
        assertTrue(before.source().commitments().isEmpty());

        Snapshot released = commitments.delete(blocked, 1);
        Plan restored = planner.generate(released, EARLY_MORNING);
        assertEquals(before.sessions(), restored.sessions());
        assertTrue(restored.isComplete());
        assertEquals(90, released.tasks().getFirst().remainingMinutes());
        assertEquals(blocked.nextCommitmentId(), released.nextCommitmentId());
    }

    @Test
    void generate_unavailablePeriodBeforeDeadline_preservesPartialWorkAndReportsRemainder() {
        Task draft = task(1, "Draft", MONDAY.atTime(10, 0), 90);
        Snapshot original = snapshot(List.of(draft), List.of());
        Snapshot blocked = new CommitmentService().add(original, "Unavailable", DayOfWeek.MONDAY,
                LocalTime.of(8, 30), 60, MONDAY, MONDAY);

        Plan result = planner.generate(blocked, EARLY_MORNING);

        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30)),
                new StudySession(1, MONDAY.atTime(9, 30), MONDAY.atTime(10, 0))), result.sessions());
        assertEquals(List.of(new UnallocatedWork(1, "Draft", draft.deadline(), 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(draft.remainingMinutes(), result.sessions().stream().mapToInt(StudySession::minutes).sum()
                + result.unallocated().getFirst().minutes());
        assertEquals(90, blocked.tasks().getFirst().remainingMinutes());
    }

    @Test
    void generate_wholeDayUnavailable_reportsAllWorkWithoutMovingItPastDeadline() {
        Task draft = task(1, "Draft", MONDAY.atTime(22, 0), 60);
        Snapshot blocked = new CommitmentService().add(snapshot(List.of(draft), List.of()), "Unavailable",
                DayOfWeek.MONDAY, LocalTime.MIDNIGHT, 1440, MONDAY, MONDAY);

        Plan result = planner.generate(blocked, EARLY_MORNING);

        assertTrue(result.sessions().isEmpty());
        assertEquals(List.of(new UnallocatedWork(1, "Draft", draft.deadline(), 60,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(60, blocked.tasks().getFirst().remainingMinutes());
    }

    @Test
    void generate_savedUnavailablePeriod_respectsRestoredDateOverrideAndReleasesAfterDeletion(
            @TempDir Path directory) throws Exception {
        Snapshot original = snapshot(List.of(task(1, "Draft", MONDAY.atTime(11, 0), 90)), List.of());
        Snapshot customHours = original.withStudyHours(original.studyHours()
                .withWindow("monday", new StudyWindow(LocalTime.of(14, 0), LocalTime.of(16, 0)))
                .withWindow(MONDAY.toString(), new StudyWindow(LocalTime.of(9, 0), LocalTime.of(11, 0))));
        CommitmentService commitments = new CommitmentService();
        Snapshot blocked = commitments.add(customHours, "Unavailable", DayOfWeek.MONDAY,
                LocalTime.of(9, 30), 30, MONDAY, MONDAY);
        Path file = directory.resolve("scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        storage.save(blocked);
        Snapshot loaded = new FileStorage(file, new StateCodec()).load();

        Plan result = planner.generate(loaded, EARLY_MORNING);

        assertEquals(blocked, loaded);
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(9, 0), MONDAY.atTime(9, 30)),
                new StudySession(1, MONDAY.atTime(10, 0), MONDAY.atTime(11, 0))), result.sessions());
        assertTrue(result.isComplete());
        storage.save(commitments.delete(loaded, 1));
        Snapshot released = new FileStorage(file, new StateCodec()).load();
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(9, 0), MONDAY.atTime(10, 30))),
                planner.generate(released, EARLY_MORNING).sessions());
        assertEquals(customHours.studyHours(), released.studyHours());
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
