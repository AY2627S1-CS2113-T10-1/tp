package scheduleflow.model;

import java.time.LocalTime;
import scheduleflow.common.ValidationException;

// @@author GabrielYuSP
/** Defines a positive same-day study interval with half-hour endpoints. */
public record StudyWindow(LocalTime start, LocalTime end) {
    public static final StudyWindow DEFAULT = new StudyWindow(LocalTime.of(8, 0), LocalTime.of(22, 0));

    /** Rejects reversed, empty, null or unaligned intervals; overnight windows are unsupported. */
    public StudyWindow {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ValidationException("Study start must be before end on the same day.");
        }
        if (!isAligned(start) || !isAligned(end)) {
            throw new ValidationException("Study hours must use half-hour boundaries (:00 or :30).");
        }
    }

    /** Checks precision as well as minute alignment. */
    private static boolean isAligned(LocalTime time) {
        return time.getMinute() % 30 == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }
}
