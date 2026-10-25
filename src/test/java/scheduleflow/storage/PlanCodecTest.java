package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.SlotKind;
import scheduleflow.planning.StudyPolicy;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.TimeRules;
import scheduleflow.planning.UnallocatedReason;
import scheduleflow.planning.UnallocatedWork;

// @@author ernestgoh-god
/** Verifies complete frozen-plan text independently of filesystem access. */
class PlanCodecTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 7, 0);
    private final StateCodec snapshots = new StateCodec();
    private final PlanCodec plans = new PlanCodec(snapshots);

    @Test
    void encodeAndDecode_storedPolicyAndBreaks_usesDistinctVersionAndRetainsExactPlan() throws StorageException {
        Snapshot source = new Snapshot(List.of(new Task(1, "Work", NOW.withHour(12), 180)), List.of(), 2, 1)
                .withStudyPolicy(Optional.of(new StudyPolicy(60, 30)));
        Plan plan = new EarliestDeadlinePlanner().generate(source, NOW);
        String text = plans.encode(plan);
        assertTrue(text.startsWith("SCHEDULEFLOW|8\n"));
        assertTrue(text.contains("POLICY|60|30\n"));
        assertEquals(plan, plans.decode(text));
        assertEquals(source, snapshots.decode(text));
        assertEquals(2, plans.decode(text).breaks().size());
        assertThrows(StorageException.class, () -> plans.decode(text.replace("POLICY|60|30\n", "")));
        assertThrows(StorageException.class, () -> plans.decode(text.replace("POLICY|60|30", "POLICY|60|0")));
    }

    @Test
    void encodeAndDecode_partialPlanWithBreakAndPipeName_preservesExactResult() throws StorageException {
        Task task = new Task(1, "Draft | review", NOW.plusDays(1), 60);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
        Plan plan = new Plan(NOW, TimeRules.horizonEnd(NOW), source,
                List.of(new StudySession(1, NOW.plusHours(1), NOW.plusMinutes(90))),
                List.of(new UnallocatedWork(1, task.name(), task.deadline().orElseThrow(), 30,
                        UnallocatedReason.INSUFFICIENT_CAPACITY)),
                List.of(new ScheduleEntry(NOW.plusHours(2), NOW.plusMinutes(150), SlotKind.BREAK, "", "")));

        String encoded = plans.encode(plan);

        assertTrue(encoded.startsWith("SCHEDULEFLOW|6\nNEXT|2|1|1\n"));
        assertTrue(encoded.contains("UNALLOCATED|1|2026-10-06T07:00|30|30|INSUFFICIENT_CAPACITY|Draft | review\n"));
        assertEquals(plan, plans.decode(encoded));
        assertEquals(source, snapshots.decode(encoded));
        assertEquals("SCHEDULEFLOW|2\nNEXT|2|1\nTASK2|1|2026-10-06T07:00|60|NORMAL|Draft | review\n",
                snapshots.encode(source));
    }

    @Test
    void decode_missingExtraAndInvalidPlanRows_rejectsWholeFile() {
        Plan empty = new Plan(NOW, TimeRules.horizonEnd(NOW), Snapshot.empty(), List.of(), List.of());
        String valid = plans.encode(empty);
        for (String invalid : List.of(valid.replace("PLAN|", "UNKNOWN|"),
                valid.replace("|0|0|0\n", "|1|0|0\n"),
                valid + "SESSION|1|2026-10-05T08:00|2026-10-05T08:30\n",
                valid.replace("2027-10-05T00:00", "2027-10-06T00:00"),
                valid.replace("SCHEDULEFLOW|6", "SCHEDULEFLOW|99"))) {
            assertThrows(StorageException.class, () -> snapshots.decode(invalid), invalid);
        }
        String unknownSession = valid.replace("|0|0|0\n", "|1|0|0\n")
                + "SESSION|1|2026-10-05T08:00|2026-10-05T08:30|30\n";
        assertThrows(StorageException.class, () -> plans.decode(unknownSession));
    }

    @Test
    void encodeAndDecode_recurrenceOptionalDeadlineAndExactWork_preservesEveryField() throws StorageException {
        LocalDateTime first = NOW.plusDays(1);
        RecurringTask template = new RecurringTask(1, "Weekly | review", first, Optional.empty(), 60,
                Optional.of(first));
        Task occurrence = new Task(1, "Draft | review", Optional.of(first), 45, TaskPriority.HIGH,
                Optional.of(new TaskOccurrence(1, first)));
        Task undated = new Task(2, "Undated | work", Optional.empty(), Integer.MAX_VALUE, TaskPriority.LOW);
        Task completed = new Task(3, "Completed", Optional.empty(), 0, TaskPriority.NORMAL);
        Snapshot source = new Snapshot(List.of(occurrence, undated, completed), List.of(), 5, 4,
                Snapshot.empty().studyHours(), List.of(template), 3);
        Plan plan = new Plan(NOW, TimeRules.horizonEnd(NOW), source,
                List.of(new StudySession(1, NOW.plusHours(1), NOW.plusMinutes(90), 17)),
                List.of(new UnallocatedWork(1, occurrence.name(), occurrence.deadline(), 28, 30,
                                UnallocatedReason.INSUFFICIENT_CAPACITY),
                        new UnallocatedWork(2, undated.name(), Optional.empty(), Integer.MAX_VALUE, 2147483670L,
                                UnallocatedReason.INSUFFICIENT_CAPACITY)));

        String encoded = plans.encode(plan);

        assertEquals(plan, plans.decode(encoded));
        assertEquals(source, snapshots.decode(encoded));
        assertTrue(encoded.contains("SESSION|1|2026-10-05T08:00|2026-10-05T08:30|17\n"));
        assertTrue(encoded.contains("UNALLOCATED|2|NONE|2147483647|2147483670|INSUFFICIENT_CAPACITY|"));
        assertEquals(source, snapshots.decode(snapshots.encode(source)));
        assertThrows(StorageException.class, () -> plans.decode(encoded.replace("|17\n", "|31\n")));
        assertThrows(StorageException.class, () -> plans.decode(encoded.replace("|28|30|", "|28|60|")));
        assertThrows(StorageException.class, () -> plans.decode(encoded.replace("|NONE|2147483647|2147483670|"
                + "INSUFFICIENT_CAPACITY|", "|NONE|2147483647|2147483670|DEADLINE_PASSED|")));
    }

    @Test
    void decode_legacyPlanAndVersionFiveSnapshot_distinguishesTheirLayouts() throws StorageException {
        String legacy = "SCHEDULEFLOW|5\r\nNEXT|2|1\r\nTASK|1|2026-10-06T07:00|30|Draft\r\n"
                + "PLAN|2026-10-05T07:00|2027-10-05T00:00|1|0|0\r\n"
                + "SESSION|1|2026-10-05T08:00|2026-10-05T08:30";
        Plan restored = plans.decode(legacy);
        assertEquals(30, restored.sessions().getFirst().workMinutes());
        assertEquals(restored, plans.decode(plans.encode(restored)));
        assertEquals(restored.source(), snapshots.decode(legacy));
        String recurrence = "SCHEDULEFLOW|5\nNEXT|1|1|2\n";
        assertEquals(2, snapshots.decode(recurrence).nextRecurringTaskId());
        assertThrows(StorageException.class, () -> plans.decode(recurrence));
    }

    @Test
    void encodeAndDecode_boundedSkippedCommitmentAndCustomHours_preservesSource() throws StorageException {
        LocalDate first = LocalDate.of(2026, 10, 5);
        Commitment commitment = new Commitment(1, "Lecture | lab", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                first, first.plusWeeks(2), Set.of(first.plusWeeks(1)));
        Snapshot original = new Snapshot(List.of(), List.of(commitment), 1, 2);
        Snapshot source = original.withStudyHours(original.studyHours()
                .withWindow("monday", new StudyWindow(LocalTime.of(9, 0), LocalTime.of(20, 0))));
        Plan plan = new Plan(NOW, TimeRules.horizonEnd(NOW), source, List.of(), List.of());

        String encoded = plans.encode(plan);

        assertTrue(encoded.contains("COMMITMENT|1|MONDAY|10:00|60|2026-10-05|2026-10-19|2026-10-12|"
                + "Lecture | lab\n"));
        assertEquals(plan, plans.decode(encoded));
        assertEquals(source, snapshots.decode(encoded));
    }
}
// @@author
