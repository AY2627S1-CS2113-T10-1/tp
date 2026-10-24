package scheduleflow.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import scheduleflow.planning.Plan;
import scheduleflow.planning.ScheduleEntry;
import scheduleflow.planning.SlotKind;
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
    void encodeAndDecode_partialPlanWithBreakAndPipeName_preservesExactResult() throws StorageException {
        Task task = new Task(1, "Draft | review", NOW.plusDays(1), 60);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
        Plan plan = new Plan(NOW, TimeRules.horizonEnd(NOW), source,
                List.of(new StudySession(1, NOW.plusHours(1), NOW.plusMinutes(90))),
                List.of(new UnallocatedWork(1, task.name(), task.deadline(), 30,
                        UnallocatedReason.INSUFFICIENT_CAPACITY)),
                List.of(new ScheduleEntry(NOW.plusHours(2), NOW.plusMinutes(150), SlotKind.BREAK, "", "")));

        String encoded = plans.encode(plan);

        assertTrue(encoded.startsWith("SCHEDULEFLOW|5\nNEXT|2|1\n"));
        assertTrue(encoded.contains("UNALLOCATED|1|2026-10-06T07:00|30|INSUFFICIENT_CAPACITY|Draft | review\n"));
        assertEquals(plan, plans.decode(encoded));
        assertEquals(source, snapshots.decode(encoded));
        assertEquals("SCHEDULEFLOW|1\nNEXT|2|1\nTASK|1|2026-10-06T07:00|60|Draft | review\n",
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
                valid.replace("SCHEDULEFLOW|5", "SCHEDULEFLOW|99"))) {
            assertThrows(StorageException.class, () -> snapshots.decode(invalid), invalid);
        }
        String unknownSession = valid.replace("|0|0|0\n", "|1|0|0\n")
                + "SESSION|1|2026-10-05T08:00|2026-10-05T08:30\n";
        assertThrows(StorageException.class, () -> plans.decode(unknownSession));
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
