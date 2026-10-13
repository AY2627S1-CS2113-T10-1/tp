package scheduleflow.storage;

import scheduleflow.model.Snapshot;

/**
 * Loads and atomically saves the sole persisted domain representation.
 */
public interface Storage {
    /**
     * Loads a complete valid snapshot, or an empty store when the file is definitely absent.
     *
     * @return complete immutable state, with persisted ID counters
     * @throws StorageException if the store cannot be read or validated
     */
    Snapshot load() throws StorageException;

    /**
     * Saves a candidate completely before callers publish it in memory.
     *
     * @param state complete validated candidate, never a partial update
     * @throws StorageException if atomic replacement cannot complete; the old target remains intact
     * @throws NullPointerException if the candidate is null
     */
    void save(Snapshot state) throws StorageException;
}
