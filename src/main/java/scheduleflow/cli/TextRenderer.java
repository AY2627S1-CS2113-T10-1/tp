//@@author ka-how
package scheduleflow.cli;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.model.Commitment;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.ScheduleView;
import scheduleflow.planning.SlotKind;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.UnallocatedWork;

/**
 * Formats output without a trailing newline, clock reads, direct console I/O or state mutation.
 * Emits stage diagnostics only when its logger is configured for {@link Level#FINE}.
 */
public final class TextRenderer {
    /**
     * Records formatting stages without including names or formatted responses.
     */
    private static final Logger LOGGER = Logger.getLogger(TextRenderer.class.getName());

    /**
     * Uses proleptic years and a fixed locale so dates remain stable across JVM defaults.
     */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);
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
        LOGGER.log(Level.FINE, "Formatting command help");
        return finish(String.join("\n", "=== ScheduleFlow Command Help ===",
                "help - Show command help.",
                "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES - Add a task.",
                "task list - List stored tasks.",
                "task delete TNUMBER - Delete a task.",
                "commitment add n/NAME day/DAY start/HHmm d/MINUTES - Add a weekly commitment.",
                "commitment list - List weekly commitments.",
                "commitment delete CNUMBER - Delete a weekly commitment.",
                "hours DAY|YYYY-MM-DD /start HH:mm /end HH:mm - Set study hours.",
                "hours DAY|YYYY-MM-DD - View effective study hours (full lowercase weekday).",
                "hours reset DAY|YYYY-MM-DD - Restore inherited study hours.",
                "plan - Generate or replace the study plan.",
                "schedule today OR schedule YYYY-MM-DD - View a date in the current plan.",
                "exit - Close the application."));
    }

    /** Formats the effective window for a selected weekday or calendar date. */
    public String hours(String target, StudyWindow window) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(window, "window");
        return "Study hours for " + target + ": " + TIME.format(window.start()) + " - " + TIME.format(window.end());
    }

    /** Shows whether a reset restores default hours or a configured recurring weekday window. */
    public String hoursReset(String target, StudyHours hours) {
        Objects.requireNonNull(hours, "hours");
        StudyWindow window = hours.forTarget(target);
        boolean inheritsWeekday = Character.isDigit(target.charAt(0)) && hours.windows().containsKey(
                LocalDate.parse(target).getDayOfWeek().name().toLowerCase(Locale.ROOT));
        String source = inheritsWeekday ? "recurring weekday hours" : "default hours";
        return "Reset study hours for " + target + ".\nUsing " + source + ": "
                + TIME.format(window.start()) + " - " + TIME.format(window.end())
                + ".\nRun plan to regenerate.";
    }

    /**
     * Formats the added task.
     *
     * @param task successfully saved task
     * @return addition response using the stored estimate and deadline
     * @throws NullPointerException if the task is null
     */
    public String taskAdded(Task task) {
        Objects.requireNonNull(task, "task");
        LOGGER.log(Level.FINE, "Formatting task addition response");
        return finish("Added " + task.displayId() + ": " + task.name() + "\nDue: " + DATE_TIME.format(task.deadline())
                + " | Duration: " + task.remainingMinutes() + " min");
    }

    /**
     * Formats the removed task.
     *
     * @param task task removed by a successfully saved mutation
     * @return deletion response retaining the original ID and name
     * @throws NullPointerException if the task is null
     */
    public String taskDeleted(Task task) {
        Objects.requireNonNull(task, "task");
        LOGGER.log(Level.FINE, "Formatting task deletion response");
        return finish("Deleted task " + task.displayId() + ": " + task.name());
    }

    /**
     * Formats the already sorted task list.
     *
     * @param tasks tasks in service-supplied order, possibly empty
     * @return list response without reordering or changing stored estimates
     * @throws NullPointerException if the list or an element is null
     */
    public String tasks(List<Task> tasks) {
        requireElements(tasks, "tasks");
        LOGGER.log(Level.FINE, "Formatting task list");
        if (tasks.isEmpty()) {
            return finish("No tasks found.");
        }
        StringJoiner lines = new StringJoiner("\n");
        lines.add("=== Actionable Tasks ===").add("ID | Name | Duration | Deadline");
        for (Task task : tasks) {
            lines.add(task.displayId() + " | " + task.name() + " | " + task.remainingMinutes()
                    + " min | " + DATE_TIME.format(task.deadline()));
        }
        return finish(lines.add("Total: " + tasks.size() + " task(s).").toString());
    }

    /**
     * Formats the added commitment, including midnight as 24:00.
     *
     * @param commitment successfully saved weekly series
     * @return addition response with a same-day endpoint
     * @throws NullPointerException if the commitment is null
     */
    public String commitmentAdded(Commitment commitment) {
        Objects.requireNonNull(commitment, "commitment");
        LOGGER.log(Level.FINE, "Formatting commitment addition response");
        return finish("Added " + commitment.displayId() + ": " + commitment.name()
                + " | " + formatWeekday(commitment.day())
                + " " + TIME.format(commitment.start()) + " - " + formatCommitmentEnd(commitment)
                + " (" + commitment.durationMinutes() + " min)");
    }

    /**
     * Formats the removed commitment.
     *
     * @param commitment weekly series removed by a successfully saved mutation
     * @return deletion response retaining the original ID and name
     * @throws NullPointerException if the commitment is null
     */
    public String commitmentDeleted(Commitment commitment) {
        Objects.requireNonNull(commitment, "commitment");
        LOGGER.log(Level.FINE, "Formatting commitment deletion response");
        return finish("Deleted commitment " + commitment.displayId() + ": " + commitment.name());
    }

    /**
     * Formats the already sorted weekly commitments.
     *
     * @param commitments weekly series in service-supplied order, possibly empty
     * @return weekday groups preserving supplied order within each group
     * @throws NullPointerException if the list or an element is null
     */
    public String commitments(List<Commitment> commitments) {
        requireElements(commitments, "commitments");
        LOGGER.log(Level.FINE, "Formatting commitment list");
        if (commitments.isEmpty()) {
            return finish("No commitments found.");
        }
        StringJoiner lines = new StringJoiner("\n");
        lines.add("=== Recurring Weekly Commitments ===");
        for (DayOfWeek day : DayOfWeek.values()) {
            List<Commitment> group = commitments.stream().filter(commitment -> commitment.day() == day).toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add("[" + formatWeekday(day) + "]");
            for (Commitment commitment : group) {
                lines.add(commitment.displayId() + ". " + TIME.format(commitment.start()) + " - "
                        + formatCommitmentEnd(commitment) + " | " + commitment.name());
            }
            lines.add("");
        }
        return finish(lines.add("Total: " + commitments.size() + " recurring commitments across the week.").toString());
    }

    /**
     * Formats complete or partial allocation results with whole-plan totals.
     *
     * @param plan generated result with frozen metadata and allocation rows
     * @return summary and any whole-plan unallocated work
     * @throws NullPointerException if the plan is null
     */
    public String plan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        LOGGER.log(Level.FINE, "Formatting plan summary");
        if (plan.source().tasks().isEmpty()) {
            return finish("No tasks to schedule.");
        }
        if (plan.isComplete()) {
            return finish("Study plan generated successfully! Type 'schedule today' to view your sessions.");
        }
        long scheduled = plan.sessions().stream().mapToLong(StudySession::minutes).sum();
        long unallocated = plan.unallocated().stream().mapToLong(UnallocatedWork::minutes).sum();
        StringJoiner lines = new StringJoiner("\n");
        lines.add("Study plan generated with unallocated work.");
        lines.add("Scheduled: " + scheduled + " min | Unallocated: " + unallocated + " min");
        appendUnallocated(lines, plan.unallocated());
        return finish(lines.toString());
    }

    /**
     * Formats the frozen schedule and whole-plan unallocated footer.
     *
     * @param view complete projection for one date, supplied by the schedule service
     * @return schedule preserving all supplied boundaries, order and metadata
     * @throws NullPointerException if the view is null
     */
    public String schedule(ScheduleView view) {
        Objects.requireNonNull(view, "view");
        LOGGER.log(Level.FINE, "Formatting schedule metadata and rows");
        StringJoiner lines = new StringJoiner("\n");
        lines.add("=== Schedule for " + formatWeekday(view.date().getDayOfWeek())
                + " (" + DATE.format(view.date()) + ") ===");
        lines.add("Generated at: " + DATE_TIME.format(view.generatedAt()));
        for (ScheduleEntry entry : view.entries()) {
            lines.add(formatScheduleRow(entry));
        }
        if (view.entries().stream().anyMatch(entry -> entry.kind() == SlotKind.UNPLANNED)) {
            lines.add("UNPLANNED periods were before the planning cutoff.");
        }
        if (!view.unallocated().isEmpty()) {
            lines.add("").add("Unallocated work for the whole plan:");
            appendUnallocated(lines, view.unallocated());
        }
        return finish(lines.toString());
    }

    /**
     * Returns the absent-plan message.
     */
    public String noPlan() {
        LOGGER.log(Level.FINE, "Formatting absent-plan response");
        return finish("No current plan. Run plan to generate one.");
    }

    /**
     * Formats a one-line user error.
     *
     * @param message explanation of the failure
     * @return error response prefixed with {@code Error: }
     * @throws NullPointerException if the message is null
     */
    public String error(String message) {
        Objects.requireNonNull(message, "message");
        LOGGER.log(Level.FINE, "Formatting error response");
        return finish("Error: " + message);
    }

    /**
     * Formats a syntax error and its expected usage.
     *
     * @param exception syntax failure with a message and usage hint
     * @return error response followed by the expected command format
     * @throws NullPointerException if the exception is null
     */
    public String parseError(ParseException exception) {
        Objects.requireNonNull(exception, "exception");
        LOGGER.log(Level.FINE, "Formatting syntax error and usage");
        return finish(error(exception.getMessage()) + "\nExpected: " + exception.usage());
    }

    /**
     * Returns the normal goodbye message.
     */
    public String exit() {
        LOGGER.log(Level.FINE, "Formatting goodbye response");
        return finish("Goodbye for now!");
    }

    /**
     * Records successful formatting, including methods with early empty-result returns.
     */
    private String finish(String output) {
        LOGGER.log(Level.FINE, "Response formatting completed");
        return output;
    }

    /**
     * Rejects null collections and elements before any response text is assembled.
     *
     * @param values supplied list to validate without modifying it
     * @param name argument name used in failure messages
     * @param <T> list element type
     * @throws NullPointerException if the list or an element is null
     */
    private <T> void requireElements(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " element");
        }
    }

    /**
     * Uses English weekday names independently of the JVM's default locale.
     */
    private String formatWeekday(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /**
     * Displays each supplied interval without clipping, merging or allocating it.
     */
    private String formatScheduleRow(ScheduleEntry entry) {
        LOGGER.log(Level.FINE, "Formatting schedule interval: {0}", entry.kind());
        String range = TIME.format(entry.start()) + " - " + TIME.format(entry.end()) + " | [" + entry.kind() + "]";
        return switch (entry.kind()) {
            case BUSY -> range + " " + entry.reference() + ": " + entry.name();
            case TASK -> range + " " + entry.reference() + ": " + entry.name()
                    + " (" + Duration.between(entry.start(), entry.end()).toMinutes() + " min)";
            case FREE, UNPLANNED -> range;
        };
    }

    /**
     * Shares the same ordered task rows between plan summaries and schedule footers.
     */
    private void appendUnallocated(StringJoiner lines, List<UnallocatedWork> unallocated) {
        LOGGER.log(Level.FINE, "Formatting whole-plan unallocated work");
        for (UnallocatedWork work : unallocated) {
            String reason = switch (work.reason()) {
                case DEADLINE_PASSED -> "deadline passed";
                case OUTSIDE_HORIZON -> "outside planning horizon";
                case INSUFFICIENT_CAPACITY -> "insufficient time before deadline";
            };
            lines.add("T" + work.taskId() + ": " + work.taskName() + " | Unallocated: " + work.minutes()
                    + " min | Reason: " + reason);
        }
    }

    /**
     * Formats the same-day endpoint without wrapping midnight to 00:00.
     */
    private String formatCommitmentEnd(Commitment commitment) {
        int endMinute = commitment.endMinuteOfDay();
        if (endMinute == MINUTES_PER_DAY) {
            return "24:00";
        }
        return TIME.format(LocalTime.of(endMinute / MINUTES_PER_HOUR, endMinute % MINUTES_PER_HOUR));
    }
}
