package scheduleflow.commitment;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;

/**
 * Creates weekly commitment candidates without saving or changing live state.
 */
public final class CommitmentService {
    /**
     * Records opt-in stages without names, schedules or exception payloads.
     * Candidate creation is not a claim that data has been saved or published.
     */
    private static final Logger LOGGER = Logger.getLogger(CommitmentService.class.getName());

    /**
     * Creates the CommitmentService dependencies without performing I/O.
     */
    public CommitmentService() {
    }

    //@@author Darren50
    /**
     * Creates a candidate after checking overlap and counter overflow.
     * Preserves the original snapshot so callers can save before publishing the change.
     *
     * @param state immutable source snapshot
     * @param name description normalized by the commitment constructor
     * @param day weekday on which the series repeats
     * @param start half-hour-aligned local start time
     * @param minutes positive multiple of 30 that ends no later than midnight
     * @return a new snapshot with the series appended and only its counter advanced
     * @throws NullPointerException if state is null
     * @throws ValidationException if the commitment is invalid, overlaps an existing series or IDs are exhausted
     */
    public Snapshot add(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment addition");
        try {
            return createAddition(state, name, day, start, minutes);
        } catch (ValidationException exception) {
            LOGGER.fine("Commitment addition rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Validates fields and overlap before constructing an unsaved addition candidate.
     * Keeps the public operation's rejection logging separate from candidate construction.
     */
    private Snapshot createAddition(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes) {
        if (state.nextCommitmentId() == Integer.MAX_VALUE) {
            throw new ValidationException("Commitment IDs are exhausted.");
        }
        Commitment added = new Commitment(state.nextCommitmentId(), name, day, start, minutes);
        LOGGER.fine("Commitment fields validated; checking weekly overlaps");
        rejectOverlaps(added, state.commitments());
        List<Commitment> commitments = new ArrayList<>(state.commitments());
        LOGGER.fine("Weekly overlaps checked; creating addition candidate");
        commitments.add(added);
        Snapshot candidate = new Snapshot(state.tasks(), commitments, state.nextTaskId(), state.nextCommitmentId() + 1,
                state.studyHours());
        assert candidate.commitments().getLast().equals(added)
                && candidate.commitments().subList(0, candidate.commitments().size() - 1).equals(state.commitments())
                : "Addition must append exactly one series and preserve existing records";
        assert candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId() + 1
                : "Addition must preserve tasks and advance only the commitment counter";
        LOGGER.fine("Commitment addition candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Reports every conflicting series in chronological order without changing stored order.
     * Retains the existing error format when only one series conflicts.
     *
     * @param added validated proposed series
     * @param existing stored series to check, including intervals outside study hours
     * @throws ValidationException if any stored series overlaps the proposed interval
     */
    private void rejectOverlaps(Commitment added, List<Commitment> existing) {
        List<Commitment> conflicts = existing.stream().filter(added::overlaps)
                .sorted(Comparator.comparing(Commitment::start).thenComparingInt(Commitment::id)).toList();
        if (conflicts.isEmpty()) {
            return;
        }
        String details = conflicts.stream()
                .map(commitment -> commitment.displayId() + ": " + commitment.name())
                .collect(Collectors.joining("; "));
        throw new ValidationException("Commitment overlaps " + details);
    }

    /**
     * Replaces selected fields of a weekly series while retaining its ID and stored position.
     * Null fields mean omitted values; an explicitly blank name remains invalid.
     * Even an identical valid edit produces a candidate for the normal save/invalidation transaction.
     *
     * @param state immutable source snapshot
     * @param commitmentId identifier of the series to edit
     * @param name replacement name, or null to preserve it
     * @param day replacement weekday, or null to preserve it
     * @param start replacement start, or null to preserve it
     * @param minutes replacement duration, or null to preserve it
     * @return validated candidate preserving tasks, other series and both counters
     * @throws NullPointerException if state is null
     * @throws ValidationException if no fields are supplied, the ID is unknown or the result is invalid
     */
    public Snapshot edit(Snapshot state, int commitmentId, String name, DayOfWeek day,
            LocalTime start, Integer minutes) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment edit");
        try {
            if (commitmentId <= 0) {
                throw new ValidationException("Commitment ID must be positive.");
            }
            if (name == null && day == null && start == null && minutes == null) {
                throw new ValidationException("Provide at least one commitment field to edit.");
            }
            return createEdit(state, commitmentId, name, day, start, minutes);
        } catch (ValidationException exception) {
            LOGGER.fine("Commitment edit rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Resolves omitted fields and validates the complete replacement before creating a candidate.
     * Excludes only the target ID from overlap checks, including when the time stays unchanged.
     */
    private Snapshot createEdit(Snapshot state, int id, String name, DayOfWeek day,
            LocalTime start, Integer minutes) {
        Commitment original = state.commitments().stream().filter(commitment -> commitment.id() == id)
                .findFirst().orElseThrow(() -> new ValidationException("Unknown commitment ID: C" + id));
        Commitment edited = new Commitment(id, name == null ? original.name() : name,
                day == null ? original.day() : day, start == null ? original.start() : start,
                minutes == null ? original.durationMinutes() : minutes);
        LOGGER.fine("Replacement fields validated; checking other weekly series");
        rejectOverlaps(edited, state.commitments().stream().filter(commitment -> commitment.id() != id).toList());
        List<Commitment> replacements = new ArrayList<>(state.commitments());
        replacements.set(replacements.indexOf(original), edited);
        Snapshot candidate = new Snapshot(state.tasks(), replacements, state.nextTaskId(), state.nextCommitmentId(),
                state.studyHours());
        assert candidate.commitments().size() == state.commitments().size()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                && candidate.nextTaskId() == state.nextTaskId() && candidate.tasks().equals(state.tasks())
                : "Edit must preserve record counts, tasks and both counters";
        LOGGER.fine("Commitment edit candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Removes a known weekly series from a candidate without reusing its ID.
     * Leaves source records and both counters unchanged until the caller publishes the candidate.
     *
     * @param state immutable source snapshot
     * @param commitmentId positive numeric suffix of the series identifier
     * @return a new snapshot without that series, retaining both counters
     * @throws NullPointerException if state is null
     * @throws ValidationException if the ID is nonpositive or does not identify a stored commitment
     */
    public Snapshot delete(Snapshot state, int commitmentId) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment deletion");
        if (commitmentId <= 0) {
            LOGGER.fine("Commitment deletion rejected; nonpositive ID");
            throw new ValidationException("Commitment ID must be positive.");
        }
        List<Commitment> remaining = state.commitments().stream()
                .filter(commitment -> commitment.id() != commitmentId).toList();
        if (remaining.size() == state.commitments().size()) {
            LOGGER.fine("Commitment deletion rejected; unknown ID");
            throw new ValidationException("Unknown commitment ID: C" + commitmentId);
        }
        // Snapshot guarantees unique IDs, so a successful deletion must remove exactly one series.
        assert remaining.size() == state.commitments().size() - 1 : "Deletion must remove exactly one commitment";
        LOGGER.fine("Commitment ID resolved; creating deletion candidate");
        Snapshot candidate = new Snapshot(state.tasks(), remaining, state.nextTaskId(), state.nextCommitmentId(),
                state.studyHours());
        assert candidate.commitments().stream().noneMatch(commitment -> commitment.id() == commitmentId)
                : "Deletion must remove the requested series";
        assert candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                : "Deletion must preserve tasks and both counters";
        LOGGER.fine("Commitment deletion candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Returns an immutable view ordered by weekday, start and numeric ID.
     * Preserves stored order and identifiers; empty snapshots produce an empty list.
     *
     * @param state immutable source snapshot
     * @return all stored series in weekly chronological order
     * @throws NullPointerException if state is null
     */
    public List<Commitment> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment listing; sorting by weekday, start and ID");
        List<Commitment> commitments = state.commitments().stream().sorted(Comparator.comparing(Commitment::day)
                .thenComparing(Commitment::start).thenComparingInt(Commitment::id)).toList();
        assert commitments.size() == state.commitments().size() : "Listing must retain every stored series";
        LOGGER.fine("Commitment listing completed; source snapshot unchanged");
        return commitments;
    }
    //@@author
}
