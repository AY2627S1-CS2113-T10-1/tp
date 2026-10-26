package scheduleflow.storage;

import java.util.Optional;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.Plan;

/**
 * Loads and atomically saves snapshots and their optional frozen plans.
 */
public interface Storage {
    /**
     * Loads a complete valid snapshot, or an empty store when the file is definitely absent.
     * A saved plan is validated with its source before the snapshot is returned.
     *
     * @return complete immutable state, with persisted ID counters
     * @throws StorageException if the store cannot be read or validated
     */
    Snapshot load() throws StorageException;

    /**
     * Restores the plan saved with a matching snapshot, if one exists.
     *
     * @param source snapshot loaded for this application session
     * @return saved frozen plan, or empty for legacy snapshot-only files
     * @throws StorageException if the saved plan is unreadable, invalid or has a different source
     */
    Optional<Plan> loadPlan(Snapshot source) throws StorageException;

    /**
     * Saves a candidate completely before callers publish it in memory.
     * A successful snapshot save removes any previously saved plan.
     *
     * @param state complete validated candidate, never a partial update
     * @throws StorageException if atomic replacement cannot complete; the old target remains intact
     * @throws NullPointerException if the candidate is null
     */
    void save(Snapshot state) throws StorageException;

    /**
     * Atomically saves a frozen plan together with its source snapshot.
     * The source may include new recurring-task occurrences; callers publish it only after saving succeeds.
     *
     * @param plan validated result to retain across restarts
     * @throws StorageException if saving fails without replacing the prior file
     */
    void savePlan(Plan plan) throws StorageException;
}
