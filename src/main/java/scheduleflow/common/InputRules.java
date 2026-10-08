package scheduleflow.common;

import scheduleflow.planning.TimeRules;

/**
 * Provides the shared name and duration rules for domain records.
 */
public final class InputRules {
    private InputRules() {
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
        if (value == null || value.indexOf('/') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ValidationException("Name must not be null or contain slash or control characters.");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new ValidationException("Name must not be blank.");
        }
        return normalized;
    }

    /**
     * Requires a positive duration in whole half-hour slots, without an arbitrary upper cap.
     *
     * @param minutes the duration in minutes
     * @throws ValidationException if the duration is nonpositive or not a multiple of {@link TimeRules#SLOT_MINUTES}
     */
    public static void requireDuration(int minutes) {
        if (minutes <= 0 || minutes % TimeRules.SLOT_MINUTES != 0) {
            throw new ValidationException("Duration must be a positive multiple of 30 minutes.");
        }
    }
}
