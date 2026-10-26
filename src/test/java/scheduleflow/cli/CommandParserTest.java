package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.task.TaskEdit;

/**
 * Verifies stage 1 grammar and transport values independently of services, storage and the clock.
 */
class CommandParserTest {
    private static final String HELP_USAGE = "Type help to see available commands";
    private static final String TASK_ADD_USAGE = "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES";
    private static final String TASK_DELETE_USAGE = "task delete TNUMBER";
    private static final String DATE_RANGE_USAGE = " [from/YYYY-MM-DD until/YYYY-MM-DD]";
    private static final String COMMITMENT_ADD_USAGE = "commitment add n/NAME day/DAY start/HHmm d/MINUTES"
            + DATE_RANGE_USAGE + " OR commitment add n/NAME on/YYYY-MM-DD start/HHmm d/MINUTES";
    private static final String COMMITMENT_EDIT_USAGE =
            "commitment edit CNUMBER [n/NAME] [day/DAY|on/YYYY-MM-DD] [start/HHmm] [d/MINUTES]" + DATE_RANGE_USAGE;
    private static final String COMMITMENT_DELETE_USAGE = "commitment delete CNUMBER";
    private static final String COMMITMENT_SKIP_USAGE = "commitment skip CNUMBER on/YYYY-MM-DD";
    private static final String SCHEDULE_USAGE = "schedule today OR schedule YYYY-MM-DD";
    private static final String UNAVAILABLE_CONTINUOUS_USAGE =
            "unavailable continuous n/NAME from/YYYY-MM-DD HHmm until/YYYY-MM-DD HHmm";
    private static final String UNAVAILABLE_DAILY_USAGE =
            "unavailable daily n/NAME from/YYYY-MM-DD until/YYYY-MM-DD start/HHmm end/HHmm";
    private final CommandParser parser = new CommandParser();

    @Test
    void parse_reopen_acceptsExactMinutesSpacingAndIntegerBoundaries() throws ParseException {
        assertEquals(new Command.ReopenTask(1, 1), parser.parse("task reopen T1 remaining/1"));
        assertEquals(new Command.ReopenTask(2, 45), parser.parse(" task  reopen  T2  remaining/45 "));
        assertEquals(new Command.ReopenTask(Integer.MAX_VALUE, Integer.MAX_VALUE),
                parser.parse("task reopen T2147483647 remaining/2147483647"));
    }

    @Test
    void parse_reopen_rejectsMalformedFieldsAndOverflowWithUsage() {
        String usage = "task reopen TNUMBER remaining/MINUTES";
        for (String suffix : List.of("", "T1", "1 remaining/45", "C1 remaining/45", "t1 remaining/45",
                "T0 remaining/45", "T01 remaining/45", "T-1 remaining/45", "T2147483648 remaining/45",
                "T1 remaining/0", "T1 remaining/-1", "T1 remaining/+1", "T1 remaining/01",
                "T1 remaining/1.5", "T1 remaining/2147483648", "T1 remaining/４５",
                "remaining/45 T1", "T1 d/45", "T1 remaining/45 remaining/60", "T1 remaining/45 extra",
                "T1\tremaining/45")) {
            assertRejected("task reopen " + suffix, usage);
        }
        assertRejected("task Reopen T1 remaining/45", HELP_USAGE);
        assertRejected("Task reopen T1 remaining/45", HELP_USAGE);
    }

    @Test
    void parse_policy_preservesDurationsAndRejectsMalformedOrOverflowingFields() throws ParseException {
        assertEquals(new Command.ShowStudyPolicy(), parser.parse(" policy  show "));
        assertEquals(new Command.ResetStudyPolicy(), parser.parse("policy reset"));
        assertEquals(new Command.SetStudyPolicy(90, 30), parser.parse("policy set max/90 break/30"));
        assertEquals(new Command.SetStudyPolicy(0, 45), parser.parse("policy set max/0 break/45"));
        assertEquals(new Command.SetStudyPolicy(Integer.MAX_VALUE, 30),
                parser.parse("policy set max/2147483647 break/30"));
        for (String input : List.of("policy", "policy show extra", "policy reset max/90", "policy set max/90",
                "policy Set max/90 break/30", "policy set break/30 max/90", "policy set max/-1 break/30",
                "policy set max/01 break/30", "policy set max/90 break/2147483648",
                "policy set max/90 break/30 break/30", "policy set max/90 break/30 extra")) {
            assertEquals("policy set max/MINUTES break/MINUTES OR policy show OR policy reset",
                    assertThrows(ParseException.class, () -> parser.parse(input)).usage());
        }
    }

    @Test
    void parse_completionAndCompletedView_acceptsSpacingAndLargestIdentifier() throws ParseException {
        assertEquals(new Command.CompleteTask(Integer.MAX_VALUE), parser.parse(" task  complete T2147483647 "));
        assertEquals(new Command.ListCompletedTasks(), parser.parse("task  list completed"));
        assertEquals(new Command.ListTasks(), parser.parse("task list"));
    }

