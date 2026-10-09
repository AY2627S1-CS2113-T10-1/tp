package scheduleflow.storage;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

/**
 * Converts snapshots to and from the versioned text format without file I/O.
 */
public final class StateCodec {
    private static final String HEADER = "SCHEDULEFLOW|1";

    /**
     * Creates the StateCodec dependencies without performing I/O.
     */
    public StateCodec() {
    }

    /**
     * Encodes a complete snapshot in canonical version 1 form.
     * Stores names last so names containing pipes do not need escaping.
     */
    public String encode(Snapshot state) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        text.append("NEXT|").append(state.nextTaskId()).append('|').append(state.nextCommitmentId()).append('\n');
        for (Task task : state.tasks()) {
            text.append("TASK|").append(task.id()).append('|').append(task.deadline()).append('|')
                    .append(task.remainingMinutes()).append('|').append(task.name()).append('\n');
        }
        for (Commitment commitment : state.commitments()) {
            text.append("COMMITMENT|").append(commitment.id()).append('|').append(commitment.day()).append('|')
                    .append(commitment.start()).append('|').append(commitment.durationMinutes()).append('|')
                    .append(commitment.name()).append('\n');
        }
        return text.toString();
    }

    /**
     * Decodes and validates the complete text, translating structural failures to StorageException.
     * Reuses domain validation and allows past deadlines when restoring saved work.
     */
    public Snapshot decode(String text) throws StorageException {
        if (text == null) {
            throw new StorageException("Saved data must not be null.");
        }
        List<String> lines = text.lines().toList();
        validateHeader(lines);
        try {
            String[] counters = splitFields(lines.get(1), 3);
            if (!counters[0].equals("NEXT")) {
                throw new StorageException("Missing next ID counters.");
            }
            List<Task> tasks = new ArrayList<>();
            List<Commitment> commitments = new ArrayList<>();
            for (int i = 2; i < lines.size(); i++) {
                decodeRecord(lines.get(i), tasks, commitments);
            }
            return new Snapshot(tasks, commitments, Integer.parseInt(counters[1]), Integer.parseInt(counters[2]));
        } catch (IllegalArgumentException | DateTimeException | ValidationException exception) {
            throw new StorageException("Invalid saved data: " + exception.getMessage(), exception);
        }
    }

    private void validateHeader(List<String> lines) throws StorageException {
        if (lines.isEmpty() || !lines.getFirst().startsWith("SCHEDULEFLOW|")) {
            throw new StorageException("Invalid saved data header.");
        }
        if (!lines.getFirst().equals(HEADER)) {
            throw new StorageException("Unsupported file version: "
                    + lines.getFirst().substring("SCHEDULEFLOW|".length()));
        }
        if (lines.size() < 2) {
            throw new StorageException("Missing next ID counters.");
        }
    }

    private void decodeRecord(String line, List<Task> tasks, List<Commitment> commitments) throws StorageException {
        if (line.startsWith("TASK|")) {
            String[] fields = splitFields(line, 5);
            tasks.add(new Task(Integer.parseInt(fields[1]), fields[4],
                    LocalDateTime.parse(fields[2]), Integer.parseInt(fields[3])));
        } else if (line.startsWith("COMMITMENT|")) {
            String[] fields = splitFields(line, 6);
            commitments.add(new Commitment(Integer.parseInt(fields[1]), fields[5], DayOfWeek.valueOf(fields[2]),
                    LocalTime.parse(fields[3]), Integer.parseInt(fields[4])));
        } else {
            throw new StorageException("Unknown or empty saved record.");
        }
    }

    private String[] splitFields(String line, int count) throws StorageException {
        // The split limit keeps any remaining pipes inside the final name field.
        String[] fields = line.split("\\|", count);
        if (fields.length != count) {
            throw new StorageException("Missing saved record fields.");
        }
        return fields;
    }
}
