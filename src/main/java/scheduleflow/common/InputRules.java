package scheduleflow.common;

import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.planning.TimeRules;

/**
 * Provides the shared name and duration rules for domain records.
 */
public final class InputRules {
    /**
     * Records opt-in shared validation stages without logging supplied names or durations.
     */
    private static final Logger LOGGER = Logger.getLogger(InputRules.class.getName());

    /**
     * Prevents instantiation of this shared validation utility.
     */
    private InputRules() {
    }

    /** Requires exact nonnegative task minutes; zero represents a retained completed task. */
    public static void requireTaskMinutes(int minutes) {
        if (minutes < 0) {
            throw new ValidationException("Task remaining minutes must be nonnegative.");
        }
    }

    /**
     * Returns a stripped, nonblank name while preserving internal spaces and Unicode.
     * Rejects null, slash and ISO controls even when a control occurs at an edge.
     *
     * @param value the name to validate and strip
     * @return the name with leading and trailing whitespace removed
     * @throws ValidationException if the name is null, blank or contains slash or ISO control characters
     */
    public static String normalizeName(String value) {
        LOGGER.log(Level.FINE, "Starting name validation");
        if (value == null || value.indexOf('/') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) {
            LOGGER.log(Level.FINE, "Name validation rejected; null or forbidden character");
            throw new ValidationException("Name must not be null or contain slash or control characters.");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            LOGGER.log(Level.FINE, "Name validation rejected; blank name");
            throw new ValidationException("Name must not be blank.");
        }
        assert normalized.equals(normalized.strip()) && !normalized.isBlank()
                : "Normalization must return a stripped, nonblank name";
        LOGGER.log(Level.FINE, "Name validation completed");
        return normalized;
    }

    /**
     * Requires a positive duration in whole half-hour slots, without an arbitrary upper cap.
     *
     * @param minutes the duration in minutes
     * @throws ValidationException if the duration is nonpositive or not a multiple of {@link TimeRules#SLOT_MINUTES}
     */
    public static void requireDuration(int minutes) {
        LOGGER.log(Level.FINE, "Starting duration validation");
        assert TimeRules.SLOT_MINUTES > 0 : "The configured slot duration must be positive";
        if (minutes <= 0 || minutes % TimeRules.SLOT_MINUTES != 0) {
            LOGGER.log(Level.FINE, "Duration validation rejected; nonpositive or unaligned duration");
            throw new ValidationException("Duration must be a positive multiple of 30 minutes.");
        }
        LOGGER.log(Level.FINE, "Duration validation completed");
    }
}