    @Test
    void parse_completionAndCompletedView_rejectsMalformedAndOverflowingInput() {
        for (String input : List.of("task complete", "task complete T0", "task complete T01",
                "task complete R1", "task complete t1", "task complete T2147483648", "task complete T1 extra")) {
            assertEquals("task complete TNUMBER", assertThrows(ParseException.class,
                    () -> parser.parse(input)).usage());
        }
        for (String input : List.of("task list complete", "task list Completed", "task list completed extra")) {
            assertEquals("task list OR task list completed", assertThrows(ParseException.class,
                    () -> parser.parse(input)).usage());
        }
    }

    @Test
    void parse_taskEdit_preservesAllThreeSubsets() throws ParseException {
        LocalDateTime due = LocalDateTime.of(2028, 2, 29, 23, 59);
        assertEquals(new Command.EditTask(12, new TaskEdit(Optional.of("New  name"), Optional.empty(), false)),
                parser.parse(" task  edit T12 n/ New  name "));
        assertEquals(new Command.EditTask(12, new TaskEdit(Optional.empty(), Optional.of(due), false)),
                parser.parse("task edit T12 due/2028-02-29 2359"));
        assertEquals(new Command.EditTask(12, new TaskEdit(Optional.of("New"), Optional.of(due), false)),
                parser.parse("task edit T12 n/New due/2028-02-29 2359"));
    }

    @Test
    void parse_taskEdit_rejectsMalformedFieldsAndIdentifiers() {
        String usage = "task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]";
        for (String suffix : List.of("T1", "R1 n/A", "T01 n/A", "T0 n/A", "T2147483648 n/A",
                "T1 n/A n/B", "T1 due/2026-02-30 0900", "T1 due/0000-01-01 0900",
                "T1 due/2026-10-06 2400", "T1 due/2026-10-06 0900 n/A", "T1 n/A d/30",
                "T1 due/none", "T1 due/2026-10-06 0900 extra", "T1 n/A\tB")) {
            assertRejected("task edit " + suffix, usage);
        }
    }

    @Test
    void parse_recurrence_acceptsBoundedUnboundedAndIndependentIds() throws ParseException {
        LocalDateTime due = LocalDateTime.of(2028, 2, 29, 9, 5);
        String input = "task recur add n/Weekly  review due/2028-02-29 0905 d/90 every/WEEKLY";
        assertEquals(new Command.AddRecurringTask("Weekly  review", due, 90, Optional.empty()), parser.parse(input));
        assertEquals(new Command.AddRecurringTask("Weekly  review", due, 90,
                Optional.of(LocalDate.of(2028, 3, 7))), parser.parse(input + " until/2028-03-07"));
        assertEquals(new Command.ListRecurringTasks(), parser.parse(" task  recur  list "));
        assertEquals(new Command.DeleteRecurringTask(Integer.MAX_VALUE),
                parser.parse("task recur delete R2147483647"));
    }

    @Test
    void parse_recurrence_rejectsStrictDateOrderExtraFieldsAndOverflow() {
        String usage = "task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES"
                + " every/WEEKLY [until/YYYY-MM-DD] OR task recur list OR task recur delete RNUMBER";
        String add = "task recur add n/A due/2026-10-06 0900 d/30 every/WEEKLY";
        for (String input : List.of(add.replace("2026-10-06", "2026-02-30"),
                add.replace("2026-10-06", "0000-01-01"), add + " until/2026-02-29",
                add.replace("d/30", "d/2147483648"), add.replace("d/30", "d/030"),
                add.replace("every/WEEKLY", "every/weekly"), add.replace("every/WEEKLY", "every/DAILY"),
                add.replace("d/30 every/WEEKLY", "every/WEEKLY d/30"), add + " d/60", add + " extra",
                add + " until/2026-11-01 until/2026-12-01", "task recur list extra",
                "task recur delete T1", "task recur delete R01", "task recur delete R0",
                "task recur delete R2147483648", "task recur delete R1 extra")) {
            assertRejected(input, usage);
        }
    }

    @Test
    void parse_unavailableContinuous_preservesNamesDatesAndTimesWithoutDomainValidation() throws ParseException {
        assertEquals(new Command.AddContinuousBlock("Trip  Café | 复习",
                LocalDateTime.of(2026, 10, 26, 15, 0), LocalDateTime.of(2026, 10, 28, 11, 0)),
                parser.parse("  unavailable  continuous  n/ Trip  Café | 复习  from/2026-10-26  1500"
                        + "  until/2026-10-28  1100  "));
        assertEquals(new Command.AddContinuousBlock("Same", LocalDateTime.of(2028, 2, 29, 15, 15),
                LocalDateTime.of(2028, 2, 29, 15, 15)),
                parser.parse("unavailable continuous n/Same from/2028-02-29 1515 until/2028-02-29 1515"));
    }

