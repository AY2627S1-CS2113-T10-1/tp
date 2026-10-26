// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;

/** Checks the immutable break-result contract and compatibility with original plan callers. */
class PlanTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);

    @Test
    void constructor_breaks_copiesListAndRetainsOriginalConstructor() {
        ScheduleEntry rest = entry(9, SlotKind.BREAK);
        List<ScheduleEntry> breaks = new ArrayList<>(List.of(rest));
        Plan plan = plan(breaks);
        breaks.clear();
        assertEquals(List.of(rest), plan.breaks());
        assertThrows(UnsupportedOperationException.class, () -> plan.breaks().clear());
        assertTrue(new Plan(NOW, TimeRules.horizonEnd(NOW), Snapshot.empty(), List.of(), List.of())
                .breaks().isEmpty());
    }

    @Test
    void constructor_invalidBreakLists_rejectsNullWrongKindAndUnsortedEntries() {
        assertThrows(ValidationException.class, () -> plan(null));
        assertThrows(ValidationException.class, () -> plan(Arrays.asList((ScheduleEntry) null)));
        assertThrows(ValidationException.class, () -> plan(List.of(entry(9, SlotKind.FREE))));
        assertThrows(ValidationException.class,
                () -> plan(List.of(entry(10, SlotKind.BREAK), entry(9, SlotKind.BREAK))));
    }

    /** Creates a plan from caller-supplied break rows without enforcing allocation relationships. */
    private static Plan plan(List<ScheduleEntry> breaks) {
        return new Plan(NOW, TimeRules.horizonEnd(NOW), Snapshot.empty(), List.of(), List.of(), breaks);
    }

    /** Creates an aligned anonymous interval for validation checks. */
    private static ScheduleEntry entry(int hour, SlotKind kind) {
        LocalDateTime start = NOW.withHour(hour);
        return new ScheduleEntry(start, start.plusMinutes(30), kind, "", "");
    }
}
// @@author
