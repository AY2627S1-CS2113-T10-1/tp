package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies partial-edit transport without duplicating service domain validation. */
class TaskEditTest {
    @Test
    void constructor_nullContainers_rejectsWithNamedPreconditions() {
        assertEquals("name", assertThrows(NullPointerException.class,
                () -> new TaskEdit(null, Optional.empty(), false)).getMessage());
        assertEquals("deadline", assertThrows(NullPointerException.class,
                () -> new TaskEdit(Optional.empty(), null, false)).getMessage());
    }

    @Test
    void constructor_values_retainsSuppliedImmutableDetails() {
        Optional<String> name = Optional.of("  Draft  ");
        Optional<LocalDateTime> deadline = Optional.of(LocalDateTime.of(2026, 9, 22, 18, 0));
        TaskEdit edit = new TaskEdit(name, deadline, true);
        assertEquals(name, edit.name());
        assertEquals(deadline, edit.deadline());
        assertEquals(true, edit.clearDeadline());
        assertEquals(new TaskEdit(name, deadline, true), edit);
    }
}
