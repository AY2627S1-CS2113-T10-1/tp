package scheduleflow.planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.StudyWindow;

/**
 * Defines local wall-time study windows and the rolling calendar-year horizon.
 */
public final class TimeRules {
    public static final int SLOT_MINUTES = 30;
    public static final LocalTime STUDY_START = LocalTime.of(8, 0);
    public static final LocalTime STUDY_END = LocalTime.of(22, 0);

    private TimeRules() {
    }

    /**
     * Returns the exclusive calendar boundary one year after today's date, at midnight.
     */
    public static LocalDateTime horizonEnd(LocalDateTime now) {
        return Objects.requireNonNull(now, "now").toLocalDate().plusYears(1).atStartOfDay();
    }

    /**
     * Returns the first eligible boundary on an included date, clamped to the study window.
     * Any fraction of a half-hour advances to the next slot, including a single nanosecond.
     */
    public static LocalDateTime eligibleStart(LocalDate date, LocalDateTime now) {
        return eligibleStart(date, now, StudyWindow.DEFAULT);
    }

    /** Returns the rounded planning cutoff clamped to the selected date's effective window. */
    public static LocalDateTime eligibleStart(LocalDate date, LocalDateTime now, StudyWindow window) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(window, "window");
        if (date == null || date.isBefore(now.toLocalDate()) || !date.isBefore(horizonEnd(now).toLocalDate())) {
            throw new ValidationException("Date must lie inside the current planning horizon.");
        }
        LocalDateTime windowStart = date.atTime(window.start());
        LocalDateTime windowEnd = date.atTime(window.end());
        if (date.isAfter(now.toLocalDate()) || !now.isAfter(windowStart)) {
            return windowStart;
        }
        if (!now.isBefore(windowEnd)) {
            return windowEnd;
        }
        LocalDateTime rounded = now.withMinute((now.getMinute() / SLOT_MINUTES) * SLOT_MINUTES)
                .withSecond(0).withNano(0);
        return rounded.isBefore(now) ? rounded.plusMinutes(SLOT_MINUTES) : rounded;
    }
}
