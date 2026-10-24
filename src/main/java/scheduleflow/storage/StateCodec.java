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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;

/**
 * Converts snapshots to and from the versioned text format without accessing saved files.
 */
public final class StateCodec {
    // TODO(Save): implement issue #55 policy persistence with Snapshot and legacy-file defaults.
    // Coordinate defaults and preserve policy through all task, commitment and study-hours mutations.
    // @@author ernestgoh-god
    /**
     * Records conversion stages at FINE level without including stored values or exception messages.
     */
    private static final Logger LOGGER = Logger.getLogger(StateCodec.class.getName());

    /**
     * Identifies the legacy format used when no custom study hours are present.
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
     * Encodes a complete snapshot using the oldest format that can represent all stored values.
     * Stores names last so names containing pipes do not need escaping.
     *
     * @param state validated immutable snapshot to encode in its existing list order
     * @return complete text with LF line endings and a final newline
     * @throws NullPointerException if the snapshot is null
     */
    public String encode(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting snapshot encoding");
        // @@author Darren50
        boolean hasDateBoundedCommitments = state.commitments().stream()
                .anyMatch(commitment -> commitment.startDate() != null);
        boolean hasSkippedOccurrences = state.commitments().stream()
                .anyMatch(commitment -> !commitment.skippedDates().isEmpty());
        // @@author nickpatt
        boolean hasRecurrence = !state.recurringTasks().isEmpty() || state.nextRecurringTaskId() != 1
                || state.tasks().stream().anyMatch(task -> task.occurrence().isPresent());
        String version = hasRecurrence ? "SCHEDULEFLOW|5" : hasSkippedOccurrences ? "SCHEDULEFLOW|4"
                : hasDateBoundedCommitments ? "SCHEDULEFLOW|3"
                : state.studyHours().windows().isEmpty() ? HEADER : "SCHEDULEFLOW|2";
        // @@author ernestgoh-god
        StringBuilder text = new StringBuilder(version).append('\n');
        text.append("NEXT|").append(state.nextTaskId()).append('|').append(state.nextCommitmentId());
        if (hasRecurrence) {
            text.append('|').append(state.nextRecurringTaskId());
        }
        text.append('\n');
        for (Task task : state.tasks()) {
            text.append(task.occurrence().isPresent() ? "OCCURRENCE|" : "TASK|")
                    .append(task.id()).append('|').append(task.deadline()).append('|')
                    .append(task.remainingMinutes()).append('|');
            if (task.occurrence().isPresent()) {
                TaskOccurrence occurrence = task.occurrence().orElseThrow();
                text.append(occurrence.templateId()).append('|').append(occurrence.originalDeadline()).append('|');
            }
            text.append(task.name()).append('\n');
        }
        for (RecurringTask recurring : state.recurringTasks()) {
            text.append("RECURRING|").append(recurring.id()).append('|').append(recurring.firstDeadline())
                    .append('|').append(recurring.minutes()).append('|')
                    .append(recurring.until().map(LocalDate::toString).orElse("-")).append('|')
                    .append(recurring.generatedThrough().map(LocalDateTime::toString).orElse("-")).append('|')
                    .append(recurring.name()).append('\n');
        }
        // @@author ernestgoh-god
        for (Commitment commitment : state.commitments()) {
            text.append("COMMITMENT|").append(commitment.id()).append('|').append(commitment.day()).append('|')
                    .append(commitment.start()).append('|').append(commitment.durationMinutes()).append('|');
            // @@author Darren50
            if (hasDateBoundedCommitments || hasSkippedOccurrences || hasRecurrence) {
                text.append(commitment.startDate() == null ? "-" : commitment.startDate()).append('|')
                        .append(commitment.endDate() == null ? "-" : commitment.endDate()).append('|');
            }
            if (hasSkippedOccurrences || hasRecurrence) {
                text.append(encodeSkippedDates(commitment.skippedDates())).append('|');
            }
            // @@author ernestgoh-god
            text.append(commitment.name()).append('\n');
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
            // @@author nickpatt
            boolean supportsRecurrence = lines.getFirst().equals("SCHEDULEFLOW|5");
            String[] counters = splitFields(lines.get(1), supportsRecurrence ? 4 : 3);
            // @@author ernestgoh-god
            if (!counters[0].equals("NEXT")) {
                throw new StorageException("Missing next ID counters.");
            }
            List<Task> tasks = new ArrayList<>();
            List<Commitment> commitments = new ArrayList<>();
            List<RecurringTask> recurringTasks = new ArrayList<>();
            Map<String, StudyWindow> windows = new HashMap<>();
            for (int i = 2; i < lines.size(); i++) {
                String line = lines.get(i);
                // @@author Darren50
                boolean supportsExtensions = !lines.getFirst().equals(HEADER);
                // @@author nickpatt
                if (supportsRecurrence && (line.startsWith("RECURRING|") || line.startsWith("OCCURRENCE|"))) {
                    decodeRecurrence(line, tasks, recurringTasks);
                } else if (supportsExtensions && line.startsWith("HOURS|")) {
                    decodeHours(line, windows);
                } else {
                    boolean supportsDateRanges = lines.getFirst().equals("SCHEDULEFLOW|3")
                            || lines.getFirst().equals("SCHEDULEFLOW|4") || supportsRecurrence;
                    decodeRecord(line, tasks, commitments, supportsDateRanges,
                            lines.getFirst().equals("SCHEDULEFLOW|4") || supportsRecurrence);
                }
                // @@author ernestgoh-god
            }
            assert (long) tasks.size() + commitments.size() + windows.size() + recurringTasks.size()
                    == lines.size() - 2L
                    : "Every saved record line must decode to exactly one record";
            LOGGER.log(Level.FINE, "Saved records decoded; validating snapshot invariants");
            Snapshot decoded = new Snapshot(tasks, commitments,
                    Integer.parseInt(counters[1]), Integer.parseInt(counters[2]), new StudyHours(windows),
                    recurringTasks, supportsRecurrence ? Integer.parseInt(counters[3]) : 1);
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
                && !lines.getFirst().equals("SCHEDULEFLOW|3")
                && !lines.getFirst().equals("SCHEDULEFLOW|4")
                && !lines.getFirst().equals("SCHEDULEFLOW|5")) {
            throw new StorageException("Unsupported file version: "
                    + lines.getFirst().substring("SCHEDULEFLOW|".length()));
        }
        if (lines.size() < 2) {
            throw new StorageException("Missing next ID counters.");
        }
    }

