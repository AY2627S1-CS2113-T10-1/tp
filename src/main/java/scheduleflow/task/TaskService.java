package scheduleflow.task;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.TimeRules;

/**
 * Creates task mutation candidates without saving or changing live state.
 */
public final class TaskService {
    /**
     * Creates the TaskService dependencies without performing I/O.
     */
    public TaskService() {
    }

    /**
     * Creates a candidate with the next ID after validating the deadline against now.
     */
    public Snapshot add(Snapshot state, String name, LocalDateTime deadline, int minutes,
            LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        if (state.nextTaskId() == Integer.MAX_VALUE) {
            throw new ValidationException("No more task IDs are available.");
        }
        Task task = new Task(state.nextTaskId(), name, deadline, minutes);
        if (!deadline.isAfter(now)) {
            throw new ValidationException("Deadline must be in the future.");
        }
        if (deadline.isAfter(TimeRules.horizonEnd(now))) {
            throw new ValidationException("Deadline must be within the rolling one-year horizon.");
        }
        List<Task> tasks = new ArrayList<>(state.tasks());
        tasks.add(task);
        return new Snapshot(tasks, state.commitments(), state.nextTaskId() + 1, state.nextCommitmentId());
    }

    /**
     * Removes a known task from a candidate while preserving both counters.
     */
    public Snapshot delete(Snapshot state, int taskId) {
        Objects.requireNonNull(state, "state");
        List<Task> tasks = state.tasks().stream().filter(task -> task.id() != taskId).toList();
        if (tasks.size() == state.tasks().size()) {
            throw new ValidationException("Unknown task ID: T" + taskId);
        }
        return new Snapshot(tasks, state.commitments(), state.nextTaskId(), state.nextCommitmentId());
    }

    /**
     * Returns an immutable view in ascending numeric task ID order.
     */
    public List<Task> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        return state.tasks().stream().sorted(Comparator.comparingInt(Task::id)).toList();
    }
}
