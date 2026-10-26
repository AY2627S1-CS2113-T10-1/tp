// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/**
 * Uses fixed wall times to verify horizon and rounding boundaries.
 */
class TimeRulesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @Test
    void horizonEnd_calendarYear_usesLeapDayAdjustment() {
        assertEquals(LocalDateTime.of(2025, 2, 28, 0, 0),
                TimeRules.horizonEnd(LocalDateTime.of(2024, 2, 29, 17, 2, 3, 4)));
        assertEquals(LocalDateTime.of(2027, 9, 21, 0, 0), TimeRules.horizonEnd(TODAY.atTime(7, 0)));
    }

    @Test
    void eligibleStart_horizonBoundaries_acceptsOnlyIncludedDates() {
        LocalDateTime now = TODAY.atTime(23, 59);
        LocalDate last = TODAY.plusYears(1).minusDays(1);
        assertEquals(last.atTime(8, 0), TimeRules.eligibleStart(last, now));
        assertThrows(ValidationException.class, () -> TimeRules.eligibleStart(TODAY.minusDays(1), now));
        assertThrows(ValidationException.class, () -> TimeRules.eligibleStart(TODAY.plusYears(1), now));
        assertThrows(ValidationException.class, () -> TimeRules.eligibleStart(null, now));
    }

    @Test
    void eligibleStart_secondsAndNanoseconds_roundsUp() {
        assertCutoff(LocalTime.of(10, 0), LocalTime.of(10, 0));
        assertCutoff(LocalTime.of(10, 0, 1), LocalTime.of(10, 30));
        assertCutoff(LocalTime.of(10, 0, 0, 1), LocalTime.of(10, 30));
        assertCutoff(LocalTime.of(10, 7), LocalTime.of(10, 30));
        assertCutoff(LocalTime.of(10, 29, 59, 999999999), LocalTime.of(10, 30));
        assertCutoff(LocalTime.of(10, 30), LocalTime.of(10, 30));
        assertCutoff(LocalTime.of(10, 30, 0, 1), LocalTime.of(11, 0));
    }

    @Test
    void eligibleStart_windowClampsAndFutureDay() {
        assertCutoff(LocalTime.MIDNIGHT, LocalTime.of(8, 0));
        assertCutoff(LocalTime.of(8, 0), LocalTime.of(8, 0));
        assertCutoff(LocalTime.of(21, 59, 59, 999999999), LocalTime.of(22, 0));
        assertCutoff(LocalTime.of(22, 0), LocalTime.of(22, 0));
        assertCutoff(LocalTime.MAX, LocalTime.of(22, 0));
        assertEquals(TODAY.plusDays(1).atTime(8, 0),
                TimeRules.eligibleStart(TODAY.plusDays(1), TODAY.atTime(23, 59)));
    }

    private void assertCutoff(LocalTime now, LocalTime expected) {
        assertEquals(TODAY.atTime(expected), TimeRules.eligibleStart(TODAY, TODAY.atTime(now)));
    }
}
// @@author
