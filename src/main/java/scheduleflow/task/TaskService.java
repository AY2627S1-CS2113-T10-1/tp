package scheduleflow.task;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.TimeRules;

/**
 * Creates task mutation candidates and immutable queries without saving or changing live state.
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
     * @param minutes positive whole remaining-work minutes
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
            requirePositiveRemaining(minutes);
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
            Snapshot candidate = state.withTasks(tasks, state.nextTaskId() + 1);
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
     * @throws ValidationException if the edit is empty, clears and replaces a deadline, or has invalid fields/ID
     */
    public Snapshot edit(Snapshot state, int taskId, TaskEdit edit, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(edit, "edit");
        Objects.requireNonNull(now, "now");
        LOGGER.log(Level.FINE, "Starting task edit");
        try {
            if (edit.clearDeadline() && edit.deadline().isPresent()) {
                throw new ValidationException("Cannot clear and replace a deadline together.");
            }
            if (edit.name().isEmpty() && edit.deadline().isEmpty() && !edit.clearDeadline()) {
                throw new ValidationException("Supply at least one task field to edit.");
            }
            Task original = state.tasks().stream().filter(task -> task.id() == taskId).findFirst()
                    .orElseThrow(() -> new ValidationException("Unknown task ID: T" + taskId));
            Task replacement = new Task(original.id(), edit.name().orElse(original.name()),
                    edit.clearDeadline() ? Optional.empty() : edit.deadline().or(() -> original.deadline()),
                    original.remainingMinutes(), original.priority(), original.occurrence());
            if (!replacement.deadline().equals(original.deadline()) && replacement.deadline().isPresent()) {
                if (!replacement.deadline().get().isAfter(now)) {
                    throw new ValidationException("Deadline must be in the future.");
                }
                if (replacement.deadline().get().isAfter(TimeRules.horizonEnd(now))) {
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
            Snapshot candidate = state.withTasks(tasks, state.nextTaskId());
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
     * Adds positive whole-minute work without inventing a deadline or reading a clock.
     * @throws NullPointerException if state is null
     * @throws ValidationException if fields are invalid or the task counter is exhausted
     */
    public Snapshot addWithoutDeadline(Snapshot state, String name, int minutes) {
        Objects.requireNonNull(state, "state");
        requirePositiveRemaining(minutes);
        if (state.nextTaskId() == Integer.MAX_VALUE) {
            throw new ValidationException("No more task IDs are available.");
        }
        List<Task> tasks = new ArrayList<>(state.tasks());
        tasks.add(Task.withoutDeadline(state.nextTaskId(), name, minutes));
        return state.withTasks(tasks, state.nextTaskId() + 1);
    }

    /**
     * Subtracts reported work from an active task; zero credit returns the identical source snapshot.
     * @throws NullPointerException if state is null
     * @throws ValidationException if the task is unknown/completed or done is outside [0, remaining]
     */
    public Snapshot recordWork(Snapshot state, int taskId, int done) {
        Task task = findTask(state, taskId);
        if (task.remainingMinutes() == 0) {
            throw new ValidationException("Reopen the completed task before recording work.");
        }
        if (done < 0 || done > task.remainingMinutes()) {
            throw new ValidationException("Reported work must be between zero and the remaining estimate.");
        }
        return replaceRemaining(state, task, task.remainingMinutes() - done);
    }

    /**
     * Credits floor(sessionMinutes * percentage / 100) using overflow-safe long arithmetic.
     * Returns the identical source when rounding gives zero, but still rejects completed tasks.
     * @throws NullPointerException if state is null
     * @throws ValidationException if session/percentage, task status/ID or credited work is invalid
     */
    public Snapshot recordSessionProgress(Snapshot state, int taskId, int sessionMinutes, int percentage) {
        Objects.requireNonNull(state, "state");
        if (sessionMinutes <= 0 || percentage < 0 || percentage > 100) {
            throw new ValidationException("Session minutes must be positive and percentage must be from 0 to 100.");
        }
        int done = (int) ((long) sessionMinutes * percentage / 100);
        return recordWork(state, taskId, done);
    }

    /**
     * Replaces an active task's estimate with positive minutes, allowing increases without recording work.
     * Returns the identical source for an unchanged estimate.
     * @throws NullPointerException if state is null
     * @throws ValidationException if the task is unknown/completed or remaining is nonpositive
     */
    public Snapshot reviseRemaining(Snapshot state, int taskId, int remaining) {
        Task task = findTask(state, taskId);
        requirePositiveRemaining(remaining);
        if (task.remainingMinutes() == 0) {
            throw new ValidationException("Reopen the completed task before revising remaining work.");
        }
        return replaceRemaining(state, task, remaining);
    }

    /**
     * Retains a task with zero remaining work; completing an already-completed task is an identity no-op.
     * @throws NullPointerException if state is null
     * @throws ValidationException if the task is unknown
     */
    public Snapshot complete(Snapshot state, int taskId) {
        return replaceRemaining(state, findTask(state, taskId), 0);
    }

    /**
     * Reopens only a completed task with an explicit positive estimate, preserving even overdue deadlines.
     * @throws NullPointerException if state is null
     * @throws ValidationException if the task is unknown/active or positiveRemaining is nonpositive
     */
    public Snapshot reopen(Snapshot state, int taskId, int positiveRemaining) {
        Task task = findTask(state, taskId);
        requirePositiveRemaining(positiveRemaining);
        if (task.remainingMinutes() != 0) {
            throw new ValidationException("Only completed tasks can be reopened.");
        }
        return replaceRemaining(state, task, positiveRemaining);
    }

    /**
     * Changes only priority at the stored position, including for completed or overdue tasks.
     * Preserves IDs, other task data, unrelated records, counters and custom study hours.
     *
     * @param state immutable source snapshot
     * @param taskId numeric identifier of the task to update
     * @param priority replacement priority
     * @return unsaved candidate, or the identical source snapshot if priority is unchanged
     * @throws NullPointerException if state or priority is null
     * @throws ValidationException if the task ID is unknown
     */
    public Snapshot setPriority(Snapshot state, int taskId, TaskPriority priority) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(priority, "priority");
        LOGGER.log(Level.FINE, "Starting task priority update");
        try {
            Task original = findTask(state, taskId);
            if (original.priority() == priority) {
                LOGGER.log(Level.FINE, "Task priority unchanged; source snapshot returned");
                return state;
            }
            Task replacement = new Task(original.id(), original.name(), original.deadline(),
                    original.remainingMinutes(), priority, original.occurrence());
            List<Task> tasks = new ArrayList<>(state.tasks());
            int position = tasks.indexOf(original);
            tasks.set(position, replacement);
            Snapshot candidate = state.withTasks(tasks, state.nextTaskId());
            assert candidate.tasks().get(position).equals(replacement)
                    && candidate.tasks().subList(0, position).equals(state.tasks().subList(0, position))
                    && candidate.tasks().subList(position + 1, tasks.size())
                            .equals(state.tasks().subList(position + 1, tasks.size()))
                    : "Priority update must replace only the target at its stored position";
            assert candidate.nextTaskId() == state.nextTaskId()
                    && candidate.nextCommitmentId() == state.nextCommitmentId()
                    && candidate.commitments().equals(state.commitments())
                    && candidate.studyHours().equals(state.studyHours())
                    : "Priority update must preserve counters, commitments and study hours";
            LOGGER.log(Level.FINE, "Task priority candidate created; not yet saved or published");
            return candidate;
        } catch (ValidationException exception) {
            LOGGER.log(Level.FINE, "Task priority update rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /** Resolves a stored task without changing its source snapshot. */
    private Task findTask(Snapshot state, int taskId) {
        Objects.requireNonNull(state, "state");
        return state.tasks().stream().filter(task -> task.id() == taskId).findFirst()
                .orElseThrow(() -> new ValidationException("Unknown task ID: T" + taskId));
    }

    /** Requires a positive estimate for creation, revision or explicit reopening. */
    private void requirePositiveRemaining(int minutes) {
        if (minutes <= 0) {
            throw new ValidationException("Remaining minutes must be positive.");
        }
    }

    /** Replaces just the estimate at its stored position, preserving metadata, settings and counters. */
    private Snapshot replaceRemaining(Snapshot state, Task task, int remaining) {
        if (task.remainingMinutes() == remaining) {
            return state;
        }
        List<Task> tasks = new ArrayList<>(state.tasks());
        tasks.set(tasks.indexOf(task), new Task(task.id(), task.name(), task.deadline(), remaining, task.priority(),
                task.occurrence()));
        return state.withTasks(tasks, state.nextTaskId());
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
        Snapshot candidate = state.withTasks(tasks, state.nextTaskId());
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

    /**
     * Returns an immutable scoped view in numeric ID order without changing stored order or task data.
     * @throws NullPointerException if state or scope is null
     */
    public List<Task> list(Snapshot state, TaskScope scope) {
        return scopedTasks(state, scope).sorted(Comparator.comparingInt(Task::id)).toList();
    }

    /**
     * Returns an immutable scoped view by HIGH, NORMAL, LOW, then dated before undated,
     * ascending deadline and numeric ID. Does not reorder the source snapshot.
     * @throws NullPointerException if state or scope is null
     */
    public List<Task> listByPriority(Snapshot state, TaskScope scope) {
        return scopedTasks(state, scope).sorted(Comparator.comparing(Task::priority)
                .thenComparing(task -> task.deadline().orElse(null), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Task::id)).toList();
    }

    /**
     * Finds literal name substrings using stripped query text and {@link Locale#ROOT} lowercase mapping.
     * Preserves Unicode text without regex interpretation or accent/normalization folding.
     * Returns immutable matches in numeric ID order without mutation or saving.
     * @throws NullPointerException if state, query or scope is null
     * @throws ValidationException if the stripped query is blank
     */
    public List<Task> search(Snapshot state, String query, TaskScope scope) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        String normalizedQuery = query.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isBlank()) {
            throw new ValidationException("Search query must not be blank.");
        }
        return scopedTasks(state, scope)
                .filter(task -> task.name().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .sorted(Comparator.comparingInt(Task::id)).toList();
    }

    /**
     * Returns immutable active dated tasks due at or before the exact captured time, by deadline then numeric ID.
     * Excludes completed and undated tasks; reads no clock and leaves the source unchanged.
     * @throws NullPointerException if state or capturedNow is null
     */
    public List<Task> overdue(Snapshot state, LocalDateTime capturedNow) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(capturedNow, "capturedNow");
        return scopedTasks(state, TaskScope.ACTIVE)
                .filter(task -> task.deadline().isPresent() && !task.deadline().get().isAfter(capturedNow))
                .sorted(Comparator.comparing((Task task) -> task.deadline().orElseThrow())
                        .thenComparingInt(Task::id)).toList();
    }

    /** Selects retained task status without mutating or sorting the source collection. */
    private Stream<Task> scopedTasks(Snapshot state, TaskScope scope) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scope, "scope");
        return state.tasks().stream().filter(task -> switch (scope) {
            case ACTIVE -> task.remainingMinutes() > 0;
            case COMPLETED -> task.remainingMinutes() == 0;
            case ALL -> true;
        });
    }
    // @@author
}
