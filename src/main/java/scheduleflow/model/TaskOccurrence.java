// @@author GabrielYuSP
package scheduleflow.model;

import java.time.LocalDateTime;
import scheduleflow.common.ValidationException;

/**
 * Identifies a logical weekly occurrence independently of later task deadline edits.
 * @param templateId stable original R-ID, retained even after the template is removed
 * @param originalDeadline immutable minute-precision deadline determining the one-week release
 */
public record TaskOccurrence(int templateId, LocalDateTime originalDeadline) {
    /** Requires a positive template ID and supported minute-precision deadline and release. */
    public TaskOccurrence {
        if (templateId <= 0 || originalDeadline == null || originalDeadline.getYear() < 1
                || originalDeadline.getYear() > 9999 || originalDeadline.getSecond() != 0
                || originalDeadline.getNano() != 0 || originalDeadline.minusWeeks(1).getYear() < 1) {
            throw new ValidationException("Occurrence requires a positive template ID and valid deadline/release.");
        }
    }

    /** Returns the fixed release time, unaffected by task deadline edits. */
    public LocalDateTime release() {
        return originalDeadline.minusWeeks(1);
    }
}
// @@author
