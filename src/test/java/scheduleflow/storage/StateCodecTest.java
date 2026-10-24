package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;

// @@author ernestgoh-god
/**
 * Checks text conversion independently of files and console integration.
 */
class StateCodecTest {
    private final StateCodec codec = new StateCodec();

    // @@author nickpatt
    @Test
    void encodeAndDecode_recurrence_preservesNamesCursorsProvenanceAndWidestCommitments() throws Exception {
        LocalDateTime first = LocalDateTime.of(2026, 10, 26, 18, 0);
        RecurringTask recurring = new RecurringTask(2, "复习 | café", first,
                Optional.of(first.toLocalDate().plusWeeks(3)), 60, Optional.of(first.plusWeeks(1)));
        Task occurrence = new Task(4, "复习 | moved", first.plusDays(1), 30,
                Optional.of(new TaskOccurrence(2, first)));
        Commitment weekly = new Commitment(1, "Weekly | café", DayOfWeek.MONDAY, LocalTime.NOON, 30,
                null, null, Set.of(first.toLocalDate()));
        Commitment once = new Commitment(2, "Meeting", DayOfWeek.FRIDAY, LocalTime.NOON, 60,
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30));
        Snapshot state = new Snapshot(List.of(occurrence, new Task(5, "Ordinary", first, 30)),
                List.of(weekly, once), 8, 7, Snapshot.empty().studyHours().withWindow("monday",
                        new StudyWindow(LocalTime.of(9, 0), LocalTime.of(20, 0))), List.of(recurring), 6);
        String encoded = codec.encode(state);
        assertEquals("SCHEDULEFLOW|5\nNEXT|8|7|6\n"
                + "OCCURRENCE|4|2026-10-27T18:00|30|2|2026-10-26T18:00|复习 | moved\n"
                + "TASK|5|2026-10-26T18:00|30|Ordinary\n"
                + "RECURRING|2|2026-10-26T18:00|60|2026-11-16|2026-11-02T18:00|复习 | café\n"
                + "COMMITMENT|1|MONDAY|12:00|30|-|-|2026-10-26|Weekly | café\n"
                + "COMMITMENT|2|FRIDAY|12:00|60|2026-10-30|2026-10-30|-|Meeting\n"
                + "HOURS|monday|09:00|20:00\n", encoded);
        assertEquals(state, codec.decode(encoded));
        Snapshot deletedTemplate = new Snapshot(state.tasks(), state.commitments(), 8, 7,
                state.studyHours(), List.of(), 6);
        assertEquals(deletedTemplate, codec.decode(codec.encode(deletedTemplate)));
        Snapshot deletedAll = new Snapshot(List.of(), List.of(), 8, 7, state.studyHours(), List.of(), 6);
        assertEquals(deletedAll, codec.decode(codec.encode(deletedAll)));
        assertEquals("SCHEDULEFLOW|5\nNEXT|8|7|6\n", codec.encode(new Snapshot(List.of(), List.of(),
                8, 7, Snapshot.empty().studyHours(), List.of(), 6)));
        Snapshot unprocessed = new Snapshot(List.of(), List.of(), 1, 1, Snapshot.empty().studyHours(),
                List.of(new RecurringTask(1, "Weekly", first, Optional.empty(), 30, Optional.empty())), 2);
        assertEquals(unprocessed, codec.decode(codec.encode(unprocessed)));
    }

    @Test
    void decode_recurrence_rejectsMalformedDuplicateAndInconsistentRecords() {
        String recurring = "RECURRING|1|2026-10-26T18:00|30|-|2026-11-02T18:00|Weekly";
        String occurrence = "OCCURRENCE|1|2026-10-26T18:00|30|1|2026-10-26T18:00|Work";
        for (String rows : List.of(recurring + "\n" + recurring,
                occurrence + "\n" + occurrence,
                occurrence + "\nOCCURRENCE|2|2026-10-27T18:00|30|1|2026-10-26T18:00|Duplicate provenance",
                occurrence + "\nTASK|1|2026-10-26T18:00|30|Duplicate",
                "RECURRING|1|2026-10-26T18:00|45|-|-|Weekly",
                "RECURRING|1|2026-10-26T18:00|30|2026-10-25|-|Weekly",
                "RECURRING|1|2026-10-26T18:00|30|-|2026-10-27T18:00|Weekly",
                "RECURRING|3|2026-10-26T18:00|30|-|-|Weekly",
                "RECURRING|1|2026-10-26T18:00|30|-|-",
                "OCCURRENCE|1|2026-10-26T18:00|30|0|2026-10-26T18:00|Work",
                "OCCURRENCE|1|2026-10-26T18:00|30|3|2026-10-26T18:00|Work",
                "OCCURRENCE|1|2026-10-26T18:00|30|1|bad|Work",
                recurring + "\nOCCURRENCE|1|2026-11-09T18:00|30|1|2026-11-09T18:00|Beyond cursor",
                "RECURRING|1|2026-10-26T18:00|30|-|-|Weekly\n" + occurrence,
                recurring + "\nOCCURRENCE|1|2026-10-27T18:00|30|1|2026-10-27T18:00|Work")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|5\nNEXT|3|1|3\n" + rows), rows);
        }
        for (String counters : List.of("NEXT|1|1", "NEXT|1|1|0", "NEXT|1|1|1|extra")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|5\n" + counters));
        }
        for (int version = 1; version <= 4; version++) {
            String header = "SCHEDULEFLOW|" + version + "\nNEXT|3|1\n";
            assertThrows(StorageException.class, () -> codec.decode(header + recurring));
            assertThrows(StorageException.class, () -> codec.decode(header + occurrence));
        }
    }
    // @@author ernestgoh-god

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

    @Test
    void encodeAndDecode_oneTimeCommitments_reuseDateRangeFormatsAndRejectNeverOccurringRows()
            throws StorageException {
        LocalDate friday = LocalDate.of(2026, 10, 30);
        Commitment meeting = new Commitment(1, "Project | meeting", DayOfWeek.FRIDAY, LocalTime.of(14, 0), 60,
                friday, friday);
        Commitment weekly = new Commitment(2, "Weekly", DayOfWeek.TUESDAY, LocalTime.NOON, 30);
        Snapshot oneTimeOnly = new Snapshot(List.of(), List.of(meeting, weekly), 1, 3);
        String versionThree = "SCHEDULEFLOW|3\nNEXT|1|3\n"
                + "COMMITMENT|1|FRIDAY|14:00|60|2026-10-30|2026-10-30|Project | meeting\n"
                + "COMMITMENT|2|TUESDAY|12:00|30|-|-|Weekly\n";
        assertEquals(versionThree, codec.encode(oneTimeOnly));
        assertEquals(oneTimeOnly, codec.decode(versionThree));

        Commitment skipped = new Commitment(3, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), Set.of(LocalDate.of(2026, 10, 19)));
        Snapshot withSkips = new Snapshot(List.of(), List.of(meeting, skipped), 1, 4);
        String versionFour = codec.encode(withSkips);
        assertEquals("SCHEDULEFLOW|4\nNEXT|1|4\n"
                + "COMMITMENT|1|FRIDAY|14:00|60|2026-10-30|2026-10-30|-|Project | meeting\n"
                + "COMMITMENT|3|MONDAY|10:00|60|2026-10-19|2026-10-26|2026-10-19|Lecture\n", versionFour);
        assertEquals(withSkips, codec.decode(versionFour));

        for (String row : List.of("COMMITMENT|1|FRIDAY|14:00|60|2026-10-30|2026-10-30|2026-10-30|Meeting",
                "COMMITMENT|1|MONDAY|14:00|60|2026-10-30|2026-10-30|-|Meeting")) {
            assertThrows(StorageException.class, () -> codec.decode("SCHEDULEFLOW|4\nNEXT|1|2\n" + row + "\n"));
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
