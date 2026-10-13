package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.Planner;
import scheduleflow.planning.ScheduleService;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.TimeRules;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskService;

/**
 * Verifies every route, save-before-publication transactions and frozen planning through public APIs.
 */
class CommandDispatcherTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final TextRenderer renderer = new TextRenderer();

    @Test
    void execute_allCommands_routesAndCapturesClockExactlyOnce() throws Exception {
        CountingClock clock = new CountingClock();
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        AppState state = new AppState(storage.saved);
        CommandDispatcher dispatcher = dispatcher(storage, clock);
        List<Command> commands = List.of(new Command.Help(), addTask(), new Command.ListTasks(),
                new Command.DeleteTask(1), addCommitment(), new Command.ListCommitments(),
                new Command.DeleteCommitment(1), new Command.GeneratePlan(), new Command.ScheduleToday(),
                new Command.ScheduleDate(NOW.toLocalDate()), new Command.Exit());
        for (Command command : commands) {
            CommandResponse response = dispatcher.execute(command, state);
            assertFalse(response.output().isBlank());
            assertEquals(command instanceof Command.Exit, response.shouldExit());
        }
        assertEquals(commands.size(), clock.reads);
        assertEquals(4, storage.saveCalls);
        assertEquals(NOW.plusSeconds(7), state.currentPlan().orElseThrow().generatedAt());
        assertEquals(Snapshot.empty().tasks(), state.snapshot().tasks());
    }

    @Test
    void execute_eachMutation_savesBeforePublishingAndClearsPlan() throws Exception {
        for (Command command : mutations()) {
            Snapshot original = populatedSnapshot();
            AppState state = new AppState(original);
            Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(plan);
            FakeStorage storage = new FakeStorage(original);
            storage.beforeSave = () -> {
                assertSame(original, state.snapshot());
                assertSame(plan, state.currentPlan().orElseThrow());
            };
            CommandResponse response = dispatcher(storage, CLOCK).execute(command, state);
            assertSame(storage.saved, state.snapshot());
            assertEquals(1, storage.saveCalls);
            assertTrue(state.currentPlan().isEmpty());
            assertEquals(mutationOutput(command), response.output());
            assertFalse(response.shouldExit());
            assertEquals(command instanceof Command.AddTask ? 4 : 3, state.snapshot().nextTaskId());
            assertEquals(command instanceof Command.AddCommitment ? 4 : 3, state.snapshot().nextCommitmentId());
        }
    }

    @Test
    void execute_eachFailedSave_preservesStatePlanCountersAndRetryId() throws Exception {
        for (Command command : mutations()) {
            Snapshot original = populatedSnapshot();
            AppState state = new AppState(original);
            Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(plan);
            FakeStorage storage = new FakeStorage(original);
            storage.failSave = true;
            CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
            assertThrows(StorageException.class, () -> dispatcher.execute(command, state));
            assertSame(original, state.snapshot());
            assertSame(original, storage.saved);
            assertSame(plan, state.currentPlan().orElseThrow());
            assertEquals(3, state.snapshot().nextTaskId());
            assertEquals(3, state.snapshot().nextCommitmentId());
            Snapshot failedCandidate = storage.attempted;
            storage.failSave = false;
            assertEquals(mutationOutput(command), dispatcher.execute(command, state).output());
            assertEquals(failedCandidate, storage.saved);
            assertTrue(state.currentPlan().isEmpty());
            assertEquals(2, storage.saveCalls);
        }
    }

    @Test
    void execute_domainErrors_preserveStateAndPlanWithoutSaving() {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        List<Command> invalid = List.of(new Command.AddTask("Past", NOW, 30),
                new Command.AddTask("Too late", TimeRules.horizonEnd(NOW).plusMinutes(1), 30),
                new Command.AddTask("Bad duration", NOW.plusDays(1), 45),
                new Command.AddCommitment("Unaligned", DayOfWeek.MONDAY, LocalTime.of(10, 15), 30),
                new Command.AddCommitment("Overlap", DayOfWeek.MONDAY, LocalTime.of(10, 30), 30),
                new Command.AddCommitment("Too long", DayOfWeek.MONDAY, LocalTime.of(23, 30), 60),
                new Command.DeleteTask(99), new Command.DeleteCommitment(99));
        for (Command command : invalid) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(command, state));
            assertSame(original, state.snapshot());
            assertSame(plan, state.currentPlan().orElseThrow());
        }
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_nonmutatingCommands_preserveSnapshotPlanAndDisk() throws Exception {
        Snapshot snapshot = populatedSnapshot();
        AppState state = new AppState(snapshot);
        Plan plan = new EarliestDeadlinePlanner().generate(snapshot, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(snapshot);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        assertEquals(renderer.help(), dispatcher.execute(new Command.Help(), state).output());
        assertEquals(renderer.tasks(new TaskService().list(snapshot)),
                dispatcher.execute(new Command.ListTasks(), state).output());
        assertEquals(renderer.commitments(new CommitmentService().list(snapshot)),
                dispatcher.execute(new Command.ListCommitments(), state).output());
        assertEquals(dispatcher.execute(new Command.ScheduleToday(), state).output(),
                dispatcher.execute(new Command.ScheduleDate(NOW.toLocalDate()), state).output());
        assertEquals(renderer.exit(), dispatcher.execute(new Command.Exit(), state).output());
        assertSame(snapshot, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        assertSame(snapshot, storage.saved);
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_deadlineAtHorizon_acceptsExactBoundary() throws Exception {
        AppState state = new AppState(Snapshot.empty());
        FakeStorage storage = new FakeStorage(state.snapshot());
        Command.AddTask command = new Command.AddTask("Boundary", TimeRules.horizonEnd(NOW), 30);
        dispatcher(storage, CLOCK).execute(command, state);
        assertEquals(command.deadline(), state.snapshot().tasks().getFirst().deadline());
    }

    @Test
    void execute_noPlan_takesPrecedenceOverScheduleRange() throws Exception {
        AppState state = new AppState(Snapshot.empty());
        CommandDispatcher dispatcher = dispatcher(new FakeStorage(state.snapshot()), CLOCK);
        assertEquals(renderer.noPlan(), dispatcher.execute(new Command.ScheduleDate(LocalDate.of(1, 1, 1)),
                state).output());
        assertEquals(renderer.noPlan(), dispatcher.execute(new Command.ScheduleToday(), state).output());
    }

    @Test
    void execute_planRange_acceptsFinalDateAndPreservesPlanOnErrors() throws Exception {
        AppState state = new AppState(Snapshot.empty());
        FakeStorage storage = new FakeStorage(state.snapshot());
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        dispatcher.execute(new Command.GeneratePlan(), state);
        Plan plan = state.currentPlan().orElseThrow();
        LocalDate last = plan.horizonEnd().toLocalDate().minusDays(1);
        assertTrue(dispatcher.execute(new Command.ScheduleDate(last), state).output().contains(last.toString()));
        for (LocalDate date : List.of(NOW.toLocalDate().minusDays(1), last.plusDays(1))) {
            ValidationException failure = assertThrows(ValidationException.class,
                    () -> dispatcher.execute(new Command.ScheduleDate(date), state));
            assertTrue(failure.getMessage().contains(NOW.toLocalDate().toString()));
            assertTrue(failure.getMessage().contains(last.toString()));
            assertTrue(failure.getMessage().contains("plan"));
            assertSame(plan, state.currentPlan().orElseThrow());
        }
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_scheduleToday_usesInjectedLocalZone() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-20T23:30:00Z"), ZoneOffset.ofHours(8));
        AppState state = new AppState(Snapshot.empty());
        CommandDispatcher dispatcher = dispatcher(new FakeStorage(state.snapshot()), clock);
        dispatcher.execute(new Command.GeneratePlan(), state);
        assertTrue(dispatcher.execute(new Command.ScheduleToday(), state).output()
                .startsWith("=== Schedule for Monday (2026-09-21) ==="));
    }

    @Test
    void execute_scheduleLater_freezesPlanUntilReplacement() throws Exception {
        AppState state = new AppState(populatedSnapshot());
        FakeStorage storage = new FakeStorage(state.snapshot());
        CommandDispatcher morning = dispatcher(storage, CLOCK);
        morning.execute(new Command.GeneratePlan(), state);
        Plan original = state.currentPlan().orElseThrow();
        String expected = morning.execute(new Command.ScheduleToday(), state).output();
        Clock noon = Clock.fixed(NOW.plusHours(5).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        CommandDispatcher later = dispatcher(storage, noon);
        assertEquals(expected, later.execute(new Command.ScheduleToday(), state).output());
        assertSame(original, state.currentPlan().orElseThrow());
        later.execute(new Command.GeneratePlan(), state);
        assertEquals(NOW.plusHours(5), state.currentPlan().orElseThrow().generatedAt());
        assertSame(original.source(), state.snapshot());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_plannerFailure_preservesExistingPlan() {
        AppState state = new AppState(Snapshot.empty());
        Plan original = new EarliestDeadlinePlanner().generate(state.snapshot(), NOW);
        state.replacePlan(original);
        FakeStorage storage = new FakeStorage(state.snapshot());
        Planner failing = (snapshot, now) -> {
            throw new IllegalStateException("Planner defect");
        };
        CommandDispatcher dispatcher = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                failing, new ScheduleService(), renderer, CLOCK);
        assertThrows(IllegalStateException.class, () -> dispatcher.execute(new Command.GeneratePlan(), state));
        assertSame(original, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_correctedSchedule_exposesEarliestSessionsAndConservesWork() throws Exception {
        AppState state = new AppState(Snapshot.empty());
        CommandDispatcher dispatcher = dispatcher(new FakeStorage(state.snapshot()), CLOCK);
        dispatcher.execute(new Command.AddTask("Draft", NOW.plusDays(1).withHour(18), 180), state);
        dispatcher.execute(new Command.AddCommitment("Lecture", DayOfWeek.MONDAY, LocalTime.of(10, 0), 120), state);
        dispatcher.execute(new Command.AddCommitment("Lab", DayOfWeek.MONDAY, LocalTime.of(12, 0), 60), state);
        dispatcher.execute(new Command.GeneratePlan(), state);
        assertEquals(String.join("\n", "=== Schedule for Monday (2026-09-21) ===",
                "Generated at: 2026-09-21 07:00", "08:00 - 10:00 | [TASK] T1: Draft (120 min)",
                "10:00 - 12:00 | [BUSY] C1: Lecture", "12:00 - 13:00 | [BUSY] C2: Lab",
                "13:00 - 14:00 | [TASK] T1: Draft (60 min)", "14:00 - 22:00 | [FREE]"),
                dispatcher.execute(new Command.ScheduleToday(), state).output());
        Plan plan = state.currentPlan().orElseThrow();
        assertEquals(180, plan.sessions().stream().mapToInt(StudySession::minutes).sum());
        assertEquals(180, state.snapshot().tasks().getFirst().remainingMinutes());
        assertTrue(plan.unallocated().isEmpty());
    }

    @Test
    void execute_partialAndBlockedPlans_keepStoredEstimatesAndWholePlanRemainders() throws Exception {
        Task task = new Task(1, "Draft", NOW.withHour(8).withMinute(30), 90);
        Commitment busy = new Commitment(1, "Busy", DayOfWeek.MONDAY, LocalTime.MIDNIGHT, 1440);
        for (List<Commitment> commitments : List.of(List.<Commitment>of(), List.of(busy))) {
            Snapshot snapshot = new Snapshot(List.of(task), commitments, 2, 2);
            AppState state = new AppState(snapshot);
            FakeStorage storage = new FakeStorage(snapshot);
            CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
            String output = dispatcher.execute(new Command.GeneratePlan(), state).output();
            int scheduled = commitments.isEmpty() ? 30 : 0;
            assertTrue(output.contains("Scheduled: " + scheduled + " min | Unallocated: " + (90 - scheduled)));
            String schedule = dispatcher.execute(new Command.ScheduleToday(), state).output();
            assertTrue(schedule.contains("Unallocated work for the whole plan:"));
            if (!commitments.isEmpty()) {
                assertTrue(schedule.contains("08:00 - 22:00 | [BUSY] C1: Busy"));
            }
            assertSame(snapshot, state.snapshot());
            assertEquals(90, state.snapshot().tasks().getFirst().remainingMinutes());
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void execute_cutoffBoundaries_preserveSecondsPrecision() throws Exception {
        for (LocalDateTime time : List.of(NOW.withHour(10).withMinute(7), NOW.withHour(10),
                NOW.withHour(10).withSecond(1))) {
            Task task = new Task(1, "Draft", NOW.plusDays(1), 30);
            Snapshot snapshot = new Snapshot(List.of(task), List.of(), 2, 1);
            AppState state = new AppState(snapshot);
            CommandDispatcher dispatcher = dispatcher(new FakeStorage(snapshot),
                    Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            dispatcher.execute(new Command.GeneratePlan(), state);
            Plan plan = state.currentPlan().orElseThrow();
            assertEquals(time, plan.generatedAt());
            assertEquals(time.getMinute() == 0 && time.getSecond() == 0 ? LocalTime.of(10, 0) : LocalTime.of(10, 30),
                    plan.sessions().getFirst().start().toLocalTime());
            assertTrue(dispatcher.execute(new Command.ScheduleToday(), state).output().contains("[UNPLANNED]"));
        }
    }

    @Test
    void execute_loadedTasks_preservesNumericIdsAndReportsPastAndOutsideHorizon() throws Exception {
        Task ten = new Task(10, "Ten", NOW.plusDays(1), 30);
        Task two = new Task(2, "Two", ten.deadline(), 30);
        Task overdue = new Task(1, "Overdue", NOW.minusDays(1), 60);
        Task outside = new Task(11, "Outside", TimeRules.horizonEnd(NOW).plusDays(1), 90);
        Snapshot snapshot = new Snapshot(List.of(ten, two, overdue, outside), List.of(), 12, 1);
        AppState state = new AppState(snapshot);
        CommandDispatcher dispatcher = dispatcher(new FakeStorage(snapshot), CLOCK);
        String result = dispatcher.execute(new Command.GeneratePlan(), state).output();
        assertTrue(result.contains("T1: Overdue | Unallocated: 60 min | Reason: deadline passed"));
        assertTrue(result.contains("T11: Outside | Unallocated: 90 min | Reason: outside planning horizon"));
        assertEquals(List.of(2, 10), state.currentPlan().orElseThrow().sessions().stream()
                .map(StudySession::taskId).toList());
        String tasks = dispatcher.execute(new Command.ListTasks(), state).output();
        assertTrue(tasks.indexOf("T2 |") < tasks.indexOf("T10 |"));
        assertSame(snapshot, state.snapshot());
    }

    /**
     * Wires the real service contracts with controlled storage and time.
     */
    private CommandDispatcher dispatcher(Storage storage, Clock clock) {
        return new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                new EarliestDeadlinePlanner(), new ScheduleService(), renderer, clock);
    }

    /**
     * Supplies gaps and independent next IDs to detect accidental renumbering.
     */
    private Snapshot populatedSnapshot() {
        return new Snapshot(List.of(new Task(2, "Existing", NOW.plusDays(1), 30)),
                List.of(new Commitment(2, "Class", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60)), 3, 3);
    }

    /**
     * Supplies all mutation paths against the same initial records.
     */
    private List<Command> mutations() {
        return List.of(addTask(), new Command.DeleteTask(2), addCommitment(), new Command.DeleteCommitment(2));
    }

    /**
     * Supplies a task within the fixed horizon.
     */
    private Command.AddTask addTask() {
        return new Command.AddTask("New", NOW.plusDays(1), 30);
    }

    /**
     * Supplies a series adjacent to the existing one.
     */
    private Command.AddCommitment addCommitment() {
        return new Command.AddCommitment("New", DayOfWeek.MONDAY, LocalTime.of(11, 0), 30);
    }

    /**
     * States the exact user-facing result for each independent transaction.
     */
    private String mutationOutput(Command command) {
        if (command instanceof Command.AddTask) {
            return "Added T3: New\nDue: 2026-09-22 07:00 | Duration: 30 min";
        }
        if (command instanceof Command.DeleteTask) {
            return "Deleted task T2: Existing";
        }
        if (command instanceof Command.AddCommitment) {
            return "Added C3: New | Monday 11:00 - 11:30 (30 min)";
        }
        return "Deleted commitment C2: Class";
    }

    /**
     * Records save attempts and allows deterministic failure before replacing the stored snapshot.
     */
    private static final class FakeStorage implements Storage {
        private Snapshot saved;
        private Snapshot attempted;
        private int saveCalls;
        private boolean failSave;
        /**
         * Observes live state at the save boundary without controlling production code.
         */
        private Runnable beforeSave = () -> { };

        /**
         * Creates an isolated stored snapshot.
         */
        private FakeStorage(Snapshot initial) {
            saved = initial;
        }

        @Override
        public Snapshot load() {
            return saved;
        }

        @Override
        public void save(Snapshot state) throws StorageException {
            saveCalls++;
            attempted = state;
            beforeSave.run();
            if (failSave) {
                throw new StorageException("Save rejected");
            }
            saved = state;
        }
    }

    /**
     * Advances one second per read to expose repeated clock captures within a command.
     */
    private static final class CountingClock extends Clock {
        private int reads;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(NOW.toInstant(ZoneOffset.UTC).plusSeconds(reads), zone);
        }

        @Override
        public Instant instant() {
            return NOW.toInstant(ZoneOffset.UTC).plusSeconds(reads++);
        }
    }
}
