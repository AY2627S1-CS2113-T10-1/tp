package scheduleflow.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import scheduleflow.common.ValidationException;

/**
 * Holds immutable persisted records and separate, never-reused identity counters.
 * Preserves input order; ordered views belong to the services that need them.
 *
 * @param tasks unfinished tasks, including valid overdue records
 * @param commitments nonoverlapping recurring weekly commitments
 * @param nextTaskId positive counter strictly greater than every stored task ID
 * @param nextCommitmentId positive counter strictly greater than every stored commitment ID
 */
public record Snapshot(List<Task> tasks, List<Commitment> commitments, int nextTaskId, int nextCommitmentId) {
    /**
     * Copies records and validates IDs, counters and weekly overlaps without consulting a clock.
     * Performs no I/O and does not reset counters when either list becomes empty.
     *
     * @throws ValidationException if lists or elements are null, IDs or counters are invalid,
     *     or weekly commitments overlap
     */
    public Snapshot {
        if (tasks == null || commitments == null || tasks.stream().anyMatch(Objects::isNull)
                || commitments.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Snapshot lists and their elements must not be null.");
        }
        tasks = List.copyOf(tasks);
        commitments = List.copyOf(commitments);
        validateTaskIds(tasks, nextTaskId);
        validateCommitmentIds(commitments, nextCommitmentId);
        validateOverlaps(commitments);
    }

    /**
     * Returns a new store with empty immutable lists and both next IDs set to one.
     *
     * @return first-launch state; existing counters must instead be restored from storage
     */
    public static Snapshot empty() {
        return new Snapshot(List.of(), List.of(), 1, 1);
    }

    /**
     * Ensures each task has a unique ID below the next available task ID.
     *
     * @throws ValidationException if IDs are duplicated or the counter is nonpositive or not above every task ID
     */
    private static void validateTaskIds(List<Task> tasks, int nextTaskId) {
        if (nextTaskId <= 0) {
            throw new ValidationException("Next task ID must be positive.");
        }
        Set<Integer> ids = new HashSet<>();
        for (Task task : tasks) {
            if (!ids.add(task.id()) || task.id() >= nextTaskId) {
                throw new ValidationException("Duplicate task ID or next task ID does not exceed " + task.displayId());
            }
        }
    }

    /**
     * Ensures commitment identities and their independent next-ID counter are consistent.
     *
     * @throws ValidationException if the counter is nonpositive or IDs are duplicated or not below it
     */
    private static void validateCommitmentIds(List<Commitment> commitments, int nextCommitmentId) {
        if (nextCommitmentId <= 0) {
            throw new ValidationException("Next commitment ID must be positive.");
        }
        Set<Integer> ids = new HashSet<>();
        for (Commitment commitment : commitments) {
            if (!ids.add(commitment.id()) || commitment.id() >= nextCommitmentId) {
                throw new ValidationException("Duplicate commitment ID or next ID does not exceed "
                        + commitment.displayId());
            }
        }
    }

    /**
     * Validates complete weekly intervals, including times outside study hours, allowing adjacency.
     *
     * @throws ValidationException if any pair overlaps according to {@link Commitment#overlaps(Commitment)}
     */
    private static void validateOverlaps(List<Commitment> commitments) {
        for (int i = 0; i < commitments.size(); i++) {
            for (int j = i + 1; j < commitments.size(); j++) {
                if (commitments.get(i).overlaps(commitments.get(j))) {
                    throw new ValidationException("Commitments overlap: " + commitments.get(i).displayId()
                            + " and " + commitments.get(j).displayId());
                }
            }
        }
    }
}
