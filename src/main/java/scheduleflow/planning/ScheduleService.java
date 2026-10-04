package scheduleflow.planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Task;

/**
 * Projects an existing frozen plan into one day's complete study window.
 */
public final class ScheduleService {
    /**
     * Creates the ScheduleService dependencies without performing I/O.
     */
    public ScheduleService() {
    }

    /**
     * Returns a merged projection without replanning or reading the current clock.
     */
    public ScheduleView forDate(Plan plan, LocalDate date) {
        Objects.requireNonNull(plan, "plan");
        LocalDate firstDate = plan.generatedAt().toLocalDate();
        LocalDate lastDate = plan.horizonEnd().toLocalDate().minusDays(1);
        if (date == null || date.isBefore(firstDate) || date.isAfter(lastDate)) {
            throw new ValidationException("Schedule date must be between " + firstDate + " and " + lastDate
                    + ". Run plan again to refresh the planning range.");
        }

        LocalDateTime cutoff = TimeRules.eligibleStart(date, plan.generatedAt());
        LocalDateTime windowEnd = date.atTime(TimeRules.STUDY_END);
        List<ScheduleEntry> entries = new ArrayList<>();
        for (LocalDateTime start = date.atTime(TimeRules.STUDY_START); start.isBefore(windowEnd);
                start = start.plusMinutes(TimeRules.SLOT_MINUTES)) {
            appendMerged(entries, classifySlot(plan, start, cutoff));
        }
        return new ScheduleView(date, plan.generatedAt(), entries, plan.unallocated());
    }

    /**
     * Classifies a slot using commitments, saved sessions, then the original generation cutoff.
     */
    private static ScheduleEntry classifySlot(Plan plan, LocalDateTime start, LocalDateTime cutoff) {
        LocalDateTime end = start.plusMinutes(TimeRules.SLOT_MINUTES);
        for (Commitment commitment : plan.source().commitments()) {
            if (commitment.day() != start.getDayOfWeek()) {
                continue;
            }
            LocalDateTime busyStart = start.toLocalDate().atTime(commitment.start());
            LocalDateTime busyEnd = busyStart.plusMinutes(commitment.durationMinutes());
            if (start.isBefore(busyEnd) && busyStart.isBefore(end)) {
                return new ScheduleEntry(start, end, SlotKind.BUSY, commitment.displayId(), commitment.name());
            }
        }
        for (StudySession session : plan.sessions()) {
            if (!start.isBefore(session.start()) && !end.isAfter(session.end())) {
                Task task = findTask(plan, session.taskId());
                return new ScheduleEntry(start, end, SlotKind.TASK, task.displayId(), task.name());
            }
        }
        SlotKind kind = start.isBefore(cutoff) ? SlotKind.UNPLANNED : SlotKind.FREE;
        return new ScheduleEntry(start, end, kind, "", "");
    }

    /**
     * Resolves a session's name from the frozen source rather than any later task state.
     */
    private static Task findTask(Plan plan, int taskId) {
        for (Task task : plan.source().tasks()) {
            if (task.id() == taskId) {
                return task;
            }
        }
        throw new ValidationException("Plan session refers to unknown task ID: T" + taskId);
    }

    /**
     * Merges only contiguous entries whose kind and complete display identity match.
     */
    private static void appendMerged(List<ScheduleEntry> entries, ScheduleEntry entry) {
        if (!entries.isEmpty()) {
            int lastIndex = entries.size() - 1;
            ScheduleEntry previous = entries.get(lastIndex);
            if (previous.end().equals(entry.start()) && previous.kind() == entry.kind()
                    && previous.reference().equals(entry.reference()) && previous.name().equals(entry.name())) {
                entries.set(lastIndex, new ScheduleEntry(previous.start(), entry.end(), entry.kind(),
                        entry.reference(), entry.name()));
                return;
            }
        }
        entries.add(entry);
    }
}
