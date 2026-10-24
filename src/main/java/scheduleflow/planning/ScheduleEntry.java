package scheduleflow.planning;

import java.time.LocalDateTime;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;

/**
 * Represents a display interval with a canonical Tn/Cn reference or empty free-period fields.
 */
public record ScheduleEntry(LocalDateTime start, LocalDateTime end, SlotKind kind, String reference, String name) {
    /**
     * Validates aligned endpoints and references without performing schedule allocation.
     */
    public ScheduleEntry {
        if (start == null || end == null || kind == null || reference == null || name == null) {
            throw new ValidationException("Schedule entry fields must not be null.");
        }
        if (!start.toLocalDate().equals(end.toLocalDate()) || !start.isBefore(end)
                || !isAligned(start) || !isAligned(end)) {
            throw new ValidationException("Schedule entry must use aligned endpoints inside one study window.");
        }
        if (kind == SlotKind.FREE || kind == SlotKind.UNPLANNED || kind == SlotKind.BREAK) {
            if (!reference.isEmpty() || !name.isEmpty()) {
                throw new ValidationException(
                        "Free, unplanned and break entries must have empty references and names.");
            }
        } else {
            requireReference(reference, kind == SlotKind.TASK ? "T" : "C");
            if (!name.equals(InputRules.normalizeName(name))) {
                throw new ValidationException("Schedule names must already be normalized.");
            }
        }
    }

    private static boolean isAligned(LocalDateTime time) {
        return time.getMinute() % TimeRules.SLOT_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }

    private static void requireReference(String reference, String prefix) {
        if (!reference.matches(prefix + "[1-9][0-9]*")) {
            throw new ValidationException("Schedule reference must be a canonical " + prefix + " identifier.");
        }
        try {
            Integer.parseInt(reference.substring(1));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Schedule reference exceeds the supported integer ID range.");
        }
    }
}
