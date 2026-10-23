package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;

// @@author ernestgoh-god
/**
 * Checks text conversion independently of files and console integration.
 */
class StateCodecTest {
    private final StateCodec codec = new StateCodec();

    // @@author Darren50
    @Test
    void encodeAndDecode_boundedCommitments_usesVersionThreeAndRetainsLegacyRows() throws StorageException {
        Commitment bounded = new Commitment(1, "Semester | lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 11, 16));
        Commitment unbounded = new Commitment(2, "Weekly", DayOfWeek.TUESDAY, LocalTime.of(12, 0), 30);
        Snapshot initial = new Snapshot(List.of(), List.of(bounded, unbounded), 1, 3);
        Snapshot state = initial.withStudyHours(initial.studyHours()
                .withWindow("monday", new StudyWindow(LocalTime.of(8, 0), LocalTime.of(20, 0))));
        String expected = "SCHEDULEFLOW|3\nNEXT|1|3\n"
                + "COMMITMENT|1|MONDAY|10:00|60|2026-08-10|2026-11-16|Semester | lecture\n"
                + "COMMITMENT|2|TUESDAY|12:00|30|-|-|Weekly\n"
                + "HOURS|monday|08:00|20:00\n";
        assertEquals(expected, codec.encode(state));
        assertEquals(state, codec.decode(expected));
        assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|3\nNEXT|1|2\n"
                + "COMMITMENT|1|MONDAY|10:00|60|2026-08-10|-|Lecture\n"));
        assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|3\nNEXT|1|2\n"
                + "COMMITMENT|1|MONDAY|10:00|60|2026-11-16|2026-08-10|Lecture\n"));
    }

    @Test
    void encodeAndDecode_skippedOccurrences_usesVersionFourAndValidatesDates() throws StorageException {
        LocalDate first = LocalDate.of(2026, 8, 10);
        LocalDate second = first.plusWeeks(1);
        Commitment skipped = new Commitment(1, "Semester | lecture", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), 60, first, second, Set.of(second, first));
        Commitment ordinary = new Commitment(2, "Weekly", DayOfWeek.TUESDAY, LocalTime.NOON, 30);
        Snapshot state = new Snapshot(List.of(), List.of(skipped, ordinary), 1, 3);
        String expected = "SCHEDULEFLOW|4\nNEXT|1|3\n"
                + "COMMITMENT|1|MONDAY|10:00|60|2026-08-10|2026-08-17|2026-08-10,2026-08-17|"
                + "Semester | lecture\n"
                + "COMMITMENT|2|TUESDAY|12:00|30|-|-|-|Weekly\n";
        assertEquals(expected, codec.encode(state));
        assertEquals(state, codec.decode(expected));
        for (String skippedToken : List.of("2026-08-10,2026-08-10", "2026-08-11", "")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|4\nNEXT|1|2\n"
                    + "COMMITMENT|1|MONDAY|10:00|60|2026-08-10|2026-08-17|" + skippedToken
                    + "|Lecture\n"));
        }
    }

    // @@author GabrielYuSP
    @Test
    void encodeAndDecode_hours_preservesSettingsAndLoadsLegacyData() throws StorageException {
        Snapshot empty = Snapshot.empty();
        Snapshot state = empty.withStudyHours(empty.studyHours()
                .withWindow("tuesday", new StudyWindow(LocalTime.of(9, 0), LocalTime.of(22, 0)))
                .withWindow("2026-10-27", new StudyWindow(LocalTime.of(14, 0), LocalTime.of(19, 0))));
        assertEquals("SCHEDULEFLOW|2\nNEXT|1|1\nHOURS|2026-10-27|14:00|19:00\n"
                + "HOURS|tuesday|09:00|22:00\n", codec.encode(state));
        assertEquals(state, codec.decode(codec.encode(state)));
        assertEquals(empty, codec.decode("SCHEDULEFLOW|1\nNEXT|1|1\n"));
        for (String row : List.of("HOURS|tuesday|22:00|09:00", "HOURS|tuesday|09:15|22:00",
                "HOURS|2026-02-30|09:00|22:00", "HOURS|Tuesday|09:00|22:00", "HOURS|tuesday|09:00",
                "HOURS|tuesday|09:00|22:00|extra", "HOURS|tuesday|09:00|22:00\nHOURS|tuesday|10:00|20:00")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|2\nNEXT|1|1\n" + row));
        }
        assertThrows(StorageException.class,
                () -> codec.decode("SCHEDULEFLOW|1\nNEXT|1|1\nHOURS|tuesday|09:00|22:00"));
    }

    // @@author ernestgoh-god
    @Test
    void encode_nullSnapshot_reportsNamedPrecondition() {
        assertEquals("state", assertThrows(NullPointerException.class, () -> codec.encode(null)).getMessage());
    }

    @Test
    void encode_emptySnapshot_writesHeaderAndCounters() {
        assertEquals("SCHEDULEFLOW|1\nNEXT|1|1\n", codec.encode(Snapshot.empty()));
    }

    @Test
    void encodeAndDecode_records_preserveDocumentedFormat() throws StorageException {
        Snapshot snapshot = new Snapshot(
                List.of(new Task(3, "复习 | café", LocalDateTime.of(2000, 1, 2, 9, 0), 60)),
                List.of(new Commitment(2, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 30), 90)), 8, 5);
        String text = "SCHEDULEFLOW|1\nNEXT|8|5\nTASK|3|2000-01-02T09:00|60|复习 | café\n"
                + "COMMITMENT|2|MONDAY|10:30|90|Class\n";
        assertEquals(text, codec.encode(snapshot));
        assertEquals(snapshot, codec.decode(text));
        assertEquals(snapshot, codec.decode(text.replace("\n", "\r\n")));
    }

    @Test
    void decode_deletedRecords_preservesCountersWithoutFinalNewline() throws StorageException {
        Snapshot snapshot = new Snapshot(List.of(), List.of(), Integer.MAX_VALUE, 7);
        assertEquals(snapshot, codec.decode("SCHEDULEFLOW|1\nNEXT|2147483647|7"));
        assertEquals(snapshot, codec.decode(codec.encode(snapshot)));
    }

    @Test
    void decode_badStructure_rejects() {
        for (String text : List.of("", "SCHEDULEFLOW|1", "wrong\nNEXT|1|1", "SCHEDULEFLOW|1\nNEXT|1",
                "SCHEDULEFLOW|1\nNEXT|1|1|extra", "SCHEDULEFLOW|1\nNEXT|0|1",
                "SCHEDULEFLOW|1\nNEXT|2147483648|1", "SCHEDULEFLOW|1\nNEXT|1|1\nUNKNOWN|x")) {
            assertThrows(StorageException.class, () -> codec.decode(text), text);
        }
        assertThrows(StorageException.class, () -> codec.decode(null));
        assertEquals("Unsupported file version: 99", assertThrows(StorageException.class,
                () -> codec.decode("SCHEDULEFLOW|99\nNEXT|1|1\n")).getMessage());
    }

    @Test
    void decode_invalidRecords_rejectsWholeSnapshot() {
        for (String record : List.of("TASK|1|2000-02-30T09:00|30|A", "TASK|1|2000-01-01T09:00|45|A",
                "TASK|1|2000-01-01T09:00|30", "TASK|1|2000-01-01T09:00|30|",
                "TASK|1|2000-01-01T09:00|30|Bad/name", "TASK|1|2000-01-01T09:00|30|Bad\tname",
                "COMMITMENT|1|MON|10:00|30|A", "COMMITMENT|1|MONDAY|10:15|30|A",
                "COMMITMENT|1|MONDAY|23:30|60|A")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|1\nNEXT|3|3\n" + record));
        }
    }

    @Test
    void decode_duplicateIdsBadCountersOrOverlaps_rejects() {
        for (String records : List.of("TASK|3|2000-01-01T09:00|30|A",
                "TASK|1|2000-01-01T09:00|30|A\nTASK|1|2000-01-01T10:00|30|B",
                "COMMITMENT|1|MONDAY|10:00|60|A\nCOMMITMENT|2|MONDAY|10:30|30|B",
                "COMMITMENT|1|MONDAY|10:00|30|A\nCOMMITMENT|1|TUESDAY|10:00|30|B")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|1\nNEXT|3|3\n" + records));
        }
    }
}
// @@author
