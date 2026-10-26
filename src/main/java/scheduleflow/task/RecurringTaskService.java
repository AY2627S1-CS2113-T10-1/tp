// @@author GabrielYuSP
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
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.ValidationException;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.planning.TimeRules;

/** Creates atomic weekly recurrence candidates without clocks, storage or live-state mutation. */
public final class RecurringTaskService {
    /** Records optional recurrence stages without names, dates, estimates or exception payloads. */
    private static final Logger LOGGER = Logger.getLogger(RecurringTaskService.class.getName());

    /**
     * Adds a future weekly template and materializes all templates through the rolling horizon.
     * @param state immutable source snapshot
     * @param name description validated by the template constructor
     * @param firstDeadline first future deadline, with a supported one-week release
     * @param minutes positive multiple-of-30 estimate for each occurrence
     * @param until optional inclusive last due date
     * @param now exact local command time captured by the caller
     * @return an unsaved candidate containing the template and generated tasks
     * @throws ValidationException if fields/time are invalid or the entire batch cannot fit available IDs
     * @throws NullPointerException if state, now or until is null
     */
    public Snapshot add(Snapshot state, String name, LocalDateTime firstDeadline, int minutes,
            Optional<LocalDate> until, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        LOGGER.log(Level.FINE, "Starting recurring task addition");
        if (state.nextRecurringTaskId() == Integer.MAX_VALUE) {
            throw new ValidationException("No more recurring task IDs are available.");
        }
        RecurringTask template = new RecurringTask(state.nextRecurringTaskId(), name, firstDeadline, until,
                minutes, Optional.empty());
        if (!firstDeadline.isAfter(now)) {
            throw new ValidationException("First deadline must be in the future.");
        }
        List<RecurringTask> templates = new ArrayList<>(state.recurringTasks());
        templates.add(template);
        Snapshot candidate = materialize(new Snapshot(state.tasks(), state.commitments(), state.nextTaskId(),
                state.nextCommitmentId(), state.studyHours(), templates, state.nextRecurringTaskId() + 1,
                state.studyPolicy()), now);
        assert candidate.nextRecurringTaskId() == state.nextRecurringTaskId() + 1
                : "Adding a template must advance its counter once";
        LOGGER.log(Level.FINE, "Recurring task addition candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Processes deadlines through the inclusive horizon, skipping ungenerated deadlines at or before now.
     * Jumps past missed weeks arithmetically and rejects an exhausted batch before allocating any IDs.
     * Returns the identical snapshot if neither occurrences nor cursors change.
     * @param state immutable source with persisted weekly processing cursors
     * @param now exact local command time captured by the caller
     * @return complete unsaved candidate, or the identical source for a no-op
     * @throws ValidationException if the command year is unsupported or all pending tasks cannot fit
     * @throws NullPointerException if state or now is null
     */
    public Snapshot materialize(Snapshot state, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");
        LOGGER.log(Level.FINE, "Starting recurring occurrence materialization");
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
                        template.minutes(), Optional.of(template.firstDeadline().plusWeeks(last)));
            }
            templates.add(updated);
            byId.put(updated.id(), updated);
        }
        if (pending.size() > (long) Integer.MAX_VALUE - state.nextTaskId()) {
            throw new ValidationException("No more task IDs are available for all recurring occurrences.");
        }
        if (templates.equals(state.recurringTasks()) && pending.isEmpty()) {
            LOGGER.log(Level.FINE, "Recurring occurrence materialization unchanged");
            return state;
        }
        pending.sort(Comparator.comparing(TaskOccurrence::originalDeadline)
                .thenComparingInt(TaskOccurrence::templateId));
        List<Task> tasks = new ArrayList<>(state.tasks());
        int nextId = state.nextTaskId();
        for (TaskOccurrence occurrence : pending) {
            RecurringTask template = byId.get(occurrence.templateId());
            tasks.add(new Task(nextId++, template.name(), occurrence.originalDeadline(),
                    template.minutes(), Optional.of(occurrence)));
        }
        Snapshot candidate = new Snapshot(tasks, state.commitments(), nextId, state.nextCommitmentId(),
                state.studyHours(), templates, state.nextRecurringTaskId(), state.studyPolicy());
        assert (long) nextId - state.nextTaskId() == pending.size()
                : "Materialization must allocate exactly one task ID per new occurrence";
        LOGGER.log(Level.FINE, "Recurring occurrence candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Removes only the identified template, retaining tasks, provenance and all counters.
     * @throws ValidationException if the template ID is unknown
     * @throws NullPointerException if state is null
     */
    public Snapshot delete(Snapshot state, int templateId) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting recurring task deletion");
        List<RecurringTask> templates = state.recurringTasks().stream()
                .filter(template -> template.id() != templateId).toList();
        if (templates.size() == state.recurringTasks().size()) {
            throw new ValidationException("Unknown recurring task ID: R" + templateId);
        }
        Snapshot candidate = new Snapshot(state.tasks(), state.commitments(), state.nextTaskId(),
                state.nextCommitmentId(), state.studyHours(), templates, state.nextRecurringTaskId(),
                state.studyPolicy());
        LOGGER.log(Level.FINE, "Recurring task deletion candidate created; not yet saved or published");
        return candidate;
    }

    /** Returns an immutable numeric-R-ID view without changing stored order. */
    public List<RecurringTask> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Listing recurring task templates by numeric ID");
        return state.recurringTasks().stream()
                .sorted(Comparator.comparingInt(RecurringTask::id)).toList();
    }
}
// @@author
