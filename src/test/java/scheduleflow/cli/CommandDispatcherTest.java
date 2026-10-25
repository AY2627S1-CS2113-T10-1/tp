//@@author ka-how
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.RecurringTask;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskOccurrence;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.Plan;
import scheduleflow.planning.Planner;
import scheduleflow.planning.ScheduleService;
import scheduleflow.planning.StudyPolicy;
import scheduleflow.planning.StudySession;
import scheduleflow.planning.TimeRules;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskEdit;
import scheduleflow.task.TaskService;

/**
 * Verifies every route, save-before-publication transactions and frozen planning through public APIs.
 */
class CommandDispatcherTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final TextRenderer renderer = new TextRenderer();

    @Test
    void execute_reopen_savesBeforePublicationAndPreservesPlanOnFailedSaveUntilRetry() throws Exception {
        Snapshot original = new TaskService().complete(populatedSnapshot(), 2);
        AppState state = new AppState(original);
        Plan prior = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(prior);
        FakeStorage storage = new FakeStorage(original);
        storage.savedPlan = Optional.of(prior);
        storage.beforeSave = () -> {
            assertSame(original, state.snapshot());
            assertSame(prior, state.currentPlan().orElseThrow());
        };
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        Command.ReopenTask command = new Command.ReopenTask(2, 45);
        storage.failSave = true;
        assertThrows(StorageException.class, () -> dispatcher.execute(command, state));
        assertEquals(45, storage.attempted.tasks().getFirst().remainingMinutes());
        assertSame(original, state.snapshot());
        assertSame(prior, state.currentPlan().orElseThrow());
        assertSame(original, storage.saved);
        assertEquals(Optional.of(prior), storage.savedPlan);

        storage.failSave = false;
        CommandResponse response = dispatcher.execute(command, state);
        assertEquals("Reopened T2: Existing | Remaining: 45 min", response.output());
        assertFalse(response.shouldExit());
        assertEquals(new TaskService().reopen(original, 2, 45), state.snapshot());
        assertSame(state.snapshot(), storage.saved);
        assertTrue(state.currentPlan().isEmpty());
        assertTrue(storage.savedPlan.isEmpty());
        assertEquals(2, storage.saveCalls);
        assertEquals(0, original.tasks().getFirst().remainingMinutes());
    }

    @Test
    void execute_reopen_preservesMetadataSettingsStoredOrderAndExhaustedCounters() throws Exception {
        Task other = new Task(8, "Unrelated", NOW.plusDays(2), 15);
        LocalDateTime first = NOW.plusDays(1);
        RecurringTask template = new RecurringTask(2, "Weekly", first, Optional.empty(), 60, Optional.of(first));
        List<Task> completed = List.of(
                new Task(2, "Overdue", Optional.of(NOW.minusDays(1)), 0, TaskPriority.HIGH),
                new Task(2, "Undated", Optional.empty(), 0, TaskPriority.LOW),
                new Task(2, "Occurrence", Optional.of(first), 0, TaskPriority.HIGH,
                        Optional.of(new TaskOccurrence(2, first))));
        for (Task target : completed) {
            Snapshot original = new Snapshot(List.of(other, target), populatedSnapshot().commitments(),
                    Integer.MAX_VALUE, Integer.MAX_VALUE,
                    new StudyHours(Map.of("monday", new StudyWindow(LocalTime.of(9, 0), LocalTime.of(18, 0)))),
                    List.of(template), Integer.MAX_VALUE, Optional.of(new StudyPolicy(60, 30)));
            for (int minutes : new int[] {1, 45, Integer.MAX_VALUE}) {
                AppState state = new AppState(original);
                FakeStorage storage = new FakeStorage(original);
                assertEquals("Reopened T2: " + target.name() + " | Remaining: " + minutes + " min",
                        dispatcher(storage, CLOCK).execute(new Command.ReopenTask(2, minutes), state).output());
                Task expected = new Task(target.id(), target.name(), target.deadline(), minutes,
                        target.priority(), target.occurrence());
                assertEquals(new Snapshot(List.of(other, expected), original.commitments(), original.nextTaskId(),
                        original.nextCommitmentId(), original.studyHours(), original.recurringTasks(),
                        original.nextRecurringTaskId(), original.studyPolicy()), state.snapshot());
                assertSame(state.snapshot(), storage.saved);
                assertEquals(1, storage.saveCalls);
                assertEquals(0, target.remainingMinutes());
            }
        }
    }

    @Test
    void execute_invalidReopen_rejectsWithoutSavingOrInvalidatingPlan() throws Exception {
        Snapshot original = new Snapshot(List.of(new Task(2, "Done", NOW, 0),
                new Task(3, "Active", NOW.plusDays(1), 30)), List.of(), 4, 1);
        AppState state = new AppState(original);
        Plan prior = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(prior);
        FakeStorage storage = new FakeStorage(original);
        storage.savedPlan = Optional.of(prior);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        for (Command.ReopenTask command : List.of(new Command.ReopenTask(2, 0), new Command.ReopenTask(2, -1),
                new Command.ReopenTask(2, Integer.MIN_VALUE), new Command.ReopenTask(0, 45),
                new Command.ReopenTask(99, 45), new Command.ReopenTask(3, 45))) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(command, state));
            assertSame(original, state.snapshot());
            assertSame(prior, state.currentPlan().orElseThrow());
            assertSame(original, storage.saved);
            assertEquals(Optional.of(prior), storage.savedPlan);
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void execute_reopenAndReplan_includesExactWorkAndRemovesTaskFromCompletedView() throws Exception {
        Snapshot original = new Snapshot(List.of(new Task(2, "Draft", NOW.plusHours(3), 0)), List.of(), 3, 1);
        AppState state = new AppState(original);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        dispatcher.execute(new Command.GeneratePlan(), state);
        assertTrue(state.currentPlan().orElseThrow().sessions().isEmpty());
        dispatcher.execute(new Command.ReopenTask(2, 45), state);
        assertEquals("No completed tasks found.",
                dispatcher.execute(new Command.ListCompletedTasks(), state).output());
        dispatcher.execute(new Command.GeneratePlan(), state);
        Plan regenerated = state.currentPlan().orElseThrow();
        assertEquals(List.of(new StudySession(2, NOW.withHour(8), NOW.withHour(9), 45)), regenerated.sessions());
        assertTrue(regenerated.unallocated().isEmpty());
        assertEquals(45, state.snapshot().tasks().getFirst().remainingMinutes());
        assertEquals("Only completed tasks can be reopened.", assertThrows(ValidationException.class,
                () -> dispatcher.execute(new Command.ReopenTask(2, 60), state)).getMessage());
        assertSame(regenerated, state.currentPlan().orElseThrow());
        assertEquals(1, storage.saveCalls);
    }

    // @@author ernestgoh-god
    @Test
    void execute_policyChanges_savesBeforePublicationAndPreservesEverythingOnFailureOrNoop() throws Exception {
        Snapshot original = populatedSnapshot().withStudyPolicy(Optional.of(new StudyPolicy(60, 30)));
        for (Command command : List.of(new Command.SetStudyPolicy(90, 30), new Command.ResetStudyPolicy())) {
            AppState state = new AppState(original);
            Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(plan);
            FakeStorage storage = new FakeStorage(original);
            storage.savedPlan = Optional.of(plan);
            storage.beforeSave = () -> {
                assertSame(original, state.snapshot());
                assertSame(plan, state.currentPlan().orElseThrow());
            };
            CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
            storage.failSave = true;
            assertThrows(StorageException.class, () -> dispatcher.execute(command, state));
            assertSame(original, storage.saved);
            assertEquals(Optional.of(plan), storage.savedPlan);
            storage.failSave = false;
            assertTrue(dispatcher.execute(command, state).output()
                    .endsWith("Saved study policy. Run plan to regenerate."));
            Snapshot changed = state.snapshot();
            assertEquals(original.tasks(), changed.tasks());
            assertEquals(original.commitments(), changed.commitments());
            assertEquals(original.nextTaskId(), changed.nextTaskId());
            assertEquals(original.studyHours(), changed.studyHours());
            assertEquals(original.recurringTasks(), changed.recurringTasks());
            assertTrue(state.currentPlan().isEmpty());
            dispatcher.execute(new Command.GeneratePlan(), state);
            Plan replacement = state.currentPlan().orElseThrow();
            assertTrue(dispatcher.execute(command, state).output().endsWith("Study policy unchanged."));
            dispatcher.execute(new Command.ShowStudyPolicy(), state);
            for (Command.SetStudyPolicy invalid : List.of(new Command.SetStudyPolicy(0, 30),
                    new Command.SetStudyPolicy(90, 45), new Command.SetStudyPolicy(-30, 30))) {
                assertThrows(ValidationException.class, () -> dispatcher.execute(invalid, state));
            }
            assertSame(changed, state.snapshot());
            assertSame(replacement, state.currentPlan().orElseThrow());
            assertEquals(2, storage.saveCalls);
        }
    }

    @Test
    void execute_existingMutations_preserveConfiguredPolicy() throws Exception {
        Snapshot source = pendingRecurrence(false).withStudyPolicy(Optional.of(new StudyPolicy(90, 30)));
        List<Command> commands = List.of(addTask(), new Command.DeleteTask(2), addCommitment(),
                new Command.DeleteCommitment(2), new Command.CompleteTask(2), new Command.RecordTaskWork(2, 1),
                new Command.ReviseTaskRemaining(2, 17), new Command.SetHours("monday", LocalTime.of(9, 0),
                        LocalTime.of(18, 0)), new Command.ResetHours("monday"), new Command.GeneratePlan(),
                new Command.EditTask(2, new TaskEdit(Optional.of("Renamed"), Optional.empty(), false)));
        for (Command command : commands) {
            AppState state = new AppState(source);
            FakeStorage storage = new FakeStorage(source);
            dispatcher(storage, CLOCK).execute(command, state);
            assertEquals(source.studyPolicy(), state.snapshot().studyPolicy(), command.toString());
            assertEquals(source.studyPolicy(), storage.saved.studyPolicy(), command.toString());
        }
    }
    // @@author ka-how

    // @@author ernestgoh-god
    @Test
    void execute_completionRetryAndNoop_preservesMetadataAndPlanUntilSaved() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan prior = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(prior);
        FakeStorage storage = new FakeStorage(original);
        storage.savedPlan = Optional.of(prior);
        storage.beforeSave = () -> {
            assertSame(original, state.snapshot());
            assertSame(prior, state.currentPlan().orElseThrow());
        };
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        storage.failSave = true;
        assertThrows(StorageException.class, () -> dispatcher.execute(new Command.CompleteTask(2), state));
        assertEquals(0, storage.attempted.tasks().getFirst().remainingMinutes());
        assertSame(original, storage.saved);
        assertEquals(Optional.of(prior), storage.savedPlan);
        assertThrows(ValidationException.class, () -> dispatcher.execute(new Command.CompleteTask(99), state));
        assertEquals(1, storage.saveCalls);
        storage.failSave = false;
        assertEquals("Completed T2: Existing", dispatcher.execute(new Command.CompleteTask(2), state).output());
        assertTrue(state.currentPlan().isEmpty());
        assertTrue(storage.savedPlan.isEmpty());
        Snapshot completed = state.snapshot();
        assertEquals(new TaskService().complete(original, 2), completed);
        dispatcher.execute(new Command.GeneratePlan(), state);
        Plan newPlan = state.currentPlan().orElseThrow();
        assertTrue(newPlan.sessions().isEmpty());
        dispatcher.execute(new Command.CompleteTask(2), state);
        assertSame(completed, state.snapshot());
        assertSame(newPlan, state.currentPlan().orElseThrow());
        assertEquals(2, storage.saveCalls);
    }

    @Test
    void execute_completedView_filtersAndSortsWithoutSavingOrInvalidatingPlan() throws Exception {
        Snapshot source = new Snapshot(List.of(new Task(8, "Done", NOW, 0),
                new Task(3, "Active", NOW, 30), new Task(2, "Earlier", NOW, 0)), List.of(), 9, 1);
        AppState state = new AppState(source);
        Plan plan = new EarliestDeadlinePlanner().generate(source, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(source);
        assertEquals("=== Completed Tasks ===\nID | Name | Duration | Deadline\n"
                + "T2 | Earlier | 0 min | 2026-09-21 07:00\nT8 | Done | 0 min | 2026-09-21 07:00\nTotal: 2 task(s).",
                dispatcher(storage, CLOCK).execute(new Command.ListCompletedTasks(), state).output());
        assertSame(source, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
        assertEquals(0, storage.planSaveCalls);
    }
    // @@author ka-how

    @Test
    void execute_taskEdit_subsetsSaveBeforePublicationAndRetryAtomically() throws Exception {
        for (String fields : List.of("n/Changed", "due/2026-09-23 0905", "n/Changed due/2026-09-23 0905")) {
            Snapshot original = populatedSnapshot();
            AppState state = new AppState(original);
            Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(plan);
            FakeStorage storage = new FakeStorage(original);
            storage.beforeSave = () -> {
                assertSame(original, state.snapshot());
                assertSame(plan, state.currentPlan().orElseThrow());
            };
            Command command = new CommandParser().parse("task edit T2 " + fields);
            CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
            storage.failSave = true;
            assertThrows(StorageException.class, () -> dispatcher.execute(command, state));
            assertSame(original, storage.saved);
            Snapshot attempted = storage.attempted;
            storage.failSave = false;
            String output = dispatcher.execute(command, state).output();
            assertEquals(attempted, state.snapshot());
            assertSame(storage.saved, state.snapshot());
            assertEquals(renderer.taskEdited(state.snapshot().tasks().getFirst()), output);
            assertEquals(2, storage.saveCalls);
            assertEquals(2, state.snapshot().tasks().getFirst().id());
            assertEquals(30, state.snapshot().tasks().getFirst().remainingMinutes());
            assertEquals(original.nextTaskId(), state.snapshot().nextTaskId());
            assertTrue(state.currentPlan().isEmpty());
        }
    }

    @Test
    void execute_taskEdit_normalizedNoopsAndInvalidValuesPreservePlan() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        CommandParser parser = new CommandParser();
        for (String fields : List.of("n/ Existing ", "due/2026-09-22 0700",
                "n/ Existing due/2026-09-22 0700")) {
            dispatcher.execute(parser.parse("task edit T2 " + fields), state);
        }
        for (String input : List.of("task edit T2 n/", "task edit T99 n/Unknown",
                "task edit T2 due/2026-09-21 0700", "task edit T2 due/2027-09-22 0000")) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(parser.parse(input), state));
        }
        assertSame(original, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_recurrenceAddDelete_failedSavesRetrySameAtomicSnapshot() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        Command add = new CommandParser().parse("task recur add n/Review due/2026-09-22 0905"
                + " d/60 every/WEEKLY until/2026-10-06");
        for (Command command : List.of(add, new Command.DeleteRecurringTask(1))) {
            Snapshot before = state.snapshot();
            Plan plan = new EarliestDeadlinePlanner().generate(before, NOW);
            state.replacePlan(plan);
            storage.beforeSave = () -> {
                assertSame(before, state.snapshot());
                assertSame(plan, state.currentPlan().orElseThrow());
            };
            int saves = storage.saveCalls;
            storage.failSave = true;
            assertThrows(StorageException.class, () -> dispatcher.execute(command, state));
            assertSame(before, storage.saved);
            Snapshot attempted = storage.attempted;
            storage.failSave = false;
            dispatcher.execute(command, state);
            assertEquals(attempted, state.snapshot());
            assertEquals(saves + 2, storage.saveCalls);
            assertTrue(state.currentPlan().isEmpty());
            assertEquals(6, state.snapshot().nextTaskId());
            assertEquals(2, state.snapshot().nextRecurringTaskId());
            assertEquals(List.of(2, 3, 4, 5), state.snapshot().tasks().stream().map(Task::id).toList());
            assertTrue(state.snapshot().tasks().stream().skip(1).allMatch(task -> task.occurrence().isPresent()));
            if (command instanceof Command.AddRecurringTask) {
                assertEquals(Optional.of(LocalDateTime.of(2026, 10, 6, 9, 5)),
                        state.snapshot().recurringTasks().getFirst().generatedThrough());
            } else {
                assertEquals(before.tasks(), state.snapshot().tasks());
                assertTrue(state.snapshot().recurringTasks().isEmpty());
            }
        }
    }

    @Test
    void execute_otherMutationsAndReadOnlyList_preserveRecurrenceMetadata() throws Exception {
        Snapshot original = pendingRecurrence(false);
        for (Command command : List.of(addTask(), new Command.DeleteTask(2), addCommitment(),
                new Command.DeleteCommitment(2), new CommandParser().parse("task edit T2 n/Renamed"),
                new Command.ReviseTaskRemaining(2, 60), new Command.SetHours("monday",
                        LocalTime.of(9, 0), LocalTime.of(18, 0)))) {
            AppState state = new AppState(original);
            FakeStorage storage = new FakeStorage(original);
            dispatcher(storage, CLOCK).execute(command, state);
            assertEquals(original.recurringTasks(), state.snapshot().recurringTasks());
            assertEquals(original.nextRecurringTaskId(), state.snapshot().nextRecurringTaskId());
        }
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        assertEquals(renderer.recurringTasks(original.recurringTasks()),
                dispatcher(storage, CLOCK).execute(new Command.ListRecurringTasks(), state).output());
        assertSame(original, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_plan_materializesBeforePlanningAndSavesBeforePublishingIncludingCursorOnly() throws Exception {
        for (boolean past : List.of(false, true)) {
            Snapshot original = pendingRecurrence(past);
            AppState state = new AppState(original);
            Plan prior = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(prior);
            FakeStorage storage = new FakeStorage(original);
            // @@author ernestgoh-god
            storage.savedPlan = Optional.of(prior);
            // @@author ka-how
            storage.beforeSave = () -> {
                assertSame(original, state.snapshot());
                assertSame(prior, state.currentPlan().orElseThrow());
            };
            Planner planner = (source, now) -> {
                assertEquals(NOW, now);
                assertEquals(past ? 1 : 3, source.tasks().size());
                assertTrue(source.recurringTasks().getFirst().generatedThrough().isPresent());
                return new EarliestDeadlinePlanner().generate(source, now);
            };
            // @@author ernestgoh-god
            storage.beforePlanSave = storage.beforeSave;
            // @@author ka-how
            CommandDispatcher dispatcher = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                    planner, new ScheduleService(), renderer, CLOCK);
            storage.failSave = true;
            assertThrows(StorageException.class, () -> dispatcher.execute(new Command.GeneratePlan(), state));
            assertSame(original, state.snapshot());
            assertSame(prior, state.currentPlan().orElseThrow());
            // @@author ernestgoh-god
            assertSame(original, storage.saved);
            assertEquals(Optional.of(prior), storage.savedPlan);
            Snapshot attempted = storage.attempted;
            // @@author ka-how
            storage.failSave = false;
            dispatcher.execute(new Command.GeneratePlan(), state);
            assertSame(storage.saved, state.snapshot());
            assertSame(state.snapshot(), state.currentPlan().orElseThrow().source());
            // @@author ernestgoh-god
            assertEquals(attempted, storage.saved);
            assertEquals(0, storage.saveCalls);
            assertEquals(2, storage.planSaveCalls);
            assertEquals(state.currentPlan(), storage.savedPlan);
            storage.beforePlanSave = () -> { };
            dispatcher.execute(new Command.GeneratePlan(), state);
            assertEquals(3, storage.planSaveCalls);
            assertEquals(0, storage.saveCalls);
            // @@author ka-how
        }
    }

    @Test
    void execute_planRejectedAfterMaterialization_preservesSourcePlanAndDisk() {
        Snapshot original = pendingRecurrence(false);
        List<Planner> invalid = List.of((source, now) -> null,
                (source, now) -> new EarliestDeadlinePlanner().generate(original, now),
                (source, now) -> {
                    throw new IllegalStateException("Planner defect");
                });
        for (Planner planner : invalid) {
            AppState state = new AppState(original);
            Plan prior = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(prior);
            FakeStorage storage = new FakeStorage(original);
            CommandDispatcher dispatcher = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                    planner, new ScheduleService(), renderer, CLOCK);
            assertThrows(RuntimeException.class, () -> dispatcher.execute(new Command.GeneratePlan(), state));
            assertSame(original, state.snapshot());
            assertSame(original, storage.saved);
            assertSame(prior, state.currentPlan().orElseThrow());
            assertEquals(0, storage.saveCalls);
        }
    }

    /** Supplies an unprocessed bounded template for generation and cursor-only transactions. */
    private Snapshot pendingRecurrence(boolean past) {
        Snapshot base = populatedSnapshot();
        LocalDateTime first = past ? NOW.minusWeeks(2) : NOW.plusDays(1);
        RecurringTask template = new RecurringTask(2, "Review", first,
                Optional.of(first.toLocalDate().plusWeeks(1)), 30, Optional.empty());
        return new Snapshot(base.tasks(), base.commitments(), base.nextTaskId(), base.nextCommitmentId(),
                base.studyHours(), List.of(template), 3);
    }

    // @@author GabrielYuSP
    @Test
    void execute_hoursMutations_saveBeforeInvalidatingAndPreserveSettingsAcrossOtherChanges() throws Exception {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        AppState state = new AppState(storage.saved);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        CommandParser parser = new CommandParser();
        dispatcher.execute(parser.parse("hours tuesday /start 09:00 /end 22:00"), state);
        dispatcher.execute(parser.parse("hours 2026-10-27 /start 14:00 /end 19:00"), state);
        var expected = state.snapshot().studyHours();
        dispatcher.execute(addTask(), state);
        dispatcher.execute(addCommitment(), state);
        dispatcher.execute(new Command.DeleteTask(1), state);
        dispatcher.execute(new Command.DeleteCommitment(1), state);
        assertEquals(expected, state.snapshot().studyHours());
        dispatcher.execute(new Command.GeneratePlan(), state);
        var plan = state.currentPlan().orElseThrow();
        int saves = storage.saveCalls;
        assertEquals("Study hours for 2026-10-27: 14:00 - 19:00",
                dispatcher.execute(parser.parse("hours 2026-10-27"), state).output());
        assertEquals(saves, storage.saveCalls);
        assertSame(plan, state.currentPlan().orElseThrow());
        for (String input : List.of("hours tuesday /start 10:00 /end 18:00", "hours reset 2026-10-27")) {
            storage.failSave = true;
            assertThrows(StorageException.class, () -> dispatcher.execute(parser.parse(input), state));
            assertEquals(expected, state.snapshot().studyHours());
            assertSame(plan, state.currentPlan().orElseThrow());
        }
        storage.failSave = false;
        assertThrows(ValidationException.class, () -> dispatcher.execute(
                parser.parse("hours tuesday /start 22:00 /end 09:00"), state));
        assertSame(plan, state.currentPlan().orElseThrow());
        assertEquals("Reset study hours for 2026-10-27.\nUsing recurring weekday hours: 09:00 - 22:00.\n"
                + "Run plan to regenerate.",
                dispatcher.execute(parser.parse("hours reset 2026-10-27"), state).output());
        assertTrue(state.currentPlan().isEmpty());
        assertEquals("Study hours for 2026-10-27: 09:00 - 22:00",
                dispatcher.execute(parser.parse("hours 2026-10-27"), state).output());
        assertEquals(state.snapshot(), storage.saved);
        assertEquals("Reset study hours for tuesday.\nUsing default hours: 08:00 - 22:00.\nRun plan to regenerate.",
                dispatcher.execute(parser.parse("hours reset tuesday"), state).output());
        assertEquals("Reset study hours for 2026-10-27.\nUsing default hours: 08:00 - 22:00.\n"
                + "Run plan to regenerate.",
                dispatcher.execute(parser.parse("hours reset 2026-10-27"), state).output());
    }

    // @@author ka-how
    @Test
    void execute_nullArguments_rejectsBeforeClockReadsOrSaves() {
        CountingClock clock = new CountingClock();
        AppState state = new AppState(Snapshot.empty());
        FakeStorage storage = new FakeStorage(state.snapshot());
        CommandDispatcher dispatcher = dispatcher(storage, clock);
        assertEquals("command",
                assertThrows(NullPointerException.class, () -> dispatcher.execute(null, state)).getMessage());
        assertEquals("state",
                assertThrows(NullPointerException.class,
                        () -> dispatcher.execute(new Command.Help(), null)).getMessage());
        assertEquals(0, clock.reads);
        assertEquals(0, storage.saveCalls);
        assertSame(storage.saved, state.snapshot());
    }

    @Test
    void execute_invalidPlannerResults_preservesExistingSnapshotAndPlan() {
        Snapshot snapshot = populatedSnapshot();
        AppState state = new AppState(snapshot);
        Plan original = new EarliestDeadlinePlanner().generate(snapshot, NOW);
        state.replacePlan(original);
        FakeStorage storage = new FakeStorage(snapshot);
        Planner missing = (source, now) -> null;
        CommandDispatcher missingResult = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                missing, new ScheduleService(), renderer, CLOCK);
        assertEquals("generated plan", assertThrows(NullPointerException.class,
                () -> missingResult.execute(new Command.GeneratePlan(), state)).getMessage());
        Planner stale = (source, now) -> new EarliestDeadlinePlanner().generate(Snapshot.empty(), now);
        CommandDispatcher staleResult = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                stale, new ScheduleService(), renderer, CLOCK);
        assertThrows(IllegalArgumentException.class, () -> staleResult.execute(new Command.GeneratePlan(), state));
        assertSame(snapshot, state.snapshot());
        assertSame(original, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void execute_allCommands_routesAndCapturesClockExactlyOnce() throws Exception {
        CountingClock clock = new CountingClock();
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        AppState state = new AppState(storage.saved);
        CommandDispatcher dispatcher = dispatcher(storage, clock);
        List<Command> commands = List.of(new Command.Help(), addTask(), new Command.ListTasks(),
                new Command.DeleteTask(1), addCommitment(),
                new Command.SkipCommitment(1, NOW.toLocalDate()), new Command.ListCommitments(),
                new Command.DeleteCommitment(1), new Command.GeneratePlan(), new Command.ScheduleToday(),
                new Command.ScheduleDate(NOW.toLocalDate()), new Command.Exit());
        for (Command command : commands) {
            CommandResponse response = dispatcher.execute(command, state);
            assertFalse(response.output().isBlank());
            assertEquals(command instanceof Command.Exit, response.shouldExit());
        }
        assertEquals(commands.size(), clock.reads);
        assertEquals(5, storage.saveCalls);
        assertEquals(NOW.plusSeconds(8), state.currentPlan().orElseThrow().generatedAt());
        assertEquals(Snapshot.empty().tasks(), state.snapshot().tasks());
    }

    //@@author Darren50
    @Test
    void execute_skipCommitment_savesExceptionAndInvalidatesPlan() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        state.replacePlan(new EarliestDeadlinePlanner().generate(original, NOW));
        FakeStorage storage = new FakeStorage(original);

        CommandResponse response = dispatcher(storage, CLOCK).execute(
                new Command.SkipCommitment(2, NOW.toLocalDate()), state);

        assertEquals("Skipped C2 on 2026-09-21: Class", response.output());
        assertEquals(1, storage.saveCalls);
        assertSame(storage.saved, state.snapshot());
        assertEquals(List.of(NOW.toLocalDate()), state.snapshot().commitments().getFirst()
                .skippedDates().stream().toList());
        assertTrue(state.currentPlan().isEmpty());
        assertTrue(original.commitments().getFirst().skippedDates().isEmpty());
    }

    @Test
    void execute_oneTimeCommitment_savesBeforePublishingAndBlocksOnlyThatDate() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        CommandParser parser = new CommandParser();
        Command add = parser.parse("commitment add n/Meeting on/2026-09-21 start/1100 d/60");

        storage.failSave = true;
        assertThrows(StorageException.class, () -> dispatcher.execute(add, state));
        assertSame(original, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        storage.failSave = false;
        assertEquals("Added C3: Meeting | One-time on 2026-09-21 (Monday) 11:00 - 12:00 (60 min)",
                dispatcher.execute(add, state).output());
        assertSame(storage.saved, state.snapshot());
        assertTrue(state.currentPlan().isEmpty());
        assertEquals(4, state.snapshot().nextCommitmentId());

        int saves = storage.saveCalls;
        Snapshot added = state.snapshot();
        for (String input : List.of("commitment add n/Clash on/2026-09-21 start/1030 d/60",
                "commitment add n/Weekly day/MON start/1130 d/30", "commitment skip C3 on/2026-09-21")) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(parser.parse(input), state));
        }
        assertSame(added, state.snapshot());
        assertEquals(saves, storage.saveCalls);

        dispatcher.execute(new Command.GeneratePlan(), state);
        String today = dispatcher.execute(new Command.ScheduleToday(), state).output();
        assertTrue(today.contains("10:00 - 11:00 | [BUSY] C2: Class\n11:00 - 12:00 | [BUSY] C3: Meeting"));
        String nextWeek = dispatcher.execute(new Command.ScheduleDate(NOW.toLocalDate().plusWeeks(1)), state)
                .output();
        assertTrue(nextWeek.contains("10:00 - 11:00 | [BUSY] C2: Class\n11:00 - 22:00 | [FREE]"));
        assertTrue(dispatcher.execute(new Command.ListCommitments(), state).output()
                .endsWith("=== One-time Commitments ===\nC3. 2026-09-21 (Monday) 11:00 - 12:00 | Meeting"
                        + "\nTotal: 1 one-time commitments."));
        assertEquals("Deleted commitment C3: Meeting",
                dispatcher.execute(new Command.DeleteCommitment(3), state).output());
        assertEquals(List.of(2), state.snapshot().commitments().stream().map(Commitment::id).toList());
    }
    //@@author ka-how

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
                new Command.AddTask("Bad duration", NOW.plusDays(1), 0),
                new Command.AddCommitment("Unaligned", DayOfWeek.MONDAY, LocalTime.of(10, 15), 30),
                new Command.AddCommitment("Overlap", DayOfWeek.MONDAY, LocalTime.of(10, 30), 30),
                new Command.AddCommitment("Too long", DayOfWeek.MONDAY, LocalTime.of(23, 30), 60),
                new Command.DeleteTask(99), new Command.DeleteCommitment(99),
                new Command.SkipCommitment(99, NOW.toLocalDate()),
                new Command.SkipCommitment(2, NOW.toLocalDate().plusDays(1)),
                new Command.EditCommitment(99, "Missing", null, null, null),
                new Command.EditCommitment(2, null, null, null, null),
                new Command.EditCommitment(2, null, null, LocalTime.of(23, 30), 60));
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
        assertEquals(command.deadline(), state.snapshot().tasks().getFirst().deadline().orElseThrow());
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
        Task two = new Task(2, "Two", ten.deadline().orElseThrow(), 30);
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

    // @@author ka-how
    @Test
    void execute_progressCommands_saveBeforePublicationAndInvalidatePlan() throws Exception {
        Map<Command, Integer> expectedCredits = Map.of(new Command.RecordTaskWork(2, 15), 15,
                new Command.RecordTaskWork(2, 30), 30, new Command.RecordTaskSession(2, 61, 25), 15,
                new Command.RecordTaskSession(2, 60, 50), 30);
        for (var entry : expectedCredits.entrySet()) {
            Command command = entry.getKey();
            int credited = entry.getValue();
            Snapshot original = populatedSnapshot();
            AppState state = new AppState(original);
            Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
            state.replacePlan(plan);
            FakeStorage storage = new FakeStorage(original);
            storage.beforeSave = () -> {
                assertSame(original, state.snapshot());
                assertSame(plan, state.currentPlan().orElseThrow());
            };
            String output = dispatcher(storage, CLOCK).execute(command, state).output();
            assertEquals("Recorded progress for T2: Existing | Credited: " + credited
                    + " min | Remaining: " + (30 - credited) + " min", output);
            assertEquals(1, storage.saveCalls);
            assertSame(storage.saved, state.snapshot());
            assertTrue(state.currentPlan().isEmpty());
            assertEquals(30, original.tasks().getFirst().remainingMinutes());
            assertEquals(30 - credited, state.snapshot().tasks().getFirst().remainingMinutes());
            assertEquals(original.commitments(), state.snapshot().commitments());
            assertEquals(original.studyHours(), state.snapshot().studyHours());
            assertEquals(original.nextTaskId(), state.snapshot().nextTaskId());
            assertEquals(original.nextCommitmentId(), state.snapshot().nextCommitmentId());
            dispatcher(storage, CLOCK).execute(new Command.GeneratePlan(), state);
            assertEquals(30 - credited, state.currentPlan().orElseThrow().sessions().stream()
                    .mapToInt(StudySession::workMinutes).sum());
        }
    }

    @Test
    void execute_zeroProgress_preservesPlanAndAvoidsSaving() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        for (Command command : List.of(new Command.RecordTaskWork(2, 0),
                new Command.RecordTaskSession(2, 60, 0), new Command.RecordTaskSession(2, 1, 1))) {
            assertEquals("Recorded progress for T2: Existing | Credited: 0 min | Remaining: 30 min",
                    dispatcher.execute(command, state).output());
            assertSame(original, state.snapshot());
            assertSame(original, storage.saved);
            assertSame(plan, state.currentPlan().orElseThrow());
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void execute_progressSaveFailure_preservesStateAndPlanThenRetriesOnce() throws Exception {
        for (Command command : List.of(new Command.RecordTaskWork(2, 15),
                new Command.RecordTaskSession(2, 61, 25))) {
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
            assertEquals(15, storage.attempted.tasks().getFirst().remainingMinutes());
            storage.failSave = false;
            assertEquals("Recorded progress for T2: Existing | Credited: 15 min | Remaining: 15 min",
                    dispatcher.execute(command, state).output());
            assertEquals(2, storage.saveCalls);
            assertSame(storage.saved, state.snapshot());
            assertEquals(15, state.snapshot().tasks().getFirst().remainingMinutes());
            assertTrue(state.currentPlan().isEmpty());
        }
    }

    // @@author ernestgoh-god
    @Test
    void execute_invalidProgress_rejectsBeforeSavingOrChangingLiveState() {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        for (Command command : List.of(new Command.RecordTaskWork(99, 1),
                new Command.RecordTaskWork(2, 31), new Command.RecordTaskSession(2, 60, 100))) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(command, state));
            assertSame(original, state.snapshot());
            assertSame(plan, state.currentPlan().orElseThrow());
            assertEquals(0, storage.saveCalls);
        }
    }
    // @@author ka-how

    //@@author Darren50
    @Test
    void execute_edit_replansAgainstReplacementAndKeepsPlanOnConflict() throws Exception {
        AppState state = new AppState(populatedSnapshot());
        FakeStorage storage = new FakeStorage(state.snapshot());
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);
        dispatcher.execute(new Command.GeneratePlan(), state);
        assertEquals(LocalTime.of(8, 0), state.currentPlan().orElseThrow().sessions().getFirst().start().toLocalTime());
        dispatcher.execute(new Command.EditCommitment(2, null, null, LocalTime.of(8, 0), null), state);
        assertTrue(state.currentPlan().isEmpty());
        dispatcher.execute(new Command.GeneratePlan(), state);
        assertEquals(LocalTime.of(9, 0), state.currentPlan().orElseThrow().sessions().getFirst().start().toLocalTime());
        dispatcher.execute(new Command.AddCommitment("Other", DayOfWeek.MONDAY, LocalTime.of(10, 0), 60), state);
        dispatcher.execute(new Command.GeneratePlan(), state);
        Snapshot original = state.snapshot();
        Plan plan = state.currentPlan().orElseThrow();
        int saves = storage.saveCalls;
        assertThrows(ValidationException.class, () -> dispatcher.execute(
                new Command.EditCommitment(2, "Rejected", null, LocalTime.of(10, 0), null), state));
        assertSame(original, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
        assertSame(original, storage.saved);
        assertEquals(saves, storage.saveCalls);
    }
    //@@author ka-how

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

    // @@author ernestgoh-god
    @Test
    void execute_reviseTaskRemaining_savesBeforePublicationAndInvalidatesPlan() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        storage.beforeSave = () -> {
            assertSame(original, state.snapshot());
            assertSame(plan, state.currentPlan().orElseThrow());
        };

        String output = dispatcher(storage, CLOCK).execute(new Command.ReviseTaskRemaining(2, 90), state).output();

        assertEquals("Remaining time for T2: Existing | 90 min", output);
        assertEquals(1, storage.saveCalls);
        assertSame(storage.saved, state.snapshot());
        assertTrue(state.currentPlan().isEmpty());
        assertEquals(30, original.tasks().getFirst().remainingMinutes());
        assertEquals(90, state.snapshot().tasks().getFirst().remainingMinutes());
        assertEquals(original.commitments(), state.snapshot().commitments());
        assertEquals(original.studyHours(), state.snapshot().studyHours());
        assertEquals(original.nextTaskId(), state.snapshot().nextTaskId());
        assertEquals(original.nextCommitmentId(), state.snapshot().nextCommitmentId());
        dispatcher(storage, CLOCK).execute(new Command.GeneratePlan(), state);
        assertEquals(90, state.currentPlan().orElseThrow().sessions().stream()
                .mapToInt(StudySession::minutes).sum());
    }

    @Test
    void execute_reviseTaskRemaining_unchangedOrInvalidPreservesPlanAndDisk() throws Exception {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        CommandDispatcher dispatcher = dispatcher(storage, CLOCK);

        assertEquals("Remaining time for T2: Existing | 30 min",
                dispatcher.execute(new Command.ReviseTaskRemaining(2, 30), state).output());
        assertSame(original, state.snapshot());
        assertSame(original, storage.saved);
        assertSame(plan, state.currentPlan().orElseThrow());
        assertEquals(0, storage.saveCalls);
        for (Command.ReviseTaskRemaining command : List.of(new Command.ReviseTaskRemaining(99, 90),
                new Command.ReviseTaskRemaining(2, 0), new Command.ReviseTaskRemaining(2, -1))) {
            assertThrows(ValidationException.class, () -> dispatcher.execute(command, state));
            assertSame(original, state.snapshot());
            assertSame(original, storage.saved);
            assertSame(plan, state.currentPlan().orElseThrow());
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void execute_reviseTaskRemaining_failedSaveRetainsTaskAndPlan() {
        Snapshot original = populatedSnapshot();
        AppState state = new AppState(original);
        Plan plan = new EarliestDeadlinePlanner().generate(original, NOW);
        state.replacePlan(plan);
        FakeStorage storage = new FakeStorage(original);
        storage.failSave = true;

        StorageException failure = assertThrows(StorageException.class,
                () -> dispatcher(storage, CLOCK).execute(new Command.ReviseTaskRemaining(2, 90), state));

        assertEquals("Save rejected", failure.getMessage());
        assertEquals(1, storage.saveCalls);
        assertEquals(90, storage.attempted.tasks().getFirst().remainingMinutes());
        assertSame(original, storage.saved);
        assertSame(original, state.snapshot());
        assertSame(plan, state.currentPlan().orElseThrow());
    }
    // @@author ka-how

    /**
     * Supplies all mutation paths against the same initial records.
     */
    private List<Command> mutations() {
        return List.of(addTask(), new Command.DeleteTask(2), addCommitment(), new Command.DeleteCommitment(2),
                new Command.EditCommitment(2, "Edited", null, LocalTime.NOON, 30),
                new Command.EditCommitment(2, "Class", null, null, null),
                new Command.SkipCommitment(2, NOW.toLocalDate()));
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
        if (command instanceof Command.EditCommitment edit) {
            return edit.name().equals("Class") ? "Edited C2: Class | Monday 10:00 - 11:00 (60 min)"
                    : "Edited C2: Edited | Monday 12:00 - 12:30 (30 min)";
        }
        if (command instanceof Command.SkipCommitment) {
            return "Skipped C2 on 2026-09-21: Class";
        }
        return "Deleted commitment C2: Class";
    }

    /**
     * Records save attempts and allows deterministic failure before replacing the stored snapshot.
     */
    private static final class FakeStorage implements Storage {
        private Snapshot saved;
        // @@author ernestgoh-god
        private Optional<Plan> savedPlan = Optional.empty();
        private int planSaveCalls;
        /** Observes source/plan publication at the combined plan-save boundary. */
        private Runnable beforePlanSave = () -> { };
        // @@author ka-how
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

        // @@author ernestgoh-god
        @Override
        public Optional<Plan> loadPlan(Snapshot source) throws StorageException {
            if (!saved.equals(source)) {
                throw new StorageException("Plan source differs from saved data");
            }
            return savedPlan;
        }
        // @@author ka-how

        @Override
        public void save(Snapshot state) throws StorageException {
            saveCalls++;
            attempted = state;
            beforeSave.run();
            if (failSave) {
                throw new StorageException("Save rejected");
            }
            saved = state;
            // @@author ernestgoh-god
            savedPlan = Optional.empty();
            // @@author ka-how
        }

        // @@author ernestgoh-god
        @Override
        public void savePlan(Plan plan) throws StorageException {
            planSaveCalls++;
            attempted = plan.source();
            beforePlanSave.run();
            if (failSave) {
                throw new StorageException("Plan save rejected");
            }
            saved = plan.source();
            savedPlan = Optional.of(plan);
        }
        // @@author ka-how
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
