package scheduleflow.model;

import java.time.LocalDateTime;
import scheduleflow.common.ValidationException;

// @@author nickpatt
/** Identifies an immutable logical occurrence, independently of later task deadline edits. */
public record TaskOccurrence(int templateId, LocalDateTime originalDeadline) {
    /** Requires a positive template ID and a minute-precision deadline with a representable release. */
    public TaskOccurrence {
        if (templateId <= 0 || originalDeadline == null || originalDeadline.getYear() < 1
                || originalDeadline.getYear() > 9999 || originalDeadline.getSecond() != 0
                || originalDeadline.getNano() != 0 || originalDeadline.minusWeeks(1).getYear() < 1) {
            throw new ValidationException("Occurrence requires a positive template ID and valid deadline/release.");
        }
    }

    /** Returns the fixed release time, even if the task's current deadline is changed or cleared. */
    public LocalDateTime release() {
        return originalDeadline.minusWeeks(1);
    }
}
// @@author
