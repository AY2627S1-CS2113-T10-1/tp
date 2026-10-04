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
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Checks frozen daily projections, interval merging and calendar boundaries.
 */
class ScheduleServiceTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime MORNING = MONDAY.atTime(7, 0);
    private final ScheduleService schedules = new ScheduleService();

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
        UnallocatedWork remainder = new UnallocatedWork(1, task.name(), task.deadline(), 30,
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

    private static Plan plan(Snapshot source, LocalDateTime generated, List<StudySession> sessions,
            List<UnallocatedWork> unallocated) {
        return new Plan(generated, TimeRules.horizonEnd(generated), source, sessions, unallocated);
    }

    private static ScheduleEntry entry(int startHour, int startMinute, int endHour, int endMinute,
            SlotKind kind, String reference, String name) {
        return new ScheduleEntry(MONDAY.atTime(startHour, startMinute), MONDAY.atTime(endHour, endMinute),
                kind, reference, name);
    }
}
