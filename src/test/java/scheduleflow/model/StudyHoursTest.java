package scheduleflow.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

// @@author GabrielYuSP
/** Verifies precedence, independent weekdays, reset and immutable settings. */
class StudyHoursTest {
    @Test
    void forDate_overridesAndResets_restoreInheritedWindows() {
        StudyWindow weekly = new StudyWindow(LocalTime.of(9, 0), LocalTime.of(22, 0));
        StudyWindow once = new StudyWindow(LocalTime.of(14, 0), LocalTime.of(19, 0));
        LocalDate tuesday = LocalDate.of(2026, 10, 27);
        StudyHours hours = new StudyHours(Map.of()).withWindow("tuesday", weekly)
                .withWindow(tuesday.toString(), once);
        assertEquals(once, hours.forDate(tuesday));
        assertEquals(weekly, hours.forDate(tuesday.plusWeeks(1)));
        assertEquals(StudyWindow.DEFAULT, hours.forDate(tuesday.plusDays(1)));
        assertEquals(weekly, hours.reset(tuesday.toString()).forDate(tuesday));
        assertEquals(once, hours.reset("tuesday").forDate(tuesday));
        assertEquals(StudyWindow.DEFAULT, hours.reset("tuesday").forDate(tuesday.plusWeeks(1)));
        assertEquals(2, hours.windows().size());
    }

    @Test
    void constructor_mutableMap_copiesAndRejectsInvalidTargets() {
        Map<String, StudyWindow> source = new HashMap<>();
        source.put("monday", StudyWindow.DEFAULT);
        StudyHours hours = new StudyHours(source);
        source.clear();
        assertEquals(1, hours.windows().size());
        assertThrows(UnsupportedOperationException.class, () -> hours.windows().clear());
        for (String target : new String[] {"", "MONDAY", "tue", "2026-02-30", "0000-01-01", "2026-1-01"}) {
            assertThrows(ValidationException.class, () -> hours.withWindow(target, StudyWindow.DEFAULT));
            assertThrows(ValidationException.class, () -> hours.reset(target));
            assertThrows(ValidationException.class, () -> hours.forTarget(target));
        }
    }
}
