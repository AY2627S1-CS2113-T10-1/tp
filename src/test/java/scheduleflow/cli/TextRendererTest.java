package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Task;

/**
 * Verifies public output using fixed records without console, storage or clock dependencies.
 */
class TextRendererTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2027, 1, 1, 9, 5);
    private static final String LONG_NAME = "项目 préparation 📚 " + "a".repeat(150);
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

    private void assertOutput(String expected, String actual) {
        assertEquals(expected, actual);
        assertFraming(actual);
    }

    private void assertFraming(String output) {
        assertFalse(output.contains("\r"));
        assertFalse(output.endsWith("\n"));
    }
}
