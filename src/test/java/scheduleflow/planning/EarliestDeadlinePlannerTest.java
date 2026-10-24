package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.model.TaskPriority;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
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
        assertEquals(List.of(new UnallocatedWork(1, "Draft", draft.deadline().orElseThrow(), 30,
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
        assertEquals(List.of(new UnallocatedWork(1, "Draft", draft.deadline().orElseThrow(), 60,
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

    @Test
    void generate_continuousRangeSegments_blockIntermediateDaysAndRetainDeadlineRemainder() {
        LocalDate first = LocalDate.of(2026, 10, 26);
        Task draft = task(1, "Draft", first.plusDays(2).atTime(12, 0), 180);
        CommitmentService commitments = new CommitmentService();
        Snapshot source = commitments.add(snapshot(List.of(draft), List.of()), "Trip", first.getDayOfWeek(),
                LocalTime.of(15, 0), 540, first, first);
        source = commitments.add(source, "Trip", first.plusDays(1).getDayOfWeek(), LocalTime.MIDNIGHT,
                1440, first.plusDays(1), first.plusDays(1));
        source = commitments.add(source, "Trip", first.plusDays(2).getDayOfWeek(), LocalTime.MIDNIGHT,
                660, first.plusDays(2), first.plusDays(2));

        Plan result = planner.generate(source, first.atTime(14, 0));

        assertEquals(List.of(new StudySession(1, first.atTime(14, 0), first.atTime(15, 0)),
                new StudySession(1, first.plusDays(2).atTime(11, 0), first.plusDays(2).atTime(12, 0))),
                result.sessions());
        assertEquals(List.of(new UnallocatedWork(1, "Draft", draft.deadline().orElseThrow(), 60,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(List.of(new ScheduleEntry(first.plusDays(1).atTime(8, 0),
                first.plusDays(1).atTime(22, 0), SlotKind.BUSY, "C2", "Trip")),
                new ScheduleService().forDate(result, first.plusDays(1)).entries());
        assertEquals(180, result.source().tasks().getFirst().remainingMinutes());
    }

    @Test
    void generate_dailyRangeSegments_preserveStudyGapsOnEveryIncludedDate() {
        LocalDate first = LocalDate.of(2026, 10, 26);
        Snapshot source = snapshot(List.of(task(1, "Draft", first.plusDays(2).atTime(22, 0), 1500)), List.of());
        CommitmentService commitments = new CommitmentService();
        for (LocalDate date : List.of(first, first.plusDays(1), first.plusDays(2))) {
            source = commitments.add(source, "Rest", date.getDayOfWeek(), LocalTime.of(15, 0), 120, date, date);
        }

        Plan result = planner.generate(source, first.atTime(14, 0));

        assertEquals(List.of(new StudySession(1, first.atTime(14, 0), first.atTime(15, 0)),
                new StudySession(1, first.atTime(17, 0), first.atTime(22, 0)),
                new StudySession(1, first.plusDays(1).atTime(8, 0), first.plusDays(1).atTime(15, 0)),
                new StudySession(1, first.plusDays(1).atTime(17, 0), first.plusDays(1).atTime(22, 0)),
                new StudySession(1, first.plusDays(2).atTime(8, 0), first.plusDays(2).atTime(15, 0))),
                result.sessions());
        assertTrue(result.isComplete());
        assertEquals(1500, result.sessions().stream().mapToInt(StudySession::minutes).sum());
        ScheduleService schedules = new ScheduleService();
        for (LocalDate date : List.of(first, first.plusDays(1), first.plusDays(2))) {
            List<ScheduleEntry> busy = schedules.forDate(result, date).entries().stream()
                    .filter(entry -> entry.kind() == SlotKind.BUSY).toList();
            assertEquals(1, busy.size());
            assertEquals(date.atTime(15, 0), busy.getFirst().start());
            assertEquals(date.atTime(17, 0), busy.getFirst().end());
        }
    }
    // @@author

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

    // @@author ernestgoh-god
    @Test
    void generate_policyWithExactWork_keepsPaddingAndBreaksSeparateFromCreditedMinutes() {
        Task active = task(1, "Draft", MONDAY.atTime(10, 0), 45);
        Task completed = Task.withoutDeadline(2, "Finished", 0);
        Snapshot source = snapshot(List.of(completed, active), List.of());
        Plan plan = new EarliestDeadlinePlanner(new StudyPolicy(30, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(8, 30), 30),
                new StudySession(1, MONDAY.atTime(9, 0), MONDAY.atTime(9, 30), 15)), plan.sessions());
        assertEquals(List.of(rest(8, 30, 9, 0)), plan.breaks());
        assertEquals(45L, plan.sessions().stream().mapToLong(StudySession::workMinutes).sum());
        assertTrue(plan.unallocated().isEmpty());
        assertSame(source, plan.source());
    }
    // @@author nickpatt

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

    @Test
    void generate_policy_splitsStudyAndReservesOnlyRequiredBreaks() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 180)), List.of());
        EarliestDeadlinePlanner limited = new EarliestDeadlinePlanner(new StudyPolicy(60, 30));
        Plan result = limited.generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 8, 0, 9, 0), study(1, 9, 30, 10, 30), study(1, 11, 0, 12, 0)),
                result.sessions());
        assertEquals(List.of(rest(9, 0, 9, 30), rest(10, 30, 11, 0)), result.breaks());
        assertTrue(result.isComplete());
        assertSame(source, result.source());
        assertEquals(180, source.tasks().getFirst().remainingMinutes());
        assertEquals(result, limited.generate(source, EARLY_MORNING));
    }

    @Test
    void generate_taskSwitch_keepsSharedLimitAndNumericDeadlineOrder() {
        Snapshot source = snapshot(List.of(task(10, "Second", MONDAY.atTime(10, 0), 60),
                task(2, "First", MONDAY.atTime(10, 0), 30)), List.of());
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(2, 8, 0, 8, 30), study(10, 8, 30, 9, 0), study(10, 9, 30, 10, 0)),
                result.sessions());
        assertEquals(List.of(rest(9, 0, 9, 30)), result.breaks());
    }

    @Test
    void generate_commitmentBelowLimit_allocatesTaskDirectlyAfterward() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(11, 0), 60)),
                List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(90, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 9, 0, 9, 30), study(1, 10, 0, 10, 30)), result.sessions());
        assertEquals(List.of(rest(9, 30, 10, 0)), result.breaks());
    }

    @Test
    void generate_commitmentAtOrBeyondLimit_requiresOneFixedBreak() {
        for (int minutes : new int[] {60, 120, 180}) {
            LocalDateTime end = MONDAY.atTime(8, 0).plusMinutes(minutes);
            Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(15, 0), 30)),
                    List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), minutes)));
            Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
            assertEquals(List.of(new StudySession(1, end.plusMinutes(30), end.plusMinutes(60))), result.sessions());
            assertEquals(List.of(new ScheduleEntry(end, end.plusMinutes(30), SlotKind.BREAK, "", "")),
                    result.breaks());
        }
    }

    @Test
    void generate_adjacentCommitments_accumulatesAcrossSeparateIds() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 30)), List.of(
                new Commitment(1, "First", DayOfWeek.MONDAY, LocalTime.of(8, 0), 30),
                new Commitment(2, "Second", DayOfWeek.MONDAY, LocalTime.of(8, 30), 30)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 9, 30, 10, 0)), result.sessions());
        assertEquals(List.of(rest(9, 0, 9, 30)), result.breaks());
    }

    @Test
    void generate_studyBeforeCommitment_countsBothAndKeepsCommitmentFixed() {
        Commitment lecture = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 30), 90);
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 60)), List.of(lecture));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 8, 0, 8, 30), study(1, 10, 30, 11, 0)), result.sessions());
        assertEquals(List.of(rest(10, 0, 10, 30)), result.breaks());
        assertEquals(List.of(lecture), result.source().commitments());
    }

    @Test
    void generate_interruptedBreak_requiresConsecutiveRestAgain() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(13, 0), 30)), List.of(
                new Commitment(1, "First", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60),
                new Commitment(2, "Second", DayOfWeek.MONDAY, LocalTime.of(9, 30), 30)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 60)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 11, 0, 11, 30)), result.sessions());
        assertEquals(List.of(rest(9, 0, 9, 30), rest(10, 0, 11, 0)), result.breaks());
    }

    @Test
    void generate_commitmentBeforeWindow_countsActualDurationAndNaturalRest() {
        for (int startHour : new int[] {6, 7}) {
            Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(10, 0), 30)),
                    List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(startHour, 0), 60)));
            Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
            LocalDateTime expectedStart = MONDAY.atTime(8, startHour == 7 ? 30 : 0);
            assertEquals(List.of(new StudySession(1, expectedStart, expectedStart.plusMinutes(30))),
                    result.sessions());
            assertEquals(startHour == 7 ? List.of(rest(8, 0, 8, 30)) : List.of(), result.breaks());
        }
    }

    @Test
    void generate_midCommitmentCutoff_countsElapsedCommitmentAndRoundsStart() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 30)),
                List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 90)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(90, 30))
                .generate(source, MONDAY.atTime(9, 0, 1));
        assertEquals(List.of(study(1, 10, 0, 10, 30)), result.sessions());
        assertEquals(List.of(rest(9, 30, 10, 0)), result.breaks());
    }

    @Test
    void generate_requiredBreakAfterCutoff_countsEarlierNaturalRest() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(12, 0), 30)),
                List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 60))
                .generate(source, MONDAY.atTime(9, 30));
        assertEquals(List.of(study(1, 10, 0, 10, 30)), result.sessions());
        assertEquals(List.of(rest(9, 30, 10, 0)), result.breaks());
    }

    @Test
    void generate_breakReducesCapacity_preservesRemaindersAndInvalidDeadlineReasons() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.atTime(9, 30), 90),
                task(2, "Overdue", MONDAY.atTime(6, 0), 30),
                task(3, "Future", TimeRules.horizonEnd(EARLY_MORNING).plusMinutes(30), 60)), List.of());
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 8, 0, 9, 0)), result.sessions());
        assertEquals(List.of(UnallocatedReason.DEADLINE_PASSED, UnallocatedReason.INSUFFICIENT_CAPACITY,
                UnallocatedReason.OUTSIDE_HORIZON),
                result.unallocated().stream().map(UnallocatedWork::reason).toList());
        for (Task task : source.tasks()) {
            int scheduled = result.sessions().stream().filter(session -> session.taskId() == task.id())
                    .mapToInt(StudySession::minutes).sum();
            int unallocated = result.unallocated().stream().filter(work -> work.taskId() == task.id())
                    .mapToInt(UnallocatedWork::minutes).sum();
            assertEquals(task.remainingMinutes(), scheduled + unallocated);
        }
    }

    @Test
    void generate_overnightRest_resetsBeforeNextStudyWindow() {
        Snapshot source = snapshot(List.of(task(1, "Draft", MONDAY.plusDays(1).atTime(9, 0), 120)), List.of());
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, MONDAY.atTime(21, 0));
        assertEquals(List.of(study(1, 21, 0, 22, 0), new StudySession(1, MONDAY.plusDays(1).atTime(8, 0),
                MONDAY.plusDays(1).atTime(9, 0))), result.sessions());
        assertTrue(result.breaks().isEmpty());
    }

    @Test
    void generate_emptyTasks_doesNotReserveBreaksAfterCommitments() {
        Snapshot source = snapshot(List.of(),
                List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 180)));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);
        assertTrue(result.sessions().isEmpty());
        assertTrue(result.breaks().isEmpty());
        assertTrue(result.isComplete());
    }

    @Test
    void constructor_nullPolicy_rejects() {
        assertThrows(NullPointerException.class, () -> new EarliestDeadlinePlanner(null));
    }

    @Test
    void generate_configuredWindow_limitsBreaksAndPreservesDateOverride() {
        Snapshot initial = snapshot(List.of(task(1, "Draft", MONDAY.atTime(16, 0), 90)), List.of());
        Snapshot source = initial.withStudyHours(initial.studyHours()
                .withWindow("monday", new StudyWindow(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .withWindow(MONDAY.toString(), new StudyWindow(LocalTime.of(14, 0), LocalTime.of(15, 30))));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(30, 30)).generate(source, EARLY_MORNING);
        assertEquals(List.of(study(1, 14, 0, 14, 30), study(1, 15, 0, 15, 30)), result.sessions());
        assertEquals(List.of(rest(14, 30, 15, 0)), result.breaks());
        assertEquals(30, result.unallocated().getFirst().minutes());
    }

    @Test
    void generate_commitmentAcrossCalendarBoundary_doesNotResetWorkAtMidnight() {
        Snapshot initial = snapshot(List.of(task(1, "Draft", MONDAY.plusDays(1).atTime(2, 0), 30)), List.of(
                new Commitment(1, "Late", DayOfWeek.MONDAY, LocalTime.of(23, 0), 60),
                new Commitment(2, "Early", DayOfWeek.TUESDAY, LocalTime.MIDNIGHT, 30)));
        Snapshot source = initial.withStudyHours(initial.studyHours().withWindow("tuesday",
                new StudyWindow(LocalTime.MIDNIGHT, LocalTime.of(2, 0))));
        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, MONDAY.atTime(23, 0));
        LocalDateTime restStart = MONDAY.plusDays(1).atTime(0, 30);
        assertEquals(List.of(new StudySession(1, restStart.plusMinutes(30), restStart.plusMinutes(60))),
                result.sessions());
        assertEquals(List.of(new ScheduleEntry(restStart, restStart.plusMinutes(30), SlotKind.BREAK, "", "")),
                result.breaks());
    }

    @Test
    void generate_weeklyInstances_waitsForEachOriginalReleaseAndLeavesOrdinaryTasksUnrestricted() {
        Task first = occurrence(1, MONDAY.plusWeeks(1).atTime(8, 15), MONDAY.plusWeeks(1).atTime(8, 15), 30);
        Task second = occurrence(2, MONDAY.plusWeeks(2).atTime(8, 15), MONDAY.plusWeeks(2).atTime(8, 15), 30);
        Task ordinary = task(3, "Ordinary", MONDAY.plusWeeks(3).atTime(12, 0), 30);
        Snapshot source = snapshot(List.of(second, ordinary, first), List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(study(3, 8, 0, 8, 30), study(1, 8, 30, 9, 0),
                new StudySession(2, MONDAY.plusWeeks(1).atTime(8, 30), MONDAY.plusWeeks(1).atTime(9, 0))),
                result.sessions());
        assertTrue(result.isComplete());
        assertEquals(result, planner.generate(source, EARLY_MORNING));
        assertEquals(List.of(second, ordinary, first), source.tasks());
    }

    @Test
    void generate_editedDeadlines_retainsOriginalReleaseAndConservesUnallocatedWork() {
        LocalDateTime original = MONDAY.plusWeeks(1).atTime(9, 0);
        Task earlier = new Task(1, "Earlier", MONDAY.atTime(8, 30), 45,
                Optional.of(new TaskOccurrence(2, original)));
        Task later = occurrence(2, MONDAY.plusWeeks(2).atTime(12, 0), original, 15);
        Snapshot source = snapshot(List.of(later, earlier), List.of());

        Plan result = planner.generate(source, EARLY_MORNING);

        assertEquals(List.of(new StudySession(2, MONDAY.atTime(9, 0), MONDAY.atTime(9, 30), 15)), result.sessions());
        assertEquals(List.of(new UnallocatedWork(1, earlier.name(), earlier.deadline(), 45, 60L,
                UnallocatedReason.INSUFFICIENT_CAPACITY)), result.unallocated());
        assertEquals(result, planner.generate(source, EARLY_MORNING));
        for (Task task : source.tasks()) {
            int scheduled = result.sessions().stream().filter(session -> session.taskId() == task.id())
                    .mapToInt(StudySession::workMinutes).sum();
            int unallocated = result.unallocated().stream().filter(work -> work.taskId() == task.id())
                    .mapToInt(UnallocatedWork::minutes).sum();
            assertEquals(task.remainingMinutes(), scheduled + unallocated);
        }
    }

    @Test
    void generate_releaseWithCustomHoursAndDatedCommitments_preservesSharedWorkClockAndSkips() {
        Task recurring = occurrence(1, MONDAY.plusWeeks(1).atTime(14, 30),
                MONDAY.plusWeeks(1).atTime(14, 30), 60);
        Task ordinary = task(2, "Ordinary", MONDAY.plusWeeks(2).atTime(12, 0), 30);
        Commitment skipped = new Commitment(1, "Cancelled", DayOfWeek.MONDAY, LocalTime.of(14, 0), 30,
                MONDAY, MONDAY.plusWeeks(1), Set.of(MONDAY));
        Commitment dated = new Commitment(2, "Meeting", DayOfWeek.MONDAY, LocalTime.of(15, 0), 30,
                MONDAY, MONDAY);
        Snapshot initial = snapshot(List.of(recurring, ordinary), List.of(skipped, dated));
        Snapshot source = initial.withStudyHours(initial.studyHours()
                .withWindow("monday", new StudyWindow(LocalTime.of(6, 0), LocalTime.of(7, 0)))
                .withWindow(MONDAY.toString(), new StudyWindow(LocalTime.of(14, 0), LocalTime.of(17, 0))));
        EarliestDeadlinePlanner limited = new EarliestDeadlinePlanner(new StudyPolicy(60, 30));

        Plan result = limited.generate(source, MONDAY.atTime(14, 0));

        assertEquals(List.of(study(2, 14, 0, 14, 30), study(1, 14, 30, 15, 0),
                study(1, 16, 0, 16, 30)), result.sessions());
        assertEquals(List.of(rest(15, 30, 16, 0)), result.breaks());
        assertTrue(result.isComplete());
        assertSame(source, result.source());
        assertEquals(List.of(skipped, dated), result.source().commitments());
    }

    @Test
    void generate_unreleasedWork_allowsNaturalRestWithoutReservingPrematureBreaks() {
        Task recurring = occurrence(1, MONDAY.plusWeeks(1).atTime(10, 0),
                MONDAY.plusWeeks(1).atTime(10, 0), 30);
        Snapshot source = snapshot(List.of(recurring), List.of(
                new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60)));

        Plan result = new EarliestDeadlinePlanner(new StudyPolicy(60, 30)).generate(source, EARLY_MORNING);

        assertEquals(List.of(study(1, 10, 0, 10, 30)), result.sessions());
        assertTrue(result.breaks().isEmpty());
    }

    /** Creates an occurrence whose logical release is independent of its editable deadline. */
    private static Task occurrence(int id, LocalDateTime deadline, LocalDateTime originalDeadline, int minutes) {
        return new Task(id, "Weekly " + id, deadline, minutes,
                Optional.of(new TaskOccurrence(1, originalDeadline)));
    }

    /** Creates an expected study interval on the fixed generation date. */
    private static StudySession study(int id, int startHour, int startMinute, int endHour, int endMinute) {
        return new StudySession(id, MONDAY.atTime(startHour, startMinute), MONDAY.atTime(endHour, endMinute));
    }

    /** Creates an expected reserved break without task or commitment identity. */
    private static ScheduleEntry rest(int startHour, int startMinute, int endHour, int endMinute) {
        return new ScheduleEntry(MONDAY.atTime(startHour, startMinute), MONDAY.atTime(endHour, endMinute),
                SlotKind.BREAK, "", "");
    }

    /** Creates a fixed task without consulting the system clock. */
    private static Task task(int id, String name, LocalDateTime deadline, int minutes) {
        return new Task(id, name, deadline, minutes);
    }

    /** Creates isolated source data with counters above the supplied IDs. */
    private static Snapshot snapshot(List<Task> tasks, List<Commitment> commitments) {
        return new Snapshot(tasks, commitments, tasks.stream().mapToInt(Task::id).max().orElse(0) + 1,
                commitments.stream().mapToInt(Commitment::id).max().orElse(0) + 1,
                Snapshot.empty().studyHours(), List.of(), tasks.stream().flatMap(task -> task.occurrence().stream())
                        .mapToInt(TaskOccurrence::templateId).max().orElse(0) + 1);
    }
}
