package scheduleflow.storage;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.SlotKind;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.UnallocatedReason;
import scheduleflow.planning.UnallocatedWork;

// @@author ernestgoh-god
/**
 * Encodes a frozen plan after its source snapshot in version 5 of the saved-data file.
 * Older snapshot-only files remain readable through {@link StateCodec}.
 */
final class PlanCodec {
    private static final String PLAN_HEADER = "SCHEDULEFLOW|5";
    private final StateCodec snapshots;

    /** Creates a plan codec without reading or writing saved data. */
    PlanCodec(StateCodec snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * Encodes the exact generated result and its source in one complete file.
     *
     * @param plan validated frozen plan
     * @return version 5 text with LF endings and a final newline
     */
    String encode(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        String source = snapshots.encodeVersionFour(plan.source());
        StringBuilder text = new StringBuilder(PLAN_HEADER).append(source.substring("SCHEDULEFLOW|4".length()));
        text.append("PLAN|").append(plan.generatedAt()).append('|').append(plan.horizonEnd()).append('|')
                .append(plan.sessions().size()).append('|').append(plan.unallocated().size()).append('|')
                .append(plan.breaks().size()).append('\n');
        for (StudySession session : plan.sessions()) {
            text.append("SESSION|").append(session.taskId()).append('|').append(session.start()).append('|')
                    .append(session.end()).append('\n');
        }
        for (UnallocatedWork work : plan.unallocated()) {
            text.append("UNALLOCATED|").append(work.taskId()).append('|').append(work.deadline()).append('|')
                    .append(work.minutes()).append('|').append(work.reason()).append('|').append(work.taskName())
                    .append('\n');
        }
        for (ScheduleEntry entry : plan.breaks()) {
            text.append("BREAK|").append(entry.start()).append('|').append(entry.end()).append('\n');
        }
        return text.toString();
    }

    /**
     * Decodes a complete version 5 file, rejecting missing, extra or invalid plan rows.
     *
     * @param text complete saved file
     * @return validated frozen plan with its exact source snapshot
     * @throws StorageException if any snapshot or plan record is invalid
     */
    Plan decode(String text) throws StorageException {
        if (text == null) {
            throw new StorageException("Saved plan must not be null.");
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(PLAN_HEADER)) {
            throw new StorageException("Saved plan requires file version 5.");
        }
        int planLine = -1;
        for (int i = 2; i < lines.size(); i++) {
            if (lines.get(i).startsWith("PLAN|")) {
                planLine = i;
                break;
            }
        }
        if (planLine < 0) {
            throw new StorageException("Saved plan metadata is missing.");
        }
        List<String> sourceLines = new ArrayList<>(lines.subList(0, planLine));
        sourceLines.set(0, "SCHEDULEFLOW|4");
        Snapshot source = snapshots.decode(String.join("\n", sourceLines) + "\n");
        try {
            String[] metadata = exactFields(lines.get(planLine), 6, "PLAN");
            int sessions = Integer.parseInt(metadata[3]);
            int unallocated = Integer.parseInt(metadata[4]);
            int breaks = Integer.parseInt(metadata[5]);
            if (sessions < 0 || unallocated < 0 || breaks < 0
                    || (long) sessions + unallocated + breaks != lines.size() - planLine - 1L) {
                throw new StorageException("Saved plan row counts do not match its records.");
            }
            int row = planLine + 1;
            List<StudySession> sessionRows = new ArrayList<>();
            for (int i = 0; i < sessions; i++) {
                String[] fields = exactFields(lines.get(row++), 4, "SESSION");
                sessionRows.add(new StudySession(Integer.parseInt(fields[1]), LocalDateTime.parse(fields[2]),
                        LocalDateTime.parse(fields[3])));
            }
            List<UnallocatedWork> remainderRows = new ArrayList<>();
            for (int i = 0; i < unallocated; i++) {
                String[] fields = namedFields(lines.get(row++), 6, "UNALLOCATED");
                remainderRows.add(new UnallocatedWork(Integer.parseInt(fields[1]), fields[5],
                        LocalDateTime.parse(fields[2]), Integer.parseInt(fields[3]),
                        UnallocatedReason.valueOf(fields[4])));
            }
            List<ScheduleEntry> breakRows = new ArrayList<>();
            for (int i = 0; i < breaks; i++) {
                String[] fields = exactFields(lines.get(row++), 3, "BREAK");
                breakRows.add(new ScheduleEntry(LocalDateTime.parse(fields[1]), LocalDateTime.parse(fields[2]),
                        SlotKind.BREAK, "", ""));
            }
            LocalDateTime generatedAt = LocalDateTime.parse(metadata[1]);
            LocalDateTime horizonEnd = LocalDateTime.parse(metadata[2]);
            validateReferences(source, generatedAt, horizonEnd, sessionRows, remainderRows, breakRows);
            return new Plan(generatedAt, horizonEnd, source, sessionRows, remainderRows, breakRows);
        } catch (IllegalArgumentException | DateTimeException | ValidationException exception) {
            throw new StorageException("Invalid saved plan: " + exception.getMessage(), exception);
        }
    }

    /** Rejects rows that cannot describe the embedded source or the saved planning range. */
    private void validateReferences(Snapshot source, LocalDateTime generatedAt, LocalDateTime horizonEnd,
            List<StudySession> sessions, List<UnallocatedWork> unallocated, List<ScheduleEntry> breaks)
            throws StorageException {
        for (StudySession session : sessions) {
            if (source.tasks().stream().noneMatch(task -> task.id() == session.taskId())
                    || !insideHorizon(session.start(), session.end(), generatedAt, horizonEnd)) {
                throw new StorageException("Saved plan has an invalid task session.");
            }
        }
        for (UnallocatedWork work : unallocated) {
            Task task = source.tasks().stream().filter(candidate -> candidate.id() == work.taskId())
                    .findFirst().orElseThrow(() -> new StorageException("Saved plan has an unknown task remainder."));
            if (!task.name().equals(work.taskName()) || !task.deadline().equals(work.deadline())) {
                throw new StorageException("Saved plan remainder differs from its source task.");
            }
        }
        for (ScheduleEntry entry : breaks) {
            if (!insideHorizon(entry.start(), entry.end(), generatedAt, horizonEnd)) {
                throw new StorageException("Saved plan has a break outside its range.");
            }
        }
    }

    /** Keeps saved intervals inside the frozen plan's included calendar dates. */
    private boolean insideHorizon(LocalDateTime start, LocalDateTime end,
            LocalDateTime generatedAt, LocalDateTime horizonEnd) {
        return !start.toLocalDate().isBefore(generatedAt.toLocalDate()) && !end.isAfter(horizonEnd);
    }

    /** Requires an exact number of unnamed fields and the expected record kind. */
    private String[] exactFields(String line, int count, String kind) throws StorageException {
        String[] fields = line.split("\\|", -1);
        if (fields.length != count || !fields[0].equals(kind)) {
            throw new StorageException("Invalid saved plan " + kind + " record.");
        }
        return fields;
    }

    /** Retains literal pipes in the last name field while requiring all preceding fields. */
    private String[] namedFields(String line, int count, String kind) throws StorageException {
        String[] fields = line.split("\\|", count);
        if (fields.length != count || !fields[0].equals(kind)) {
            throw new StorageException("Invalid saved plan " + kind + " record.");
        }
        return fields;
    }
}
// @@author
