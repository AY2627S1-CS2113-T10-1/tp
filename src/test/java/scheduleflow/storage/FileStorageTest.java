package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.TimeRules;
import scheduleflow.task.TaskService;

// @@author ernestgoh-god
/**
 * Demonstrates saving and restoring real files in isolated temporary folders.
 */
@Isolated
class FileStorageTest {
    @TempDir
    private Path directory;

    // @@author nickpatt
    @Test
    void saveAndLoad_recurrence_preservesBytesAndDoesNotRecreateDeletedTemplates() throws Exception {
        LocalDateTime first = LocalDateTime.of(2026, 10, 26, 18, 0);
        RecurringTask recurring = new RecurringTask(1, "Weekly | café", first, Optional.empty(), 60,
                Optional.of(first.plusWeeks(2)));
        Task occurrence = new Task(1, "Work | 复习", first, 30,
                Optional.of(new TaskOccurrence(1, first)));
        Snapshot state = new Snapshot(List.of(occurrence), List.of(), 5, 4,
                Snapshot.empty().studyHours(), List.of(recurring), 3);
        Path file = directory.resolve("recurrence.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        storage.save(state);
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(state, new FileStorage(file, new StateCodec()).load());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Snapshot deleted = new Snapshot(state.tasks(), List.of(), 5, 4, state.studyHours(), List.of(), 3);
        storage.save(deleted);
        bytes = Files.readAllBytes(file);
        assertEquals(deleted, new FileStorage(file, new StateCodec()).load());
        assertEquals(deleted, storage.load());
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void saveAndLoad_occurrenceLifecycle_preservesRecurrenceAndPeerTaskMetadata() throws Exception {
        LocalDateTime first = LocalDateTime.of(2026, 10, 26, 18, 0);
        Task occurrence = new Task(1, "Work|kept", Optional.empty(), 45, TaskPriority.HIGH,
                Optional.of(new TaskOccurrence(1, first)));
        RecurringTask recurring = new RecurringTask(1, "Weekly", first, Optional.empty(), 60, Optional.of(first));
        Snapshot source = new Snapshot(List.of(occurrence), List.of(), 5, 4,
                Snapshot.empty().studyHours().withWindow("monday",
                        new StudyWindow(LocalTime.of(9, 0), LocalTime.of(20, 0))), List.of(recurring), 3);
        TaskService tasks = new TaskService();
        Snapshot progressed = tasks.recordWork(source, 1, 14);
        Snapshot completed = tasks.complete(progressed, 1);
        Snapshot reopened = tasks.reopen(completed, 1, 17);
        Snapshot revised = tasks.reviseRemaining(reopened, 1, 19);
        Snapshot session = tasks.recordSessionProgress(revised, 1, 5, 50);
        Path file = directory.resolve("occurrence-lifecycle.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        for (Snapshot candidate : List.of(progressed, completed, reopened, revised, session)) {
            Task task = candidate.tasks().getFirst();
            assertEquals(occurrence.occurrence(), task.occurrence());
            assertEquals(occurrence.deadline(), task.deadline());
            assertEquals(occurrence.priority(), task.priority());
            assertEquals(source.recurringTasks(), candidate.recurringTasks());
            assertEquals(source.nextRecurringTaskId(), candidate.nextRecurringTaskId());
            assertEquals(source.nextTaskId(), candidate.nextTaskId());
            assertEquals(source.nextCommitmentId(), candidate.nextCommitmentId());
            assertEquals(source.studyHours(), candidate.studyHours());
            storage.save(candidate);
            byte[] bytes = Files.readAllBytes(file);
            assertEquals(candidate, new FileStorage(file, new StateCodec()).load());
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
        assertEquals(List.of(31, 0, 17, 19, 17), List.of(progressed, completed, reopened, revised, session)
                .stream().map(candidate -> candidate.tasks().getFirst().remainingMinutes()).toList());
        assertEquals(45, source.tasks().getFirst().remainingMinutes());
    }

    @Test
    void saveAndLoad_lifecycleCandidates_preservesExactStateAndReadOnlyMigration() throws Exception {
        Path file = directory.resolve("lifecycle.txt");
        String legacy = "SCHEDULEFLOW|1\r\nNEXT|4|2\r\nTASK|3|2000-01-01T09:00|60|Old|task\r\n"
                + "COMMITMENT|1|MONDAY|10:00|30|Class";
        Files.writeString(file, legacy);
        FileStorage storage = new FileStorage(file, new StateCodec());
        Snapshot source = storage.load();
        assertEquals(legacy, Files.readString(file));
        source = source.withStudyHours(source.studyHours().withWindow("monday",
                new StudyWindow(LocalTime.of(9, 0), LocalTime.of(11, 0))));
        TaskService tasks = new TaskService();
        Snapshot progressed = tasks.recordWork(source, 3, 15);
        Snapshot mixed = tasks.addWithoutDeadline(progressed, "Undated|name", 45);
        Snapshot completed = tasks.complete(mixed, 3);
        for (Snapshot candidate : List.of(progressed, mixed, completed, tasks.reopen(completed, 3, 1))) {
            storage.save(candidate);
            byte[] bytes = Files.readAllBytes(file);
            assertEquals(candidate, new FileStorage(file, new StateCodec()).load());
            assertArrayEquals(bytes, Files.readAllBytes(file));
            assertTrue(Files.readString(file).startsWith("SCHEDULEFLOW|2\n"));
        }
        assertEquals(60, source.tasks().getFirst().remainingMinutes());
        assertEquals(4, source.nextTaskId());
        assertEquals(5, completed.nextTaskId());
        assertEquals(2, completed.nextCommitmentId());
    }
    // @@author ernestgoh-god

    @Test
    void savePlan_restartRestoresFrozenResultAndMutationRemovesIt() throws Exception {
        Path file = directory.resolve("data/scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 7, 0);
        Plan empty = new Plan(now, TimeRules.horizonEnd(now), Snapshot.empty(), List.of(), List.of());

        assertEquals(Optional.empty(), storage.loadPlan(Snapshot.empty()));
        storage.savePlan(empty);
        assertEquals(Snapshot.empty(), new FileStorage(file, new StateCodec()).load());
        assertEquals(Optional.of(empty), new FileStorage(file, new StateCodec()).loadPlan(Snapshot.empty()));
        assertTrue(Files.readString(file).startsWith("SCHEDULEFLOW|6\n"));

        Snapshot changed = new Snapshot(List.of(new Task(1, "Draft", now.plusDays(1), 30)), List.of(), 2, 1);
        storage.save(changed);
        assertEquals(Optional.empty(), storage.loadPlan(changed));
        assertEquals(changed, storage.load());
    }

    @Test
    void savePlan_candidateSourceAndPlan_replaceTogetherAndRejectMismatchedLoad() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 7, 0);
        Snapshot source = new Snapshot(List.of(new Task(1, "Draft", now.plusDays(1), 30)), List.of(), 2, 1);
        storage.save(source);
        Snapshot candidate = new TaskService().recordWork(source, 1, 15);
        Plan plan = new EarliestDeadlinePlanner().generate(candidate, now);

        storage.savePlan(plan);

        FileStorage restarted = new FileStorage(file, new StateCodec());
        byte[] saved = Files.readAllBytes(file);
        assertEquals(candidate, restarted.load());
        assertEquals(Optional.of(plan), restarted.loadPlan(candidate));
        assertThrows(StorageException.class, () -> restarted.loadPlan(source));
        assertArrayEquals(saved, Files.readAllBytes(file));
    }

    @Test
    void savePlan_failedReplacement_preservesExistingTargetAndCleansTemporaryFile() throws Exception {
        Path file = directory.resolve("occupied");
        Files.createDirectory(file);
        Path retained = file.resolve("retained.txt");
        Files.writeString(retained, "keep");
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 7, 0);
        Plan plan = new Plan(now, TimeRules.horizonEnd(now), Snapshot.empty(), List.of(), List.of());

        assertThrows(StorageException.class, () -> new FileStorage(file, new StateCodec()).savePlan(plan));

        assertEquals("keep", Files.readString(retained));
        try (var children = Files.list(directory)) {
            assertEquals(List.of(file), children.toList());
        }
    }

    @Test
    void constructor_nullDependencies_rejectsWithoutCreatingData() {
        Path file = directory.resolve("data/scheduleflow.txt");
        assertEquals("file", assertThrows(NullPointerException.class,
                () -> new FileStorage(null, new StateCodec())).getMessage());
        assertEquals("codec", assertThrows(NullPointerException.class,
                () -> new FileStorage(file, null)).getMessage());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void save_nullSnapshot_preservesExistingBytesAndMissingDirectories() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        Files.writeString(file, "SCHEDULEFLOW|1\nNEXT|3|2\n");
        byte[] original = Files.readAllBytes(file);
        FileStorage storage = new FileStorage(file, new StateCodec());
        assertEquals("state", assertThrows(NullPointerException.class, () -> storage.save(null)).getMessage());
        assertArrayEquals(original, Files.readAllBytes(file));
        assertOnlyTargetRemains(file);

        Path missing = directory.resolve("missing/scheduleflow.txt");
        assertThrows(NullPointerException.class, () -> new FileStorage(missing, new StateCodec()).save(null));
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    void saveAndLoad_diagnostics_recordsStagesWithoutStoredValues() throws Throwable {
        Path file = directory.resolve("private-file-name.txt");
        Snapshot snapshot = new Snapshot(List.of(
                new Task(1, "Private task name", LocalDateTime.of(2000, 1, 1, 9, 0), 30)), List.of(), 2, 1);
        List<LogRecord> records = captureLogs(() -> {
            FileStorage storage = new FileStorage(file, new StateCodec());
            storage.save(snapshot);
            assertEquals(snapshot, new FileStorage(file, new StateCodec()).load());
        });
        List<String> stages = records.stream().map(LogRecord::getMessage).toList();
        assertTrue(stages.containsAll(List.of("Starting snapshot save", "Starting snapshot encoding",
                "Snapshot encoding completed", "Preparing save directory", "Writing temporary saved data",
                "Replacing saved data atomically", "Snapshot save completed", "Starting saved data load",
                "Saved data read; validating snapshot", "Starting saved data decoding",
                "Saved records decoded; validating snapshot invariants", "Saved data decoding completed",
                "Saved data load completed")));
        assertTrue(stages.indexOf("Snapshot encoding completed") < stages.indexOf("Writing temporary saved data"));
        assertTrue(stages.indexOf("Replacing saved data atomically") < stages.indexOf("Snapshot save completed"));
        assertTrue(stages.indexOf("Saved data decoding completed") < stages.indexOf("Saved data load completed"));
        for (LogRecord record : records) {
            assertEquals(Level.FINE, record.getLevel());
            assertFalse(record.getMessage().contains("Private task name"));
            assertFalse(record.getMessage().contains("private-file-name"));
            assertNull(record.getParameters());
            assertNull(record.getThrown());
        }
    }

    @Test
    void load_diagnostics_distinguishesMissingInvalidAndUnreadableData() throws Throwable {
        Path file = directory.resolve("scheduleflow.txt");
        List<LogRecord> absent = captureLogs(() ->
                assertEquals(Snapshot.empty(), new FileStorage(file, new StateCodec()).load()));
        assertTrue(absent.stream().anyMatch(record ->
                record.getMessage().equals("Saved data absent; returning empty snapshot")));
        for (String text : List.of("SCHEDULEFLOW|99\nNEXT|1|1\n",
                "SCHEDULEFLOW|1\nNEXT|2|1\nTASK|private-invalid-id|2000-01-01T09:00|30|Private\n")) {
            Files.writeString(file, text);
            List<LogRecord> invalid = captureLogs(() ->
                    assertThrows(StorageException.class, () -> new FileStorage(file, new StateCodec()).load()));
            assertTrue(invalid.stream().anyMatch(record ->
                    record.getMessage().equals("Saved data load rejected; invalid contents")));
            assertFalse(invalid.stream().anyMatch(record -> record.getMessage().contains("completed")));
            for (LogRecord record : invalid) {
                assertFalse(record.getMessage().contains("private-invalid-id"));
                assertNull(record.getThrown());
                assertNull(record.getParameters());
            }
            assertEquals(text, Files.readString(file));
        }
        List<LogRecord> unreadable = captureLogs(() -> {
            StorageException failure = assertThrows(StorageException.class,
                    () -> new FileStorage(directory, new StateCodec()).load());
            assertTrue(failure.getCause() instanceof IOException);
        });
        assertTrue(unreadable.stream().anyMatch(record ->
                record.getMessage().equals("Saved data load failed during file access")));
        assertFalse(unreadable.stream().anyMatch(record -> record.getMessage().equals("Saved data load completed")));
    }

    @Test
    void save_failedReplacement_logsCleanupWithoutSuccess() throws Throwable {
        Path target = Files.createDirectory(directory.resolve("scheduleflow.txt"));
        Path sentinel = target.resolve("keep.txt");
        Files.writeString(sentinel, "original content");
        List<LogRecord> records = captureLogs(() -> {
            StorageException failure = assertThrows(StorageException.class,
                    () -> new FileStorage(target, new StateCodec()).save(Snapshot.empty()));
            assertTrue(failure.getCause() instanceof IOException);
        });
        List<String> stages = records.stream().map(LogRecord::getMessage).toList();
        assertTrue(stages.contains("Snapshot save failed; attempting temporary file cleanup"));
        assertTrue(stages.contains("Temporary saved data cleanup completed"));
        assertFalse(stages.contains("Snapshot save completed"));
        assertEquals("original content", Files.readString(sentinel));
        assertOnlyTargetRemains(target);
    }

    @Test
    void load_missingFile_returnsEmptyWithoutCreatingDirectories() throws StorageException {
        Path file = directory.resolve("data/scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        assertFalse(Files.exists(file.getParent()));
        assertEquals(Snapshot.empty(), storage.load());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void saveAndLoad_freshStorage_restoresTasksCommitmentsAndCounters() throws Exception {
        Path file = directory.resolve("data/scheduleflow.txt");
        Snapshot original = new Snapshot(
                List.of(new Task(3, "复习 | café", LocalDateTime.of(2000, 1, 2, 9, 0), 60)),
                List.of(new Commitment(2, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 30), 90)), 8, 5);

        new FileStorage(file, new StateCodec()).save(original);

        assertEquals(original, new FileStorage(file, new StateCodec()).load());
        assertEquals(new StateCodec().encode(original), Files.readString(file));
        assertOnlyTargetRemains(file);
    }

    @Test
    void save_existingFile_replacesContentsAndKeepsDeletedIds() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        Files.writeString(file, "SCHEDULEFLOW|1\nNEXT|2|1\nTASK|1|2000-01-01T09:00|30|Old\n");
        Snapshot deleted = new Snapshot(List.of(), List.of(), 2, 1);
        new FileStorage(file, new StateCodec()).save(deleted);
        assertEquals(deleted, new FileStorage(file, new StateCodec()).load());
        assertOnlyTargetRemains(file);
    }

    @Test
    void saveAndLoad_serviceCandidates_preserveChangesAndIndependentCounters() throws Exception {
        Path file = directory.resolve("data/scheduleflow.txt");
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 7, 0);
        TaskService tasks = new TaskService();
        CommitmentService commitments = new CommitmentService();
        Snapshot loaded = saveAndReload(file, tasks.add(Snapshot.empty(), "Old task", now.plusDays(1), 30, now));
        loaded = saveAndReload(file, tasks.add(loaded, "复习 | café", now.plusDays(2), 90, now));
        loaded = saveAndReload(file, commitments.add(loaded, "Old class", DayOfWeek.MONDAY, LocalTime.NOON, 60));
        loaded = saveAndReload(file, commitments.add(loaded, "课程", DayOfWeek.TUESDAY, LocalTime.NOON, 60));
        loaded = saveAndReload(file, tasks.delete(loaded, 1));
        loaded = saveAndReload(file, commitments.delete(loaded, 1));

        assertEquals(List.of(new Task(2, "复习 | café", now.plusDays(2), 90)), loaded.tasks());
        assertEquals(List.of(new Commitment(2, "课程", DayOfWeek.TUESDAY, LocalTime.NOON, 60)),
                loaded.commitments());
        assertEquals(3, loaded.nextTaskId());
        assertEquals(3, loaded.nextCommitmentId());

        loaded = saveAndReload(file, tasks.delete(loaded, 2));
        loaded = saveAndReload(file, commitments.delete(loaded, 2));
        assertEquals(new Snapshot(List.of(), List.of(), 3, 3), loaded);
        loaded = saveAndReload(file, tasks.add(loaded, "Next task", now.plusDays(3), 30, now));
        loaded = saveAndReload(file, commitments.add(loaded, "Next class", DayOfWeek.WEDNESDAY, LocalTime.NOON, 30));
        assertEquals(3, loaded.tasks().getFirst().id());
        assertEquals(3, loaded.commitments().getFirst().id());
        assertEquals(4, loaded.nextTaskId());
        assertEquals(4, loaded.nextCommitmentId());
    }

    @Test
    void load_validOverdueRecords_preservesOriginalBytesAndStoredOrder() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        String original = "SCHEDULEFLOW|1\r\nNEXT|8|5\r\n"
                + "TASK|3|2000-01-02T09:00|90|复习 | café\r\nTASK|1|2000-01-01T09:00|30|Older\r\n"
                + "COMMITMENT|2|TUESDAY|12:00|60|课程";
        Files.writeString(file, original);
        byte[] bytes = Files.readAllBytes(file);
        Snapshot expected = new Snapshot(List.of(
                new Task(3, "复习 | café", LocalDateTime.of(2000, 1, 2, 9, 0), 90),
                new Task(1, "Older", LocalDateTime.of(2000, 1, 1, 9, 0), 30)),
                List.of(new Commitment(2, "课程", DayOfWeek.TUESDAY, LocalTime.NOON, 60)), 8, 5);

        assertEquals(expected, new FileStorage(file, new StateCodec()).load());
        assertEquals(expected, new FileStorage(file, new StateCodec()).load());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertOnlyTargetRemains(file);
    }

