package scheduleflow.planning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Allocates the earliest free slots in deadline and numeric task ID order.
 */
public final class EarliestDeadlinePlanner implements Planner {
    /**
     * Creates the EarliestDeadlinePlanner dependencies without performing I/O.
     */
    public EarliestDeadlinePlanner() {
    }

    @Override
    public Plan generate(Snapshot state, LocalDateTime now) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(now, "now");

        LocalDateTime horizonEnd = TimeRules.horizonEnd(now);
        List<Slot> slots = createSlots(state, now, horizonEnd);
        // @@author nickpatt
        List<Task> tasks = new ArrayList<>(state.tasks().stream()
                .filter(task -> task.remainingMinutes() > 0).toList());
        tasks.sort(Comparator.comparing((Task task) -> task.deadline().isEmpty())
                .thenComparing((left, right) -> left.deadline().isPresent() && right.deadline().isPresent()
                        ? left.deadline().get().compareTo(right.deadline().get()) : 0)
                .thenComparing(Task::priority).thenComparingInt(Task::id));
        // @@author

        List<UnallocatedWork> unallocated = new ArrayList<>();
        for (Task task : tasks) {
            addUnallocatedOrAllocate(task, now, horizonEnd, slots, unallocated);
        }

        List<StudySession> sessions = mergeSessions(slots);
        return new Plan(now, horizonEnd, state, sessions, unallocated);
    }

    /**
     * Creates every eligible half-hour slot and marks slots intersecting commitments unavailable.
     */
    private static List<Slot> createSlots(Snapshot state, LocalDateTime now,
            LocalDateTime horizonEnd) {
        List<Slot> slots = new ArrayList<>();
        for (var date = now.toLocalDate(); date.isBefore(horizonEnd.toLocalDate()); date = date.plusDays(1)) {
            var window = state.studyHours().forDate(date);
            LocalDateTime slotStart = TimeRules.eligibleStart(date, now, window);
            LocalDateTime windowEnd = date.atTime(window.end());
            while (!slotStart.plusMinutes(TimeRules.SLOT_MINUTES).isAfter(windowEnd)) {
                Slot slot = new Slot(slotStart);
                if (isBlocked(slotStart, state.commitments())) {
                    slot.available = false;
                }
                slots.add(slot);
                slotStart = slotStart.plusMinutes(TimeRules.SLOT_MINUTES);
            }
        }
        return slots;
    }

    /**
     * Returns whether a slot intersects a recurring commitment on its weekday.
     */
    private static boolean isBlocked(LocalDateTime slotStart, List<Commitment> commitments) {
        int slotStartMinute = slotStart.getHour() * 60 + slotStart.getMinute();
        int slotEndMinute = slotStartMinute + TimeRules.SLOT_MINUTES;
        for (Commitment commitment : commitments) {
            if (commitment.day() == slotStart.getDayOfWeek()) {
                int commitmentStart = commitment.start().getHour() * 60 + commitment.start().getMinute();
                if (slotStartMinute < commitment.endMinuteOfDay() && commitmentStart < slotEndMinute) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Allocates an eligible task or records the reason its full duration remains unallocated.
     */
    private static void addUnallocatedOrAllocate(Task task, LocalDateTime now, LocalDateTime horizonEnd,
            List<Slot> slots, List<UnallocatedWork> unallocated) {
        // @@author nickpatt
        if (task.deadline().isPresent() && !task.deadline().get().isAfter(now)) {
            addUnallocated(task, task.remainingMinutes(), UnallocatedReason.DEADLINE_PASSED, unallocated);
            return;
        }
        if (task.deadline().isPresent() && task.deadline().get().isAfter(horizonEnd)) {
            addUnallocated(task, task.remainingMinutes(), UnallocatedReason.OUTSIDE_HORIZON, unallocated);
            return;
        }

        int remainingMinutes = task.remainingMinutes();
        for (Slot slot : slots) {
            if (remainingMinutes == 0) {
                break;
            }
            LocalDateTime slotEnd = slot.start.plusMinutes(TimeRules.SLOT_MINUTES);
            if (slot.available && (task.deadline().isEmpty() || !slotEnd.isAfter(task.deadline().get()))) {
                slot.available = false;
                slot.taskId = task.id();
                slot.workMinutes = Math.min(remainingMinutes, TimeRules.SLOT_MINUTES);
                remainingMinutes -= slot.workMinutes;
            }
        }
        if (remainingMinutes > 0) {
            addUnallocated(task, remainingMinutes, UnallocatedReason.INSUFFICIENT_CAPACITY, unallocated);
        }
        // @@author
    }

    /**
     * Adds a task remainder with the task's source identity and deadline.
     */
    private static void addUnallocated(Task task, int minutes, UnallocatedReason reason,
            List<UnallocatedWork> unallocated) {
        // @@author nickpatt
        long capacity = ((long) minutes + TimeRules.SLOT_MINUTES - 1)
                / TimeRules.SLOT_MINUTES * TimeRules.SLOT_MINUTES;
        unallocated.add(new UnallocatedWork(task.id(), task.name(), task.deadline(), minutes, capacity, reason));
        // @@author
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
            // @@author nickpatt
            int workMinutes = first.workMinutes;
            // @@author
            index++;
            while (index < slots.size()) {
                Slot next = slots.get(index);
                if (next.taskId != taskId || !next.start.equals(sessionEnd)
                        || !next.start.toLocalDate().equals(sessionStart.toLocalDate())) {
                    break;
                }
                sessionEnd = sessionEnd.plusMinutes(TimeRules.SLOT_MINUTES);
                // @@author nickpatt
                workMinutes += next.workMinutes;
                // @@author
                index++;
            }
            // @@author nickpatt
            sessions.add(new StudySession(taskId, sessionStart, sessionEnd, workMinutes));
            // @@author
        }
        return sessions;
    }

    /**
     * Holds one chronological half-hour slot and its allocation state.
     */
    private static final class Slot {
        private final LocalDateTime start;
        private boolean available = true;
        private int taskId;
        // @@author nickpatt
        /** Exact work credited to this reserved slot, excluding padding. */
        private int workMinutes;
        // @@author

        private Slot(LocalDateTime start) {
            this.start = start;
        }
    }
}
