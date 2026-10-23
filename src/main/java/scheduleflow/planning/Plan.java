package scheduleflow.planning;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;

/**
 * Holds a frozen planning result and its exact immutable source data.
 * The planner supplies sorted lists and owns cross-record allocation invariants.
 */
public record Plan(LocalDateTime generatedAt, LocalDateTime horizonEnd, Snapshot source,
        List<StudySession> sessions, List<UnallocatedWork> unallocated, List<ScheduleEntry> breaks) {
    /** Retains the original constructor for plans without reserved breaks. */
    public Plan(LocalDateTime generatedAt, LocalDateTime horizonEnd, Snapshot source,
            List<StudySession> sessions, List<UnallocatedWork> unallocated) {
        this(generatedAt, horizonEnd, source, sessions, unallocated, List.of());
    }

    /**
     * Copies ordered results and requires the horizon derived from the generation time.
     * Rejects unsorted input rather than silently repairing a planner's output.
     */
    public Plan {
        if (generatedAt == null || horizonEnd == null || source == null
                || !horizonEnd.equals(TimeRules.horizonEnd(generatedAt))) {
            throw new ValidationException("Plan requires metadata, source and the matching calendar-year horizon.");
        }
        if (sessions == null || unallocated == null || breaks == null
                || sessions.stream().anyMatch(Objects::isNull) || unallocated.stream().anyMatch(Objects::isNull)
                || breaks.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Plan lists and their elements must not be null.");
        }
        sessions = List.copyOf(sessions);
        unallocated = List.copyOf(unallocated);
        breaks = List.copyOf(breaks);
        if (breaks.stream().anyMatch(entry -> entry.kind() != SlotKind.BREAK)) {
            throw new ValidationException("Plan breaks must contain only BREAK entries.");
        }
        requireSorted(breaks, Comparator.comparing(ScheduleEntry::start));
        requireSorted(sessions, Comparator.comparing(StudySession::start).thenComparingInt(StudySession::taskId));
        requireSorted(unallocated,
                Comparator.comparing(UnallocatedWork::deadline).thenComparingInt(UnallocatedWork::taskId));
    }

    /**
     * Returns whether all work was allocated, including when there were no tasks.
     */
    public boolean isComplete() {
        return unallocated.isEmpty();
    }

    /** Rejects out-of-order results instead of concealing allocation defects by sorting them. */
    private static <T> void requireSorted(List<T> values, Comparator<T> order) {
        for (int i = 1; i < values.size(); i++) {
            if (order.compare(values.get(i - 1), values.get(i)) > 0) {
                throw new ValidationException("Plan results must be supplied in contract order.");
            }
        }
    }
}
