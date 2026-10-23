package scheduleflow.task;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.TimeRules;

/**
 * Creates task mutation candidates without saving or changing live state.
 * The caller must save each candidate before publishing it and invalidating a plan.
 * Uses the supplied command time rather than reading a clock.
 */
public final class TaskService {
    // @@author nickpatt
    /**
     * Records opt-in processing stages without task names, deadlines or exception payloads.
     */
    private static final Logger LOGGER = Logger.getLogger(TaskService.class.getName());
    // @@author

    /**
     * Creates a stateless task service without performing I/O or logging.
     */
    public TaskService() {
    }

    // @@author nickpatt
    /**
     * Creates a candidate with the next ID after validating the deadline against now.
     * Accepts deadlines strictly after now and at or before {@link TimeRules#horizonEnd(LocalDateTime)}.
     * Preserves existing list order, commitments and the commitment counter; duplicate names are allowed.
     * A rejected addition leaves the supplied snapshot and its counters unchanged.
     *
     * @param state immutable source snapshot
     * @param name task description, normalized by the task constructor
     * @param deadline local deadline with minute precision and a year from 0001 to 9999
     * @param minutes whole remaining-work estimate, a positive multiple of 30
     * @param now local command time captured by the caller, including seconds and nanoseconds
     * @return a new snapshot with the task appended and the task counter incremented once
     * @throws NullPointerException if state or now is null
     * @throws ValidationException if task fields or the deadline range are invalid, or IDs are exhausted
     */
    public Snapshot add(Snapshot state, String name, LocalDateTime deadline, int minutes,
            LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        LOGGER.log(Level.FINE, "Starting task addition");
        try {
            if (state.nextTaskId() == Integer.MAX_VALUE) {
                throw new ValidationException("No more task IDs are available.");
            }
            Task task = new Task(state.nextTaskId(), name, deadline, minutes);
            LOGGER.log(Level.FINE, "Task fields validated; checking deadline range");
            if (!deadline.isAfter(now)) {
                throw new ValidationException("Deadline must be in the future.");
            }
            if (deadline.isAfter(TimeRules.horizonEnd(now))) {
                throw new ValidationException("Deadline must be within the rolling one-year horizon.");
            }
            LOGGER.log(Level.FINE, "Task deadline validated; creating addition candidate");
            List<Task> tasks = new ArrayList<>(state.tasks());
            tasks.add(task);
            Snapshot candidate = new Snapshot(tasks, state.commitments(), state.nextTaskId() + 1,
                    state.nextCommitmentId(), state.studyHours());
            assert candidate.tasks().getLast().equals(task)
                    && candidate.tasks().subList(0, candidate.tasks().size() - 1).equals(state.tasks())
                    : "Addition must append exactly one task without changing existing records";
            assert candidate.nextTaskId() == state.nextTaskId() + 1
                    && candidate.nextCommitmentId() == state.nextCommitmentId()
                    && candidate.commitments().equals(state.commitments())
                    : "Addition must advance only the task counter and preserve commitments";
            LOGGER.log(Level.FINE, "Task addition candidate created; not yet saved or published");
            return candidate;
        } catch (ValidationException exception) {
            LOGGER.log(Level.FINE, "Task addition rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Replaces supplied details of a known task without saving or changing live state.
     * Validates the complete resulting task; only an actually changed deadline must be future and
     * within the rolling horizon. Renaming overdue or out-of-horizon work is allowed.
     * Preserves stored position, IDs, estimates, other records, counters and study hours.
     *
     * @param state immutable source snapshot
     * @param taskId numeric suffix of an existing task identifier
     * @param edit partial replacement details; at least one field must be supplied
     * @param now captured local command time, including seconds and nanoseconds
     * @return a validated candidate, or the original snapshot instance if no value effectively changes
     * @throws NullPointerException if state, edit or now is null
     * @throws ValidationException if the edit is empty, clears a deadline, has invalid fields or an unknown ID
     */
    public Snapshot edit(Snapshot state, int taskId, TaskEdit edit, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(edit, "edit");
        Objects.requireNonNull(now, "now");
        LOGGER.log(Level.FINE, "Starting task edit");
        try {
            if (edit.clearDeadline()) {
                throw new ValidationException("Clearing a task deadline is not supported yet.");
            }
            if (edit.name().isEmpty() && edit.deadline().isEmpty()) {
                throw new ValidationException("Supply at least one task field to edit.");
            }
            Task original = state.tasks().stream().filter(task -> task.id() == taskId).findFirst()
                    .orElseThrow(() -> new ValidationException("Unknown task ID: T" + taskId));
            Task replacement = new Task(original.id(), edit.name().orElse(original.name()),
                    edit.deadline().orElse(original.deadline()), original.remainingMinutes());
            if (!replacement.deadline().equals(original.deadline())) {
                if (!replacement.deadline().isAfter(now)) {
                    throw new ValidationException("Deadline must be in the future.");
                }
                if (replacement.deadline().isAfter(TimeRules.horizonEnd(now))) {
                    throw new ValidationException("Deadline must be within the rolling one-year horizon.");
                }
            }
            if (replacement.equals(original)) {
                LOGGER.log(Level.FINE, "Task edit has no effective change; source snapshot returned");
                return state;
            }
            List<Task> tasks = new ArrayList<>(state.tasks());
            int position = tasks.indexOf(original);
            tasks.set(position, replacement);
            Snapshot candidate = new Snapshot(tasks, state.commitments(), state.nextTaskId(),
                    state.nextCommitmentId(), state.studyHours());
            assert candidate.tasks().size() == state.tasks().size()
                    && candidate.tasks().get(position).equals(replacement)
                    && candidate.tasks().subList(0, position).equals(state.tasks().subList(0, position))
                    && candidate.tasks().subList(position + 1, tasks.size())
                            .equals(state.tasks().subList(position + 1, tasks.size()))
                    : "Editing must replace only the target at its original position";
            assert candidate.nextTaskId() == state.nextTaskId()
                    && candidate.nextCommitmentId() == state.nextCommitmentId()
                    && candidate.commitments().equals(state.commitments())
                    && candidate.studyHours().equals(state.studyHours())
                    : "Editing must preserve counters, commitments and study hours";
            LOGGER.log(Level.FINE, "Task edit candidate created; not yet saved or published");
            return candidate;
        } catch (ValidationException exception) {
            LOGGER.log(Level.FINE, "Task edit rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Removes a known task from a candidate while preserving both counters.
     * Preserves every other record and its stored order; deleted IDs are never reused.
     *
     * @param state immutable source snapshot
     * @param taskId numeric suffix of an existing task identifier
     * @return a new snapshot without the matching task
     * @throws NullPointerException if state is null
     * @throws ValidationException if taskId does not identify a stored task
     */
    public Snapshot delete(Snapshot state, int taskId) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting task deletion");
        List<Task> tasks = state.tasks().stream().filter(task -> task.id() != taskId).toList();
        if (tasks.size() == state.tasks().size()) {
            LOGGER.log(Level.FINE, "Task deletion rejected; unknown ID and source snapshot unchanged");
            throw new ValidationException("Unknown task ID: T" + taskId);
        }
        LOGGER.log(Level.FINE, "Task ID resolved; creating deletion candidate");
        Snapshot candidate = new Snapshot(tasks, state.commitments(), state.nextTaskId(), state.nextCommitmentId(),
                state.studyHours());
        assert candidate.tasks().size() == state.tasks().size() - 1
                && candidate.tasks().stream().noneMatch(task -> task.id() == taskId)
                : "Deletion must remove exactly one matching task";
        assert candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                && candidate.commitments().equals(state.commitments())
                : "Deletion must preserve both counters and all commitments";
        LOGGER.log(Level.FINE, "Task deletion candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Returns an immutable copy in ascending numeric task ID order, including overdue tasks.
     * Neither filters by time nor changes stored estimates or snapshot order.
     *
     * @param state immutable source snapshot
     * @return all stored tasks ordered by their numeric ID, or an empty immutable list
     * @throws NullPointerException if state is null
     */
    public List<Task> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting task listing; sorting by numeric ID");
        List<Task> tasks = state.tasks().stream().sorted(Comparator.comparingInt(Task::id)).toList();
        assert tasks.size() == state.tasks().size() : "Listing must include every stored task";
        LOGGER.log(Level.FINE, "Task listing completed; source snapshot unchanged");
        return tasks;
    }
    // @@author
}
