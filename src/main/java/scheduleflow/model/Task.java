package scheduleflow.model;

import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

/**
 * Stores an unfinished task's whole estimate, independent of any generated plan.
 * A past deadline remains structurally valid so saved tasks can be restored.
 *
 * @param id stable positive numeric task identifier
 * @param name nonblank description, stripped at both ends; slash and ISO controls are forbidden
 * @param deadline local deadline with minute precision and a year from 0001 to 9999
 * @param remainingMinutes whole remaining-work estimate, a positive multiple of 30
 */
public record Task(int id, String name, LocalDateTime deadline, int remainingMinutes) {
    /**
     * Records opt-in structural validation stages without task values or exception payloads.
     */
    private static final Logger LOGGER = Logger.getLogger(Task.class.getName());

    /**
     * Creates a structurally valid task and normalizes its name.
     * Permits overdue and out-of-horizon saved deadlines; new-task temporal validation belongs to TaskService.
     *
     * @throws ValidationException if the ID, name, deadline or duration violates a structural rule
     */
    public Task {
        LOGGER.log(Level.FINE, "Starting task structural validation");
        try {
            if (id <= 0) {
                throw new ValidationException("Task ID must be positive.");
            }
            name = InputRules.normalizeName(name);
            LOGGER.log(Level.FINE, "Task identity and name validated; checking deadline structure");
            if (deadline == null || deadline.getYear() < 1 || deadline.getYear() > 9999
                    || deadline.getSecond() != 0 || deadline.getNano() != 0) {
                throw new ValidationException("Deadline must have minute precision and a year from 0001 to 9999.");
            }
            InputRules.requireDuration(remainingMinutes);
            assert name.equals(name.strip()) : "Stored task names must already be normalized";
            LOGGER.log(Level.FINE, "Task structural validation completed");
        } catch (ValidationException exception) {
            LOGGER.log(Level.FINE, "Task structural validation rejected");
            throw exception;
        }
    }

    /**
     * Returns the stable task identifier with no zero padding.
     *
     * @return {@code T} followed by this task's numeric ID, independent of list position
     */
    public String displayId() {
        assert id > 0 : "A stored task must retain a positive ID";
        return "T" + id;
    }
}
