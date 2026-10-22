package scheduleflow.task;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import scheduleflow.common.ValidationException;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.TimeRules;

// @@author nickpatt
/** Creates atomic weekly recurrence candidates without clocks, storage or live-state mutation. */
public final class RecurringTaskService {
    /**
     * Adds a future weekly template and immediately materializes all templates through the rolling horizon.
     *
     * @param state immutable source, unchanged on success or failure
     * @param name description normalized by the template constructor
     * @param firstDeadline future minute-precision deadline, with a release within years 0001-9999
     * @param minutes positive exact work for each occurrence
     * @param until optional inclusive last due date
     * @param priority default priority for generated occurrences
     * @param now captured local command time in years 0001-9999
     * @return unsaved candidate containing the template, occurrences and updated counters/cursors
     * @throws ValidationException if fields/time are invalid or either ID sequence cannot fit the whole addition
     * @throws NullPointerException if state, now, until or priority is null
     */
    public Snapshot add(Snapshot state, String name, LocalDateTime firstDeadline, int minutes,
            Optional<LocalDate> until, TaskPriority priority, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        if (state.nextRecurringTaskId() == Integer.MAX_VALUE) {
            throw new ValidationException("No more recurring task IDs are available.");
        }
        RecurringTask template = new RecurringTask(state.nextRecurringTaskId(), name, firstDeadline, until,
                minutes, priority, Optional.empty());
        if (!firstDeadline.isAfter(now)) {
            throw new ValidationException("First deadline must be in the future.");
        }
        List<RecurringTask> templates = new ArrayList<>(state.recurringTasks());
        templates.add(template);
        return materialize(new Snapshot(state.tasks(), state.commitments(), state.nextTaskId(),
                state.nextCommitmentId(), state.studyHours(), templates, state.nextRecurringTaskId() + 1), now);
    }

    /**
     * Processes deadlines at or before the inclusive horizon, skipping ungenerated deadlines at or before now.
     * Arithmetic jumps over past weeks; only future horizon occurrences are enumerated.
     * Returns the identical snapshot on a no-op. Exhaustion rejects the whole candidate before allocating IDs.
     *
     * @param state immutable source containing persisted weekly cursors
     * @param now captured local command time in years 0001-9999
     * @return unsaved candidate, or the identical source if no occurrence or cursor changes
     * @throws ValidationException if time is outside the supported years or the complete batch cannot fit
     * @throws NullPointerException if state or now is null
     */
    public Snapshot materialize(Snapshot state, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        if (now.getYear() < 1 || now.getYear() > 9999) {
            throw new ValidationException("Command time must be in years 0001 to 9999.");
        }
        LocalDateTime lastSupported = LocalDate.of(9999, 12, 31).atTime(23, 59);
        LocalDateTime horizon = TimeRules.horizonEnd(now);
        if (horizon.isAfter(lastSupported)) {
            horizon = lastSupported;
        }
        List<RecurringTask> templates = new ArrayList<>();
        List<TaskOccurrence> pending = new ArrayList<>();
        Map<Integer, RecurringTask> byId = new HashMap<>();
        for (RecurringTask template : state.recurringTasks()) {
            LocalDateTime end = horizon;
            if (template.until().isPresent() && template.until().get().atTime(23, 59).isBefore(end)) {
                end = template.until().get().atTime(23, 59);
            }
            long previous = template.generatedThrough()
                    .map(cursor -> ChronoUnit.WEEKS.between(template.firstDeadline(), cursor)).orElse(-1L);
            long last = end.isBefore(template.firstDeadline()) ? -1
                    : ChronoUnit.WEEKS.between(template.firstDeadline(), end);
            long skipped = now.isBefore(template.firstDeadline()) ? -1
                    : ChronoUnit.WEEKS.between(template.firstDeadline(), now);
            for (long index = Math.max(previous, skipped) + 1; index <= last; index++) {
                pending.add(new TaskOccurrence(template.id(), template.firstDeadline().plusWeeks(index)));
            }
            RecurringTask updated = template;
            if (last > previous) {
                updated = new RecurringTask(template.id(), template.name(), template.firstDeadline(), template.until(),
                        template.minutes(), template.priority(), Optional.of(template.firstDeadline().plusWeeks(last)));
            }
            templates.add(updated);
            byId.put(updated.id(), updated);
        }
        if (pending.size() > (long) Integer.MAX_VALUE - state.nextTaskId()) {
            throw new ValidationException("No more task IDs are available for all recurring occurrences.");
        }
        if (templates.equals(state.recurringTasks()) && pending.isEmpty()) {
            return state;
        }
        pending.sort(Comparator.comparing(TaskOccurrence::originalDeadline)
                .thenComparingInt(TaskOccurrence::templateId));
        List<Task> tasks = new ArrayList<>(state.tasks());
        int nextId = state.nextTaskId();
        for (TaskOccurrence occurrence : pending) {
            RecurringTask template = byId.get(occurrence.templateId());
            tasks.add(new Task(nextId++, template.name(), Optional.of(occurrence.originalDeadline()),
                    template.minutes(), template.priority(), Optional.of(occurrence)));
        }
        return new Snapshot(tasks, state.commitments(), nextId, state.nextCommitmentId(), state.studyHours(),
                templates, state.nextRecurringTaskId());
    }

    /**
     * Removes only a template; existing tasks, provenance and all counters survive.
     * @return unsaved candidate without the identified template
     * @throws ValidationException if templateId is unknown
     * @throws NullPointerException if state is null
     */
    public Snapshot delete(Snapshot state, int templateId) {
        Objects.requireNonNull(state, "state");
        List<RecurringTask> templates = state.recurringTasks().stream()
                .filter(template -> template.id() != templateId).toList();
        if (templates.size() == state.recurringTasks().size()) {
            throw new ValidationException("Unknown recurring task ID: R" + templateId);
        }
        return new Snapshot(state.tasks(), state.commitments(), state.nextTaskId(), state.nextCommitmentId(),
                state.studyHours(), templates, state.nextRecurringTaskId());
    }

    /**
     * Returns an immutable numeric-R-ID view without changing stored order.
     * @throws NullPointerException if state is null
     */
    public List<RecurringTask> list(Snapshot state) {
        return Objects.requireNonNull(state, "state").recurringTasks().stream()
                .sorted(Comparator.comparingInt(RecurringTask::id)).toList();
    }
}
// @@author
