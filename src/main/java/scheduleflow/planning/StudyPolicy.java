// @@author GabrielYuSP
package scheduleflow.planning;

import scheduleflow.common.ValidationException;

/**
 * Defines the continuous task-and-commitment work limit and the fixed rest needed to reset it.
 * Both durations use whole half-hour slots. A commitment may exceed the limit without increasing rest.
 *
 * @param maxContinuousMinutes maximum accumulated work between qualifying breaks
 * @param breakMinutes consecutive unoccupied minutes required to reset accumulated work
 */
public record StudyPolicy(int maxContinuousMinutes, int breakMinutes) {
    /** Rejects nonpositive or unaligned durations without reading settings or performing I/O. */
    public StudyPolicy {
        if (maxContinuousMinutes <= 0 || maxContinuousMinutes % TimeRules.SLOT_MINUTES != 0
                || breakMinutes <= 0 || breakMinutes % TimeRules.SLOT_MINUTES != 0) {
            throw new ValidationException("Study limit and break duration must be positive multiples of 30 minutes.");
        }
    }
}
// @@author
