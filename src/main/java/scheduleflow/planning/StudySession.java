// @@author GabrielYuSP
package scheduleflow.planning;

import java.time.Duration;
import java.time.LocalDateTime;
import scheduleflow.common.ValidationException;

/**
 * Represents a positive aligned task interval inside one day's study window.
 * Exact positive {@code workMinutes} excludes padding and cannot exceed reserved {@link #minutes()}.
 */
public record StudySession(int taskId, LocalDateTime start, LocalDateTime end, int workMinutes) {
    /** Preserves legacy sessions whose exact work equals their reserved capacity. */
    public StudySession(int taskId, LocalDateTime start, LocalDateTime end) {
        this(taskId, start, end, start == null || end == null || !start.toLocalDate().equals(end.toLocalDate()) ? 0
                : Math.toIntExact(Duration.between(start, end).toMinutes()));
    }

    /**
     * Validates this interval; the planner enforces configured hours and relationships to source tasks.
     */
    public StudySession {
        if (taskId <= 0 || start == null || end == null) {
            throw new ValidationException("Session requires a positive task ID and nonnull endpoints.");
        }
        if (!start.toLocalDate().equals(end.toLocalDate()) || !start.isBefore(end)
                || !isAligned(start) || !isAligned(end)) {
            throw new ValidationException("Session must use aligned endpoints inside one study window.");
        }
        if (workMinutes <= 0 || workMinutes > Duration.between(start, end).toMinutes()) {
            throw new ValidationException("Session work must be positive and cannot exceed reserved capacity.");
        }
    }

    /**
     * Returns the exact wall-clock minutes in this interval.
     */
    public int minutes() {
        return Math.toIntExact(Duration.between(start, end).toMinutes());
    }

    private static boolean isAligned(LocalDateTime time) {
        return time.getMinute() % TimeRules.SLOT_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }
}
// @@author
