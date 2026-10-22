// @@author nickpatt
package scheduleflow.task;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries immutable partial task details; omitted values retain their stored values.
 * Field validation belongs to {@link TaskService#edit}; clearing a deadline is not supported yet.
 *
 * @param name replacement name, or empty to retain the name
 * @param deadline replacement deadline, or empty to retain the deadline
 * @param clearDeadline reserved request to remove the deadline, currently rejected by the service
 */
public record TaskEdit(Optional<String> name, Optional<LocalDateTime> deadline, boolean clearDeadline) {
    /**
     * Rejects null containers without validating domain values or performing I/O.
     *
     * @throws NullPointerException if either optional container is null
     */
    public TaskEdit {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(deadline, "deadline");
    }
}
// @@author
