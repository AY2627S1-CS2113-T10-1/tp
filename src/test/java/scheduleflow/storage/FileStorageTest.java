package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.task.TaskService;

/**
 * Demonstrates saving and restoring real files in isolated temporary folders.
 */
class FileStorageTest {
    @TempDir
    private Path directory;

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
