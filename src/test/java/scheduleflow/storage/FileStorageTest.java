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
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;

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

    private void assertOnlyTargetRemains(Path target) throws Exception {
        try (var files = Files.list(target.getParent())) {
            assertEquals(List.of(target), files.toList());
        }
    }
}
