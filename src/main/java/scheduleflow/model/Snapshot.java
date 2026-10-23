package scheduleflow.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import scheduleflow.common.ValidationException;

/**
 * Holds immutable persisted records and separate, never-reused identity counters.
 * Preserves input order; ordered views belong to the services that need them.
 *
 * @param tasks retained active and completed tasks, including valid overdue records
 * @param commitments nonoverlapping recurring weekly commitments
 * @param nextTaskId positive counter strictly greater than every stored task ID
 * @param nextCommitmentId positive counter strictly greater than every stored commitment ID
 * @param studyHours recurring weekday windows and calendar-date overrides
 * @param recurringTasks weekly templates with persisted processing cursors
 * @param nextRecurringTaskId counter above all live and retained provenance template IDs
 */
public record Snapshot(List<Task> tasks, List<Commitment> commitments, int nextTaskId, int nextCommitmentId,
        StudyHours studyHours, List<RecurringTask> recurringTasks, int nextRecurringTaskId) {
    // @@author nickpatt
    /** Retains the study-hours constructor with empty recurrence and its initial counter. */
    public Snapshot(List<Task> tasks, List<Commitment> commitments, int nextTaskId, int nextCommitmentId,
            StudyHours studyHours) {
        this(tasks, commitments, nextTaskId, nextCommitmentId, studyHours, List.of(), 1);
    }
    // @@author
    /** Retains the original constructor for callers using default study hours. */
    public Snapshot(List<Task> tasks, List<Commitment> commitments, int nextTaskId, int nextCommitmentId) {
        this(tasks, commitments, nextTaskId, nextCommitmentId, new StudyHours(Map.of()));
    }

    /**
     * Copies records and validates IDs, counters and weekly overlaps without consulting a clock.
     * Performs no I/O and does not reset counters when either list becomes empty.
     *
     * @throws ValidationException if lists or elements are null, IDs or counters are invalid,
     *     weekly commitments overlap, or recurring templates/provenance are inconsistent
     */
    public Snapshot {
        Objects.requireNonNull(studyHours, "studyHours");
        if (tasks == null || commitments == null || recurringTasks == null || tasks.stream().anyMatch(Objects::isNull)
                || commitments.stream().anyMatch(Objects::isNull)
                || recurringTasks.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Snapshot lists and their elements must not be null.");
        }
        tasks = List.copyOf(tasks);
        commitments = List.copyOf(commitments);
        validateTaskIds(tasks, nextTaskId);
        validateCommitmentIds(commitments, nextCommitmentId);
        validateOverlaps(commitments);
        recurringTasks = List.copyOf(recurringTasks);
        validateRecurrence(tasks, recurringTasks, nextRecurringTaskId);
    }

    /** Returns a candidate changing only study hours. */
    public Snapshot withStudyHours(StudyHours hours) {
        return new Snapshot(tasks, commitments, nextTaskId, nextCommitmentId, hours,
                recurringTasks, nextRecurringTaskId);
    }

    // @@author nickpatt
    /** Returns a task candidate while preserving other owners' data and recurrence history. */
    public Snapshot withTasks(List<Task> replacements, int nextId) {
        return new Snapshot(replacements, commitments, nextId, nextCommitmentId, studyHours,
                recurringTasks, nextRecurringTaskId);
    }

    /** Returns a commitment candidate while preserving recurrence history and settings. */
    public Snapshot withCommitments(List<Commitment> replacements, int nextId) {
        return new Snapshot(tasks, replacements, nextTaskId, nextId, studyHours, recurringTasks, nextRecurringTaskId);
    }

    /** Validates logical uniqueness and cursor coverage; deleted templates need not remain live. */
    private static void validateRecurrence(List<Task> tasks, List<RecurringTask> templates, int nextId) {
        if (nextId <= 0) {
            throw new ValidationException("Next recurring task ID must be positive.");
        }
        Map<Integer, RecurringTask> byId = new HashMap<>();
        for (RecurringTask template : templates) {
            if (template.id() >= nextId || byId.putIfAbsent(template.id(), template) != null) {
                throw new ValidationException("Duplicate recurring task ID or invalid next recurring task ID.");
            }
        }
        Set<TaskOccurrence> occurrences = new HashSet<>();
        for (Task task : tasks) {
            if (task.occurrence().isEmpty()) {
                continue;
            }
            TaskOccurrence occurrence = task.occurrence().get();
            if (occurrence.templateId() >= nextId || !occurrences.add(occurrence)) {
                throw new ValidationException("Duplicate occurrence or invalid retained template ID counter.");
            }
            RecurringTask template = byId.get(occurrence.templateId());
            if (template != null && (!template.includes(occurrence.originalDeadline())
                    || template.generatedThrough().isEmpty()
                    || occurrence.originalDeadline().isAfter(template.generatedThrough().get()))) {
                throw new ValidationException("Occurrence must belong to its template and be covered by its cursor.");
            }
        }
    }
    // @@author

    /**
     * Returns a new store with empty immutable lists and all three next IDs set to one.
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