    /**
     * Appends exactly one validated record to its corresponding private accumulation list.
     * Domain and token errors propagate to {@link #decode(String)} for translation.
     *
     * @throws StorageException if the record type is unknown or required fields are missing
     */
    private void decodeRecord(String line, List<Task> tasks, List<Commitment> commitments,
            boolean supportsDateRanges, boolean supportsSkippedDates) throws StorageException {
        if (line.startsWith("TASK|")) {
            String[] fields = splitFields(line, 5);
            tasks.add(new Task(Integer.parseInt(fields[1]), fields[4],
                    LocalDateTime.parse(fields[2]), Integer.parseInt(fields[3])));
        } else if (line.startsWith("COMMITMENT|")) {
            // @@author Darren50
            if (supportsSkippedDates) {
                String[] fields = splitFields(line, 9);
                commitments.add(new Commitment(Integer.parseInt(fields[1]), fields[8],
                        DayOfWeek.valueOf(fields[2]), LocalTime.parse(fields[3]), Integer.parseInt(fields[4]),
                        parseOptionalDate(fields[5]), parseOptionalDate(fields[6]), parseSkippedDates(fields[7])));
            } else if (supportsDateRanges) {
                String[] fields = splitFields(line, 8);
                LocalDate first = parseOptionalDate(fields[5]);
                LocalDate last = parseOptionalDate(fields[6]);
                commitments.add(new Commitment(Integer.parseInt(fields[1]), fields[7],
                        DayOfWeek.valueOf(fields[2]), LocalTime.parse(fields[3]), Integer.parseInt(fields[4]),
                        first, last));
            } else {
                String[] fields = splitFields(line, 6);
                commitments.add(new Commitment(Integer.parseInt(fields[1]), fields[5], DayOfWeek.valueOf(fields[2]),
                        LocalTime.parse(fields[3]), Integer.parseInt(fields[4])));
            }
            // @@author ernestgoh-god
        } else {
            throw new StorageException("Unknown or empty saved record.");
        }
    }

    // @@author nickpatt
    /** Decodes recurrence records privately before the complete snapshot validates cross-record invariants. */
    private void decodeRecurrence(String line, List<Task> tasks, List<RecurringTask> recurringTasks)
            throws StorageException {
        String[] fields = splitFields(line, 7);
        if (fields[0].equals("OCCURRENCE")) {
            tasks.add(new Task(Integer.parseInt(fields[1]), fields[6], LocalDateTime.parse(fields[2]),
                    Integer.parseInt(fields[3]), Optional.of(new TaskOccurrence(Integer.parseInt(fields[4]),
                            LocalDateTime.parse(fields[5])))));
        } else {
            recurringTasks.add(new RecurringTask(Integer.parseInt(fields[1]), fields[6],
                    LocalDateTime.parse(fields[2]), Optional.ofNullable(parseOptionalDate(fields[4])),
                    Integer.parseInt(fields[3]), fields[5].equals("-") ? Optional.empty()
                            : Optional.of(LocalDateTime.parse(fields[5]))));
        }
    }
    // @@author

    // @@author Darren50
    /** Decodes the version 3 dash sentinel used for an unbounded endpoint. */
    private LocalDate parseOptionalDate(String token) {
        return token.equals("-") ? null : LocalDate.parse(token);
    }

    /** Encodes skipped dates deterministically, using a dash when the series has no exceptions. */
    private String encodeSkippedDates(Set<LocalDate> skippedDates) {
        if (skippedDates.isEmpty()) {
            return "-";
        }
        return skippedDates.stream().sorted().map(LocalDate::toString).collect(Collectors.joining(","));
    }

    /** Decodes unique comma-separated skipped dates from a version 4 commitment record. */
    private Set<LocalDate> parseSkippedDates(String token) throws StorageException {
        if (token.equals("-")) {
            return Set.of();
        }
        String[] dateTokens = token.split(",", -1);
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (String dateToken : dateTokens) {
            if (dateToken.isEmpty() || !dates.add(LocalDate.parse(dateToken))) {
                throw new StorageException("Invalid or duplicate skipped commitment date.");
            }
        }
        return Set.copyOf(dates);
    }
    // @@author ernestgoh-god

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
