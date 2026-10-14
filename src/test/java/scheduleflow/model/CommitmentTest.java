package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
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
    void overlaps_nullSeries_rejectsWithNamedPrecondition() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> monday(1, 10, 0, 30).overlaps(null));
        assertEquals("other", exception.getMessage());
    }

    /**
     * Creates a Monday series with a padded name to exercise normalization.
     */
    private Commitment monday(int id, int hour, int minute, int duration) {
        return new Commitment(id, " Same name ", DayOfWeek.MONDAY, LocalTime.of(hour, minute), duration);
    }
}
