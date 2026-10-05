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
import org.junit.jupiter.api.Test;

/**
 * Verifies stage 1 grammar and transport values independently of services, storage and the clock.
 */
class CommandParserTest {
    private static final String HELP_USAGE = "Type help to see available commands";
    private static final String TASK_ADD_USAGE = "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES";
    private static final String TASK_DELETE_USAGE = "task delete TNUMBER";
    private static final String COMMITMENT_ADD_USAGE = "commitment add n/NAME day/DAY start/HHmm d/MINUTES";
    private static final String COMMITMENT_DELETE_USAGE = "commitment delete CNUMBER";
    private static final String SCHEDULE_USAGE = "schedule today OR schedule YYYY-MM-DD";
    private final CommandParser parser = new CommandParser();

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
        for (String command : List.of("help", "task list", "commitment list", "plan", "exit")) {
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
                "task", "commitment", "task edit T1", "commitment remove C1", "save", "helpful", "", "   ")) {
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

    private void assertRejected(String input, String usage) {
        ParseException exception = assertThrows(ParseException.class, () -> parser.parse(input), input);
        assertEquals(usage, exception.usage(), input);
        assertFalse(exception.getMessage().isBlank(), input);
    }
}
