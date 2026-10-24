package scheduleflow.planning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Allocates the earliest free slots in deadline and numeric task ID order.
 */
public final class EarliestDeadlinePlanner implements Planner {
    /** Holds an opt-in policy until Printing and Save integrate user-configurable settings. */
    private final Optional<StudyPolicy> policy;

    /**
     * Creates the EarliestDeadlinePlanner dependencies without performing I/O.
     */
    public EarliestDeadlinePlanner() {
        policy = Optional.empty();
    }

    /**
     * Creates a planner enforcing the supplied work limit without reading or saving settings.
     *
     * @param policy fixed continuous-work and break durations for each generated plan
     * @throws NullPointerException if policy is null
     */
    public EarliestDeadlinePlanner(StudyPolicy policy) {
        this.policy = Optional.of(Objects.requireNonNull(policy, "policy"));
    }

    @Override
    public Plan generate(Snapshot state, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");

        LocalDateTime horizonEnd = TimeRules.horizonEnd(now);
        List<Task> tasks = new ArrayList<>(state.tasks());
        tasks.sort(Comparator.comparing(Task::deadline).thenComparingInt(Task::id));
        List<PendingTask> pending = tasks.stream().map(task -> new PendingTask(task, now, horizonEnd)).toList();
        List<Slot> slots = createSlots(state, now, horizonEnd, pending);
        List<UnallocatedWork> unallocated = new ArrayList<>();
        for (PendingTask work : pending) {
            if (work.remainingMinutes > 0) {
                addUnallocated(work.task, work.remainingMinutes, work.reason, unallocated);
            }
        }

        List<StudySession> sessions = mergeSessions(slots);
        return new Plan(now, horizonEnd, state, sessions, unallocated, collectBreaks(slots));
    }

    /**
     * Allocates chronologically so all tasks and commitments share one continuous-work counter.
     * Counts commitments from midnight on the generation date, including before hours and cutoff.
     * Earlier task activity is unknown; only generated sessions contribute task work.
     */
    private List<Slot> createSlots(Snapshot state, LocalDateTime now,
            LocalDateTime horizonEnd, List<PendingTask> pending) {
        List<Slot> slots = new ArrayList<>();
        WorkClock workClock = new WorkClock(policy);
        for (var date = now.toLocalDate(); date.isBefore(horizonEnd.toLocalDate()); date = date.plusDays(1)) {
            var window = state.studyHours().forDate(date);
            LocalDateTime cutoff = TimeRules.eligibleStart(date, now, window);
            LocalDateTime windowEnd = date.atTime(window.end());
            for (LocalDateTime start = date.atStartOfDay(); start.isBefore(date.plusDays(1).atStartOfDay());
                    start = start.plusMinutes(TimeRules.SLOT_MINUTES)) {
                boolean eligible = !start.isBefore(cutoff) && start.isBefore(windowEnd);
                Slot slot = allocateSlot(start, eligible, state.commitments(), pending, workClock);
                if (eligible) {
                    slots.add(slot);
                }
            }
        }
        return slots;
    }

    /** Processes one fixed commitment, allocated task, reserved break or naturally idle slot. */
    private static Slot allocateSlot(LocalDateTime start, boolean eligible, List<Commitment> commitments,
            List<PendingTask> pending, WorkClock workClock) {
        Slot slot = new Slot(start);
        if (isBlocked(start, commitments)) {
            workClock.work();
            return slot;
        }
        PendingTask next = eligible ? nextTask(pending, start) : null;
        if (next != null && !workClock.needsBreak()) {
            slot.taskId = next.task.id();
            next.remainingMinutes -= TimeRules.SLOT_MINUTES;
            workClock.work();
        } else {
            slot.reservedBreak = next != null && workClock.needsBreak();
            workClock.rest();
        }
        return slot;
    }

    /** Selects unfinished work released by the slot start whose whole slot fits its deadline. */
    private static PendingTask nextTask(List<PendingTask> pending, LocalDateTime slotStart) {
        LocalDateTime slotEnd = slotStart.plusMinutes(TimeRules.SLOT_MINUTES);
        for (PendingTask work : pending) {
            if (work.reason == UnallocatedReason.INSUFFICIENT_CAPACITY && work.remainingMinutes > 0
                    && work.task.occurrence().map(occurrence -> !slotStart.isBefore(occurrence.release())).orElse(true)
                    && !slotEnd.isAfter(work.task.deadline())) {
                return work;
            }
        }
        return null;
    }

