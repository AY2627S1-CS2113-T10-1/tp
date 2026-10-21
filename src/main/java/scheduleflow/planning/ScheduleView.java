package scheduleflow.planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.StudyWindow;

/**
 * Holds one complete study-window projection and the whole plan's unallocated work.
 */
public record ScheduleView(LocalDate date, LocalDateTime generatedAt,
        List<ScheduleEntry> entries, List<UnallocatedWork> unallocated, StudyWindow window) {
    /** Retains the original default-window constructor. */
    public ScheduleView(LocalDate date, LocalDateTime generatedAt,
            List<ScheduleEntry> entries, List<UnallocatedWork> unallocated) {
        this(date, generatedAt, entries, unallocated, StudyWindow.DEFAULT);
    }

    /**
     * Copies the supplied projection and requires contiguous coverage in its given order.
     */
    public ScheduleView {
        if (date == null || generatedAt == null || entries == null || unallocated == null || window == null
                || entries.stream().anyMatch(Objects::isNull) || unallocated.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Schedule view fields and list elements must not be null.");
        }
        entries = List.copyOf(entries);
        unallocated = List.copyOf(unallocated);
        LocalDateTime nextStart = date.atTime(window.start());
        for (ScheduleEntry entry : entries) {
            if (!entry.start().equals(nextStart)) {
                throw new ValidationException("Schedule entries must cover the window without gaps or overlaps.");
            }
            nextStart = entry.end();
        }
        if (!nextStart.equals(date.atTime(window.end()))) {
            throw new ValidationException("Schedule entries must cover the complete study window.");
        }
    }
}