    @Test
    void load_corruptTextOrUtf8_preservesFile() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        Files.writeString(file, "SCHEDULEFLOW|1\nNEXT|2|1\nTASK|1|2000-01-01T09:00|30|Valid\nBROKEN\n");
        byte[] original = Files.readAllBytes(file);
        assertThrows(StorageException.class, () -> new FileStorage(file, new StateCodec()).load());
        assertArrayEquals(original, Files.readAllBytes(file));

        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        Files.write(file, invalidUtf8);
        assertThrows(StorageException.class, () -> new FileStorage(file, new StateCodec()).load());
        assertArrayEquals(invalidUtf8, Files.readAllBytes(file));
    }

    @Test
    void load_directoryTarget_reportsError() {
        assertThrows(StorageException.class, () -> new FileStorage(directory, new StateCodec()).load());
    }

    @Test
    void save_blockedParent_preservesExistingFile() throws Exception {
        Path parent = directory.resolve("blocked");
        Files.writeString(parent, "keep this file");
        assertThrows(StorageException.class,
                () -> new FileStorage(parent.resolve("scheduleflow.txt"), new StateCodec()).save(Snapshot.empty()));
        assertEquals("keep this file", Files.readString(parent));
    }

    @Test
    void save_failedReplacement_preservesTargetAndRemovesTemporaryFile() throws Exception {
        // A nonempty directory prevents replacement without relying on platform-specific permissions.
        Path target = Files.createDirectory(directory.resolve("scheduleflow.txt"));
        Path sentinel = target.resolve("keep.txt");
        Files.writeString(sentinel, "original content");
        assertThrows(StorageException.class, () -> new FileStorage(target, new StateCodec()).save(Snapshot.empty()));
        assertEquals("original content", Files.readString(sentinel));
        assertOnlyTargetRemains(target);
    }

    /**
     * Captures storage diagnostics in memory and restores the shared namespace even if the action fails.
     * The isolated test class prevents concurrent tests from observing the temporary logger settings.
     *
     * @param action storage operation and assertions to exercise with diagnostics enabled
     * @return emitted storage and codec records in occurrence order
     * @throws Throwable if the test action fails
     */
    private List<LogRecord> captureLogs(Executable action) throws Throwable {
        Logger logger = Logger.getLogger("scheduleflow.storage");
        Level originalLevel = logger.getLevel();
        boolean originalUseParents = logger.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        try {
            handler.setLevel(Level.FINE);
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            action.execute();
            return records;
        } finally {
            logger.removeHandler(handler);
            handler.close();
            logger.setLevel(originalLevel);
            logger.setUseParentHandlers(originalUseParents);
        }
    }

    /**
     * Saves one service candidate and restores it through a fresh reader without console dependencies.
     */
    private Snapshot saveAndReload(Path file, Snapshot candidate) throws Exception {
        new FileStorage(file, new StateCodec()).save(candidate);
        Snapshot loaded = new FileStorage(file, new StateCodec()).load();
        assertEquals(candidate, loaded);
        assertOnlyTargetRemains(file);
        return loaded;
    }

    /**
     * Checks that successful writes and handled failures leave no temporary sibling files.
     */
    private void assertOnlyTargetRemains(Path target) throws Exception {
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList());
        }
    }
}
// @@author
