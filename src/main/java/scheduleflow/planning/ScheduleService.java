package scheduleflow.planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Task;

/**
 * Projects an existing frozen plan into one day's complete study window.
 */
public final class ScheduleService {
    private static final Logger LOGGER = Logger.getLogger(ScheduleService.class.getName());

    /**
     * Creates the ScheduleService dependencies without performing I/O.
     */
    public ScheduleService() {
    }

    /**
     * Returns a merged projection without replanning or reading the current clock.
     *
     * @param plan the frozen plan containing source commitments and saved sessions
     * @param date the date to display within the plan's calendar range
     * @return a complete study window with the whole plan's unallocated work
     * @throws NullPointerException if plan is null
     * @throws ValidationException if date is null or outside the plan's range,
     *         or a displayed session refers to a missing source task
     */
    public ScheduleView forDate(Plan plan, LocalDate date) {
        LOGGER.fine(() -> "Starting schedule projection for " + date);
        Objects.requireNonNull(plan, "plan");
        LocalDate firstDate = plan.generatedAt().toLocalDate();
        LocalDate lastDate = plan.horizonEnd().toLocalDate().minusDays(1);
        if (date == null || date.isBefore(firstDate) || date.isAfter(lastDate)) {
            LOGGER.fine(() -> "Rejected schedule date " + date + "; allowed range: "
                    + firstDate + " to " + lastDate);
            throw new ValidationException("Schedule date must be between " + firstDate + " and " + lastDate
                    + ". Run plan again to refresh the planning range.");
        }
        LOGGER.fine(() -> "Validated schedule date " + date);

        var window = plan.source().studyHours().forDate(date);
        LocalDateTime cutoff = TimeRules.eligibleStart(date, plan.generatedAt(), window);
        LocalDateTime windowEnd = date.atTime(window.end());
        assert !cutoff.isBefore(date.atTime(window.start())) && !cutoff.isAfter(windowEnd)
                : "Planning cutoff must lie within the requested study window";
        LOGGER.fine(() -> "Classifying and merging study slots for " + date + "; cutoff: " + cutoff);
        List<ScheduleEntry> entries = new ArrayList<>();
        for (LocalDateTime start = date.atTime(window.start()); start.isBefore(windowEnd);
                start = start.plusMinutes(TimeRules.SLOT_MINUTES)) {
            appendMerged(entries, classifySlot(plan, start, cutoff));
        }
        LOGGER.fine(() -> "Validating completed schedule view for " + date);
        ScheduleView view = new ScheduleView(date, plan.generatedAt(), entries, plan.unallocated(), window);
        LOGGER.fine(() -> "Projected schedule for " + date + " from plan generated at " + plan.generatedAt()
                + "; merged entries: " + entries.size() + "; unallocated tasks: " + plan.unallocated().size());
        return view;
    }

    /**
     * Classifies a slot using commitments, saved sessions, then the original generation cutoff.
     *
     * @param plan the frozen source of commitments and sessions
     * @param start the start of the half-hour study slot
     * @param cutoff the earliest eligible allocation time on the requested date
     * @return the slot's busy, task, unplanned, or free entry in that precedence order
     * @throws ValidationException if an assigned session refers to a missing source task
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
     *
     * @param plan the plan whose source owns the task
     * @param taskId the numeric task identifier saved in the session
     * @return the matching source task
     * @throws ValidationException if the source contains no matching task
     */
    private static Task findTask(Plan plan, int taskId) {
        for (Task task : plan.source().tasks()) {
            if (task.id() == taskId) {
                return task;
            }
        }
        LOGGER.fine(() -> "Cannot project plan: session refers to unknown task ID T" + taskId);
        throw new ValidationException("Plan session refers to unknown task ID: T" + taskId);
    }

    /**
     * Merges only contiguous entries whose kind and complete display identity match.
     *
     * @param entries the accumulated chronological projection, updated in place
     * @param entry the next slot, contiguous with the accumulated projection
     */
    private static void appendMerged(List<ScheduleEntry> entries, ScheduleEntry entry) {
        if (!entries.isEmpty()) {
            int lastIndex = entries.size() - 1;
            ScheduleEntry previous = entries.get(lastIndex);
            assert previous.end().equals(entry.start()) : "Projected slots must be contiguous and ordered";
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
