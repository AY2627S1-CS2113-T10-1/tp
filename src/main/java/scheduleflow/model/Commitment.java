package scheduleflow.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;
import scheduleflow.planning.TimeRules;

/**
 * Stores a weekly half-open busy interval which may end at, but never cross, midnight.
 * Validates the complete interval, including time outside study hours, without I/O.
 *
 * @param id stable positive identifier
 * @param name nonblank description normalized using the shared name rules
 * @param day nonnull recurrence weekday
 * @param start nonnull local time aligned to a half-hour boundary
 * @param durationMinutes positive multiple of 30 ending no later than midnight
 */
public record Commitment(int id, String name, DayOfWeek day, LocalTime start, int durationMinutes) {
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    /**
     * Creates an aligned commitment and normalizes its name using the shared validation rules.
     *
     * @throws ValidationException if any field or the same-day interval is invalid
     */
    public Commitment {
        if (id <= 0) {
            throw new ValidationException("Commitment ID must be positive.");
        }
        if (day == null) {
            throw new ValidationException("Commitment weekday must be provided.");
        }
        if (start == null) {
            throw new ValidationException("Commitment start time must be provided.");
        }
        name = InputRules.normalizeName(name);
        InputRules.requireDuration(durationMinutes);
        if (start.getSecond() != 0 || start.getNano() != 0 || start.getMinute() % TimeRules.SLOT_MINUTES != 0) {
            throw new ValidationException("Commitment start must be on a half-hour boundary.");
        }
        int startMinute = start.getHour() * MINUTES_PER_HOUR + start.getMinute();
        // Subtraction avoids overflowing when the supplied duration is near Integer.MAX_VALUE.
        if (durationMinutes > MINUTES_PER_DAY - startMinute) {
            throw new ValidationException("Commitment must not continue past midnight.");
        }
    }

    /**
     * Returns the stable commitment identifier with no zero padding.
     */
    public String displayId() {
        return "C" + id;
    }

    /**
     * Returns the exclusive endpoint, using 1440 for midnight rather than wrapping to zero.
     *
     * @return minute of day in the range 30 through 1440
     */
    public int endMinuteOfDay() {
        int endpoint = start.getHour() * MINUTES_PER_HOUR + start.getMinute() + durationMinutes;
        assert endpoint > 0 && endpoint <= MINUTES_PER_DAY : "A validated interval must end within its day";
        return endpoint;
    }

    /**
     * Tests intersection on the same weekday; adjacent endpoints do not overlap.
     *
     * @param other validated series to compare
     * @return whether the two half-open weekly intervals intersect
     * @throws NullPointerException if other is null
     */
    public boolean overlaps(Commitment other) {
        Objects.requireNonNull(other, "other");
        int thisStart = endMinuteOfDay() - durationMinutes;
        int otherStart = other.endMinuteOfDay() - other.durationMinutes;
        return day == other.day && thisStart < other.endMinuteOfDay() && otherStart < endMinuteOfDay();
    }
}
