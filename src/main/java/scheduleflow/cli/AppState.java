//@@author ka-how
package scheduleflow.cli;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.Plan;

/**
 * Holds the saved snapshot and optional temporary plan for one running application.
 */
public final class AppState {
    /**
     * Records state transitions when diagnostic logging is enabled.
     */
    private static final Logger LOGGER = Logger.getLogger(AppState.class.getName());

    /**
     * Holds only the last successfully loaded or saved data.
     */
    private Snapshot snapshot;
    /**
     * Holds a temporary plan derived from the current snapshot.
     */
    private Optional<Plan> currentPlan = Optional.empty();

    /**
     * Creates live state from a loaded snapshot with no restored plan.
     *
     * @param initial successfully loaded data
     * @throws NullPointerException if the snapshot is null
     */
    public AppState(Snapshot initial) {
        snapshot = Objects.requireNonNull(initial, "initial");
    }

    /**
     * Returns the last successfully loaded or saved snapshot.
     */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Returns the current temporary plan, if one has been generated since the last mutation.
     */
    public Optional<Plan> currentPlan() {
        assert currentPlan.isEmpty() || currentPlan.orElseThrow().source().equals(snapshot)
                : "The current plan must describe the live snapshot";
        return currentPlan;
    }

    /**
     * Publishes a previously validated, successfully saved nonnull candidate and clears the plan.
     * The caller establishes this precondition before saving; no domain validation or direct I/O occurs here.
     *
     * @param saved successfully saved candidate
     * @throws NullPointerException if the candidate is null
     */
    public void commit(Snapshot saved) {
        snapshot = Objects.requireNonNull(saved, "saved");
        currentPlan = Optional.empty();
        assert snapshot == saved && currentPlan.isEmpty() : "Publication must invalidate the previous plan";
        LOGGER.log(Level.FINE, "Saved snapshot published and temporary plan cleared");
    }

    /**
     * Replaces only the plan after checking that its source equals the current snapshot.
     * Rejects stale plans before changing live state, even when assertions are disabled.
     *
     * @param plan plan derived from the current snapshot
     * @throws NullPointerException if the plan is null
     * @throws IllegalArgumentException if the plan describes different data
     */
    public void replacePlan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.source().equals(snapshot)) {
            LOGGER.log(Level.FINE, "Rejected a plan for a different snapshot");
            throw new IllegalArgumentException("Plan source must match the current snapshot.");
        }
        currentPlan = Optional.of(plan);
        assert currentPlan.orElseThrow().source().equals(snapshot) : "Plan replacement must preserve source data";
        LOGGER.log(Level.FINE, "Temporary plan replaced");
    }
}
