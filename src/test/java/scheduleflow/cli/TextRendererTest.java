package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.ScheduleService;
import scheduleflow.planning.ScheduleView;
import scheduleflow.planning.SlotKind;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.TimeRules;
import scheduleflow.planning.UnallocatedReason;
import scheduleflow.planning.UnallocatedWork;

/**
 * Verifies public output using fixed records without console, storage or clock dependencies.
 */
class TextRendererTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2027, 1, 1, 9, 5);
    private static final String LONG_NAME = "项目 préparation 📚 " + "a".repeat(150);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime NOW = DATE.atTime(7, 0);
    private final TextRenderer renderer = new TextRenderer();

    @Test
    void help_allCommands_includesFormatsAndDescriptions() {
        String output = renderer.help();
        assertTrue(output.startsWith("=== ScheduleFlow Command Help ===\n"));
        List<String> formats = List.of("help", "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES", "task list",
                "task delete TNUMBER", "commitment add n/NAME day/DAY start/HHmm d/MINUTES", "commitment list",
                "commitment delete CNUMBER", "plan", "schedule today OR schedule YYYY-MM-DD", "exit");
        List<String> lines = output.lines().skip(1).toList();
        assertEquals(formats.size(), lines.size());
        for (int i = 0; i < formats.size(); i++) {
            assertTrue(lines.get(i).startsWith(formats.get(i) + " - "));
            assertTrue(lines.get(i).length() > formats.get(i).length() + 3);
        }
        assertFraming(output);
    }

    @Test
    void taskMessages_stableIdAndLongUnicodeName_preservesAllFields() {
        Task task = new Task(10, LONG_NAME, DEADLINE, 90);
        assertOutput("Added T10: " + LONG_NAME + "\nDue: 2027-01-01 09:05 | Duration: 90 min",
                renderer.taskAdded(task));
        assertOutput("Deleted task T10: " + LONG_NAME, renderer.taskDeleted(task));
    }

    @Test
    void tasks_empty_returnsExactMessage() {
        assertOutput("No tasks found.", renderer.tasks(List.of()));
    }

    @Test
    void tasks_suppliedOrderAndStoredEstimates_preservesRowsAndInput() {
        Task first = new Task(2, LONG_NAME, DEADLINE.plusDays(1), 180);
        Task second = new Task(10, "Earlier deadline", DEADLINE, 30);
        List<Task> tasks = new ArrayList<>(List.of(first, second));
        List<Task> original = List.copyOf(tasks);
        assertOutput("=== Actionable Tasks ===\nID | Name | Duration | Deadline\nT2 | " + LONG_NAME
                + " | 180 min | 2027-01-02 09:05\nT10 | Earlier deadline | 30 min | 2027-01-01 09:05"
                + "\nTotal: 2 task(s).", renderer.tasks(tasks));
        assertEquals(original, tasks);
    }

    @Test
    void commitmentMessages_midnightAndLongName_usesSameDayEndpoint() {
        Commitment late = new Commitment(10, LONG_NAME, DayOfWeek.FRIDAY, LocalTime.of(23, 30), 30);
        assertOutput("Added C10: " + LONG_NAME + " | Friday 23:30 - 24:00 (30 min)",
                renderer.commitmentAdded(late));
        assertOutput("Deleted commitment C10: " + LONG_NAME, renderer.commitmentDeleted(late));
        Commitment ordinary = new Commitment(2, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        assertOutput("Added C2: Lecture | Monday 10:00 - 12:00 (120 min)", renderer.commitmentAdded(ordinary));
        Commitment fullDay = new Commitment(3, "Away", DayOfWeek.SUNDAY, LocalTime.MIDNIGHT, 1440);
        assertOutput("Added C3: Away | Sunday 00:00 - 24:00 (1440 min)", renderer.commitmentAdded(fullDay));
    }

    @Test
    void commitments_empty_returnsExactMessage() {
        assertOutput("No commitments found.", renderer.commitments(List.of()));
    }

    @Test
    void commitments_weekdayGroups_preservesWithinDayOrderAndInput() {
        Commitment friday = new Commitment(1, LONG_NAME, DayOfWeek.FRIDAY, LocalTime.of(23, 30), 30);
        Commitment mondayFirst = new Commitment(2, "First", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        Commitment mondaySecond = new Commitment(10, "Second", DayOfWeek.MONDAY, LocalTime.of(12, 0), 60);
        List<Commitment> commitments = new ArrayList<>(List.of(friday, mondayFirst, mondaySecond));
        List<Commitment> original = List.copyOf(commitments);
        assertOutput("=== Recurring Weekly Commitments ===\n[Monday]\nC2. 10:00 - 12:00 | First"
                + "\nC10. 12:00 - 13:00 | Second\n\n[Friday]\nC1. 23:30 - 24:00 | " + LONG_NAME
                + "\n\nTotal: 3 recurring commitments across the week.", renderer.commitments(commitments));
        assertEquals(original, commitments);
    }

    @Test
    void commitments_allWeekdays_usesEnglishMondayThroughSunday() {
        List<Commitment> commitments = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            commitments.addFirst(new Commitment(day.getValue(), "Class", day, LocalTime.of(9, 0), 30));
        }
        String output = renderer.commitments(commitments);
        List<String> headings = output.lines().filter(line -> line.startsWith("[")).toList();
        assertEquals(List.of("[Monday]", "[Tuesday]", "[Wednesday]", "[Thursday]", "[Friday]", "[Saturday]",
                "[Sunday]"), headings);
        assertTrue(output.endsWith("Total: 7 recurring commitments across the week."));
        assertFraming(output);
    }

    @Test
    void shortMessages_errorsAndExit_usesExactPublicText() {
        assertOutput("No current plan. Run plan to generate one.", renderer.noPlan());
        assertOutput("Error: Save failed.", renderer.error("Save failed."));
        assertOutput("Error: Invalid date or time\nExpected: schedule today OR schedule YYYY-MM-DD",
                renderer.parseError(new ParseException("Invalid date or time",
                        "schedule today OR schedule YYYY-MM-DD")));
        assertOutput("Goodbye for now!", renderer.exit());
    }

    @Test
    void plan_emptyAndComplete_returnsExactMessages() {
        Plan empty = createPlan(Snapshot.empty(), List.of(), List.of());
        assertOutput("No tasks to schedule.", renderer.plan(empty));
        Task task = new Task(1, "Draft", DATE.plusDays(1).atTime(18, 0), 90);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
        Plan complete = createPlan(source,
                List.of(new StudySession(1, DATE.atTime(8, 0), DATE.atTime(9, 30))), List.of());
        assertOutput("Study plan generated successfully! Type 'schedule today' to view your sessions.",
                renderer.plan(complete));
        assertEquals(90, complete.source().tasks().getFirst().remainingMinutes());
    }

    @Test
    void plan_partial_mapsEveryReasonAndPreservesWholePlanOrder() {
        Task overdue = new Task(1, "Overdue", NOW, 30);
        Task partial = new Task(2, LONG_NAME, DATE.atTime(8, 30), 90);
        Task outside = new Task(10, "Future", TimeRules.horizonEnd(NOW).plusDays(1), 60);
        Snapshot source = new Snapshot(List.of(outside, partial, overdue), List.of(), 11, 1);
        List<UnallocatedWork> unallocated = List.of(createWork(overdue, 30, UnallocatedReason.DEADLINE_PASSED),
                createWork(partial, 60, UnallocatedReason.INSUFFICIENT_CAPACITY),
                createWork(outside, 60, UnallocatedReason.OUTSIDE_HORIZON));
        Plan partialPlan = createPlan(source,
                List.of(new StudySession(2, DATE.atTime(8, 0), DATE.atTime(8, 30))), unallocated);
        assertOutput("Study plan generated with unallocated work.\nScheduled: 30 min | Unallocated: 150 min"
                + "\nT1: Overdue | Unallocated: 30 min | Reason: deadline passed"
                + "\nT2: " + LONG_NAME + " | Unallocated: 60 min | Reason: insufficient time before deadline"
                + "\nT10: Future | Unallocated: 60 min | Reason: outside planning horizon", renderer.plan(partialPlan));
        assertEquals(90, partialPlan.source().tasks().get(1).remainingMinutes());
        assertEquals(unallocated, partialPlan.unallocated());
    }

    @Test
    void plan_zeroAllocationAndLargeRemainders_usesLongTotals() {
        // Each estimate fits in int, but their combined unallocated total exceeds it.
        int largeMinutes = 2_147_483_640;
        Task first = new Task(2, "Large first", NOW, largeMinutes);
        Task second = new Task(10, "Large second", NOW, largeMinutes);
        Snapshot source = new Snapshot(List.of(first, second), List.of(), 11, 1);
        Plan blocked = createPlan(source, List.of(),
                List.of(createWork(first, largeMinutes, UnallocatedReason.DEADLINE_PASSED),
                createWork(second, largeMinutes, UnallocatedReason.DEADLINE_PASSED)));
        assertOutput("Study plan generated with unallocated work.\nScheduled: 0 min | Unallocated: 4294967280 min"
                + "\nT2: Large first | Unallocated: 2147483640 min | Reason: deadline passed"
                + "\nT10: Large second | Unallocated: 2147483640 min | Reason: deadline passed",
                renderer.plan(blocked));
    }

    @Test
    void plan_sessionsOnMultipleDates_totalsWholePlan() {
        Task task = new Task(1, "Draft", DATE.plusDays(1).atTime(9, 0), 120);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
        List<StudySession> sessions = List.of(new StudySession(1, DATE.atTime(21, 30), DATE.atTime(22, 0)),
                new StudySession(1, DATE.plusDays(1).atTime(8, 0), DATE.plusDays(1).atTime(9, 0)));
        Plan partial = createPlan(source, sessions,
                List.of(createWork(task, 30, UnallocatedReason.INSUFFICIENT_CAPACITY)));
        assertOutput("Study plan generated with unallocated work.\nScheduled: 90 min | Unallocated: 30 min"
                + "\nT1: Draft | Unallocated: 30 min | Reason: insufficient time before deadline",
                renderer.plan(partial));
    }

    @Test
    void schedule_correctedExample_integratesExistingPlannerAndProjection() {
        Task task = new Task(1, "CS2113 draft", DATE.plusDays(1).atTime(18, 0), 180);
        Commitment lecture = new Commitment(1, "CS2113 lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        Commitment nextLecture = new Commitment(2, "EE2026 lecture", DayOfWeek.MONDAY, LocalTime.of(12, 0), 60);
        Snapshot source = new Snapshot(List.of(task), List.of(lecture, nextLecture), 2, 3);
        Plan generated = new EarliestDeadlinePlanner().generate(source, NOW);
        ScheduleView view = new ScheduleService().forDate(generated, DATE);
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 10:00 | [TASK] T1: CS2113 draft (120 min)"
                + "\n10:00 - 12:00 | [BUSY] C1: CS2113 lecture"
                + "\n12:00 - 13:00 | [BUSY] C2: EE2026 lecture"
                + "\n13:00 - 14:00 | [TASK] T1: CS2113 draft (60 min)"
                + "\n14:00 - 22:00 | [FREE]", renderer.schedule(view));
        assertEquals(source, generated.source());
    }

    @Test
    void schedule_cutoffAndPlanWideFooter_preservesFrozenMetadataAndNames() {
        LocalDateTime generatedAt = DATE.atTime(10, 7, 1).plusNanos(123);
        List<ScheduleEntry> entries = List.of(createEntry(8, 0, 9, 0, SlotKind.BUSY, "C10", LONG_NAME),
                createEntry(9, 0, 10, 30, SlotKind.UNPLANNED, "", ""),
                createEntry(10, 30, 11, 0, SlotKind.TASK, "T2", LONG_NAME),
                createEntry(11, 0, 22, 0, SlotKind.FREE, "", ""));
        Task overdue = new Task(1, "Earlier", NOW, 30);
        Task future = new Task(10, "Later", TimeRules.horizonEnd(NOW).plusDays(1), 60);
        List<UnallocatedWork> unallocated = List.of(createWork(overdue, 30, UnallocatedReason.DEADLINE_PASSED),
                new UnallocatedWork(2, LONG_NAME, DATE.plusDays(1).atTime(8, 30), 60,
                        UnallocatedReason.INSUFFICIENT_CAPACITY),
                createWork(future, 60, UnallocatedReason.OUTSIDE_HORIZON));
        ScheduleView view = new ScheduleView(DATE, generatedAt, entries, unallocated);
        String expected = "=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 10:07"
                + "\n08:00 - 09:00 | [BUSY] C10: " + LONG_NAME
                + "\n09:00 - 10:30 | [UNPLANNED]\n10:30 - 11:00 | [TASK] T2: " + LONG_NAME + " (30 min)"
                + "\n11:00 - 22:00 | [FREE]\nUNPLANNED periods were before the planning cutoff."
                + "\n\nUnallocated work for the whole plan:"
                + "\nT1: Earlier | Unallocated: 30 min | Reason: deadline passed"
                + "\nT2: " + LONG_NAME + " | Unallocated: 60 min | Reason: insufficient time before deadline"
                + "\nT10: Later | Unallocated: 60 min | Reason: outside planning horizon";
        assertOutput(expected, renderer.schedule(view));
        assertOutput(expected, renderer.schedule(view));
        assertEquals(generatedAt, view.generatedAt());
        assertEquals(entries, view.entries());
        assertEquals(unallocated, view.unallocated());
    }

    @Test
    void schedule_adjacentMatchingRows_preservesSuppliedBoundaries() {
        ScheduleView view = new ScheduleView(DATE, NOW,
                List.of(createEntry(8, 0, 8, 30, SlotKind.TASK, "T2", "Draft"),
                        createEntry(8, 30, 9, 0, SlotKind.TASK, "T2", "Draft"),
                        createEntry(9, 0, 22, 0, SlotKind.FREE, "", "")), List.of());
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 08:30 | [TASK] T2: Draft (30 min)\n08:30 - 09:00 | [TASK] T2: Draft (30 min)"
                + "\n09:00 - 22:00 | [FREE]", renderer.schedule(view));
    }

    @Test
    void schedule_emptyTaskPlanAndFullyBusy_showsSuppliedRows() {
        Plan empty = new EarliestDeadlinePlanner().generate(Snapshot.empty(), NOW);
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 22:00 | [FREE]", renderer.schedule(new ScheduleService().forDate(empty, DATE)));
        ScheduleView busy = new ScheduleView(DATE, NOW,
                List.of(createEntry(8, 0, 22, 0, SlotKind.BUSY, "C1", "Away")), List.of());
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 22:00 | [BUSY] C1: Away", renderer.schedule(busy));
        ScheduleView unplanned = new ScheduleView(DATE, DATE.atTime(22, 0),
                List.of(createEntry(8, 0, 22, 0, SlotKind.UNPLANNED, "", "")), List.of());
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 22:00"
                + "\n08:00 - 22:00 | [UNPLANNED]\nUNPLANNED periods were before the planning cutoff.",
                renderer.schedule(unplanned));
    }

    private Plan createPlan(Snapshot source, List<StudySession> sessions, List<UnallocatedWork> unallocated) {
        return new Plan(NOW, TimeRules.horizonEnd(NOW), source, sessions, unallocated);
    }

    private UnallocatedWork createWork(Task task, int minutes, UnallocatedReason reason) {
        return new UnallocatedWork(task.id(), task.name(), task.deadline(), minutes, reason);
    }

    private ScheduleEntry createEntry(int startHour, int startMinute, int endHour, int endMinute,
            SlotKind kind, String reference, String name) {
        return new ScheduleEntry(DATE.atTime(startHour, startMinute), DATE.atTime(endHour, endMinute),
                kind, reference, name);
    }

    private void assertOutput(String expected, String actual) {
        assertEquals(expected, actual);
        assertFraming(actual);
    }

    private void assertFraming(String output) {
        assertFalse(output.contains("\r"));
        assertFalse(output.endsWith("\n"));
    }
}
