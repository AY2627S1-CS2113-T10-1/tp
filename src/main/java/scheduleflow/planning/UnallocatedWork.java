// @@author GabrielYuSP
package scheduleflow.planning;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

/**
 * Describes a task's positive unscheduled remainder; source matching belongs to the planner.
 * Exact {@code minutes} may be unaligned; {@code capacityMinutes} is its long-safe rounded slot demand.
 * An absent deadline permits only an insufficient-capacity reason.
 */
public record UnallocatedWork(int taskId, String taskName, Optional<LocalDateTime> deadline,
        int minutes, long capacityMinutes, UnallocatedReason reason) {
    /** Preserves concrete-deadline callers, rounding capacity without overflowing an int. */
    public UnallocatedWork(int taskId, String taskName, LocalDateTime deadline,
            int minutes, UnallocatedReason reason) {
        this(taskId, taskName, Optional.ofNullable(deadline), minutes,
                ((long) minutes + TimeRules.SLOT_MINUTES - 1) / TimeRules.SLOT_MINUTES * TimeRules.SLOT_MINUTES,
                reason);
        if (deadline == null) {
            throw new ValidationException("Unallocated deadline must have minute precision.");
        }
    }

    /**
     * Validates the remainder's own fields and normalizes its task name.
     */
    public UnallocatedWork {
        if (taskId <= 0 || reason == null) {
            throw new ValidationException("Unallocated work requires a positive task ID and reason.");
        }
        taskName = InputRules.normalizeName(taskName);
        Objects.requireNonNull(deadline, "deadline");
        if (minutes <= 0 || capacityMinutes != ((long) minutes + TimeRules.SLOT_MINUTES - 1)
                / TimeRules.SLOT_MINUTES * TimeRules.SLOT_MINUTES) {
            throw new ValidationException("Unallocated capacity must equal rounded positive exact work.");
        }
        if (deadline.isEmpty() && reason != UnallocatedReason.INSUFFICIENT_CAPACITY) {
            throw new ValidationException("Undated work can only have insufficient capacity.");
        }
        if (deadline.isPresent() && (deadline.get().getSecond() != 0 || deadline.get().getNano() != 0
                || deadline.get().getYear() < 1 || deadline.get().getYear() > 9999)) {
            throw new ValidationException("Unallocated deadline must have minute precision.");
        }
    }
}
// @@author
