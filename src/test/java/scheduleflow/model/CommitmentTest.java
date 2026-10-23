package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/**
 * Checks complete weekly intervals, including portions outside study hours.
 */
class CommitmentTest {
    @Test
    void overlaps_intersectionAndContainment_isSymmetric() {
        Commitment lecture = monday(1, 10, 0, 120);
        for (Commitment other : new Commitment[] {
                monday(2, 11, 30, 60), monday(3, 10, 30, 30),
                monday(4, 9, 0, 240), monday(5, 10, 0, 120)
        }) {
            assertTrue(lecture.overlaps(other));
            assertTrue(other.overlaps(lecture));
        }
    }

    @Test
    void overlaps_adjacencyAndOtherDays_doNotOverlap() {
        Commitment lecture = monday(1, 10, 0, 120);
        assertFalse(lecture.overlaps(monday(2, 12, 0, 60)));
        assertFalse(lecture.overlaps(monday(3, 9, 0, 60)));
        assertFalse(lecture.overlaps(new Commitment(2, "A", DayOfWeek.TUESDAY, LocalTime.of(10, 0), 120)));
    }

    @Test
    void constructor_midnightAndFullDay_keepExclusiveEndpoint() {
        Commitment midnight = monday(10, 23, 30, 30);
        assertEquals(1440, midnight.endMinuteOfDay());
        assertEquals("C10", midnight.displayId());
        Commitment fullDay = monday(2, 0, 0, 1440);
        assertTrue(fullDay.overlaps(midnight));
        assertEquals(1440, fullDay.endMinuteOfDay());
        assertEquals("Same name", fullDay.name());
    }

    @Test
    void constructor_invalidFieldsAndOverflow_rejects() {
        assertThrows(ValidationException.class, () -> monday(0, 10, 0, 30));
        assertThrows(ValidationException.class, () -> monday(1, 10, 15, 30));
        assertThrows(ValidationException.class, () -> monday(1, 23, 30, 60));
        assertThrows(ValidationException.class, () -> monday(1, 0, 0, Integer.MAX_VALUE - 7));
        assertThrows(ValidationException.class, () -> monday(1, 0, 0, 0));
        assertThrows(ValidationException.class,
                () -> new Commitment(1, "A", null, LocalTime.NOON, 30));
        for (LocalTime start : new LocalTime[] {null, LocalTime.NOON.withSecond(1), LocalTime.NOON.withNano(1)}) {
            assertThrows(ValidationException.class,
                    () -> new Commitment(1, "A", DayOfWeek.MONDAY, start, 30));
        }
    }

    @Test
    void overlaps_outsideStudyHours_stillRejectsClashes() {
        assertTrue(monday(1, 0, 0, 120).overlaps(monday(2, 1, 0, 30)));
        assertFalse(monday(1, 23, 30, 30).overlaps(monday(2, 0, 0, 30)));
    }

