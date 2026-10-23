package scheduleflow.storage;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.model.TaskPriority;

/**
 * Converts snapshots to and from the versioned text format without accessing saved files.
 */
public final class StateCodec {
    // @@author ernestgoh-god
    /**
     * Records conversion stages at FINE level without including stored values or exception messages.
     */
    private static final Logger LOGGER = Logger.getLogger(StateCodec.class.getName());

    /**
     * Identifies the readable legacy format, which supplies default study hours and task priorities.
     */
    private static final String HEADER = "SCHEDULEFLOW|1";
    // @@author

    /**
     * Creates the StateCodec dependencies without performing I/O.
     */
    public StateCodec() {
    }

    // @@author ernestgoh-god
    /**
     * Encodes a complete snapshot as version 3, retaining recurrence history and study-hours rows.
     * Stores names last so names containing pipes do not need escaping.
     *
     * @param state validated immutable snapshot to encode in its existing list order
     * @return complete text with LF line endings and a final newline
     * @throws NullPointerException if the snapshot is null
     */
    public String encode(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting snapshot encoding");
        // @@author nickpatt
        StringBuilder text = new StringBuilder("SCHEDULEFLOW|3").append('\n');
        text.append("NEXT|").append(state.nextTaskId()).append('|').append(state.nextCommitmentId())
                .append('|').append(state.nextRecurringTaskId()).append('\n');
        for (Task task : state.tasks()) {
            // @@author nickpatt
            text.append("TASK3|").append(task.id()).append('|')
                    .append(task.deadline().map(LocalDateTime::toString).orElse("NONE")).append('|')
                    .append(task.remainingMinutes()).append('|').append(task.priority()).append('|')
                    .append(task.occurrence().map(value -> Integer.toString(value.templateId())).orElse("NONE"))
                    .append('|').append(task.occurrence().map(value -> value.originalDeadline().toString())
                            .orElse("NONE")).append('|')
                    .append(task.name()).append('\n');
            // @@author ernestgoh-god
        }
        // @@author nickpatt
        for (RecurringTask template : state.recurringTasks()) {
            text.append("RECURRING|").append(template.id()).append('|').append(template.firstDeadline()).append('|')
                    .append(template.until().map(LocalDate::toString).orElse("NONE")).append('|')
                    .append(template.minutes()).append('|').append(template.priority()).append('|')
                    .append(template.generatedThrough().map(LocalDateTime::toString).orElse("NONE")).append('|')
                    .append(template.name()).append('\n');
        }
        // @@author ernestgoh-god
        for (Commitment commitment : state.commitments()) {
            text.append("COMMITMENT|").append(commitment.id()).append('|').append(commitment.day()).append('|')
                    .append(commitment.start()).append('|').append(commitment.durationMinutes()).append('|')
                    .append(commitment.name()).append('\n');
        }
        new TreeMap<>(state.studyHours().windows()).forEach((target, window) -> text.append("HOURS|")
                .append(target).append('|').append(window.start()).append('|').append(window.end()).append('\n'));
        String encoded = text.toString();
        assert encoded.lines().count() == 2L + state.tasks().size() + state.commitments().size()
                + state.studyHours().windows().size() + state.recurringTasks().size()
                : "Encoding must produce a header, counters and one line per record";
        LOGGER.log(Level.FINE, "Snapshot encoding completed");
        return encoded;
    }

