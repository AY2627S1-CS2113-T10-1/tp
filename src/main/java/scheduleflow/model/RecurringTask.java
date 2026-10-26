package scheduleflow.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

/**
 * Stores a weekly template and its last processed deadline, including skipped past dates.
 *
 * @param id independent positive template ID, displayed with an R prefix
 * @param name normalized description for newly generated tasks
 * @param firstDeadline first weekly deadline, fixing the weekday and time
 * @param until optional inclusive last due date, not before the first deadline
 * @param minutes positive half-hour estimate for each occurrence
 * @param generatedThrough last processed weekly deadline, including skipped dates
 */
public record RecurringTask(int id, String name, LocalDateTime firstDeadline, Optional<LocalDate> until,
        int minutes, Optional<LocalDateTime> generatedThrough) {
    /**
     * Validates saved structure without requiring future dates or consulting a clock.
     * @throws ValidationException if IDs, dates, cursor, name or estimate are invalid
     * @throws NullPointerException if an optional container is null
     */
    public RecurringTask {
        new TaskOccurrence(id, firstDeadline);
        name = InputRules.normalizeName(name);
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(generatedThrough, "generatedThrough");
        InputRules.requireDuration(minutes);
        if (until.isPresent() && (until.get().getYear() < 1 || until.get().getYear() > 9999
                || until.get().isBefore(firstDeadline.toLocalDate()))) {
            throw new ValidationException("Recurring task until must be on or after its first due date.");
        }
        if (generatedThrough.isPresent() && !isWeeklyDeadline(firstDeadline, until, generatedThrough.get())) {
            throw new ValidationException("Generation cursor must belong to the bounded weekly sequence.");
        }
    }

    /** Tests membership in the inclusive weekly sequence at the original time of day. */
    public boolean includes(LocalDateTime deadline) {
        return isWeeklyDeadline(firstDeadline, until, Objects.requireNonNull(deadline, "deadline"));
    }

    /** Checks sequence membership without potentially overflowing date arithmetic. */
    private static boolean isWeeklyDeadline(LocalDateTime first, Optional<LocalDate> until,
            LocalDateTime deadline) {
        return deadline.getYear() <= 9999 && !deadline.isBefore(first)
                && deadline.toLocalTime().equals(first.toLocalTime())
                && ChronoUnit.DAYS.between(first.toLocalDate(), deadline.toLocalDate()) % 7 == 0
                && (until.isEmpty() || !deadline.toLocalDate().isAfter(until.get()));
    }

    /** Returns the independent stable template identifier. */
    public String displayId() {
        return "R" + id;
    }
}
