package scheduleflow.cli;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import scheduleflow.model.Commitment;
import scheduleflow.model.Task;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleView;

/**
 * Formats output without a trailing newline, clock reads, console I/O or state mutation.
 */
public final class TextRenderer {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    /**
     * Creates a formatter without performing I/O.
     */
    public TextRenderer() {
    }

    /**
     * Returns command help.
     */
    public String help() {
        return String.join("\n", "=== ScheduleFlow Command Help ===",
                "help - Show command help.",
                "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES - Add a task.",
                "task list - List stored tasks.",
                "task delete TNUMBER - Delete a task.",
                "commitment add n/NAME day/DAY start/HHmm d/MINUTES - Add a weekly commitment.",
                "commitment list - List weekly commitments.",
                "commitment delete CNUMBER - Delete a weekly commitment.",
                "plan - Generate or replace the study plan.",
                "schedule today OR schedule YYYY-MM-DD - View a date in the current plan.",
                "exit - Close the application.");
    }

    /**
     * Formats the added task.
     */
    public String taskAdded(Task task) {
        return "Added " + task.displayId() + ": " + task.name() + "\nDue: " + DATE_TIME.format(task.deadline())
                + " | Duration: " + task.remainingMinutes() + " min";
    }

    /**
     * Formats the removed task.
     */
    public String taskDeleted(Task task) {
        return "Deleted task " + task.displayId() + ": " + task.name();
    }

    /**
     * Formats the already sorted task list.
     */
    public String tasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return "No tasks found.";
        }
        StringJoiner lines = new StringJoiner("\n");
        lines.add("=== Actionable Tasks ===").add("ID | Name | Duration | Deadline");
        for (Task task : tasks) {
            lines.add(task.displayId() + " | " + task.name() + " | " + task.remainingMinutes()
                    + " min | " + DATE_TIME.format(task.deadline()));
        }
        return lines.add("Total: " + tasks.size() + " task(s).").toString();
    }

    /**
     * Formats the added commitment, including midnight as 24:00.
     */
    public String commitmentAdded(Commitment commitment) {
        return "Added " + commitment.displayId() + ": " + commitment.name() + " | " + weekday(commitment.day())
                + " " + TIME.format(commitment.start()) + " - " + commitmentEnd(commitment)
                + " (" + commitment.durationMinutes() + " min)";
    }

    /**
     * Formats the removed commitment.
     */
    public String commitmentDeleted(Commitment commitment) {
        return "Deleted commitment " + commitment.displayId() + ": " + commitment.name();
    }

    /**
     * Formats the already sorted weekly commitments.
     */
    public String commitments(List<Commitment> commitments) {
        if (commitments.isEmpty()) {
            return "No commitments found.";
        }
        StringJoiner lines = new StringJoiner("\n");
        lines.add("=== Recurring Weekly Commitments ===");
        for (DayOfWeek day : DayOfWeek.values()) {
            List<Commitment> group = commitments.stream().filter(commitment -> commitment.day() == day).toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add("[" + weekday(day) + "]");
            for (Commitment commitment : group) {
                lines.add(commitment.displayId() + ". " + TIME.format(commitment.start()) + " - "
                        + commitmentEnd(commitment) + " | " + commitment.name());
            }
            lines.add("");
        }
        return lines.add("Total: " + commitments.size() + " recurring commitments across the week.").toString();
    }

    /**
     * Formats complete or partial allocation results with whole-plan totals.
     * This feature is intentionally unfinished in the shared starter.
     */
    public String plan(Plan plan) {
        throw new UnsupportedOperationException("TODO(Printing): implement TextRenderer.plan");
    }

    /**
     * Formats the frozen schedule and whole-plan unallocated footer.
     * This feature is intentionally unfinished in the shared starter.
     */
    public String schedule(ScheduleView view) {
        throw new UnsupportedOperationException("TODO(Printing): implement TextRenderer.schedule");
    }

    /**
     * Returns the absent-plan message.
     */
    public String noPlan() {
        return "No current plan. Run plan to generate one.";
    }

    /**
     * Formats a one-line user error.
     */
    public String error(String message) {
        return "Error: " + message;
    }

    /**
     * Formats a syntax error and its expected usage.
     */
    public String parseError(ParseException exception) {
        return error(exception.getMessage()) + "\nExpected: " + exception.usage();
    }

    /**
     * Returns the normal goodbye message.
     */
    public String exit() {
        return "Goodbye for now!";
    }

    private String weekday(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /**
     * Formats the same-day endpoint without wrapping midnight to 00:00.
     */
    private String commitmentEnd(Commitment commitment) {
        int endMinute = commitment.endMinuteOfDay();
        if (endMinute == MINUTES_PER_DAY) {
            return "24:00";
        }
        return TIME.format(LocalTime.of(endMinute / MINUTES_PER_HOUR, endMinute % MINUTES_PER_HOUR));
    }
}