    /**
     * Decodes and validates the complete text, translating structural failures to StorageException.
     * Reuses domain validation and allows past deadlines when restoring saved work.
     *
     * @param text complete saved text, accepting LF, CRLF or CR and an optional final newline
     * @return validated snapshot with stored order and independent ID counters
     * @throws StorageException if text is null, malformed, unsupported or violates snapshot invariants
     */
    public Snapshot decode(String text) throws StorageException {
        LOGGER.log(Level.FINE, "Starting saved data decoding");
        if (text == null) {
            LOGGER.log(Level.FINE, "Saved data decoding rejected; null text");
            throw new StorageException("Saved data must not be null.");
        }
        List<String> lines = text.lines().toList();
        try {
            validateHeader(lines);
            LOGGER.log(Level.FINE, "Saved data header validated; reading counters and records");
            boolean version3 = lines.getFirst().equals("SCHEDULEFLOW|3");
            boolean extended = !lines.getFirst().equals(HEADER);
            String[] counters = splitFields(lines.get(1), version3 ? 4 : 3);
            if (!counters[0].equals("NEXT")) {
                throw new StorageException("Missing next ID counters.");
            }
            List<Task> tasks = new ArrayList<>();
            List<Commitment> commitments = new ArrayList<>();
            List<RecurringTask> templates = new ArrayList<>();
            Map<String, StudyWindow> windows = new HashMap<>();
            for (int i = 2; i < lines.size(); i++) {
                String line = lines.get(i);
                if (version3 && line.startsWith("RECURRING|")) {
                    // @@author nickpatt
                    String[] fields = splitFields(line, 8);
                    templates.add(new RecurringTask(Integer.parseInt(fields[1]), fields[7],
                            LocalDateTime.parse(fields[2]), fields[3].equals("NONE")
                                    ? Optional.empty() : Optional.of(LocalDate.parse(fields[3])),
                            Integer.parseInt(fields[4]), TaskPriority.valueOf(fields[5]),
                            optionalDeadline(fields[6])));
                } else if (version3 && line.startsWith("TASK3|")) {
                    String[] fields = splitFields(line, 8);
                    Optional<TaskOccurrence> occurrence = Optional.empty();
                    if (!fields[5].equals("NONE") || !fields[6].equals("NONE")) {
                        occurrence = Optional.of(new TaskOccurrence(Integer.parseInt(fields[5]),
                                LocalDateTime.parse(fields[6])));
                    }
                    tasks.add(new Task(Integer.parseInt(fields[1]), fields[7], optionalDeadline(fields[2]),
                            Integer.parseInt(fields[3]), TaskPriority.valueOf(fields[4]), occurrence));
                    // @@author ernestgoh-god
                } else if (extended && line.startsWith("HOURS|")) {
                    decodeHours(line, windows);
                } else if (extended && line.startsWith("TASK2|")) {
                    // @@author nickpatt
                    String[] fields = splitFields(line, 6);
                    tasks.add(new Task(Integer.parseInt(fields[1]), fields[5], fields[2].equals("NONE")
                            ? Optional.empty() : Optional.of(LocalDateTime.parse(fields[2])),
                            Integer.parseInt(fields[3]), TaskPriority.valueOf(fields[4])));
                    // @@author ernestgoh-god
                } else {
                    decodeRecord(line, tasks, commitments);
                }
            }
            assert (long) tasks.size() + commitments.size() + windows.size() + templates.size() == lines.size() - 2L
                    : "Every saved record line must decode to exactly one record";
            LOGGER.log(Level.FINE, "Saved records decoded; validating snapshot invariants");
            Snapshot decoded = new Snapshot(tasks, commitments,
                    Integer.parseInt(counters[1]), Integer.parseInt(counters[2]), new StudyHours(windows),
                    templates, version3 ? Integer.parseInt(counters[3]) : 1);
            LOGGER.log(Level.FINE, "Saved data decoding completed");
            return decoded;
        } catch (StorageException exception) {
            LOGGER.log(Level.FINE, "Saved data decoding rejected; invalid structure or version");
            throw exception;
        } catch (IllegalArgumentException | DateTimeException | ValidationException exception) {
            LOGGER.log(Level.FINE, "Saved data decoding rejected; invalid record or snapshot");
            throw new StorageException("Invalid saved data: " + exception.getMessage(), exception);
        }
    }

    /**
     * Requires a supported header and a counters line before any indexed access by the decoder.
     *
     * @throws StorageException if the header, version or counters line is missing or unsupported
     */
    private void validateHeader(List<String> lines) throws StorageException {
        if (lines.isEmpty() || !lines.getFirst().startsWith("SCHEDULEFLOW|")) {
            throw new StorageException("Invalid saved data header.");
        }
        if (!lines.getFirst().equals(HEADER) && !lines.getFirst().equals("SCHEDULEFLOW|2")
                && !lines.getFirst().equals("SCHEDULEFLOW|3")) {
            throw new StorageException("Unsupported file version: "
                    + lines.getFirst().substring("SCHEDULEFLOW|".length()));
        }
        if (lines.size() < 2) {
            throw new StorageException("Missing next ID counters.");
        }
    }

    // @@author nickpatt
    /** Decodes explicit absence without using a sentinel deadline. */
    private static Optional<LocalDateTime> optionalDeadline(String value) {
        return value.equals("NONE") ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
    }
    // @@author ernestgoh-god

    /**
     * Appends exactly one validated record to its corresponding private accumulation list.
     * Domain and token errors propagate to {@link #decode(String)} for translation.
     *
     * @throws StorageException if the record type is unknown or required fields are missing
     */
    private void decodeRecord(String line, List<Task> tasks, List<Commitment> commitments) throws StorageException {
        if (line.startsWith("TASK|")) {
            String[] fields = splitFields(line, 5);
            // @@author nickpatt
            // Legacy TASK rows retain their original positive half-hour contract, including old v2 hours files.
            InputRules.requireDuration(Integer.parseInt(fields[3]));
            // @@author ernestgoh-god
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

    // @@author GabrielYuSP
    /** Rejects duplicate targets and malformed windows rather than silently replacing saved settings. */
    private void decodeHours(String line, Map<String, StudyWindow> windows) throws StorageException {
        String[] fields = splitFields(line, 4);
        StudyWindow window = new StudyWindow(LocalTime.parse(fields[2]), LocalTime.parse(fields[3]));
        if (windows.putIfAbsent(fields[1], window) != null) {
            throw new StorageException("Duplicate study hours target: " + fields[1]);
        }
    }

    // @@author ernestgoh-god
    /**
     * Splits required fields while preserving literal pipes within the final name field.
     *
     * @throws StorageException if fewer than the required number of fields are present
     */
    private String[] splitFields(String line, int count) throws StorageException {
        // The split limit keeps any remaining pipes inside the final name field.
        String[] fields = line.split("\\|", count);
        if (fields.length != count) {
            throw new StorageException("Missing saved record fields.");
        }
        return fields;
    }
    // @@author
}
