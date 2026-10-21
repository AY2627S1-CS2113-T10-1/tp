//@@author Darren50
package scheduleflow.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.UnallocatedReason;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskService;

/**
 * Verifies commitment operations and immutable candidates using fixed data and isolated storage.
 */
@Isolated("Logging checks temporarily configure the commitment service logger.")
class CommitmentServiceTest {
    private final CommitmentService service = new CommitmentService();

    @Test
    void operations_loggingEnabled_recordsStagesWithoutPrivateValues() {
        List<LogRecord> records = captureLogs(() -> {
            Snapshot added = service.add(Snapshot.empty(), "Private lecture", DayOfWeek.MONDAY, LocalTime.NOON, 90);
            assertEquals(added.commitments(), service.list(added));
            assertTrue(service.delete(added, 1).commitments().isEmpty());
        });
        assertEquals(List.of("Starting commitment addition",
                "Commitment fields validated; checking weekly overlaps",
                "Weekly overlaps checked; creating addition candidate",
                "Commitment addition candidate created; not yet saved or published",
                "Starting commitment listing; sorting by weekday, start and ID",
                "Commitment listing completed; source snapshot unchanged",
                "Starting commitment deletion", "Commitment ID resolved; creating deletion candidate",
                "Commitment deletion candidate created; not yet saved or published"),
                records.stream().map(LogRecord::getMessage).toList());
        for (LogRecord record : records) {
            assertEquals(Level.FINE, record.getLevel());
            assertNull(record.getThrown());
            assertNull(record.getParameters());
            assertFalse(record.getMessage().contains("Private lecture"));
        }
    }

    @Test
    void add_rejectedInputs_logsRejectionWithoutCandidateSuccess() {
        Snapshot original = service.add(Snapshot.empty(), "Private lecture", DayOfWeek.MONDAY, LocalTime.NOON, 60);
        Snapshot exhausted = new Snapshot(List.of(), List.of(), 1, Integer.MAX_VALUE);
        List<Runnable> attempts = List.of(
                () -> service.add(original, "Private overlap", DayOfWeek.MONDAY, LocalTime.NOON, 30),
                () -> service.add(original, "Private invalid", DayOfWeek.TUESDAY, LocalTime.NOON, 45),
                () -> service.add(exhausted, "Private exhausted", DayOfWeek.TUESDAY, LocalTime.NOON, 30));
        for (Runnable attempt : attempts) {
            List<LogRecord> records = captureLogs(() -> assertThrows(ValidationException.class, attempt::run));
            List<String> messages = records.stream().map(LogRecord::getMessage).toList();
            assertEquals("Starting commitment addition", messages.getFirst());
            assertEquals("Commitment addition rejected; source snapshot unchanged", messages.getLast());
            assertTrue(messages.stream().noneMatch(message -> message.contains("candidate created")));
            for (LogRecord record : records) {
                assertFalse(record.getMessage().contains("Private"));
                assertNull(record.getThrown());
                assertNull(record.getParameters());
            }
        }
        assertEquals(1, original.commitments().size());
        assertEquals(2, original.nextCommitmentId());
    }

