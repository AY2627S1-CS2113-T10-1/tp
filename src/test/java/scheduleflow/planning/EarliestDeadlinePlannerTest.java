package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;

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
                commitments.stream().mapToInt(Commitment::id).max().orElse(0) + 1);
    }
}
