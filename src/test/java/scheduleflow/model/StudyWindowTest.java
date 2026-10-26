// @@author GabrielYuSP
package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/** Checks positive half-hour windows and same-day limits. */
class StudyWindowTest {
    @Test
    void constructor_validBoundaries_acceptsEarlyAndLateHours() {
        assertEquals(LocalTime.MIDNIGHT, new StudyWindow(LocalTime.MIDNIGHT, LocalTime.of(0, 30)).start());
        assertEquals(LocalTime.of(23, 30), new StudyWindow(LocalTime.of(23, 0), LocalTime.of(23, 30)).end());
    }

    @Test
    void constructor_invalidEndpoints_rejects() {
        LocalTime start = LocalTime.of(9, 0);
        for (LocalTime end : new LocalTime[] {null, start, start.minusMinutes(30), LocalTime.MIDNIGHT,
                start.plusMinutes(15), start.plusHours(1).plusSeconds(1), start.plusHours(1).plusNanos(1)}) {
            assertThrows(ValidationException.class, () -> new StudyWindow(start, end));
        }
        assertThrows(ValidationException.class, () -> new StudyWindow(null, start));
        assertThrows(ValidationException.class, () -> new StudyWindow(start.plusMinutes(1), start.plusHours(1)));
    }
}
// @@author
