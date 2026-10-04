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
        List<Slot> slots = createSlots(state.commitments(), now, horizonEnd);
        List<Task> tasks = new ArrayList<>(state.tasks());
        tasks.sort(Comparator.comparing(Task::deadline).thenComparingInt(Task::id));

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
    private static List<Slot> createSlots(List<Commitment> commitments, LocalDateTime now,
            LocalDateTime horizonEnd) {
        List<Slot> slots = new ArrayList<>();
        for (var date = now.toLocalDate(); date.isBefore(horizonEnd.toLocalDate()); date = date.plusDays(1)) {
            LocalDateTime slotStart = TimeRules.eligibleStart(date, now);
            LocalDateTime windowEnd = date.atTime(TimeRules.STUDY_END);
            while (!slotStart.plusMinutes(TimeRules.SLOT_MINUTES).isAfter(windowEnd)) {
                Slot slot = new Slot(slotStart);
                if (isBlocked(slotStart, commitments)) {
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
        if (!task.deadline().isAfter(now)) {
            addUnallocated(task, task.remainingMinutes(), UnallocatedReason.DEADLINE_PASSED, unallocated);
            return;
        }
        if (task.deadline().isAfter(horizonEnd)) {
            addUnallocated(task, task.remainingMinutes(), UnallocatedReason.OUTSIDE_HORIZON, unallocated);
            return;
        }

        int remainingMinutes = task.remainingMinutes();
        for (Slot slot : slots) {
            if (remainingMinutes == 0) {
                break;
            }
            LocalDateTime slotEnd = slot.start.plusMinutes(TimeRules.SLOT_MINUTES);
            if (slot.available && !slotEnd.isAfter(task.deadline())) {
                slot.available = false;
                slot.taskId = task.id();
                remainingMinutes -= TimeRules.SLOT_MINUTES;
            }
        }
        if (remainingMinutes > 0) {
            addUnallocated(task, remainingMinutes, UnallocatedReason.INSUFFICIENT_CAPACITY, unallocated);
        }
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

    /**
     * Holds one chronological half-hour slot and its allocation state.
     */
    private static final class Slot {
        private final LocalDateTime start;
        private boolean available = true;
        private int taskId;

        private Slot(LocalDateTime start) {
            this.start = start;
        }
    }
}