    @Test
    void delete_rejectedIds_logsFailureWithoutCandidateSuccess() {
        for (int id : new int[] {0, -1, 99}) {
            List<LogRecord> records = captureLogs(() ->
                    assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), id)));
            assertEquals(List.of("Starting commitment deletion", id <= 0
                    ? "Commitment deletion rejected; nonpositive ID"
                    : "Commitment deletion rejected; unknown ID"),
                    records.stream().map(LogRecord::getMessage).toList());
        }
    }

    @Test
    void operations_nullSourcesAndConstruction_doNotLogProcessing() {
        List<LogRecord> records = captureLogs(() -> {
            new CommitmentService();
            List<Runnable> operations = List.of(
                    () -> service.add(null, "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30),
                    () -> service.delete(null, 1), () -> service.list(null));
            for (Runnable operation : operations) {
                NullPointerException exception = assertThrows(NullPointerException.class, operation::run);
                assertEquals("state", exception.getMessage());
            }
        });
        assertTrue(records.isEmpty());
    }

    /**
     * Captures only this service's diagnostics and restores shared logger configuration even on failure.
     */
    private List<LogRecord> captureLogs(Runnable action) {
        Logger logger = Logger.getLogger(CommitmentService.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousParents = logger.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        try {
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            action.run();
            return records;
        } finally {
            logger.removeHandler(handler);
            handler.close();
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParents);
        }
    }

    @Test
    void add_savedDurations_preservesEstimatesAndPartialAllocation(@TempDir Path directory) throws StorageException {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Snapshot tasks = new TaskService().add(Snapshot.empty(), "Draft", monday.atTime(10, 0),
                90, monday.atTime(7, 0));
        Snapshot candidate = service.add(tasks, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 30), 90);
        FileStorage storage = new FileStorage(directory.resolve("scheduleflow.txt"), new StateCodec());
        storage.save(candidate);
        Snapshot reloaded = new FileStorage(directory.resolve("scheduleflow.txt"), new StateCodec()).load();

        assertEquals(candidate, reloaded);
        Plan plan = new EarliestDeadlinePlanner().generate(reloaded, monday.atTime(7, 0));
        assertEquals(List.of(new StudySession(1, monday.atTime(8, 0), monday.atTime(8, 30))), plan.sessions());
        assertEquals(1, plan.unallocated().size());
        assertEquals(60, plan.unallocated().getFirst().minutes());
        assertEquals(UnallocatedReason.INSUFFICIENT_CAPACITY, plan.unallocated().getFirst().reason());
        assertEquals(90, reloaded.tasks().getFirst().remainingMinutes());
        assertEquals(90, reloaded.commitments().getFirst().durationMinutes());
        assertEquals(600, reloaded.commitments().getFirst().endMinuteOfDay());
        storage.save(plan.source());
        assertEquals(candidate, storage.load());
    }

    @Test
    void add_reloadedWeeklyDuration_blocksExactIntervalOnSuccessiveMondays(@TempDir Path directory)
            throws StorageException {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Snapshot candidate = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 90);
        FileStorage storage = new FileStorage(directory.resolve("scheduleflow.txt"), new StateCodec());
        storage.save(candidate);
        Snapshot reloaded = storage.load();
        for (LocalDate date : List.of(monday, monday.plusWeeks(1))) {
            Snapshot withTask = new TaskService().add(reloaded, "Draft", date.atTime(10, 0),
                    30, date.atTime(7, 0));
            Plan plan = new EarliestDeadlinePlanner().generate(withTask, date.atTime(7, 0));
            assertTrue(plan.isComplete());
            assertEquals(List.of(new StudySession(1, date.atTime(9, 30), date.atTime(10, 0))), plan.sessions());
            assertEquals(candidate.commitments(), plan.source().commitments());
        }
    }

    @Test
    void delete_existingId_preservesOtherRecordsOrderAndCounters() {
        Commitment first = new Commitment(10, "Lecture", DayOfWeek.FRIDAY, LocalTime.NOON, 60);
        Commitment removed = new Commitment(2, "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 60);
        Commitment last = new Commitment(7, "Tutorial", DayOfWeek.TUESDAY, LocalTime.NOON, 30);
        Task task = new Task(4, "Draft", LocalDate.of(2026, 10, 12).atTime(18, 0), 60);
        Snapshot original = new Snapshot(List.of(task), List.of(first, removed, last), 9, 11);

        Snapshot candidate = service.delete(original, 2);

        assertEquals(new Snapshot(List.of(task), List.of(first, last), 9, 11), candidate);
        assertEquals(new Snapshot(List.of(task), List.of(first, removed, last), 9, 11), original);
        assertThrows(UnsupportedOperationException.class, () -> candidate.commitments().clear());
    }

    @Test
    void delete_unknownOrInvalidId_rejectsWithoutChanges() {
        Snapshot original = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        for (int id : new int[] {2, Integer.MAX_VALUE}) {
            ValidationException exception = assertThrows(ValidationException.class, () -> service.delete(original, id));
            assertEquals("Unknown commitment ID: C" + id, exception.getMessage());
        }
        for (int id : new int[] {0, -1, Integer.MIN_VALUE}) {
            ValidationException exception = assertThrows(ValidationException.class, () -> service.delete(original, id));
            assertEquals("Commitment ID must be positive.", exception.getMessage());
        }
        assertEquals(new Snapshot(List.of(), List.of(new Commitment(1, "Lecture", DayOfWeek.MONDAY,
                LocalTime.NOON, 30)), 1, 2), original);
        assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), 1));
    }

    @Test
    void delete_lastSeries_doesNotReuseIdAndRejectsRepeatedDeletion() {
        Snapshot original = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        Snapshot deleted = service.delete(original, 1);
        assertEquals(new Snapshot(List.of(), List.of(), 1, 2), deleted);
        assertThrows(ValidationException.class, () -> service.delete(deleted, 1));
        Snapshot added = service.add(deleted, "Replacement", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        assertEquals(2, added.commitments().getFirst().id());
        assertEquals(3, added.nextCommitmentId());
    }

    @Test
    void delete_exhaustedCounter_preservesExhaustion() {
        Commitment last = new Commitment(Integer.MAX_VALUE - 1, "Last", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        Snapshot deleted = service.delete(new Snapshot(List.of(), List.of(last), 1, Integer.MAX_VALUE), last.id());
        assertEquals(Integer.MAX_VALUE, deleted.nextCommitmentId());
        assertTrue(deleted.commitments().isEmpty());
        assertThrows(ValidationException.class,
                () -> service.add(deleted, "New", DayOfWeek.MONDAY, LocalTime.NOON, 30));
    }

    @Test
    void delete_discardedCandidate_keepsOriginalSeriesAvailable() {
        Snapshot original = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        Snapshot discarded = service.delete(original, 1);
        assertEquals(discarded, service.delete(original, 1));
        assertEquals(1, original.commitments().size());
        assertEquals(2, original.nextCommitmentId());
    }

    @Test
    void list_unsortedSeries_ordersByWeekdayThenTimeWithoutRenumbering() {
        Commitment sunday = new Commitment(2, "Sunday", DayOfWeek.SUNDAY, LocalTime.MIDNIGHT, 30);
        Commitment late = new Commitment(7, "Late", DayOfWeek.MONDAY, LocalTime.of(23, 30), 30);
        Commitment early = new Commitment(10, "Early", DayOfWeek.MONDAY, LocalTime.of(9, 0), 30);
        Commitment tuesday = new Commitment(1, "Tuesday", DayOfWeek.TUESDAY, LocalTime.MIDNIGHT, 30);
        Snapshot original = new Snapshot(List.of(), List.of(sunday, late, early, tuesday), 1, 11);
        List<Commitment> listed = service.list(original);
        assertEquals(List.of(early, late, tuesday, sunday), listed);
        assertEquals(List.of(sunday, late, early, tuesday), original.commitments());
        assertThrows(UnsupportedOperationException.class, () -> listed.removeFirst());
        assertEquals(List.of(early, late, tuesday, sunday), service.list(original));
    }

    @Test
    void list_emptySnapshot_returnsImmutableEmptyList() {
        List<Commitment> listed = service.list(Snapshot.empty());
        assertTrue(listed.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> listed.add(new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30)));
    }

    @Test
    void operations_nullSnapshot_rejectBeforeProcessing() {
        assertThrows(NullPointerException.class, () -> service.delete(null, 1));
        assertThrows(NullPointerException.class, () -> service.list(null));
    }

    @Test
    void delete_savedAndReloaded_preservesRemovalAndNextId(@TempDir Path directory) throws StorageException {
        Path file = directory.resolve("scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        Snapshot original = service.add(Snapshot.empty(), "Late", DayOfWeek.MONDAY, LocalTime.of(23, 30), 30);
        storage.save(original);
        Snapshot deleted = service.delete(storage.load(), 1);
        storage.save(deleted);
        Snapshot reloaded = new FileStorage(file, new StateCodec()).load();
        assertEquals(deleted, reloaded);
        assertTrue(service.list(reloaded).isEmpty());
        Snapshot added = service.add(reloaded, "New", DayOfWeek.MONDAY, LocalTime.of(23, 30), 30);
        assertEquals(2, added.commitments().getFirst().id());
    }

    @Test
    void delete_weeklySeries_freesTimeOnBothMondaysWhenReplanned() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Task first = new Task(1, "First", monday.atTime(9, 0), 60);
        Task next = new Task(2, "Next", monday.plusWeeks(1).atTime(9, 0), 60);
        Commitment lecture = new Commitment(1, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60);
        Snapshot original = new Snapshot(List.of(first, next), List.of(lecture), 3, 2);
        Snapshot deleted = service.delete(original, 1);
        for (LocalDate date : List.of(monday, monday.plusWeeks(1))) {
            Plan before = new EarliestDeadlinePlanner().generate(original, date.atTime(7, 0));
            Plan after = new EarliestDeadlinePlanner().generate(deleted, date.atTime(7, 0));
            assertTrue(before.sessions().stream().noneMatch(session -> session.start().equals(date.atTime(8, 0))));
            assertEquals(date.atTime(8, 0), after.sessions().getFirst().start());
        }
        assertEquals(List.of(lecture), original.commitments());
    }

    @Test
    void add_emptySnapshot_assignsFirstIdAndNormalizesName() {
        Snapshot original = Snapshot.empty();
        Snapshot candidate = service.add(original, "  CS2113 项目  meeting  ",
                DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);

        assertEquals(List.of(new Commitment(1, "CS2113 项目  meeting", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), 120)), candidate.commitments());
        assertEquals(2, candidate.nextCommitmentId());
        assertEquals(1, candidate.nextTaskId());
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_existingSnapshot_preservesRecordsAndUsesStoredCounter() {
        Task task = new Task(4, "Draft", LocalDate.of(2026, 10, 12).atTime(18, 0), 60);
        Commitment existing = new Commitment(2, "Lecture", DayOfWeek.FRIDAY, LocalTime.NOON, 60);
        Snapshot original = new Snapshot(List.of(task), List.of(existing), 9, 10);
        Snapshot candidate = service.add(original, "Tutorial", DayOfWeek.MONDAY, LocalTime.of(9, 0), 30);

        assertEquals(original.tasks(), candidate.tasks());
        assertEquals(9, candidate.nextTaskId());
        assertEquals(List.of(existing, new Commitment(10, "Tutorial", DayOfWeek.MONDAY,
                LocalTime.of(9, 0), 30)), candidate.commitments());
        assertEquals(11, candidate.nextCommitmentId());
        assertEquals(new Snapshot(List.of(task), List.of(existing), 9, 10), original);
        assertThrows(UnsupportedOperationException.class, () -> candidate.commitments().clear());
    }

    @Test
    void add_multipleOverlaps_reportsEveryConflictWithoutChangingSource() {
        Commitment late = new Commitment(2, "Seminar", DayOfWeek.MONDAY, LocalTime.of(11, 0), 60);
        Commitment early = new Commitment(10, "Lecture", DayOfWeek.MONDAY, LocalTime.of(9, 0), 60);
        Commitment adjacent = new Commitment(3, "Lunch", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        Commitment otherDay = new Commitment(4, "Tuesday", DayOfWeek.TUESDAY, LocalTime.of(9, 0), 60);
        List<Commitment> stored = List.of(late, adjacent, otherDay, early);
        Snapshot original = new Snapshot(List.of(), stored, 1, 11);
        ValidationException exception = assertThrows(ValidationException.class,
                () -> service.add(original, "Workshop", DayOfWeek.MONDAY, LocalTime.of(9, 0), 180));
        assertEquals("Commitment overlaps C10: Lecture; C2: Seminar", exception.getMessage());
        assertEquals(new Snapshot(List.of(), stored, 1, 11), original);
        Snapshot retry = service.add(original, "Gap", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60);
        assertEquals(11, retry.commitments().getLast().id());
        assertEquals(stored, original.commitments());
    }

    @Test
    void add_fullDayOverlap_reportsAllSameDayConflictsIncludingMidnight() {
        Commitment late = new Commitment(1, "Class", DayOfWeek.SUNDAY, LocalTime.of(23, 30), 30);
        Commitment early = new Commitment(2, "Class", DayOfWeek.SUNDAY, LocalTime.MIDNIGHT, 30);
        Snapshot original = new Snapshot(List.of(), List.of(late, early), 1, 3);
        ValidationException exception = assertThrows(ValidationException.class,
                () -> service.add(original, "Away", DayOfWeek.SUNDAY, LocalTime.MIDNIGHT, 1440));
        assertEquals("Commitment overlaps C2: Class; C1: Class", exception.getMessage());
    }

    @Test
    void add_sameDayOverlap_rejectsAndIdentifiesExistingCommitment() {
        Commitment lecture = new Commitment(7, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        Snapshot original = new Snapshot(List.of(), List.of(lecture), 1, 8);
        for (int[] interval : new int[][] {{9, 90}, {11, 120}, {10, 30}, {9, 240}, {10, 120}}) {
            ValidationException exception = assertThrows(ValidationException.class,
                    () -> service.add(original, "Clash", DayOfWeek.MONDAY,
                            LocalTime.of(interval[0], 0), interval[1]));
            assertEquals("Commitment overlaps C7: Lecture", exception.getMessage());
        }
        assertEquals(new Snapshot(List.of(), List.of(lecture), 1, 8), original);
        Snapshot retry = service.add(original, "Later", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        assertEquals(8, retry.commitments().getLast().id());
    }

    @Test
    void add_adjacentIntervals_acceptsBothEndpoints() {
        Snapshot original = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        Snapshot before = service.add(original, "Before", DayOfWeek.MONDAY, LocalTime.of(9, 30), 30);
        Snapshot after = service.add(before, "After", DayOfWeek.MONDAY, LocalTime.NOON, 60);
        assertEquals(3, after.commitments().size());
        assertEquals(4, after.nextCommitmentId());
    }

    @Test
    void add_sameTimeAndNameOnDifferentDays_acceptsAllWeekdays() {
        Snapshot candidate = Snapshot.empty();
        for (DayOfWeek day : DayOfWeek.values()) {
            candidate = service.add(candidate, "Lecture", day, LocalTime.NOON, 60);
        }
        assertEquals(7, candidate.commitments().size());
        assertEquals(8, candidate.nextCommitmentId());
    }

    @Test
    void add_duplicateNameWithoutOverlap_acceptsSeparateSeries() {
        Snapshot original = service.add(Snapshot.empty(), "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        Snapshot candidate = service.add(original, "Lecture", DayOfWeek.MONDAY, LocalTime.of(14, 0), 30);
        assertEquals(List.of(1, 2), candidate.commitments().stream().map(Commitment::id).toList());
    }

    @Test
    void add_midnightAndFullDay_preservesSameDayEndpoints() {
        Snapshot late = service.add(Snapshot.empty(), "Late", DayOfWeek.SUNDAY, LocalTime.of(23, 30), 30);
        assertEquals(1440, late.commitments().getFirst().endMinuteOfDay());
        Snapshot fullDay = service.add(late, "Away", DayOfWeek.MONDAY, LocalTime.MIDNIGHT, 1440);
        assertEquals(1440, fullDay.commitments().getLast().durationMinutes());
        assertEquals(1440, fullDay.commitments().getLast().endMinuteOfDay());
    }

    @Test
    void add_overlapOutsideStudyHours_rejects() {
        Snapshot original = service.add(Snapshot.empty(), "Early", DayOfWeek.MONDAY, LocalTime.MIDNIGHT, 120);
        assertThrows(ValidationException.class,
                () -> service.add(original, "Clash", DayOfWeek.MONDAY, LocalTime.of(1, 0), 30));
        assertEquals(1, original.commitments().size());
        assertEquals(2, original.nextCommitmentId());
    }

    @Test
    void add_invalidNames_rejectsWithoutConsumingId() {
        Snapshot original = Snapshot.empty();
        for (String name : new String[] {null, "", "   ", "A/B", "A\nB", "\tA"}) {
            assertThrows(ValidationException.class,
                    () -> service.add(original, name, DayOfWeek.MONDAY, LocalTime.NOON, 30));
        }
        assertEquals(Snapshot.empty(), original);
        assertEquals(1, service.add(original, "Valid", DayOfWeek.MONDAY,
                LocalTime.NOON, 30).commitments().getFirst().id());
    }

    @Test
    void add_invalidTimesAndMissingDay_rejects() {
        Snapshot original = Snapshot.empty();
        for (LocalTime start : new LocalTime[] {null, LocalTime.of(10, 15),
                LocalTime.NOON.withSecond(1), LocalTime.NOON.withNano(1)}) {
            assertThrows(ValidationException.class,
                    () -> service.add(original, "Lecture", DayOfWeek.MONDAY, start, 30));
        }
        assertThrows(ValidationException.class,
                () -> service.add(original, "Lecture", null, LocalTime.NOON, 30));
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_invalidDurationsOrCrossingMidnight_rejects() {
        Snapshot original = Snapshot.empty();
        for (int minutes : new int[] {0, -30, 45, 1470, Integer.MAX_VALUE - 7, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class,
                    () -> service.add(original, "Lecture", DayOfWeek.MONDAY, LocalTime.MIDNIGHT, minutes));
        }
        assertThrows(ValidationException.class,
                () -> service.add(original, "Late", DayOfWeek.MONDAY, LocalTime.of(23, 30), 60));
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_lastUsableIdThenExhaustedCounter_rejectsWithoutOverflow() {
        Snapshot original = new Snapshot(List.of(), List.of(), 1, Integer.MAX_VALUE - 1);
        Snapshot last = service.add(original, "Lecture", DayOfWeek.MONDAY, LocalTime.NOON, 30);
        assertEquals(Integer.MAX_VALUE - 1, last.commitments().getFirst().id());
        assertEquals(Integer.MAX_VALUE, last.nextCommitmentId());
        ValidationException exception = assertThrows(ValidationException.class,
                () -> service.add(last, "Tutorial", DayOfWeek.TUESDAY, LocalTime.NOON, 30));
        assertTrue(exception.getMessage().contains("exhausted"));
        assertEquals(1, last.commitments().size());
        assertEquals(Integer.MAX_VALUE, last.nextCommitmentId());
    }

    @Test
    void add_discardedCandidate_leavesOriginalAvailableForRetry() {
        Snapshot original = Snapshot.empty();
        service.add(original, "Discarded", DayOfWeek.MONDAY, LocalTime.NOON, 60);
        Snapshot retry = service.add(original, "Retry", DayOfWeek.MONDAY, LocalTime.NOON, 60);
        assertEquals(List.of(new Commitment(1, "Retry", DayOfWeek.MONDAY, LocalTime.NOON, 60)),
                retry.commitments());
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_candidateUsedByPlanner_blocksWeeklyStudySlots() {
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Task task = new Task(1, "Draft", monday.atTime(10, 0), 60);
        Snapshot original = new Snapshot(List.of(task), List.of(), 2, 1);
        Snapshot candidate = service.add(original, "Lecture", DayOfWeek.MONDAY, LocalTime.of(8, 0), 60);
        Plan plan = new EarliestDeadlinePlanner().generate(candidate, monday.atTime(7, 0));
        assertTrue(plan.isComplete());
        assertEquals(1, plan.sessions().size());
        assertEquals(monday.atTime(9, 0), plan.sessions().getFirst().start());
        assertEquals(monday.atTime(10, 0), plan.sessions().getFirst().end());
        assertEquals(original.tasks(), candidate.tasks());
    }
}
//@@author
