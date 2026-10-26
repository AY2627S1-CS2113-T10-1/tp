// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;

/**
 * Checks frozen daily projections, interval merging and calendar boundaries.
 */
class ScheduleServiceTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime MORNING = MONDAY.atTime(7, 0);
    private final ScheduleService schedules = new ScheduleService();

    @Test
    void forDate_customWindow_clipsBusyAndUsesFrozenCutoff() {
        Snapshot initial = new Snapshot(List.of(), List.of(
                new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(5, 30), 60)), 1, 2);
        StudyWindow window = new StudyWindow(LocalTime.of(6, 0), LocalTime.of(7, 30));
        Snapshot source = initial.withStudyHours(initial.studyHours().withWindow("monday", window));
        Plan generated = new EarliestDeadlinePlanner().generate(source, MONDAY.atTime(6, 30, 1));
        ScheduleView view = schedules.forDate(generated, MONDAY);
        assertEquals(window, view.window());
        assertEquals(List.of(entry(6, 0, 6, 30, SlotKind.BUSY, "C1", "Class"),
                entry(6, 30, 7, 0, SlotKind.UNPLANNED, "", ""),
                entry(7, 0, 7, 30, SlotKind.FREE, "", "")), view.entries());
        assertEquals(LocalTime.of(8, 0), schedules.forDate(generated, MONDAY.plusDays(1))
                .entries().getFirst().start().toLocalTime());
    }

    @Test
    void forDate_oneTimeCommitment_showsBusyOnlyOnItsDate() {
        LocalDate tuesday = MONDAY.plusDays(1);
        Snapshot source = new Snapshot(List.of(), List.of(
                new Commitment(1, "Meeting", DayOfWeek.TUESDAY, LocalTime.of(14, 0), 60, tuesday, tuesday)), 1, 2);
        Plan generated = new EarliestDeadlinePlanner().generate(source, MORNING);
        assertEquals(List.of(
                new ScheduleEntry(tuesday.atTime(8, 0), tuesday.atTime(14, 0), SlotKind.FREE, "", ""),
                new ScheduleEntry(tuesday.atTime(14, 0), tuesday.atTime(15, 0), SlotKind.BUSY, "C1", "Meeting"),
                new ScheduleEntry(tuesday.atTime(15, 0), tuesday.atTime(22, 0), SlotKind.FREE, "", "")),
                schedules.forDate(generated, tuesday).entries());
        LocalDate nextTuesday = tuesday.plusWeeks(1);
        assertEquals(List.of(new ScheduleEntry(nextTuesday.atTime(8, 0), nextTuesday.atTime(22, 0),
                SlotKind.FREE, "", "")), schedules.forDate(generated, nextTuesday).entries());
    }

    @Test
    void forDate_correctedContractExample_preservesAdjacentCommitmentIds() {
        Task task = new Task(1, "Draft", MONDAY.plusDays(1).atTime(18, 0), 180);
        Snapshot source = new Snapshot(List.of(task), List.of(
                new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120),
                new Commitment(2, "Lecture", DayOfWeek.MONDAY, LocalTime.of(12, 0), 60)), 2, 3);
        Plan plan = plan(source, MORNING, List.of(
                new StudySession(1, MONDAY.atTime(8, 0), MONDAY.atTime(10, 0)),
                new StudySession(1, MONDAY.atTime(13, 0), MONDAY.atTime(14, 0))), List.of());

        ScheduleView view = schedules.forDate(plan, MONDAY);

        assertEquals(List.of(
                entry(8, 0, 10, 0, SlotKind.TASK, "T1", "Draft"),
                entry(10, 0, 12, 0, SlotKind.BUSY, "C1", "Lecture"),
                entry(12, 0, 13, 0, SlotKind.BUSY, "C2", "Lecture"),
                entry(13, 0, 14, 0, SlotKind.TASK, "T1", "Draft"),
                entry(14, 0, 22, 0, SlotKind.FREE, "", "")), view.entries());
        assertEquals(MORNING, view.generatedAt());
        assertTrue(view.unallocated().isEmpty());
    }

    @Test
    void forDate_savedCutoff_keepsBusyPeriodsAndWholePlanRemainders() {
        LocalDateTime generated = MONDAY.atTime(10, 7);
        Task task = new Task(1, "Tomorrow", MONDAY.plusDays(1).atTime(8, 30), 30);
        Snapshot source = new Snapshot(List.of(task), List.of(
                new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(9, 0), 60)), 2, 2);
        UnallocatedWork remainder = new UnallocatedWork(1, task.name(), task.deadline().orElseThrow(), 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY);
        Plan plan = plan(source, generated, List.of(), List.of(remainder));

        ScheduleView view = schedules.forDate(plan, MONDAY);

        assertEquals(List.of(
                entry(8, 0, 9, 0, SlotKind.UNPLANNED, "", ""),
                entry(9, 0, 10, 0, SlotKind.BUSY, "C1", "Lecture"),
                entry(10, 0, 10, 30, SlotKind.UNPLANNED, "", ""),
                entry(10, 30, 22, 0, SlotKind.FREE, "", "")), view.entries());
        assertEquals(List.of(remainder), view.unallocated());
        assertEquals(view, schedules.forDate(plan, MONDAY));
        ScheduleView tomorrow = schedules.forDate(plan, MONDAY.plusDays(1));
        assertEquals(List.of(new ScheduleEntry(MONDAY.plusDays(1).atTime(8, 0),
                MONDAY.plusDays(1).atTime(22, 0), SlotKind.FREE, "", "")), tomorrow.entries());
        assertEquals(plan.unallocated(), tomorrow.unallocated());
    }

    @Test
    void forDate_commitmentsOutsideWindow_clipsMorningAndIgnoresMidnight() {
        Snapshot source = new Snapshot(List.of(), List.of(
                new Commitment(1, "Morning", DayOfWeek.MONDAY, LocalTime.of(7, 30), 90),
                new Commitment(2, "Late", DayOfWeek.MONDAY, LocalTime.of(23, 30), 30),
                new Commitment(3, "Evening", DayOfWeek.MONDAY, LocalTime.of(21, 30), 120)), 1, 4);
        Plan plan = plan(source, MORNING, List.of(), List.of());
        assertEquals(List.of(
                entry(8, 0, 9, 0, SlotKind.BUSY, "C1", "Morning"),
                entry(9, 0, 21, 30, SlotKind.FREE, "", ""),
                entry(21, 30, 22, 0, SlotKind.BUSY, "C3", "Evening")),
                schedules.forDate(plan, MONDAY).entries());
    }

    @Test
    void forDate_boundedCommitment_showsBusyOnlyWithinInclusiveDates() {
        Commitment semester = new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                MONDAY.plusWeeks(1), MONDAY.plusWeeks(2));
        Plan plan = plan(new Snapshot(List.of(), List.of(semester), 1, 2), MORNING, List.of(), List.of());
        assertTrue(schedules.forDate(plan, MONDAY).entries().stream()
                .noneMatch(entry -> entry.kind() == SlotKind.BUSY));
        assertTrue(schedules.forDate(plan, MONDAY.plusWeeks(1)).entries().stream()
                .anyMatch(entry -> entry.kind() == SlotKind.BUSY));
        assertTrue(schedules.forDate(plan, MONDAY.plusWeeks(2)).entries().stream()
                .anyMatch(entry -> entry.kind() == SlotKind.BUSY));
        assertTrue(schedules.forDate(plan, MONDAY.plusWeeks(3)).entries().stream()
                .noneMatch(entry -> entry.kind() == SlotKind.BUSY));
    }

    @Test
    void forDate_rangeBoundaries_rejectsOutsideAndAcceptsLastDay() {
        Plan plan = plan(Snapshot.empty(), MORNING, List.of(), List.of());
        LocalDate last = plan.horizonEnd().toLocalDate().minusDays(1);
        assertEquals(last, schedules.forDate(plan, last).date());
        for (LocalDate date : new LocalDate[] {MONDAY.minusDays(1), last.plusDays(1), null}) {
            ValidationException error = assertThrows(ValidationException.class, () -> schedules.forDate(plan, date));
            assertTrue(error.getMessage().contains(MONDAY.toString()));
            assertTrue(error.getMessage().contains(last.toString()));
            assertTrue(error.getMessage().contains("Run plan again"));
        }
    }

    @Test
    void forDate_afterStudyHours_todayUnplannedAndFutureFree() {
        Plan plan = plan(Snapshot.empty(), MONDAY.atTime(23, 0), List.of(), List.of());
        assertEquals(List.of(entry(8, 0, 22, 0, SlotKind.UNPLANNED, "", "")),
                schedules.forDate(plan, MONDAY).entries());
        ScheduleView nextDay = schedules.forDate(plan, MONDAY.plusDays(1));
        assertEquals(1, nextDay.entries().size());
        assertEquals(SlotKind.FREE, nextDay.entries().getFirst().kind());
    }

    @Test
    void forDate_unavailableStudyPeriod_projectsRegeneratedBlockAndKeepsOriginalPlanFrozen() {
        Task draft = new Task(1, "Draft", MONDAY.atTime(12, 0), 90);
        Snapshot original = new Snapshot(List.of(draft), List.of(), 2, 1);
        EarliestDeadlinePlanner planner = new EarliestDeadlinePlanner();
        Plan before = planner.generate(original, MORNING);
        Snapshot blocked = new CommitmentService().add(original, "Unavailable",
                DayOfWeek.MONDAY, LocalTime.of(8, 30), 60, MONDAY, MONDAY);
        Plan after = planner.generate(blocked, MORNING);

        assertEquals(List.of(entry(8, 0, 8, 30, SlotKind.TASK, "T1", "Draft"),
                entry(8, 30, 9, 30, SlotKind.BUSY, "C1", "Unavailable"),
                entry(9, 30, 10, 30, SlotKind.TASK, "T1", "Draft"),
                entry(10, 30, 22, 0, SlotKind.FREE, "", "")), schedules.forDate(after, MONDAY).entries());
        assertEquals(List.of(entry(8, 0, 9, 30, SlotKind.TASK, "T1", "Draft"),
                entry(9, 30, 22, 0, SlotKind.FREE, "", "")), schedules.forDate(before, MONDAY).entries());
        assertEquals(List.of(new ScheduleEntry(MONDAY.plusWeeks(1).atTime(8, 0),
                MONDAY.plusWeeks(1).atTime(22, 0), SlotKind.FREE, "", "")),
                schedules.forDate(after, MONDAY.plusWeeks(1)).entries());
    }

    @Test
    void forDate_policyPlan_projectsMergedBreaksAndKeepsFrozenResults() {
        Snapshot source = new Snapshot(List.of(new Task(1, "Draft", MONDAY.atTime(12, 0), 90)), List.of(), 2, 1);
        Plan plan = new EarliestDeadlinePlanner(new StudyPolicy(60, 60)).generate(source, MORNING);
        List<ScheduleEntry> expected = List.of(entry(8, 0, 9, 0, SlotKind.TASK, "T1", "Draft"),
                entry(9, 0, 10, 0, SlotKind.BREAK, "", ""),
                entry(10, 0, 10, 30, SlotKind.TASK, "T1", "Draft"),
                entry(10, 30, 22, 0, SlotKind.FREE, "", ""));
        assertEquals(expected, schedules.forDate(plan, MONDAY).entries());
        new EarliestDeadlinePlanner(new StudyPolicy(30, 30)).generate(source, MONDAY.atTime(11, 0));
        assertEquals(expected, schedules.forDate(plan, MONDAY).entries());
        assertEquals(MORNING, plan.generatedAt());
    }

    @Test
    void forDate_interruptedBreak_preservesCommitmentAndPartialRestBoundaries() {
        Snapshot source = new Snapshot(List.of(new Task(1, "Draft", MONDAY.atTime(12, 0), 30)), List.of(
                new Commitment(1, "First", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60),
                new Commitment(2, "Second", DayOfWeek.MONDAY, LocalTime.of(9, 30), 30)), 2, 3);
        Plan plan = new EarliestDeadlinePlanner(new StudyPolicy(60, 60)).generate(source, MORNING);
        assertEquals(List.of(entry(8, 0, 9, 0, SlotKind.BUSY, "C1", "First"),
                entry(9, 0, 9, 30, SlotKind.BREAK, "", ""),
                entry(9, 30, 10, 0, SlotKind.BUSY, "C2", "Second"),
                entry(10, 0, 11, 0, SlotKind.BREAK, "", ""),
                entry(11, 0, 11, 30, SlotKind.TASK, "T1", "Draft"),
                entry(11, 30, 22, 0, SlotKind.FREE, "", "")), schedules.forDate(plan, MONDAY).entries());
    }

    @Test
    void forDate_partialEarlierRest_preservesUnplannedCutoffAndWholePlanRemainder() {
        Snapshot source = new Snapshot(List.of(new Task(1, "Draft", MONDAY.atTime(10, 0), 30)), List.of(
                new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60)), 2, 2);
        Plan plan = new EarliestDeadlinePlanner(new StudyPolicy(60, 60)).generate(source, MONDAY.atTime(9, 30));
        ScheduleView view = schedules.forDate(plan, MONDAY);
        assertEquals(List.of(entry(8, 0, 9, 0, SlotKind.BUSY, "C1", "Lecture"),
                entry(9, 0, 9, 30, SlotKind.UNPLANNED, "", ""),
                entry(9, 30, 10, 0, SlotKind.BREAK, "", ""),
                entry(10, 0, 22, 0, SlotKind.FREE, "", "")), view.entries());
        assertEquals(plan.unallocated(), view.unallocated());
        assertEquals(30, view.unallocated().getFirst().minutes());
    }

    /** Creates a legacy plan without reserved breaks for projection-only checks. */
    private static Plan plan(Snapshot source, LocalDateTime generated, List<StudySession> sessions,
            List<UnallocatedWork> unallocated) {
        return new Plan(generated, TimeRules.horizonEnd(generated), source, sessions, unallocated);
    }

    /** Creates an expected row on the fixed schedule date. */
    private static ScheduleEntry entry(int startHour, int startMinute, int endHour, int endMinute,
            SlotKind kind, String reference, String name) {
        return new ScheduleEntry(MONDAY.atTime(startHour, startMinute), MONDAY.atTime(endHour, endMinute),
                kind, reference, name);
    }
}
// @@author
