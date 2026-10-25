// @@author ka-how
package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.Plan;
import scheduleflow.planning.TimeRules;

/**
 * Verifies live-state publication and independent plan replacement.
 */
class AppStateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);

    @Test
    void constructor_startsWithoutPlan() {
        Snapshot initial = Snapshot.empty();
        AppState state = new AppState(initial);
        assertSame(initial, state.snapshot());
        assertTrue(state.currentPlan().isEmpty());
    }

    @Test
    void commit_savedCandidate_clearsPlanAndPublishesSnapshot() {
        AppState state = new AppState(Snapshot.empty());
        state.replacePlan(plan(state.snapshot(), NOW));
        Snapshot saved = new Snapshot(List.of(new Task(1, "A", NOW.plusDays(1), 30)), List.of(), 2, 1);
        state.commit(saved);
        assertSame(saved, state.snapshot());
        assertTrue(state.currentPlan().isEmpty());
    }

    @Test
    void replacePlan_keepsSnapshotAndCounters() {
        Snapshot snapshot = new Snapshot(List.of(), List.of(), 9, 17);
        AppState state = new AppState(snapshot);
        Plan first = plan(snapshot, NOW);
        Plan replacement = plan(snapshot, NOW.plusHours(5));
        state.replacePlan(first);
        state.replacePlan(replacement);
        assertSame(snapshot, state.snapshot());
        assertSame(replacement, state.currentPlan().orElseThrow());
        assertEquals(9, state.snapshot().nextTaskId());
        assertEquals(17, state.snapshot().nextCommitmentId());
        assertFalse(state.currentPlan().orElseThrow().equals(first));
    }

    @Test
    void nullPreconditions_failWithoutChangingState() {
        assertThrows(NullPointerException.class, () -> new AppState(null));
        Snapshot snapshot = Snapshot.empty();
        AppState state = new AppState(snapshot);
        Plan plan = plan(snapshot, NOW);
        state.replacePlan(plan);
        assertThrows(NullPointerException.class, () -> state.commit(null));
        assertThrows(NullPointerException.class, () -> state.replacePlan(null));
        assertSame(snapshot, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
    }

    @Test
    void replacePlan_differentSource_rejectsWithoutChangingSnapshotOrExistingPlan() {
        Snapshot snapshot = Snapshot.empty();
        AppState state = new AppState(snapshot);
        Plan original = plan(snapshot, NOW);
        state.replacePlan(original);
        Snapshot different = new Snapshot(List.of(new Task(1, "A", NOW.plusDays(1), 30)), List.of(), 2, 1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> state.replacePlan(plan(different, NOW)));
        assertEquals("Plan source must match the current snapshot.", failure.getMessage());
        assertSame(snapshot, state.snapshot());
        assertSame(original, state.currentPlan().orElseThrow());
    }

    @Test
    void replacePlan_equalSourceFromDifferentInstance_acceptsPlan() {
        Snapshot snapshot = Snapshot.empty();
        AppState state = new AppState(snapshot);
        Plan replacement = plan(new Snapshot(List.of(), List.of(), 1, 1), NOW);
        state.replacePlan(replacement);
        assertSame(snapshot, state.snapshot());
        assertSame(replacement, state.currentPlan().orElseThrow());
    }

    /**
     * Supplies a fixed-time plan without allocating work or accessing saved data.
     */
    private Plan plan(Snapshot snapshot, LocalDateTime now) {
        return new Plan(now, TimeRules.horizonEnd(now), snapshot, List.of(), List.of());
    }
}
// @@author
