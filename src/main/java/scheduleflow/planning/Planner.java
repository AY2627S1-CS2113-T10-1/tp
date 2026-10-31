// @@author GabrielYuSP
package scheduleflow.planning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Generates a deterministic immutable plan from one snapshot and captured time.
 */
public interface Planner {
    /**
     * Allocates eligible whole slots, preserving source estimates and recording any remainder.
     */
    Plan generate(Snapshot state, LocalDateTime now);

    /**
     * Checks feasibility and exact work conservation against a plan's frozen source and time.
     * Accepts alternative feasible allocations without rerunning the greedy algorithm or recurrence.
     * Breaks need not have a stored policy: legacy callers may use a constructor policy fallback.
     *
     * @param plan frozen result to validate without mutation or I/O
     * @throws NullPointerException if plan is null
     * @throws ValidationException if allocation relationships are inconsistent
     */
    static void validate(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<Integer, Task> tasks = new HashMap<>();
        Map<Integer, Long> accounted = new HashMap<>();
        for (Task task : plan.source().tasks()) {
            tasks.put(task.id(), task);
        }
        var occupied = new ArrayList<ScheduleEntry>(plan.breaks());
        for (StudySession session : plan.sessions()) {
            Task task = requireTask(tasks, session.taskId());
            if (reason(task, plan) != UnallocatedReason.INSUFFICIENT_CAPACITY
                    || task.deadline().map(deadline -> session.end().isAfter(deadline)).orElse(false)
                    || task.occurrence().map(value -> session.start().isBefore(value.release())).orElse(false)) {
                throw new ValidationException("Session violates its task deadline or original release.");
            }
            accounted.merge(task.id(), (long) session.workMinutes(), Long::sum);
            occupied.add(new ScheduleEntry(session.start(), session.end(), SlotKind.TASK,
                    "T" + task.id(), task.name()));
        }
        occupied.sort(Comparator.comparing(ScheduleEntry::start));
        LocalDateTime previousEnd = null;
        for (ScheduleEntry interval : occupied) {
            validateInterval(plan, interval);
            if (previousEnd != null && interval.start().isBefore(previousEnd)) {
                throw new ValidationException("Task sessions and breaks must not overlap.");
            }
            previousEnd = interval.end();
        }
        Set<Integer> remainders = new HashSet<>();
        for (UnallocatedWork work : plan.unallocated()) {
            Task task = requireTask(tasks, work.taskId());
            if (!remainders.add(task.id()) || !work.taskName().equals(task.name())
                    || !work.deadline().equals(task.deadline()) || work.reason() != reason(task, plan)) {
                throw new ValidationException("Remainder must uniquely match its source task and reason.");
            }
            accounted.merge(task.id(), (long) work.minutes(), Long::sum);
        }
        for (Task task : tasks.values()) {
            if (accounted.getOrDefault(task.id(), 0L) != task.remainingMinutes()) {
                throw new ValidationException("Scheduled work plus remainder must equal source remaining work.");
            }
        }
    }

    /** Requires a known unfinished task for every allocation row. */
    private static Task requireTask(Map<Integer, Task> tasks, int id) {
        Task task = tasks.get(id);
        if (task == null || task.remainingMinutes() == 0) {
            throw new ValidationException("Allocation must reference an unfinished source task.");
        }
        return task;
    }

    /** Classifies deadlines using the captured generation time, including the horizon boundary. */
    private static UnallocatedReason reason(Task task, Plan plan) {
        if (task.deadline().isPresent()) {
            LocalDateTime deadline = task.deadline().orElseThrow();
            if (!deadline.isAfter(plan.generatedAt())) {
                return UnallocatedReason.DEADLINE_PASSED;
            }
            if (deadline.isAfter(plan.horizonEnd())) {
                return UnallocatedReason.OUTSIDE_HORIZON;
            }
        }
        return UnallocatedReason.INSUFFICIENT_CAPACITY;
    }

    /** Checks half-open task/break intervals against effective hours, cutoff and occurring commitments. */
    private static void validateInterval(Plan plan, ScheduleEntry interval) {
        var date = interval.start().toLocalDate();
        if (date.isBefore(plan.generatedAt().toLocalDate()) || !interval.end().isBefore(plan.horizonEnd())) {
            throw new ValidationException("Allocation lies outside the frozen horizon.");
        }
        var window = plan.source().studyHours().forDate(date);
        if (interval.start().isBefore(TimeRules.eligibleStart(date, plan.generatedAt(), window))
                || interval.end().isAfter(date.atTime(window.end()))) {
            throw new ValidationException("Allocation lies outside eligible study hours.");
        }
        for (var commitment : plan.source().commitments()) {
            LocalDateTime start = date.atTime(commitment.start());
            LocalDateTime end = date.atStartOfDay().plusMinutes(commitment.endMinuteOfDay());
            if (commitment.occursOn(date) && interval.start().isBefore(end) && start.isBefore(interval.end())) {
                throw new ValidationException("Allocation intersects an occurring commitment.");
            }
        }
    }
}
// @@author
