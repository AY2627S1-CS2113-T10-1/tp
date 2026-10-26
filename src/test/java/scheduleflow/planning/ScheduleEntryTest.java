// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/** Checks that break intervals carry no invented task or commitment identity. */
class ScheduleEntryTest {
    @Test
    void constructor_break_requiresEmptyReferenceAndName() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 21, 9, 0);
        ScheduleEntry rest = new ScheduleEntry(start, start.plusMinutes(30), SlotKind.BREAK, "", "");
        assertEquals(SlotKind.BREAK, rest.kind());
        assertThrows(ValidationException.class,
                () -> new ScheduleEntry(start, start.plusMinutes(30), SlotKind.BREAK, "T1", ""));
        assertThrows(ValidationException.class,
                () -> new ScheduleEntry(start, start.plusMinutes(30), SlotKind.BREAK, "", "Rest"));
    }
}
// @@author
