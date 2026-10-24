package scheduleflow.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

// @@author nickpatt
/**
 * Stores exact remaining work, independent of any generated plan; zero means retained completion.
 * A past deadline remains structurally valid so saved tasks can be restored.
 *
 * @param id stable positive numeric task identifier
 * @param name nonblank description, stripped at both ends; slash and ISO controls are forbidden
 * @param deadline optional local deadline with minute precision and a year from 0001 to 9999
 * @param remainingMinutes nonnegative whole remaining-work minutes
 * @param priority stored priority, defaulting to NORMAL for legacy callers
 * @param occurrence immutable weekly provenance, absent for ordinary tasks
 */
public record Task(int id, String name, Optional<LocalDateTime> deadline, int remainingMinutes,
        TaskPriority priority, Optional<TaskOccurrence> occurrence) {
    /**
     * Records opt-in structural validation stages without task values or exception payloads.
     */
    private static final Logger LOGGER = Logger.getLogger(Task.class.getName());
    // @@author

    /** Creates an ordinary task with an optional deadline and explicit priority. */
    public Task(int id, String name, Optional<LocalDateTime> deadline, int remainingMinutes,
            TaskPriority priority) {
        this(id, name, deadline, remainingMinutes, priority, Optional.empty());
    }

    /** Creates a dated recurrence occurrence with default priority. */
    public Task(int id, String name, LocalDateTime deadline, int remainingMinutes,
            Optional<TaskOccurrence> occurrence) {
        this(id, name, Optional.of(requireDeadline(deadline)), remainingMinutes, TaskPriority.NORMAL, occurrence);
    }

    /**
     * Creates a structurally valid task and normalizes its name.
     * Permits overdue and out-of-horizon saved deadlines; new-task temporal validation belongs to TaskService.
     *
     * @throws ValidationException if the ID, name, deadline or duration violates a structural rule
     */
    public Task {
        Objects.requireNonNull(occurrence, "occurrence");
        // @@author nickpatt
        LOGGER.log(Level.FINE, "Starting task structural validation");
        try {
            // @@author
            if (id <= 0) {
                throw new ValidationException("Task ID must be positive.");
            }
            name = InputRules.normalizeName(name);
            // @@author nickpatt
            LOGGER.log(Level.FINE, "Task identity and name validated; checking deadline structure");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(priority, "priority");
            if (deadline.isPresent() && (deadline.get().getYear() < 1 || deadline.get().getYear() > 9999
                    || deadline.get().getSecond() != 0 || deadline.get().getNano() != 0)) {
                throw new ValidationException("Deadline must have minute precision and a year from 0001 to 9999.");
            }
            InputRules.requireTaskMinutes(remainingMinutes);
            assert name.equals(name.strip()) : "Stored task names must already be normalized";
            LOGGER.log(Level.FINE, "Task structural validation completed");
        } catch (ValidationException exception) {
            LOGGER.log(Level.FINE, "Task structural validation rejected");
            throw exception;
        }
        // @@author
    }

    // @@author nickpatt
    /** Preserves the concrete-deadline constructor and its rejection of null deadlines. */
    public Task(int id, String name, LocalDateTime deadline, int remainingMinutes) {
        this(id, name, Optional.of(requireDeadline(deadline)), remainingMinutes, TaskPriority.NORMAL);
    }

    /** Creates a task with explicitly absent deadline and default priority. */
    public static Task withoutDeadline(int id, String name, int remainingMinutes) {
        return new Task(id, name, Optional.empty(), remainingMinutes, TaskPriority.NORMAL);
    }

    /** Rejects legacy null deadlines rather than interpreting them as omission. */
    private static LocalDateTime requireDeadline(LocalDateTime deadline) {
        if (deadline == null) {
            throw new ValidationException("Deadline must have minute precision and a year from 0001 to 9999.");
        }
        return deadline;
    }
    // @@author

    /**
     * Returns the stable task identifier with no zero padding.
     *
     * @return {@code T} followed by this task's numeric ID, independent of list position
     */
    public String displayId() {
        // @@author nickpatt
        assert id > 0 : "A stored task must retain a positive ID";
        // @@author
        return "T" + id;
    }
}