    @Test
    void parse_unavailableDaily_preservesLeapAndBoundaryDatesWithoutDomainValidation() throws ParseException {
        assertEquals(new Command.AddDailyBlocks("Appointment", LocalDate.of(2028, 2, 28),
                LocalDate.of(2028, 3, 1), LocalTime.of(15, 0), LocalTime.of(17, 0)),
                parser.parse("unavailable daily n/Appointment from/2028-02-28"
                        + " until/2028-03-01 start/1500 end/1700"));
        assertEquals(new Command.AddDailyBlocks("Boundary", LocalDate.of(9999, 12, 31),
                LocalDate.of(1, 1, 1), LocalTime.of(23, 59), LocalTime.MIDNIGHT),
                parser.parse("unavailable daily n/Boundary from/9999-12-31"
                        + " until/0001-01-01 start/2359 end/0000"));
    }

    @Test
    void parse_unavailable_rejectsMissingReorderedDuplicateMixedAndExtraFields() {
        for (String input : List.of("unavailable continuous", "unavailable continuous n/A",
                "unavailable continuous from/2026-10-26 1500 until/2026-10-28 1100",
                "unavailable continuous n/A until/2026-10-28 1100 from/2026-10-26 1500",
                "unavailable continuous n/A from/2026-10-26 1500 from/2026-10-27 0000"
                        + " until/2026-10-28 1100",
                "unavailable continuous n/A from/2026-10-26 1500 until/2026-10-28 1100 extra",
                "unavailable continuous n/A from/2026-10-26 1500 until/2026-10-28 1100 start/1500",
                "unavailable continuous n/A/B from/2026-10-26 1500 until/2026-10-28 1100",
                "unavailable continuous n/A from/2026-10-26 150 until/2026-10-28 1100")) {
            assertUnavailableSyntaxRejected(input, UNAVAILABLE_CONTINUOUS_USAGE);
        }
        for (String input : List.of("unavailable daily", "unavailable daily n/A from/2026-10-26",
                "unavailable daily n/A until/2026-10-28 from/2026-10-26 start/1500 end/1700",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 end/1700 start/1500",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 start/1500 start/1600 end/1700",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 start/1500 end/1700 extra",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 start/1500 end/1700"
                        + " from/2026-10-29",
                "unavailable daily n/A from/2026-10-26 1500 until/2026-10-28 start/1500 end/1700",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 start/1500 end/17000")) {
            assertUnavailableSyntaxRejected(input, UNAVAILABLE_DAILY_USAGE);
        }
        ParseException unknown = assertThrows(ParseException.class, () -> parser.parse("unavailable weekly n/A"));
        assertEquals("Invalid unavailable command format", unknown.getMessage());
        assertEquals(UNAVAILABLE_CONTINUOUS_USAGE + " OR " + UNAVAILABLE_DAILY_USAGE, unknown.usage());
    }

    @Test
    void parse_unavailable_rejectsInvalidCalendarDatesYearsAndTimesWithModeUsage() {
        for (String input : List.of(
                "unavailable continuous n/A from/2026-02-30 1500 until/2026-03-01 1100",
                "unavailable continuous n/A from/2026-10-26 2400 until/2026-10-28 1100",
                "unavailable daily n/A from/2026-10-26 until/2026-02-29 start/1500 end/1700",
                "unavailable daily n/A from/2026-10-26 until/2026-10-28 start/1500 end/2460")) {
            ParseException failure = assertThrows(ParseException.class, () -> parser.parse(input), input);
            assertEquals("Invalid date or time", failure.getMessage());
            assertEquals(input.startsWith("unavailable continuous") ? UNAVAILABLE_CONTINUOUS_USAGE
                    : UNAVAILABLE_DAILY_USAGE, failure.usage());
        }
        for (String input : List.of(
                "unavailable continuous n/A from/0000-01-01 1500 until/2026-10-28 1100",
                "unavailable daily n/A from/2026-10-26 until/0000-01-01 start/1500 end/1700")) {
            ParseException failure = assertThrows(ParseException.class, () -> parser.parse(input), input);
            assertEquals("Year must be between 0001 and 9999", failure.getMessage());
            assertEquals(input.startsWith("unavailable continuous") ? UNAVAILABLE_CONTINUOUS_USAGE
                    : UNAVAILABLE_DAILY_USAGE, failure.usage());
        }
    }

    @Test
    void parse_unavailable_embeddedControlsRetainSelectedModeUsage() {
        ParseException failure = assertThrows(ParseException.class, () -> parser.parse(
                "unavailable continuous n/A\tB from/2026-10-26 1500 until/2026-10-28 1100"));
        assertEquals("Use ordinary spaces instead of embedded control characters", failure.getMessage());
        assertEquals(UNAVAILABLE_CONTINUOUS_USAGE, failure.usage());
    }

    /** Checks the specified mode's exact syntax error and usage hint. */
    private void assertUnavailableSyntaxRejected(String input, String usage) {
        ParseException failure = assertThrows(ParseException.class, () -> parser.parse(input), input);
        assertEquals("Invalid unavailable command format", failure.getMessage(), input);
        assertEquals(usage, failure.usage(), input);
    }

    @Test
    void parse_taskProgress_acceptsBothReportedWorkForms() throws ParseException {
        assertEquals(new Command.RecordTaskWork(1, 30), parser.parse("task progress T1 done/30"));
        assertEquals(new Command.RecordTaskWork(2, 0), parser.parse(" task  progress  T2  done/0 "));
        assertEquals(new Command.RecordTaskSession(3, 60, 50),
                parser.parse("task progress T3 session/60 percent/50"));
        assertEquals(new Command.RecordTaskSession(4, 1, 100),
                parser.parse("task progress T4 session/1 percent/100"));
    }

    @Test
    void parse_taskProgress_rejectsMixedMalformedAndOverflowingInput() {
        String usage = "task progress TNUMBER done/MINUTES OR task progress TNUMBER session/MINUTES percent/PERCENT";
        for (String input : List.of("task progress", "task progress T0 done/30", "task progress T01 done/30",
                "task progress T1 done/-1", "task progress T1 done/01", "task progress T1 done/2147483648",
                "task progress T2147483648 done/1", "task progress T1 session/0 percent/50",
                "task progress T1 session/60 percent/-1", "task progress T1 session/60 percent/101",
                "task progress T1 session/60", "task progress T1 percent/50 session/60",
                "task progress T1 done/30 percent/50", "task progress T1 session/60 percent/50 done/30",
                "task progress T1 done/30 done/30", "task progress T1 done/30 extra")) {
            assertEquals(usage, assertThrows(ParseException.class, () -> parser.parse(input), input).usage());
        }
    }

    @Test
    void parse_taskRemaining_acceptsPositiveWholeMinuteTokens() throws ParseException {
        assertEquals(new Command.ReviseTaskRemaining(1, 30),
                parser.parse("task remaining T1 remaining/30"));
        assertEquals(new Command.ReviseTaskRemaining(12, Integer.MAX_VALUE),
                parser.parse(" task  remaining  T12  remaining/2147483647 "));
    }

    @Test
    void parse_taskRemaining_rejectsMalformedAndOverflowingInput() {
        String usage = "task remaining TNUMBER remaining/MINUTES";
        for (String input : List.of("task remaining", "task remaining 1 remaining/30",
                "task remaining C1 remaining/30", "task remaining T0 remaining/30",
                "task remaining T01 remaining/30", "task remaining T1 remaining/0",
                "task remaining T1 remaining/01", "task remaining T1 remaining/-30",
                "task remaining T1 remaining/2147483648", "task remaining T2147483648 remaining/30",
                "task remaining T1 d/30", "task remaining T1 remaining/30 extra",
                "task remaining T1 remaining/30 remaining/60", "task remaining T1 done/30 remaining/60")) {
            assertEquals(usage, assertThrows(ParseException.class, () -> parser.parse(input), input).usage());
        }
    }

    @Test
    void parse_hoursCommands_supportsWeekdaysDatesAndResets() throws ParseException {
        assertEquals(new Command.SetHours("tuesday", LocalTime.of(9, 0), LocalTime.of(22, 0)),
                parser.parse("hours tuesday /start 09:00 /end 22:00"));
        assertEquals(new Command.ShowHours("2026-10-27"), parser.parse("hours 2026-10-27"));
        assertEquals(new Command.ResetHours("tuesday"), parser.parse("hours reset tuesday"));
        for (String input : List.of("hours Tuesday", "hours tue", "hours 2026-02-30", "hours 0000-01-01",
                "hours tuesday /start 24:00 /end 22:00", "hours tuesday /start 0900 /end 2200",
                "hours tuesday /end 22:00 /start 09:00", "hours tuesday /start 09:00",
                "hours reset tuesday /start 09:00 /end 22:00", "hours tuesday extra")) {
            assertThrows(ParseException.class, () -> parser.parse(input), input);
        }
    }

    @Test
    void parse_commitmentEdit_allFieldSubsetsPreserveOmission() throws ParseException {
        for (int mask = 1; mask < 16; mask++) {
            String name = (mask & 1) == 0 ? null : "新  lecture";
            DayOfWeek day = (mask & 2) == 0 ? null : DayOfWeek.SUNDAY;
            LocalTime start = (mask & 4) == 0 ? null : LocalTime.of(23, 30);
            Integer minutes = (mask & 8) == 0 ? null : 30;
            String input = "  commitment  edit C12" + (name == null ? "" : " n/  " + name)
                    + (day == null ? "" : " day/SUN") + (start == null ? "" : " start/2330")
                    + (minutes == null ? "" : " d/30") + "  ";
            assertEquals(new Command.EditCommitment(12, name, day, start, minutes), parser.parse(input));
        }
        assertEquals(new Command.EditCommitment(1, "", null, null, null), parser.parse("commitment edit C1 n/"));
        assertEquals(new Command.EditCommitment(1, null, null, null, null), parser.parse("commitment edit C1"));
    }

    @Test
    void parse_commitmentEdit_rejectsMalformedAndDuplicateFieldsWithUsage() {
        String usage = COMMITMENT_EDIT_USAGE;
        for (String suffix : List.of("", "1 n/A", "T1 n/A", "C01 n/A", "C0 n/A", "C2147483648 n/A",
                "C1 n/A n/B", "C1 day/MON day/TUE", "C1 start/1000 start/1100", "C1 d/30 d/60",
                "C1 d/30 n/A", "C1 day/MONDAY", "C1 day/mon", "C1 on/2026-02-30", "C1 start/2400",
                "C1 start/1260", "C1 start/930", "C1 d/0", "C1 d/-30", "C1 d/030", "C1 d/2147483648",
                "C1 d/30 extra", "C1 n/A/foo", "C1 day/", "C1 start/", "C1 d/", "C1 n/A\tB")) {
            assertEquals(usage, assertThrows(ParseException.class,
                    () -> parser.parse("commitment edit " + suffix), suffix).usage());
        }
    }

    @Test
    void parse_commitmentDateRanges_preservesInclusiveDatesAndRejectsIncompleteSyntax() throws ParseException {
        LocalDate first = LocalDate.of(2026, 8, 10);
        LocalDate last = LocalDate.of(2026, 11, 16);
        assertEquals(new Command.AddCommitment("Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120,
                first, last), parser.parse("commitment add n/Lecture day/MON start/1000 d/120"
                        + " from/2026-08-10 until/2026-11-16"));
        assertEquals(new Command.EditCommitment(3, null, null, null, null, first, last),
                parser.parse("commitment edit C3 from/2026-08-10 until/2026-11-16"));
        for (String input : List.of(
                "commitment add n/A day/MON start/1000 d/30 from/2026-08-10",
                "commitment add n/A day/MON start/1000 d/30 until/2026-11-16",
                "commitment add n/A day/MON start/1000 d/30 until/2026-11-16 from/2026-08-10",
                "commitment add n/A day/MON start/1000 d/30 from/2026-02-30 until/2026-11-16")) {
            assertRejected(input, COMMITMENT_ADD_USAGE);
        }
    }

    @Test
    void parse_oneTimeCommitment_mapsDateToItsWeekdayAndSingleDateRange() throws ParseException {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        assertEquals(new Command.AddCommitment("Project meeting", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60,
                friday, friday), parser.parse("commitment add n/Project meeting on/2026-10-30 start/1400 d/60"));
        assertEquals(new Command.AddCommitment("A  B", DayOfWeek.FRIDAY, LocalTime.of(23, 30), 30, friday, friday),
                parser.parse("  commitment  add  n/ A  B  on/2026-10-30  start/2330  d/30 "));
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        assertEquals(new Command.AddCommitment("Leap", DayOfWeek.TUESDAY, LocalTime.MIDNIGHT, 1440,
                leapDay, leapDay), parser.parse("commitment add n/Leap on/2028-02-29 start/0000 d/1440"));
        LocalDate monday = LocalDate.of(2026, 11, 2);
        assertEquals(new Command.EditCommitment(3, null, DayOfWeek.MONDAY, null, null, monday, monday),
                parser.parse("commitment edit C3 on/2026-11-02"));
        assertEquals(new Command.EditCommitment(3, "Moved", DayOfWeek.MONDAY, LocalTime.of(9, 0), 30,
                monday, monday), parser.parse("commitment edit C3 n/Moved on/2026-11-02 start/0900 d/30"));
    }

    @Test
    void parse_oneTimeCommitment_rejectsInvalidDatesAndMixedRecurrenceFields() {
        for (String input : List.of("commitment add n/A on/2026-02-30 start/1400 d/60",
                "commitment add n/A on/2026-10-30 start/1415x d/60",
                "commitment add n/A on/2026-10-30 start/2400 d/60",
                "commitment add n/A on/2026-10-30 start/1400 d/0",
                "commitment add n/A on/26-10-30 start/1400 d/60",
                "commitment add n/A on/ start/1400 d/60",
                "commitment add n/A day/FRI on/2026-10-30 start/1400 d/60",
                "commitment add n/A on/2026-10-30 on/2026-10-31 start/1400 d/60",
                "commitment add n/A start/1400 on/2026-10-30 d/60",
                "commitment add n/A on/2026-10-30 start/1400 d/60 from/2026-10-30 until/2026-10-30")) {
            assertRejected(input, COMMITMENT_ADD_USAGE);
        }
        assertEquals("Year must be between 0001 and 9999", assertThrows(ParseException.class,
                () -> parser.parse("commitment add n/A on/0000-01-01 start/1400 d/60")).getMessage());
        assertEquals("Use on/ for one date or from/ and until/ for a weekly range, not both",
                assertThrows(ParseException.class, () -> parser.parse("commitment edit C1 on/2026-10-30"
                        + " from/2026-10-30 until/2026-11-06")).getMessage());
        for (String suffix : List.of("C1 day/FRI on/2026-10-30", "C1 on/2026-10-30 day/FRI",
                "C1 start/1400 on/2026-10-30", "C1 on/0000-01-01")) {
            assertRejected("commitment edit " + suffix, COMMITMENT_EDIT_USAGE);
        }
    }

    @Test
    void parse_commitmentSkip_acceptsStrictDateAndRejectsMalformedFields() throws ParseException {
        assertEquals(new Command.SkipCommitment(12, LocalDate.of(2026, 10, 19)),
                parser.parse(" commitment  skip  C12  on/2026-10-19 "));
        for (String input : List.of("commitment skip", "commitment skip 12 on/2026-10-19",
                "commitment skip C0 on/2026-10-19", "commitment skip C01 on/2026-10-19",
                "commitment skip C1 2026-10-19", "commitment skip C1 on/2026-02-30",
                "commitment skip C1 on/2026-10-19 extra")) {
            assertRejected(input, COMMITMENT_SKIP_USAGE);
        }
    }
    @Test
    void parse_nullLine_rejectsWithArgumentName() {
        assertEquals("line", assertThrows(NullPointerException.class, () -> parser.parse(null)).getMessage());
    }

    @Test
    void parse_supportedCommands_returnsEveryCommandType() throws ParseException {
        assertEquals(new Command.Help(), parser.parse("help"));
        assertEquals(new Command.AddTask("CS2113 draft", LocalDateTime.of(2026, 10, 6, 18, 0), 180),
                parser.parse("task add n/CS2113 draft due/2026-10-06 1800 d/180"));
        assertEquals(new Command.ListTasks(), parser.parse("task list"));
        assertEquals(new Command.DeleteTask(12), parser.parse("task delete T12"));
        assertEquals(new Command.AddCommitment("CS2113 lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120),
                parser.parse("commitment add n/CS2113 lecture day/MON start/1000 d/120"));
        assertEquals(new Command.ListCommitments(), parser.parse("commitment list"));
        assertEquals(new Command.DeleteCommitment(12), parser.parse("commitment delete C12"));
        assertEquals(new Command.SkipCommitment(12, LocalDate.of(2026, 10, 19)),
                parser.parse("commitment skip C12 on/2026-10-19"));
        assertEquals(new Command.GeneratePlan(), parser.parse("plan"));
        assertEquals(new Command.ScheduleToday(), parser.parse("schedule today"));
        assertEquals(new Command.ScheduleDate(LocalDate.of(2026, 10, 6)), parser.parse("schedule 2026-10-06"));
        assertEquals(new Command.Exit(), parser.parse("exit"));
    }

    @Test
    void parse_extraSpaces_preservesInternalNameSpacing() throws ParseException {
        assertEquals(new Command.AddTask("CS2113  draft", LocalDateTime.of(2026, 10, 6, 18, 0), 180),
                parser.parse("  task   add  n/  CS2113  draft   due/2026-10-06   1800  d/180  "));
        assertEquals(new Command.AddCommitment("Weekly  review", DayOfWeek.FRIDAY, LocalTime.of(23, 30), 30),
                parser.parse(" commitment  add  n/ Weekly  review  day/FRI  start/2330  d/30 "));
        assertEquals(new Command.ListTasks(), parser.parse("\t task   list \r\n"));
        assertEquals(new Command.ListCommitments(), parser.parse(" commitment  list "));
        assertEquals(new Command.DeleteTask(2), parser.parse(" task  delete   T2 "));
        assertEquals(new Command.DeleteCommitment(2), parser.parse(" commitment  delete   C2 "));
        assertEquals(new Command.ScheduleToday(), parser.parse(" schedule   today "));
        assertEquals(new Command.Help(), parser.parse(" help "));
        assertEquals(new Command.GeneratePlan(), parser.parse(" plan "));
        assertEquals(new Command.Exit(), parser.parse(" exit "));
    }

    @Test
    void parse_unicodeAndQuotes_keepsNamesLiteral() throws ParseException {
        for (String name : List.of("复习 数学", "Café | résumé", "\"Study group\"", "help plan exit", "A".repeat(500))) {
            assertEquals(new Command.AddTask(name, LocalDateTime.of(2026, 10, 6, 18, 0), 30),
                    parser.parse("task add n/" + name + " due/2026-10-06 1800 d/30"));
            assertEquals(new Command.AddCommitment(name, DayOfWeek.MONDAY, LocalTime.of(10, 0), 30),
                    parser.parse("commitment add n/" + name + " day/MON start/1000 d/30"));
        }
    }

    @Test
    void parse_weekdayTokens_mapsAllSevenDays() throws ParseException {
        String[] tokens = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        for (int index = 0; index < tokens.length; index++) {
            assertEquals(new Command.AddCommitment("A", DayOfWeek.of(index + 1), LocalTime.MIDNIGHT, 1440),
                    parser.parse("commitment add n/A day/" + tokens[index] + " start/0000 d/1440"));
        }
    }

    @Test
    void parse_realDates_acceptsLeapDaysAndYearBoundaries() throws ParseException {
        for (String date : List.of("0001-01-01", "2024-02-29", "2000-02-29", "9999-12-31")) {
            LocalDate expected = LocalDate.parse(date);
            assertEquals(new Command.ScheduleDate(expected), parser.parse("schedule " + date));
            assertEquals(new Command.AddTask("A", expected.atTime(23, 59), 30),
                    parser.parse("task add n/A due/" + date + " 2359 d/30"));
        }
    }

    @Test
    void parse_invalidDates_rejectsRolloverAndWrongShape() {
        for (String date : List.of("2026-02-30", "2026-02-29", "1900-02-29", "2026-04-31",
                "2026-00-01", "2026-13-01", "2026-01-00", "0000-01-01", "10000-01-01",
                "026-01-01", "2026-1-01", "2026-01-1", "+2026-01-01", "-0001-01-01",
                "2026/01/01", "２０２６-01-01")) {
            assertRejected("schedule " + date, SCHEDULE_USAGE);
            assertRejected("task add n/A due/" + date + " 0900 d/30", TASK_ADD_USAGE);
        }
    }

    @Test
    void parse_validTimes_preservesMinutePrecision() throws ParseException {
        for (LocalTime time : List.of(LocalTime.MIDNIGHT, LocalTime.of(8, 59), LocalTime.of(23, 59))) {
            String token = String.format(Locale.ROOT, "%02d%02d", time.getHour(), time.getMinute());
            assertEquals(new Command.AddTask("A", LocalDate.of(2026, 10, 6).atTime(time), 30),
                    parser.parse("task add n/A due/2026-10-06 " + token + " d/30"));
            assertEquals(new Command.AddCommitment("A", DayOfWeek.MONDAY, time, 30),
                    parser.parse("commitment add n/A day/MON start/" + token + " d/30"));
        }
    }

    @Test
    void parse_invalidTimes_rejectsRolloverAndWrongShape() {
        for (String time : List.of("2400", "2360", "1260", "9999", "900", "09000", "09:00",
                "090000", "-100", "+900", "09a0", "０９００")) {
            assertRejected("task add n/A due/2026-10-06 " + time + " d/30", TASK_ADD_USAGE);
            assertRejected("commitment add n/A day/MON start/" + time + " d/30", COMMITMENT_ADD_USAGE);
        }
    }

    @Test
    void parse_invalidWeekdays_rejectsNamesNumbersAndWrongCase() {
        for (String day : List.of("mon", "Mon", "MONDAY", "Monday", "1", "XXX", "")) {
            assertRejected("commitment add n/A day/" + day + " start/1000 d/30", COMMITMENT_ADD_USAGE);
        }
    }

    @Test
    void parse_malformedTaskFields_rejectsMissingDuplicateReorderedAndUnknownPrefixes() {
        for (String fields : List.of("", "n/A", "due/2026-10-06 0900 d/30", "n/A d/30",
                "n/A due/2026-10-06 0900", "n/A n/B due/2026-10-06 0900 d/30",
                "n/A due/2026-10-06 0900 due/2026-10-07 0900 d/30",
                "n/A due/2026-10-06 0900 d/30 d/60", "n/A d/30 due/2026-10-06 0900",
                "due/2026-10-06 0900 n/A d/30", "n/A x/B due/2026-10-06 0900 d/30",
                "n/A due/2026-10-06 0900 d/30 x/B", "name/A due/2026-10-06 0900 d/30",
                "n/A/B due/2026-10-06 0900 d/30", "n/A due/2026-10-06 0900 d/ 30",
                "n/A due/2026-10-06 0900 d/30 junk", "n/A due/2026-10-06 0900d/30")) {
            assertRejected("task add " + fields, TASK_ADD_USAGE);
        }
    }

    @Test
    void parse_malformedCommitmentFields_rejectsMissingDuplicateReorderedAndUnknownPrefixes() {
        for (String fields : List.of("", "n/A", "day/MON start/1000 d/30", "n/A start/1000 d/30",
                "n/A day/MON d/30", "n/A day/MON start/1000", "n/A n/B day/MON start/1000 d/30",
                "n/A day/MON day/TUE start/1000 d/30", "n/A day/MON start/1000 start/1100 d/30",
                "n/A day/MON start/1000 d/30 d/60", "n/A start/1000 day/MON d/30",
                "n/A day/MON d/30 start/1000", "n/A x/B day/MON start/1000 d/30",
                "n/A day/MON start/1000 d/30 x/B", "n/A/B day/MON start/1000 d/30",
                "n/A day/MON start/1000 d/30 junk", "n/A day/MONstart/1000 d/30")) {
            assertRejected("commitment add " + fields, COMMITMENT_ADD_USAGE);
        }
    }

    @Test
    void parse_invalidIntegers_rejectsSignsZeroPaddingOverflowAndNonAsciiDigits() {
        for (String number : List.of("0", "00", "01", "030", "-30", "+30", "30.0", "3e1",
                "thirty", "2147483648", "99999999999999999999999999999999", "３０", "٣٠")) {
            assertRejected("task add n/A due/2026-10-06 0900 d/" + number, TASK_ADD_USAGE);
            assertRejected("commitment add n/A day/MON start/1000 d/" + number, COMMITMENT_ADD_USAGE);
            assertRejected("task delete T" + number, TASK_DELETE_USAGE);
            assertRejected("commitment delete C" + number, COMMITMENT_DELETE_USAGE);
        }
    }

    @Test
    void parse_integerBoundaries_acceptsFullPositiveIntRange() throws ParseException {
        for (int number : List.of(1, 2147483640, Integer.MAX_VALUE)) {
            assertEquals(new Command.DeleteTask(number), parser.parse("task delete T" + number));
            assertEquals(new Command.DeleteCommitment(number), parser.parse("commitment delete C" + number));
            assertEquals(new Command.AddTask("A", LocalDateTime.of(2026, 10, 6, 9, 0), number),
                    parser.parse("task add n/A due/2026-10-06 0900 d/" + number));
            assertEquals(new Command.AddCommitment("A", DayOfWeek.MONDAY, LocalTime.of(10, 0), number),
                    parser.parse("commitment add n/A day/MON start/1000 d/" + number));
        }
    }

    @Test
    void parse_invalidIds_rejectsMissingWrongTypeAndTrailingInput() {
        for (String id : List.of("", "1", "C1", "t1", "T", "T 1", "T1 extra", "T1T2", "T1 C2")) {
            assertRejected("task delete " + id, TASK_DELETE_USAGE);
        }
        for (String id : List.of("", "1", "T1", "c1", "C", "C 1", "C1 extra", "C1C2", "C1 T2")) {
            assertRejected("commitment delete " + id, COMMITMENT_DELETE_USAGE);
        }
    }

    @Test
    void parse_noArgumentCommands_rejectsTrailingInputWithSpecificUsage() {
        for (String command : List.of("help", "commitment list", "plan", "exit")) {
            assertRejected(command + " extra", command);
            assertRejected(command + " n/A", command);
        }
    }

    @Test
    void parse_schedule_rejectsMissingArgumentsWrongCaseAndTrailingInput() {
        for (String input : List.of("schedule", "schedule Today", "schedule TODAY", "schedule tomorrow",
                "schedule today extra", "schedule 2026-10-06 0900", "schedule 2026-10-06 extra")) {
            assertRejected(input, SCHEDULE_USAGE);
        }
    }

    @Test
    void parse_wrongCommandCaseAndUnknownCommands_returnsHelpHint() {
        for (String input : List.of("HELP", "Task list", "task LIST", "task ADD", "task addendum",
                "Commitment list", "commitment DELETE C1", "PLAN", "SCHEDULE today", "EXIT",
                "task", "commitment", "commitment remove C1", "save", "helpful", "", "   ")) {
            assertRejected(input, HELP_USAGE);
        }
    }

    @Test
    void parse_wrongPrefixCase_rejectsWithSpecificUsage() {
        for (String field : List.of("n/", "due/", "d/")) {
            assertRejected("task add n/A due/2026-10-06 0900 d/30".replace(field, field.toUpperCase(Locale.ROOT)),
                    TASK_ADD_USAGE);
        }
        for (String field : List.of("n/", "day/", "start/", "d/")) {
            assertRejected("commitment add n/A day/MON start/1000 d/30"
                    .replace(field, field.toUpperCase(Locale.ROOT)), COMMITMENT_ADD_USAGE);
        }
    }

    @Test
    void parse_embeddedControlsAndNonSpaceSeparators_rejectsInput() {
        for (String separator : List.of("\t", "\n", "\r", "\r\n", "\u0000", "\u0085")) {
            assertRejected("task add n/A" + separator + "B due/2026-10-06 0900 d/30", TASK_ADD_USAGE);
            assertRejected("commitment add n/A" + separator + "B day/MON start/1000 d/30", COMMITMENT_ADD_USAGE);
            assertRejected("schedule today" + separator + "exit", SCHEDULE_USAGE);
        }
        assertRejected("task\tadd n/A due/2026-10-06 0900 d/30", HELP_USAGE);
        assertRejected("task add n/A due/2026-10-06\t0900 d/30", TASK_ADD_USAGE);
        assertRejected("task add n/A due/2026-10-06\u00a00900 d/30", TASK_ADD_USAGE);
        assertRejected("schedule\u2003today", HELP_USAGE);
    }

    @Test
    void parse_domainValues_defersBusinessValidation() throws ParseException {
        assertEquals(new Command.AddTask("A", LocalDateTime.of(2000, 1, 1, 0, 0), 45),
                parser.parse("task add n/A due/2000-01-01 0000 d/45"));
        assertEquals(new Command.AddCommitment("A", DayOfWeek.MONDAY, LocalTime.of(23, 59), 1440),
                parser.parse("commitment add n/A day/MON start/2359 d/1440"));
        assertEquals(new Command.AddTask("", LocalDateTime.of(2026, 10, 6, 9, 0), 30),
                parser.parse("task add n/   due/2026-10-06 0900 d/30"));
    }

    @Test
    void parse_scheduleToday_keepsDateUnresolvedAcrossCalls() throws ParseException {
        assertEquals(new Command.ScheduleToday(), parser.parse("schedule today"));
        parser.parse("schedule 0001-01-01");
        assertRejected("schedule 2026-02-30", SCHEDULE_USAGE);
        assertEquals(new Command.ScheduleToday(), parser.parse("schedule today"));
    }

    /**
     * Checks that invalid syntax retains the relevant usage hint and a useful message.
     */
    private void assertRejected(String input, String usage) {
        ParseException exception = assertThrows(ParseException.class, () -> parser.parse(input), input);
        assertEquals(usage, exception.usage(), input);
        assertFalse(exception.getMessage().isBlank(), input);
    }
}