    /**
     * Returns whether a slot intersects a recurring commitment on its weekday.
     */
    private static boolean isBlocked(LocalDateTime slotStart, List<Commitment> commitments) {
        int slotStartMinute = slotStart.getHour() * 60 + slotStart.getMinute();
        int slotEndMinute = slotStartMinute + TimeRules.SLOT_MINUTES;
        for (Commitment commitment : commitments) {
            if (commitment.occursOn(slotStart.toLocalDate())) {
                int commitmentStart = commitment.start().getHour() * 60 + commitment.start().getMinute();
                if (slotStartMinute < commitment.endMinuteOfDay() && commitmentStart < slotEndMinute) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds a task remainder with the task's source identity and deadline.
     */
    private static void addUnallocated(Task task, int minutes, UnallocatedReason reason,
            List<UnallocatedWork> unallocated) {
        unallocated.add(new UnallocatedWork(task.id(), task.name(), task.deadline(), minutes, reason));
    }

    /**
     * Combines adjacent slots assigned to one task on the same day into sessions.
     */
    private static List<StudySession> mergeSessions(List<Slot> slots) {
        List<StudySession> sessions = new ArrayList<>();
        int index = 0;
        while (index < slots.size()) {
            Slot first = slots.get(index);
            if (first.taskId == 0) {
                index++;
                continue;
            }

            LocalDateTime sessionStart = first.start;
            LocalDateTime sessionEnd = sessionStart.plusMinutes(TimeRules.SLOT_MINUTES);
            int taskId = first.taskId;
            index++;
            while (index < slots.size()) {
                Slot next = slots.get(index);
                if (next.taskId != taskId || !next.start.equals(sessionEnd)
                        || !next.start.toLocalDate().equals(sessionStart.toLocalDate())) {
                    break;
                }
                sessionEnd = sessionEnd.plusMinutes(TimeRules.SLOT_MINUTES);
                index++;
            }
            sessions.add(new StudySession(taskId, sessionStart, sessionEnd));
        }
        return sessions;
    }

    /** Collects reserved idle intervals without adding trailing breaks after the last eligible task. */
    private static List<ScheduleEntry> collectBreaks(List<Slot> slots) {
        List<ScheduleEntry> breaks = new ArrayList<>();
        for (Slot slot : slots) {
            if (!slot.reservedBreak) {
                continue;
            }
            LocalDateTime start = slot.start;
            if (!breaks.isEmpty() && breaks.getLast().end().equals(start)
                    && breaks.getLast().start().toLocalDate().equals(start.toLocalDate())) {
                start = breaks.removeLast().start();
            }
            breaks.add(new ScheduleEntry(start, slot.start.plusMinutes(TimeRules.SLOT_MINUTES),
                    SlotKind.BREAK, "", ""));
        }
        return breaks;
    }

    /** Holds one chronological half-hour slot and its allocation state. */
    private static final class Slot {
        private final LocalDateTime start;
        private int taskId;
        private boolean reservedBreak;

        /** Creates an unassigned slot at an aligned local time. */
        private Slot(LocalDateTime start) {
            this.start = start;
        }
    }

    /** Tracks a task's unscheduled minutes without changing its immutable source record. */
    private static final class PendingTask {
        private final Task task;
        private final UnallocatedReason reason;
        private int remainingMinutes;

        /** Classifies ineligible deadlines before chronological allocation begins. */
        private PendingTask(Task task, LocalDateTime now, LocalDateTime horizonEnd) {
            this.task = task;
            remainingMinutes = task.remainingMinutes();
            if (!task.deadline().isAfter(now)) {
                reason = UnallocatedReason.DEADLINE_PASSED;
            } else if (task.deadline().isAfter(horizonEnd)) {
                reason = UnallocatedReason.OUTSIDE_HORIZON;
            } else {
                reason = UnallocatedReason.INSUFFICIENT_CAPACITY;
            }
        }
    }

    /** Carries work and consecutive rest across task switches, commitments and calendar days. */
    private static final class WorkClock {
        private final Optional<StudyPolicy> policy;
        private long continuousMinutes;
        private long restMinutes;

        /** Creates an idle counter; an absent policy retains the existing unrestricted allocator. */
        private WorkClock(Optional<StudyPolicy> policy) {
            this.policy = policy;
        }

        /** Returns whether another task slot must wait for a complete break. */
        private boolean needsBreak() {
            return policy.isPresent() && continuousMinutes >= policy.orElseThrow().maxContinuousMinutes();
        }

        /** Counts fixed commitments even after the limit; extra work does not multiply required rest. */
        private void work() {
            continuousMinutes += TimeRules.SLOT_MINUTES;
            restMinutes = 0;
        }

        /** Resets work only after a consecutive unoccupied interval meets the configured duration. */
        private void rest() {
            restMinutes += TimeRules.SLOT_MINUTES;
            if (policy.isPresent() && restMinutes >= policy.orElseThrow().breakMinutes()) {
                continuousMinutes = 0;
            }
        }
    }
}
