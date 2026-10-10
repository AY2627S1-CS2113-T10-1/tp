package scheduleflow.commitment;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;

/**
 * Creates weekly commitment candidates without saving or changing live state.
 */
public final class CommitmentService {
    private static final Logger LOGGER = Logger.getLogger(CommitmentService.class.getName());

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
     * Leaves source records and both counters unchanged until the caller publishes the candidate.
     *
     * @throws ValidationException if the ID is nonpositive or does not identify a stored commitment
     */
    public Snapshot delete(Snapshot state, int commitmentId) {
        Objects.requireNonNull(state, "state");
        if (commitmentId <= 0) {
            throw new ValidationException("Commitment ID must be positive.");
        }
        List<Commitment> remaining = state.commitments().stream()
                .filter(commitment -> commitment.id() != commitmentId).toList();
        if (remaining.size() == state.commitments().size()) {
            LOGGER.fine(() -> "Rejected deletion of unknown commitment C" + commitmentId);
            throw new ValidationException("Unknown commitment ID: C" + commitmentId);
        }
        // Snapshot guarantees unique IDs, so a successful deletion must remove exactly one series.
        assert remaining.size() == state.commitments().size() - 1 : "Deletion must remove exactly one commitment";
        Snapshot candidate = new Snapshot(state.tasks(), remaining, state.nextTaskId(), state.nextCommitmentId());
        LOGGER.fine(() -> "Created deletion candidate for commitment C" + commitmentId);
        return candidate;
    }

    /**
     * Returns an immutable view ordered by weekday, start and numeric ID.
     * Preserves stored order and identifiers; empty snapshots produce an empty list.
     */
    public List<Commitment> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        return state.commitments().stream().sorted(Comparator.comparing(Commitment::day)
                .thenComparing(Commitment::start).thenComparingInt(Commitment::id)).toList();
    }
}
