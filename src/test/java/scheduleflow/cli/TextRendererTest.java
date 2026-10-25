//@@author ka-how
package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.ScheduleService;
import scheduleflow.planning.ScheduleView;
import scheduleflow.planning.SlotKind;
import scheduleflow.planning.StudyPolicy;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.TimeRules;
import scheduleflow.planning.UnallocatedReason;
import scheduleflow.planning.UnallocatedWork;

/**
 * Verifies public output using fixed records without console, storage or clock dependencies.
 */
@Isolated("Locale checks temporarily change JVM defaults.")
class TextRendererTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2027, 1, 1, 9, 5);
    private static final String LONG_NAME = "项目 préparation 📚 " + "a".repeat(150);
    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime NOW = DATE.atTime(7, 0);
    private final TextRenderer renderer = new TextRenderer();

    @Test
    void unavailablePeriodsAdded_continuousBatch_matchesHandoffAndPreservesInput() {
        LocalDate first = LocalDate.of(2026, 10, 26);
        List<Commitment> added = new ArrayList<>(List.of(
                oneTime(1, "Trip", first, LocalTime.of(15, 0), 540),
                oneTime(2, "Trip", first.plusDays(1), LocalTime.MIDNIGHT, 1440),
                oneTime(3, "Trip", first.plusDays(2), LocalTime.MIDNIGHT, 660)));
        List<Commitment> original = List.copyOf(added);
        String expected = "Blocked 3 unavailable periods."
                + "\nC1 | 2026-10-26 | 15:00 - 24:00 | Trip"
                + "\nC2 | 2026-10-27 | 00:00 - 24:00 | Trip"
                + "\nC3 | 2026-10-28 | 00:00 - 11:00 | Trip"
                + "\nSaved unavailable periods. Run plan to regenerate.";
        assertOutput(expected, renderer.unavailablePeriodsAdded(added));
        assertOutput(expected, renderer.unavailablePeriodsAdded(added));
        assertEquals(original, added);
    }

    @Test
    void unavailablePeriodsAdded_dailyServiceBatch_includesOnlyNewIdsAndUnicodeNames() {
        LocalDate first = LocalDate.of(2028, 2, 28);
        Snapshot source = new Snapshot(List.of(),
                List.of(new Commitment(2, "Existing", DayOfWeek.MONDAY, LocalTime.of(9, 0), 30)), 1, 3);
        Snapshot candidate = new CommitmentService().addDailyBlocks(source, LONG_NAME, first,
                first.plusDays(2), LocalTime.of(15, 0), LocalTime.of(17, 0));
        List<Commitment> added = candidate.commitments().stream()
                .filter(commitment -> commitment.id() >= source.nextCommitmentId()).toList();
        assertOutput("Blocked 3 unavailable periods."
                + "\nC3 | 2028-02-28 | 15:00 - 17:00 | " + LONG_NAME
                + "\nC4 | 2028-02-29 | 15:00 - 17:00 | " + LONG_NAME
                + "\nC5 | 2028-03-01 | 15:00 - 17:00 | " + LONG_NAME
                + "\nSaved unavailable periods. Run plan to regenerate.", renderer.unavailablePeriodsAdded(added));
        assertEquals(1, source.commitments().size());
        assertEquals(4, candidate.commitments().size());
    }

    @Test
    void unavailablePeriodsAdded_singlePeriod_formatsSingularAndBoundaryYears() {
        for (LocalDate date : List.of(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31))) {
            Commitment added = oneTime(10, LONG_NAME, date, LocalTime.of(23, 30), 30);
            assertOutput("Blocked 1 unavailable period.\nC10 | " + date + " | 23:30 - 24:00 | " + LONG_NAME
                    + "\nSaved unavailable periods. Run plan to regenerate.",
                    renderer.unavailablePeriodsAdded(List.of(added)));
        }
    }

    @Test
    void unavailablePeriodsAdded_invalidBatch_rejectsWithoutChangingInput() {
        assertEquals("added", assertThrows(NullPointerException.class,
                () -> renderer.unavailablePeriodsAdded(null)).getMessage());
        Commitment dated = oneTime(1, "Trip", DATE, LocalTime.NOON, 60);
        List<Commitment> withNull = Arrays.asList(dated, null);
        assertEquals("added element", assertThrows(NullPointerException.class,
                () -> renderer.unavailablePeriodsAdded(withNull)).getMessage());
        assertEquals(Arrays.asList(dated, null), withNull);
        assertThrows(IllegalArgumentException.class, () -> renderer.unavailablePeriodsAdded(List.of()));
        Commitment weekly = new Commitment(2, "Class", DayOfWeek.MONDAY, LocalTime.of(9, 0), 30);
        assertThrows(IllegalArgumentException.class,
                () -> renderer.unavailablePeriodsAdded(List.of(dated, weekly)));
    }

    @Test
    void taskProgressUpdated_exactCreditAndRemaining_formatsZeroPartialAndCompletedWork() {
        Task active = Task.withoutDeadline(10, LONG_NAME, 45);
        assertOutput("Recorded progress for T10: " + LONG_NAME + " | Credited: 15 min | Remaining: 45 min",
                renderer.taskProgressUpdated(active, 15));
        assertOutput("Recorded progress for T10: " + LONG_NAME + " | Credited: 0 min | Remaining: 45 min",
                renderer.taskProgressUpdated(active, 0));
        Task completed = Task.withoutDeadline(10, LONG_NAME, 0);
        assertOutput("Recorded progress for T10: " + LONG_NAME + " | Credited: 45 min | Remaining: 0 min",
                renderer.taskProgressUpdated(completed, 45));
        assertOutput("Recorded progress for T10: " + LONG_NAME
                + " | Credited: 2147483647 min | Remaining: 0 min",
                renderer.taskProgressUpdated(completed, Integer.MAX_VALUE));
        assertEquals(45, active.remainingMinutes());
        assertEquals(0, completed.remainingMinutes());
        assertThrows(IllegalArgumentException.class, () -> renderer.taskProgressUpdated(active, -1));
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.taskProgressUpdated(null, 0)).getMessage());
    }

    @Test
    void taskLifecycleMessages_validatedRecords_preserveIdentityAndExactRemainingWork() {
        Task completed = new Task(10, LONG_NAME, DEADLINE, 0);
        Task reopened = Task.withoutDeadline(10, LONG_NAME, 1);
        assertOutput("Completed T10: " + LONG_NAME, renderer.taskCompleted(completed));
        assertOutput("Reopened T10: " + LONG_NAME + " | Remaining: 1 min", renderer.taskReopened(reopened));
        assertEquals(0, completed.remainingMinutes());
        assertEquals(1, reopened.remainingMinutes());
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.taskCompleted(null)).getMessage());
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.taskReopened(null)).getMessage());
    }

    @Test
    void recurringTasks_exactMessages_preserveDefinitionsAndFraming() {
        RecurringTask bounded = new RecurringTask(2, LONG_NAME, DEADLINE,
                Optional.of(DEADLINE.toLocalDate().plusWeeks(1)), 90, Optional.of(DEADLINE));
        RecurringTask unbounded = new RecurringTask(10, "Review", DEADLINE, Optional.empty(), 30, Optional.empty());
        String details = "Weekly from: 2027-01-01 09:05 | Duration: 90 min | Until: 2027-01-08";
        assertOutput("Added R2: " + LONG_NAME + "\n" + details, renderer.recurringTaskAdded(bounded));
        assertOutput("Deleted recurring task R2: " + LONG_NAME + "\nExisting task occurrences are retained.",
                renderer.recurringTaskDeleted(bounded));
        assertOutput("No recurring tasks found.", renderer.recurringTasks(List.of()));
        assertOutput("=== Weekly Recurring Tasks ===\nR2 | " + LONG_NAME + " | " + details
                + "\nR10 | Review | Weekly from: 2027-01-01 09:05 | Duration: 30 min | Until: No end date"
                + "\nTotal: 2 recurring task(s).", renderer.recurringTasks(List.of(bounded, unbounded)));
        assertOutput("Edited T2: " + LONG_NAME + "\nDue: 2027-01-01 09:05 | Duration: 90 min",
                renderer.taskEdited(new Task(2, LONG_NAME, DEADLINE, 90)));
    }

    @Test
    void newTaskMessages_nullInputs_rejectWithArgumentNames() {
        assertEquals("task", assertThrows(NullPointerException.class, () -> renderer.taskEdited(null)).getMessage());
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.recurringTaskAdded(null)).getMessage());
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.recurringTaskDeleted(null)).getMessage());
        assertEquals("tasks", assertThrows(NullPointerException.class,
                () -> renderer.recurringTasks(null)).getMessage());
        assertEquals("tasks element", assertThrows(NullPointerException.class,
                () -> renderer.recurringTasks(Arrays.asList((RecurringTask) null))).getMessage());
    }
    // @@author nickpatt
    @Test
    void existingTaskOutput_absentDeadlineAndExactMinutes_usesExplicitAbsence() {
        Task task = Task.withoutDeadline(1, "Draft", 45);
        assertOutput("Added T1: Draft\nDue: No deadline | Duration: 45 min", renderer.taskAdded(task));
        assertOutput("=== Actionable Tasks ===\nID | Name | Duration | Deadline"
                + "\nT1 | Draft | 45 min | No deadline\nTotal: 1 task(s).", renderer.tasks(List.of(task)));
        UnallocatedWork work = new UnallocatedWork(1, "Draft", Optional.empty(), 15, 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY);
        Plan plan = new Plan(NOW, TimeRules.horizonEnd(NOW), new Snapshot(List.of(task), List.of(), 2, 1),
                List.of(new StudySession(1, DATE.atTime(8, 0), DATE.atTime(8, 30))), List.of(work));
        String remainder = "T1: Draft | Unallocated: 15 min | Reason: insufficient available study time"
                + " | No deadline";
        assertOutput("Study plan generated with unallocated work.\nScheduled: 30 min | Unallocated: 15 min\n"
                + remainder, renderer.plan(plan));
        assertTrue(renderer.schedule(new ScheduleService().forDate(plan, DATE)).endsWith(remainder));
    }

    @Test
    void existingPlanSummary_paddedSession_totalsExactWorkInsteadOfCapacity() {
        Task task = new Task(1, "Draft", DEADLINE, 45);
        Task overdue = new Task(2, "Old", NOW, 1);
        Plan plan = new EarliestDeadlinePlanner().generate(
                new Snapshot(List.of(task, overdue), List.of(), 3, 1), NOW);
        assertOutput("Study plan generated with unallocated work.\nScheduled: 45 min | Unallocated: 1 min"
                + "\nT2: Old | Unallocated: 1 min | Reason: deadline passed", renderer.plan(plan));
    }
    // @@author ka-how

    //@@author Darren50
    @Test
    void commitmentEdited_reportsStoredFieldsAndMidnight() {
        assertOutput("Edited C12: 新 lecture | Sunday 23:30 - 24:00 (30 min)", renderer.commitmentEdited(
                new Commitment(12, "新 lecture", DayOfWeek.SUNDAY, LocalTime.of(23, 30), 30)));
        assertOutput("Edited C1: Full day | Monday 00:00 - 24:00 (1440 min)", renderer.commitmentEdited(
                new Commitment(1, "Full day", DayOfWeek.MONDAY, LocalTime.MIDNIGHT, 1440)));
        assertEquals("commitment", assertThrows(NullPointerException.class,
                () -> renderer.commitmentEdited(null)).getMessage());
    }
    //@@author ka-how

    @Test
    void publicMethods_nullInputs_rejectWithArgumentNames() {
        assertEquals("task", assertThrows(NullPointerException.class, () -> renderer.taskAdded(null)).getMessage());
        assertEquals("task", assertThrows(NullPointerException.class, () -> renderer.taskDeleted(null)).getMessage());
        // @@author ernestgoh-god
        assertEquals("task", assertThrows(NullPointerException.class,
                () -> renderer.taskRemainingUpdated(null)).getMessage());
        // @@author ka-how
        assertEquals("tasks", assertThrows(NullPointerException.class, () -> renderer.tasks(null)).getMessage());
        assertEquals("commitment",
                assertThrows(NullPointerException.class, () -> renderer.commitmentAdded(null)).getMessage());
        assertEquals("commitment",
                assertThrows(NullPointerException.class, () -> renderer.commitmentDeleted(null)).getMessage());
        assertEquals("commitments",
                assertThrows(NullPointerException.class, () -> renderer.commitments(null)).getMessage());
        assertEquals("plan", assertThrows(NullPointerException.class, () -> renderer.plan(null)).getMessage());
        assertEquals("view", assertThrows(NullPointerException.class, () -> renderer.schedule(null)).getMessage());
        assertEquals("message", assertThrows(NullPointerException.class, () -> renderer.error(null)).getMessage());
        assertEquals("exception",
                assertThrows(NullPointerException.class, () -> renderer.parseError(null)).getMessage());
    }

    @Test
    void lists_nullElements_rejectWithoutModifyingSuppliedLists() {
        Task task = new Task(1, "A", DEADLINE, 30);
        List<Task> tasks = Arrays.asList(task, null);
        Commitment commitment = new Commitment(1, "A", DayOfWeek.MONDAY, LocalTime.of(10, 0), 30);
        List<Commitment> commitments = Arrays.asList(commitment, null);
        assertEquals("tasks element",
                assertThrows(NullPointerException.class, () -> renderer.tasks(tasks)).getMessage());
        assertEquals("commitments element",
                assertThrows(NullPointerException.class, () -> renderer.commitments(commitments)).getMessage());
        assertEquals(Arrays.asList(task, null), tasks);
        assertEquals(Arrays.asList(commitment, null), commitments);
        assertOutput("No tasks found.", renderer.tasks(List.of()));
        assertOutput("No commitments found.", renderer.commitments(List.of()));
    }

    @Test
    void help_allCommands_includesFormatsAndDescriptions() {
        String output = renderer.help();
        assertTrue(output.startsWith("=== ScheduleFlow Command Help ===\n"));
        List<String> formats = List.of("help", "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES", "task list",
                "task delete TNUMBER",
                "task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]",
                "task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES every/WEEKLY [until/YYYY-MM-DD]",
                "task recur list", "task recur delete RNUMBER",
                // @@author ernestgoh-god
                "task remaining TNUMBER remaining/MINUTES",
                // @@author ka-how
                "task progress TNUMBER done/MINUTES", "task progress TNUMBER session/MINUTES percent/PERCENT",
                "commitment add n/NAME day/DAY start/HHmm d/MINUTES"
                        + " [from/YYYY-MM-DD until/YYYY-MM-DD]",
                "commitment add n/NAME on/YYYY-MM-DD start/HHmm d/MINUTES", "commitment list",
                "commitment delete CNUMBER", "commitment skip CNUMBER on/YYYY-MM-DD",
                "hours DAY|YYYY-MM-DD /start HH:mm /end HH:mm",
                "hours DAY|YYYY-MM-DD", "hours reset DAY|YYYY-MM-DD",
                "commitment edit CNUMBER [n/NAME] [day/DAY|on/YYYY-MM-DD] [start/HHmm] [d/MINUTES]"
                        + " [from/YYYY-MM-DD until/YYYY-MM-DD]", "plan",
                "schedule today OR schedule YYYY-MM-DD", "exit");
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

    // @@author ernestgoh-god
    @Test
    void taskRemainingUpdated_stableIdAndUnicodeName_reportsExactEstimate() {
        Task task = new Task(10, LONG_NAME, DEADLINE, 90);
        assertOutput("Remaining time for T10: " + LONG_NAME + " | 90 min", renderer.taskRemainingUpdated(task));
        assertEquals(90, task.remainingMinutes());
    }
    // @@author ka-how

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
    void tasks_boundaryYears_preservesFourDigitDatesAndMinutePrecision() {
        Task earliest = new Task(1, "Early", LocalDateTime.of(1, 1, 1, 0, 0), 30);
        Task latest = new Task(2, "Late", LocalDateTime.of(9999, 12, 31, 23, 59), 60);
        assertOutput("Added T1: Early\nDue: 0001-01-01 00:00 | Duration: 30 min", renderer.taskAdded(earliest));
        assertOutput("Added T2: Late\nDue: 9999-12-31 23:59 | Duration: 60 min", renderer.taskAdded(latest));
        assertOutput("=== Actionable Tasks ===\nID | Name | Duration | Deadline"
                + "\nT1 | Early | 30 min | 0001-01-01 00:00\nT2 | Late | 60 min | 9999-12-31 23:59"
                + "\nTotal: 2 task(s).", renderer.tasks(List.of(earliest, latest)));
    }

    @Test
    void rendering_nonEnglishLocales_preservesContractText() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            for (Locale locale : List.of(Locale.FRANCE, Locale.forLanguageTag("ar-SA"))) {
                Locale.setDefault(locale);
                TextRenderer localizedRenderer = new TextRenderer();
                Task task = new Task(1, "Draft", DEADLINE, 90);
                Commitment late = new Commitment(1, "Review", DayOfWeek.MONDAY, LocalTime.of(23, 30), 30);
                ScheduleView free = new ScheduleView(DATE, NOW,
                        List.of(createEntry(8, 0, 22, 0, SlotKind.FREE, "", "")), List.of());
                assertOutput("Added T1: Draft\nDue: 2027-01-01 09:05 | Duration: 90 min",
                        localizedRenderer.taskAdded(task));
                assertOutput("Added C1: Review | Monday 23:30 - 24:00 (30 min)",
                        localizedRenderer.commitmentAdded(late));
                assertOutput("=== Recurring Weekly Commitments ===\n[Monday]\nC1. 23:30 - 24:00 | Review"
                        + "\n\nTotal: 1 recurring commitments across the week.",
                        localizedRenderer.commitments(List.of(late)));
                assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                        + "\n08:00 - 22:00 | [FREE]", localizedRenderer.schedule(free));
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
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

    //@@author Darren50
    @Test
    void commitmentMessages_boundedSeries_displaysInclusiveRange() {
        Commitment bounded = new Commitment(4, "Semester lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 11, 16));
        String range = " | 2026-08-10 to 2026-11-16";
        assertOutput("Added C4: Semester lecture | Monday 10:00 - 12:00 (120 min)" + range,
                renderer.commitmentAdded(bounded));
        assertOutput("Edited C4: Semester lecture | Monday 10:00 - 12:00 (120 min)" + range,
                renderer.commitmentEdited(bounded));
        assertOutput("=== Recurring Weekly Commitments ===\n[Monday]\n"
                + "C4. 10:00 - 12:00 | Semester lecture" + range
                + "\n\nTotal: 1 recurring commitments across the week.", renderer.commitments(List.of(bounded)));
    }

    @Test
    void commitmentMessages_skippedOccurrences_displayChronologically() {
        LocalDate first = LocalDate.of(2026, 8, 10);
        LocalDate second = first.plusWeeks(1);
        Commitment commitment = new Commitment(4, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, second, Set.of(second, first));
        assertOutput("Skipped C4 on 2026-08-10: Lecture", renderer.commitmentSkipped(commitment, first));
        assertOutput("=== Recurring Weekly Commitments ===\n[Monday]\nC4. 10:00 - 11:00 | Lecture"
                + " | 2026-08-10 to 2026-08-17 | skipped: 2026-08-10, 2026-08-17"
                + "\n\nTotal: 1 recurring commitments across the week.",
                renderer.commitments(List.of(commitment)));
    }

    @Test
    void commitmentMessages_oneTime_namesExactDateInsteadOfWeeklyRange() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment meeting = new Commitment(5, "Project meeting", DayOfWeek.FRIDAY, LocalTime.of(23, 30), 30,
                friday, friday);
        String timing = " | One-time on 2026-10-30 (Friday) 23:30 - 24:00 (30 min)";
        assertOutput("Added C5: Project meeting" + timing, renderer.commitmentAdded(meeting));
        assertOutput("Edited C5: Project meeting" + timing, renderer.commitmentEdited(meeting));
        assertOutput("Deleted commitment C5: Project meeting", renderer.commitmentDeleted(meeting));
    }

    @Test
    void commitments_oneTimeOnly_listsDatedSectionWithoutWeeklyHeading() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment later = new Commitment(2, "Later", DayOfWeek.MONDAY, LocalTime.of(9, 0), 60,
                friday.plusDays(3), friday.plusDays(3));
        Commitment meeting = new Commitment(7, "Meeting", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60, friday, friday);
        assertOutput("=== One-time Commitments ===\nC7. 2026-10-30 (Friday) 14:00 - 15:00 | Meeting"
                + "\nC2. 2026-11-02 (Monday) 09:00 - 10:00 | Later\nTotal: 2 one-time commitments.",
                renderer.commitments(List.of(later, meeting)));
    }

    @Test
    void commitments_mixedKinds_separatesSectionsAndCountsEachKind() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment weekly = new Commitment(1, "Lecture", DayOfWeek.FRIDAY, LocalTime.of(10, 0), 60);
        Commitment early = new Commitment(3, "Early", DayOfWeek.FRIDAY, LocalTime.of(8, 0), 30, friday, friday);
        Commitment late = new Commitment(2, "Late", DayOfWeek.FRIDAY, LocalTime.of(18, 0), 30, friday, friday);
        String output = renderer.commitments(List.of(early, weekly, late));
        assertOutput("=== Recurring Weekly Commitments ===\n[Friday]\nC1. 10:00 - 11:00 | Lecture"
                + "\n\nTotal: 1 recurring commitments across the week.\n\n=== One-time Commitments ==="
                + "\nC3. 2026-10-30 (Friday) 08:00 - 08:30 | Early\nC2. 2026-10-30 (Friday) 18:00 - 18:30 | Late"
                + "\nTotal: 2 one-time commitments.", output);
        assertFraming(output);
    }
    //@@author ka-how

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
    void schedule_configuredPlanner_rendersFrozenBreakWithoutChangingAllocation() {
        Task task = new Task(1, LONG_NAME, DATE.atTime(12, 0), 150);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
        Plan plan = new EarliestDeadlinePlanner(new StudyPolicy(90, 30)).generate(source, NOW);
        ScheduleView view = new ScheduleService().forDate(plan, DATE);
        assertOutput("=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 09:30 | [TASK] T1: " + LONG_NAME + " (90 min)"
                + "\n09:30 - 10:00 | [BREAK]"
                + "\n10:00 - 11:00 | [TASK] T1: " + LONG_NAME + " (60 min)"
                + "\n11:00 - 22:00 | [FREE]", renderer.schedule(view));
        assertEquals(List.of(new ScheduleEntry(DATE.atTime(9, 30), DATE.atTime(10, 0),
                SlotKind.BREAK, "", "")), plan.breaks());
        assertEquals(150, source.tasks().getFirst().remainingMinutes());
    }

    @Test
    void schedule_adjacentBreaks_retainsSuppliedBoundariesAndFooter() {
        List<ScheduleEntry> entries = List.of(createEntry(8, 0, 8, 30, SlotKind.UNPLANNED, "", ""),
                createEntry(8, 30, 9, 0, SlotKind.BREAK, "", ""),
                createEntry(9, 0, 9, 30, SlotKind.BREAK, "", ""),
                createEntry(9, 30, 22, 0, SlotKind.FREE, "", ""));
        Task task = new Task(1, "Draft", DATE.atTime(9, 30), 30);
        ScheduleView view = new ScheduleView(DATE, NOW, entries,
                List.of(createWork(task, 30, UnallocatedReason.INSUFFICIENT_CAPACITY)));
        String expected = "=== Schedule for Monday (2026-09-21) ===\nGenerated at: 2026-09-21 07:00"
                + "\n08:00 - 08:30 | [UNPLANNED]\n08:30 - 09:00 | [BREAK]\n09:00 - 09:30 | [BREAK]"
                + "\n09:30 - 22:00 | [FREE]\nUNPLANNED periods were before the planning cutoff."
                + "\n\nUnallocated work for the whole plan:"
                + "\nT1: Draft | Unallocated: 30 min | Reason: insufficient time before deadline";
        assertOutput(expected, renderer.schedule(view));
        assertOutput(expected, renderer.schedule(view));
        assertEquals(entries, view.entries());
    }

    @Test
    void schedule_leapDay_usesViewDateAndOriginalGenerationDate() {
        LocalDate leapDay = LocalDate.of(2024, 2, 29);
        LocalDateTime generatedAt = LocalDateTime.of(2024, 2, 28, 21, 45);
        ScheduleEntry free = new ScheduleEntry(leapDay.atTime(8, 0), leapDay.atTime(22, 0),
                SlotKind.FREE, "", "");
        ScheduleView view = new ScheduleView(leapDay, generatedAt, List.of(free), List.of());
        assertOutput("=== Schedule for Thursday (2024-02-29) ===\nGenerated at: 2024-02-28 21:45"
                + "\n08:00 - 22:00 | [FREE]", renderer.schedule(view));
        assertEquals(leapDay, view.date());
        assertEquals(generatedAt, view.generatedAt());
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

    /** Creates a validated dated commitment without clock or storage dependencies. */
    private Commitment oneTime(int id, String name, LocalDate date, LocalTime start, int minutes) {
        return new Commitment(id, name, date.getDayOfWeek(), start, minutes, date, date);
    }

    /**
     * Constructs a frozen result with the fixed horizon and supplied allocation rows.
     */
    private Plan createPlan(Snapshot source, List<StudySession> sessions, List<UnallocatedWork> unallocated) {
        return new Plan(NOW, TimeRules.horizonEnd(NOW), source, sessions, unallocated);
    }

    /**
     * Retains task metadata while selecting its remaining minutes and reason.
     */
    private UnallocatedWork createWork(Task task, int minutes, UnallocatedReason reason) {
        return new UnallocatedWork(task.id(), task.name(), task.deadline().orElseThrow(), minutes, reason);
    }

    /**
     * Constructs one interval on the fixed test date without projecting or merging it.
     */
    private ScheduleEntry createEntry(int startHour, int startMinute, int endHour, int endMinute,
            SlotKind kind, String reference, String name) {
        return new ScheduleEntry(DATE.atTime(startHour, startMinute), DATE.atTime(endHour, endMinute),
                kind, reference, name);
    }

    /**
     * Checks exact content together with the formatter's line-ending contract.
     */
    private void assertOutput(String expected, String actual) {
        assertEquals(expected, actual);
        assertFraming(actual);
    }

    /**
     * Requires LF-only separators and leaves the final newline to the console UI.
     */
    private void assertFraming(String output) {
        assertFalse(output.contains("\r"));
        assertFalse(output.endsWith("\n"));
    }
}
