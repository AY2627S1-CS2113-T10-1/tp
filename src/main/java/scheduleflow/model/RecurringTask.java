package scheduleflow.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

// @@author nickpatt
/** Stores a weekly template and the last processed deadline, including deliberately skipped past dates. */
public record RecurringTask(int id, String name, LocalDateTime firstDeadline, Optional<LocalDate> until,
        int minutes, TaskPriority priority, Optional<LocalDateTime> generatedThrough) {
    /** Validates saved structure without requiring future dates or consulting a clock. */
    public RecurringTask {
        new TaskOccurrence(id, firstDeadline);
        name = InputRules.normalizeName(name);
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(generatedThrough, "generatedThrough");
        if (minutes <= 0) {
            throw new ValidationException("Recurring task minutes must be positive.");
        }
        if (until.isPresent() && (until.get().getYear() < 1 || until.get().getYear() > 9999
                || until.get().isBefore(firstDeadline.toLocalDate()))) {
            throw new ValidationException("Recurring task until must be on or after its first due date.");
        }
        if (generatedThrough.isPresent() && !isWeeklyDeadline(firstDeadline, until, generatedThrough.get())) {
            throw new ValidationException("Generation cursor must belong to the bounded weekly sequence.");
        }
    }

    /**
     * Tests whether a deadline belongs to this template's inclusive weekly sequence.
     * @return true only for the original time on a weekly due date within the bounds
     * @throws NullPointerException if deadline is null
     */
    public boolean includes(LocalDateTime deadline) {
        return isWeeklyDeadline(firstDeadline, until, deadline);
    }

    /** Checks sequence membership without date arithmetic that could overflow the supported years. */
    private static boolean isWeeklyDeadline(LocalDateTime first, Optional<LocalDate> until,
            LocalDateTime deadline) {
        return deadline.getYear() <= 9999 && !deadline.isBefore(first)
                && deadline.toLocalTime().equals(first.toLocalTime())
                && ChronoUnit.DAYS.between(first.toLocalDate(), deadline.toLocalDate()) % 7 == 0
                && (until.isEmpty() || !deadline.toLocalDate().isAfter(until.get()));
    }

    /** Returns this template's independent stable display ID. */
    public String displayId() {
        return "R" + id;
    }
}
// @@author
