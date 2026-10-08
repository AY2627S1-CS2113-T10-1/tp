package scheduleflow.commitment;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;

/**
 * Creates weekly commitment candidates without saving or changing live state.
 */
public final class CommitmentService {
    /**
     * Creates the CommitmentService dependencies without performing I/O.
     */
    public CommitmentService() {
    }

    /**
     * Creates a candidate after checking overlap and counter overflow.
     * Preserves the original snapshot so callers can save before publishing the change.
     *
     * @throws ValidationException if the commitment is invalid, overlaps an existing series or IDs are exhausted
     */
    public Snapshot add(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes) {
        Objects.requireNonNull(state, "state");
        if (state.nextCommitmentId() == Integer.MAX_VALUE) {
            throw new ValidationException("Commitment IDs are exhausted.");
        }
        Commitment added = new Commitment(state.nextCommitmentId(), name, day, start, minutes);
        for (Commitment existing : state.commitments()) {
            if (added.overlaps(existing)) {
                throw new ValidationException("Commitment overlaps " + existing.displayId() + ": " + existing.name());
            }
        }
        List<Commitment> commitments = new ArrayList<>(state.commitments());
        commitments.add(added);
        return new Snapshot(state.tasks(), commitments, state.nextTaskId(), state.nextCommitmentId() + 1);
    }

    /**
     * Removes a known weekly series from a candidate without reusing its ID.
     * This feature is intentionally unfinished in the shared starter.
     */
    public Snapshot delete(Snapshot state, int commitmentId) {
        throw new UnsupportedOperationException("TODO(Commitment): implement CommitmentService.delete");
    }

    /**
     * Returns an immutable view ordered by weekday, start and numeric ID.
     * This feature is intentionally unfinished in the shared starter.
     */
    public List<Commitment> list(Snapshot state) {
        throw new UnsupportedOperationException("TODO(Commitment): implement CommitmentService.list");
    }
}
