package scheduleflow.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * @param startDate inclusive first date, or null together with endDate for an unbounded series
 * @param endDate inclusive last date, or null together with startDate
 */
public record Commitment(int id, String name, DayOfWeek day, LocalTime start, int durationMinutes,
        LocalDate startDate, LocalDate endDate) {
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;

    //@@author Darren50
    /** Retains the original constructor for weekly series without date limits. */
    public Commitment(int id, String name, DayOfWeek day, LocalTime start, int durationMinutes) {
        this(id, name, day, start, durationMinutes, null, null);
    }
    //@@author

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
        validateDates(day, startDate, endDate);
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

    //@@author Darren50
    /** Validates a paired inclusive range containing at least one occurrence of the weekday. */
    private static void validateDates(DayOfWeek day, LocalDate first, LocalDate last) {
        if (first == null && last == null) {
            return;
        }
        if (first == null || last == null) {
            throw new ValidationException("Provide both commitment start and end dates.");
        }
        if (first.getYear() < 1 || last.getYear() > 9999) {
            throw new ValidationException("Commitment dates must use years 0001 to 9999.");
        }
        if (first.isAfter(last)) {
            throw new ValidationException("Commitment start date must not be after its end date.");
        }
        if (!containsWeekday(first, last, day)) {
            throw new ValidationException("Commitment date range must contain its weekday.");
        }
    }

    /** Checks for a matching day without stepping beyond the maximum supported date. */
    private static boolean containsWeekday(LocalDate first, LocalDate last, DayOfWeek day) {
        int offset = Math.floorMod(day.getValue() - first.getDayOfWeek().getValue(), 7);
        return last.toEpochDay() - first.toEpochDay() >= offset;
    }

    /** Returns whether this weekly series reserves time on the supplied calendar date. */
    public boolean occursOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.getDayOfWeek() == day
                && (startDate == null || (!date.isBefore(startDate) && !date.isAfter(endDate)));
    }

    /** Checks whether two series share an actual occurrence, not merely intersecting date ranges. */
    private boolean sharesOccurrence(Commitment other) {
        if (startDate == null || other.startDate == null) {
            return true;
        }
        LocalDate first = startDate.isAfter(other.startDate) ? startDate : other.startDate;
        LocalDate last = endDate.isBefore(other.endDate) ? endDate : other.endDate;
        return !first.isAfter(last) && containsWeekday(first, last, day);
    }
    //@@author

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
        return day == other.day && thisStart < other.endMinuteOfDay() && otherStart < endMinuteOfDay()
                && sharesOccurrence(other);
    }
}
