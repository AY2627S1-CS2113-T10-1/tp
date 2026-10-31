// @@author GabrielYuSP
package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;

/** Exercises feasibility independently of the generator's choice of earliest slots. */
class PlannerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final Task TASK = new Task(1, "Draft", NOW.withHour(12), 45);

    @Test
    void generate_customHoursPolicyAndDatedBlock_preservesEditedOccurrenceRelease() {
        Task task = new Task(1, "Draft", NOW.withHour(16), 90,
                Optional.of(new TaskOccurrence(1, NOW.plusWeeks(1).withHour(11))));
        Snapshot base = source(task);
        Snapshot configured = base.withStudyHours(base.studyHours().withWindow(NOW.toLocalDate().toString(),
                new StudyWindow(LocalTime.of(10, 0), LocalTime.of(16, 0))))
                .withStudyPolicy(Optional.of(new StudyPolicy(30, 30)))
                .withCommitments(List.of(new Commitment(1, "Unavailable", DayOfWeek.MONDAY,
                        LocalTime.of(11, 0), 30, NOW.toLocalDate(), NOW.toLocalDate().plusDays(2))), 2);
        Plan plan = new EarliestDeadlinePlanner().generate(configured, NOW);
        assertEquals(NOW.withHour(12), plan.sessions().getFirst().start());
        assertEquals(90, plan.sessions().stream().mapToInt(StudySession::workMinutes).sum());
        assertEquals(90, configured.tasks().getFirst().remainingMinutes());
        assertDoesNotThrow(() -> Planner.validate(plan));
    }

    @Test
    void validate_ineligibleDeadlines_preservesFullRemainderAndRejectsSessions() {
        for (LocalDateTime deadline : List.of(NOW, TimeRules.horizonEnd(NOW).plusMinutes(1))) {
            Task task = new Task(1, "Draft", deadline, 45);
            UnallocatedReason reason = deadline.equals(NOW) ? UnallocatedReason.DEADLINE_PASSED
                    : UnallocatedReason.OUTSIDE_HORIZON;
            UnallocatedWork work = new UnallocatedWork(1, "Draft", deadline, 45, reason);
            assertDoesNotThrow(() -> Planner.validate(plan(source(task), List.of(), List.of(work), List.of())));
            assertThrows(ValidationException.class, () -> Planner.validate(plan(source(task),
                    List.of(session(10, 45)), List.of(), List.of())));
        }
    }

    @Test
    void validate_horizonAndBreakBounds_rejects() {
        assertInvalid(List.of(session(10, 45)), List.of(), List.of(rest(6)));
        LocalDateTime nextYear = TimeRules.horizonEnd(NOW).withHour(10);
        assertInvalid(List.of(new StudySession(1, nextYear, nextYear.plusMinutes(60), 45)),
                List.of(), List.of());
        assertInvalid(List.of(session(10, 45)), List.of(), List.of(new ScheduleEntry(
                NOW.minusDays(1).withHour(9), NOW.minusDays(1).withHour(10), SlotKind.BREAK, "", "")));
    }

    @Test
    void validate_nonGreedyPartialWorkAndLegacyBreak_acceptsWithoutChangingSource() {
        Snapshot source = source(TASK);
        Plan plan = plan(source, List.of(session(10, 30)), List.of(remainder(15)),
                List.of(rest(9), rest(11)));
        assertDoesNotThrow(() -> Planner.validate(plan));
        assertDoesNotThrow(() -> Planner.validate(plan));
        assertEquals(45, source.tasks().getFirst().remainingMinutes());
        assertDoesNotThrow(() -> Planner.validate(plan(source, List.of(session(10, 45)), List.of(), List.of())));
    }

    @Test
    void validate_missingDuplicatedOrExcessiveWork_rejects() {
        assertInvalid(List.of(), List.of(), List.of());
        assertInvalid(List.of(session(10, 30)), List.of(remainder(15), remainder(15)), List.of());
        assertInvalid(List.of(session(10, 46)), List.of(), List.of());
        assertInvalid(List.of(session(10, 30)), List.of(remainder(30)), List.of());
        assertThrows(NullPointerException.class, () -> Planner.validate(null));
    }

    @Test
    void validate_overlappingSessionsAndBreaks_rejectsButAdjacencyPasses() {
        assertInvalid(List.of(session(10, 30), session(10, 15)), List.of(), List.of());
        assertInvalid(List.of(session(10, 45)), List.of(), List.of(rest(10)));
        assertInvalid(List.of(session(10, 45)), List.of(), List.of(rest(9), rest(9)));
        assertDoesNotThrow(() -> Planner.validate(plan(source(TASK), List.of(session(10, 45)),
                List.of(), List.of(rest(9), rest(11)))));
    }

    @Test
    void validate_cutoffDeadlineOverrideAndOriginalRelease_rejects() {
        Plan beforeCutoff = new Plan(NOW.withHour(10).withMinute(1), TimeRules.horizonEnd(NOW), source(TASK),
                List.of(session(10, 45)), List.of());
        assertThrows(ValidationException.class, () -> Planner.validate(beforeCutoff));
        assertInvalid(List.of(session(12, 45)), List.of(), List.of());
        Snapshot custom = source(TASK).withStudyHours(source(TASK).studyHours().withWindow(
                NOW.toLocalDate().toString(), new StudyWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))));
        assertThrows(ValidationException.class, () -> Planner.validate(plan(custom,
                List.of(session(10, 45)), List.of(), List.of())));
        Task occurrence = new Task(1, "Draft", NOW.plusDays(2), 45,
                Optional.of(new TaskOccurrence(1, NOW.plusWeeks(1).withHour(11))));
        assertThrows(ValidationException.class, () -> Planner.validate(plan(source(occurrence),
                List.of(session(10, 45)), List.of(), List.of())));
    }

    @Test
    void validate_commitmentOccurrenceHonorsSkipsAndAdjacency() {
        Commitment busy = new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60);
        Snapshot blocked = source(TASK).withCommitments(List.of(busy), 2);
        assertThrows(ValidationException.class, () -> Planner.validate(plan(blocked,
                List.of(session(10, 45)), List.of(), List.of())));
        Commitment skipped = new Commitment(1, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60,
                null, null, Set.of(NOW.toLocalDate()));
        assertDoesNotThrow(() -> Planner.validate(plan(source(TASK).withCommitments(List.of(skipped), 2),
                List.of(session(10, 45)), List.of(), List.of())));
        assertDoesNotThrow(() -> Planner.validate(plan(blocked, List.of(session(11, 45)),
                List.of(), List.of())));
    }

    @Test
    void validate_wrongIdentityReasonUnknownAndCompletedTasks_rejects() {
        assertInvalid(List.of(), List.of(new UnallocatedWork(1, "Other", TASK.deadline().orElseThrow(),
                45, UnallocatedReason.INSUFFICIENT_CAPACITY)), List.of());
        assertInvalid(List.of(), List.of(new UnallocatedWork(1, "Draft", TASK.deadline().orElseThrow(),
                45, UnallocatedReason.DEADLINE_PASSED)), List.of());
        assertThrows(ValidationException.class, () -> Planner.validate(plan(Snapshot.empty(),
                List.of(session(10, 45)), List.of(), List.of())));
        assertThrows(ValidationException.class, () -> Planner.validate(plan(source(
                new Task(1, "Draft", NOW.withHour(12), 0)), List.of(session(10, 45)), List.of(), List.of())));
    }

    /** Checks a malformed allocation against the ordinary 45-minute source. */
    private static void assertInvalid(List<StudySession> sessions, List<UnallocatedWork> work,
            List<ScheduleEntry> breaks) {
        assertThrows(ValidationException.class, () -> Planner.validate(plan(source(TASK), sessions, work, breaks)));
    }

    /** Builds a frozen source with counters above its task and provenance IDs. */
    private static Snapshot source(Task task) {
        return new Snapshot(List.of(task), List.of(), 2, 1, Snapshot.empty().studyHours(), List.of(), 2);
    }

    /** Builds a raw plan so validation is exercised independently of generation. */
    private static Plan plan(Snapshot source, List<StudySession> sessions, List<UnallocatedWork> work,
            List<ScheduleEntry> breaks) {
        return new Plan(NOW, TimeRules.horizonEnd(NOW), source, sessions, work, breaks);
    }

    /** Reserves one hour while allowing exact work smaller than capacity. */
    private static StudySession session(int hour, int work) {
        return new StudySession(1, NOW.withHour(hour), NOW.withHour(hour + 1), work);
    }

    /** Builds a matching capacity remainder. */
    private static UnallocatedWork remainder(int minutes) {
        return new UnallocatedWork(1, "Draft", TASK.deadline().orElseThrow(), minutes,
                UnallocatedReason.INSUFFICIENT_CAPACITY);
    }

    /** Builds an anonymous one-hour reserved rest interval. */
    private static ScheduleEntry rest(int hour) {
        return new ScheduleEntry(NOW.withHour(hour), NOW.withHour(hour + 1), SlotKind.BREAK, "", "");
    }
}
// @@author