    @Test
    void dateRange_occurrencesBoundariesAndDisjointSemesters_areCalendarAware() {
        LocalDate first = LocalDate.of(2026, 8, 10);
        LocalDate last = LocalDate.of(2026, 11, 16);
        Commitment semester = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, last);
        assertTrue(semester.occursOn(first));
        assertTrue(semester.occursOn(last));
        assertFalse(semester.occursOn(first.minusWeeks(1)));
        assertFalse(semester.occursOn(last.plusWeeks(1)));
        assertFalse(semester.occursOn(first.plusDays(1)));
        Commitment nextSemester = new Commitment(2, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                LocalDate.of(2027, 1, 11), LocalDate.of(2027, 5, 3));
        assertFalse(semester.overlaps(nextSemester));
        assertTrue(semester.overlaps(new Commitment(3, "Clash", DayOfWeek.MONDAY, LocalTime.of(10, 30), 30,
                first, first)));
    }

    @Test
    void dateRange_missingReversedOrWithoutWeekday_rejects() {
        LocalDate monday = LocalDate.of(2026, 8, 10);
        assertThrows(ValidationException.class, () -> new Commitment(1, "A", DayOfWeek.MONDAY,
                LocalTime.NOON, 30, monday, null));
        assertThrows(ValidationException.class, () -> new Commitment(1, "A", DayOfWeek.MONDAY,
                LocalTime.NOON, 30, null, monday));
        assertThrows(ValidationException.class, () -> new Commitment(1, "A", DayOfWeek.MONDAY,
                LocalTime.NOON, 30, monday.plusDays(1), monday));
        assertThrows(ValidationException.class, () -> new Commitment(1, "A", DayOfWeek.MONDAY,
                LocalTime.NOON, 30, monday.plusDays(1), monday.plusDays(6)));
        assertEquals("date", assertThrows(NullPointerException.class,
                () -> monday(1, 10, 0, 30).occursOn(null)).getMessage());
    }

    @Test
    void skippedDates_removeOnlySelectedOccurrencesAndControlOverlap() {
        LocalDate first = LocalDate.of(2026, 8, 10);
        LocalDate second = first.plusWeeks(1);
        Commitment skipped = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, second, Set.of(first));
        assertTrue(skipped.recursOn(first));
        assertFalse(skipped.occursOn(first));
        assertTrue(skipped.occursOn(second));
        assertFalse(skipped.overlaps(new Commitment(2, "Single", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, first)));
        assertTrue(skipped.overlaps(new Commitment(3, "Both", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, second)));

        assertThrows(ValidationException.class, () -> new Commitment(4, "Wrong day", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), 60, first, second, Set.of(first.plusDays(1))));
        assertThrows(ValidationException.class, () -> new Commitment(4, "Outside", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), 60, first, second, Set.of(first.minusWeeks(1))));
        assertEquals("skippedDates", assertThrows(NullPointerException.class,
                () -> new Commitment(4, "Missing", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                        first, second, null)).getMessage());
    }

    //@@author Darren50
    @Test
    void constructor_missingIdentityOrTime_identifiesInvalidField() {
        assertEquals("Commitment ID must be positive.", assertThrows(ValidationException.class,
                () -> new Commitment(0, "Lecture", null, null, 30)).getMessage());
        assertEquals("Commitment weekday must be provided.", assertThrows(ValidationException.class,
                () -> new Commitment(1, "Lecture", null, null, 30)).getMessage());
        assertEquals("Commitment start time must be provided.", assertThrows(ValidationException.class,
                () -> new Commitment(1, "Lecture", DayOfWeek.MONDAY, null, 30)).getMessage());
    }

    @Test
    void overlaps_nullSeries_rejectsWithNamedPrecondition() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> monday(1, 10, 0, 30).overlaps(null));
        assertEquals("other", exception.getMessage());
    }

    @Test
    void isOneTime_equalDates_occursOnlyOnThatDate() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment meeting = oneTime(1, friday, 14, 60);
        assertTrue(meeting.isOneTime());
        assertTrue(meeting.occursOn(friday));
        assertFalse(meeting.occursOn(friday.minusWeeks(1)));
        assertFalse(meeting.occursOn(friday.plusWeeks(1)));
        assertFalse(meeting.occursOn(friday.plusDays(1)));
        assertFalse(new Commitment(2, "Weekly", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60).isOneTime());
        assertFalse(new Commitment(3, "Two Fridays", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60,
                friday, friday.plusWeeks(1)).isOneTime());
    }

    @Test
    void overlaps_oneTime_comparesActualDateWithRecurringAndOneTimeCommitments() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment meeting = oneTime(1, friday, 14, 60);
        Commitment weekly = new Commitment(2, "Weekly", DayOfWeek.FRIDAY, LocalTime.of(14, 30), 30);
        assertTrue(meeting.overlaps(weekly));
        assertTrue(weekly.overlaps(meeting));
        assertTrue(meeting.overlaps(oneTime(3, friday, 13, 90)));
        assertFalse(meeting.overlaps(oneTime(4, friday.plusWeeks(1), 14, 60)));
        assertFalse(meeting.overlaps(oneTime(5, friday, 15, 30)));
        assertFalse(meeting.overlaps(oneTime(6, friday, 13, 60)));
        Commitment skippedThatDay = new Commitment(7, "Skipped", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60,
                friday.minusWeeks(1), friday.plusWeeks(1), Set.of(friday));
        assertFalse(meeting.overlaps(skippedThatDay));
        assertFalse(skippedThatDay.overlaps(meeting));
    }

    @Test
    void constructor_oneTimeWithSkippedDate_rejectsRecordThatNeverOccurs() {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        assertEquals("A one-time commitment cannot skip its only occurrence.",
                assertThrows(ValidationException.class, () -> new Commitment(1, "Meeting", DayOfWeek.FRIDAY,
                        LocalTime.of(14, 0), 60, friday, friday, Set.of(friday))).getMessage());
        assertEquals("Commitment date range must contain its weekday.",
                assertThrows(ValidationException.class, () -> new Commitment(1, "Meeting", DayOfWeek.MONDAY,
                        LocalTime.of(14, 0), 60, friday, friday)).getMessage());
    }

    /**
     * Creates a one-time commitment whose weekday is derived from its only date.
     */
    private Commitment oneTime(int id, LocalDate date, int hour, int duration) {
        return new Commitment(id, "Meeting", date.getDayOfWeek(), LocalTime.of(hour, 0), duration, date, date);
    }
    //@@author

    /**
     * Creates a Monday series with a padded name to exercise normalization.
     */
    private Commitment monday(int id, int hour, int minute, int duration) {
        return new Commitment(id, " Same name ", DayOfWeek.MONDAY, LocalTime.of(hour, minute), duration);
    }
}
