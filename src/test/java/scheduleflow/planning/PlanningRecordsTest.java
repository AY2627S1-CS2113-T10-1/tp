// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;

/**
 * Checks each planning record's own invariants, leaving allocation correctness to Planner tests.
 */
class PlanningRecordsTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);
    private static final LocalDateTime NOW = DATE.atTime(7, 0);

    @Test
    void studySession_windowEndpoints_countsWallMinutes() {
        assertEquals(840, new StudySession(1, DATE.atTime(8, 0), DATE.atTime(22, 0)).minutes());
        assertEquals(30, session(1, 8).minutes());
    }

    @Test
    void studySession_invalidIntervals_rejects() {
        LocalDateTime start = DATE.atTime(8, 0);
        assertThrows(ValidationException.class, () -> new StudySession(0, start, start.plusMinutes(30)));
        assertThrows(ValidationException.class, () -> new StudySession(1, null, start));
        assertThrows(ValidationException.class, () -> new StudySession(1, start, null));
        assertThrows(ValidationException.class, () -> new StudySession(1, start, start));
        assertThrows(ValidationException.class, () -> new StudySession(1, start.plusMinutes(30), start));
        assertThrows(ValidationException.class, () -> new StudySession(1, start, start.plusDays(1)));
        assertEquals(30, new StudySession(1, start.minusMinutes(30), start).minutes());
        assertEquals(870, new StudySession(1, start, DATE.atTime(22, 30)).minutes());
        assertThrows(ValidationException.class, () -> new StudySession(1, start.plusMinutes(1), start.plusMinutes(30)));
        assertThrows(ValidationException.class, () -> new StudySession(1, start.plusSeconds(1), start.plusMinutes(30)));
        assertThrows(ValidationException.class, () -> new StudySession(1, start, start.plusMinutes(30).plusNanos(1)));
    }

    @Test
    void unallocatedWork_invalidFields_rejects() {
        assertEquals("Draft", work(1).taskName());
        assertThrows(ValidationException.class,
                () -> new UnallocatedWork(0, "A", NOW, 30, UnallocatedReason.DEADLINE_PASSED));
        assertThrows(ValidationException.class, () -> new UnallocatedWork(1, "A", NOW, 30, null));
        assertThrows(ValidationException.class,
                () -> new UnallocatedWork(1, "", NOW, 30, UnallocatedReason.DEADLINE_PASSED));
        assertThrows(ValidationException.class,
                () -> new UnallocatedWork(1, "A", NOW, 0, UnallocatedReason.DEADLINE_PASSED));
        for (LocalDateTime deadline : new LocalDateTime[] {null, NOW.plusSeconds(1), NOW.plusNanos(1)}) {
            assertThrows(ValidationException.class,
                    () -> new UnallocatedWork(1, "A", deadline, 30, UnallocatedReason.DEADLINE_PASSED));
        }
    }

    @Test
    void plan_copiesLists_preservesSourceAndClockPrecision() {
        LocalDateTime generated = NOW.plusNanos(123);
        Snapshot source = Snapshot.empty();
        List<StudySession> sessions = new ArrayList<>(List.of(session(1, 8)));
        List<UnallocatedWork> unallocated = new ArrayList<>(List.of(work(1)));
        Plan plan = new Plan(generated, TimeRules.horizonEnd(generated), source, sessions, unallocated);
        sessions.clear();
        unallocated.clear();
        assertEquals(generated, plan.generatedAt());
        assertSame(source, plan.source());
        assertEquals(1, plan.sessions().size());
        assertEquals(1, plan.unallocated().size());
        assertFalse(plan.isComplete());
        assertThrows(UnsupportedOperationException.class, () -> plan.sessions().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.unallocated().clear());
        assertTrue(plan(List.of(), List.of()).isComplete());
        // These deliberately source-unmatched rows show that cross-record checks remain with Planner.
    }

    @Test
    void plan_invalidMetadataOrLists_rejects() {
        assertThrows(ValidationException.class,
                () -> new Plan(NOW, NOW.plusYears(1), Snapshot.empty(), List.of(), List.of()));
        assertThrows(ValidationException.class,
                () -> new Plan(null, TimeRules.horizonEnd(NOW), Snapshot.empty(), List.of(), List.of()));
        assertThrows(ValidationException.class,
                () -> new Plan(NOW, null, Snapshot.empty(), List.of(), List.of()));
        assertThrows(ValidationException.class,
                () -> new Plan(NOW, TimeRules.horizonEnd(NOW), null, List.of(), List.of()));
        assertThrows(ValidationException.class, () -> plan(null, List.of()));
        assertThrows(ValidationException.class, () -> plan(List.of(), null));
        assertThrows(ValidationException.class, () -> plan(Arrays.asList((StudySession) null), List.of()));
        assertThrows(ValidationException.class, () -> plan(List.of(), Arrays.asList((UnallocatedWork) null)));
    }

    @Test
    void plan_ordering_checksChronologyDeadlineAndNumericTies() {
        assertThrows(ValidationException.class, () -> plan(List.of(session(1, 9), session(2, 8)), List.of()));
        assertThrows(ValidationException.class, () -> plan(List.of(session(10, 8), session(2, 8)), List.of()));
        assertThrows(ValidationException.class, () -> plan(List.of(), List.of(work(10), work(2))));
        UnallocatedWork later = new UnallocatedWork(1, "A", NOW.plusDays(1), 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY);
        assertThrows(ValidationException.class, () -> plan(List.of(), List.of(later, work(2))));
        assertEquals(2, plan(List.of(session(2, 8), session(10, 8)), List.of()).sessions().size());
        assertEquals(List.of(work(2), work(10)), plan(List.of(), List.of(work(2), work(10))).unallocated());
    }

    @Test
    void scheduleEntry_referenceRules_requireCanonicalIds() {
        for (String reference : new String[] {"1", "T0", "T01", "T-1", "C1", "T2147483648", ""}) {
            assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.TASK, reference, "A"));
        }
        assertEquals("T2147483647", entry(8, 9, SlotKind.TASK, "T2147483647", "A").reference());
        assertEquals("C1", entry(8, 9, SlotKind.BUSY, "C1", "A").reference());
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.BUSY, "T1", "A"));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.FREE, "T1", ""));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.UNPLANNED, "", "A"));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.TASK, "T1", " A "));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.TASK, "T1", ""));
    }

    @Test
    void scheduleEntry_invalidFieldsAndTimes_rejects() {
        assertThrows(ValidationException.class, () -> entry(8, 9, null, "", ""));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.FREE, null, ""));
        assertThrows(ValidationException.class, () -> entry(8, 9, SlotKind.FREE, "", null));
        assertEquals(DATE.atTime(7, 0), entry(7, 8, SlotKind.FREE, "", "").start());
        assertEquals(DATE.atTime(23, 0), entry(21, 23, SlotKind.FREE, "", "").end());
        assertThrows(ValidationException.class, () -> entry(8, 8, SlotKind.FREE, "", ""));
        assertThrows(ValidationException.class,
                () -> new ScheduleEntry(DATE.atTime(8, 1), DATE.atTime(9, 0), SlotKind.FREE, "", ""));
        assertThrows(ValidationException.class,
                () -> new ScheduleEntry(DATE.atTime(8, 0), DATE.plusDays(1).atTime(9, 0), SlotKind.FREE, "", ""));
    }

    @Test
    void scheduleView_copiesLists_preservesWholePlanRemainders() {
        List<ScheduleEntry> entries = new ArrayList<>(List.of(entry(8, 22, SlotKind.FREE, "", "")));
        List<UnallocatedWork> unallocated = new ArrayList<>(List.of(work(2), work(10)));
        ScheduleView view = new ScheduleView(DATE, NOW, entries, unallocated);
        entries.clear();
        unallocated.clear();
        assertEquals(1, view.entries().size());
        assertEquals(List.of(work(2), work(10)), view.unallocated());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.unallocated().clear());
    }

    @Test
    void scheduleView_gapsOverlapsWrongDateAndNulls_rejects() {
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW, List.of(), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW,
                List.of(entry(8, 10, SlotKind.FREE, "", ""), entry(11, 22, SlotKind.FREE, "", "")), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW,
                List.of(entry(8, 10, SlotKind.FREE, "", ""), entry(9, 22, SlotKind.FREE, "", "")), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE.plusDays(1), NOW,
                List.of(entry(8, 22, SlotKind.FREE, "", "")), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(null, NOW, List.of(), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, null, List.of(), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW, null, List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW, List.of(), null));
        assertThrows(ValidationException.class,
                () -> new ScheduleView(DATE, NOW, Arrays.asList((ScheduleEntry) null), List.of()));
        assertThrows(ValidationException.class, () -> new ScheduleView(DATE, NOW,
                List.of(entry(8, 22, SlotKind.FREE, "", "")), Arrays.asList((UnallocatedWork) null)));
    }

    @Test
    void exactWorkRecords_validateWorkCapacityAndOptionalDeadline() {
        LocalDateTime start = DATE.atTime(8, 0);
        assertEquals(45, new StudySession(1, start, start.plusMinutes(60), 45).workMinutes());
        assertEquals(60, new StudySession(1, start, start.plusMinutes(60)).workMinutes());
        for (int work : new int[] {-1, 0, 61}) {
            assertThrows(ValidationException.class, () -> new StudySession(1, start, start.plusMinutes(60), work));
        }
        assertEquals(60L, new UnallocatedWork(1, "Draft", NOW, 45,
                UnallocatedReason.DEADLINE_PASSED).capacityMinutes());
        assertEquals(2147483670L, new UnallocatedWork(1, "Draft", NOW, Integer.MAX_VALUE,
                UnallocatedReason.DEADLINE_PASSED).capacityMinutes());
        for (long capacity : new long[] {-30, 0, 15, 30, 90, Long.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> new UnallocatedWork(1, "Draft", Optional.empty(),
                    45, capacity, UnallocatedReason.INSUFFICIENT_CAPACITY));
        }
        for (UnallocatedReason reason : List.of(UnallocatedReason.DEADLINE_PASSED,
                UnallocatedReason.OUTSIDE_HORIZON)) {
            assertThrows(ValidationException.class,
                    () -> new UnallocatedWork(1, "Draft", Optional.empty(), 45, 60, reason));
        }
    }

    private StudySession session(int id, int hour) {
        return new StudySession(id, DATE.atTime(hour, 0), DATE.atTime(hour, 30));
    }

    private UnallocatedWork work(int id) {
        return new UnallocatedWork(id, " Draft ", DATE.atTime(22, 0), 30,
                UnallocatedReason.INSUFFICIENT_CAPACITY);
    }

    private Plan plan(List<StudySession> sessions, List<UnallocatedWork> unallocated) {
        return new Plan(NOW, TimeRules.horizonEnd(NOW), Snapshot.empty(), sessions, unallocated);
    }

    private ScheduleEntry entry(int startHour, int endHour, SlotKind kind, String reference, String name) {
        return new ScheduleEntry(DATE.atTime(startHour, 0), DATE.atTime(endHour, 0), kind, reference, name);
    }
}
// @@author
