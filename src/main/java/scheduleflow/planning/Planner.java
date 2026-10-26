// @@author GabrielYuSP
package scheduleflow.planning;

import java.time.LocalDateTime;
import scheduleflow.model.Snapshot;

/**
 * Generates a deterministic immutable plan from one snapshot and captured time.
 */
public interface Planner {
    /**
     * Allocates eligible whole slots, preserving source estimates and recording any remainder.
     */
    Plan generate(Snapshot state, LocalDateTime now);
}
// @@author
