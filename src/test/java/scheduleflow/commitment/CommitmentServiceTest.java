package scheduleflow.commitment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
import scheduleflow.storage.StorageException;

/**
 * Verifies commitment operations and immutable candidates using fixed data and isolated storage.
 */
class CommitmentServiceTest {
    private final CommitmentService service = new CommitmentService();

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
    void add_sameDayOverlap_rejectsAndIdentifiesExistingCommitment() {
        Commitment lecture = new Commitment(7, "Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120);
        Snapshot original = new Snapshot(List.of(), List.of(lecture), 1, 8);
        for (int[] interval : new int[][] {{9, 90}, {11, 120}, {10, 30}, {9, 240}, {10, 120}}) {
            ValidationException exception = assertThrows(ValidationException.class,
                    () -> service.add(original, "Clash", DayOfWeek.MONDAY,
                            LocalTime.of(interval[0], 0), interval[1]));
            assertTrue(exception.getMessage().contains("C7"));
            assertTrue(exception.getMessage().contains("Lecture"));
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
