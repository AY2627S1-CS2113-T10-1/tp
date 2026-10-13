package scheduleflow.cli;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.planning.Plan;
import scheduleflow.planning.Planner;
import scheduleflow.planning.ScheduleService;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskService;

/**
 * Routes parsed commands and owns the candidate, save, commit, response transaction order.
 */
public final class CommandDispatcher {
    private final TaskService tasks;
    private final CommitmentService commitments;
    private final Storage storage;
    private final Planner planner;
    private final ScheduleService schedules;
    private final TextRenderer renderer;
    private final Clock clock;

    /**
     * Creates the CommandDispatcher dependencies without performing I/O.
     */
    public CommandDispatcher(TaskService tasks, CommitmentService commitments, Storage storage,
            Planner planner, ScheduleService schedules, TextRenderer renderer, Clock clock) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.commitments = Objects.requireNonNull(commitments, "commitments");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Captures time once and executes the command while preserving state on validation or save failure.
     *
     * @throws StorageException if a candidate cannot be saved; live state remains unchanged
     */
    public CommandResponse execute(Command command, AppState state) throws StorageException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
        LocalDateTime now = LocalDateTime.now(clock);
        String output = switch (command) {
            case Command.Help ignored -> renderer.help();
            case Command.AddTask add -> addTask(add, state, now);
            case Command.ListTasks ignored -> renderer.tasks(tasks.list(state.snapshot()));
            case Command.DeleteTask delete -> deleteTask(delete.id(), state);
            case Command.AddCommitment add -> addCommitment(add, state);
            case Command.ListCommitments ignored -> renderer.commitments(commitments.list(state.snapshot()));
            case Command.DeleteCommitment delete -> deleteCommitment(delete.id(), state);
            case Command.GeneratePlan ignored -> generatePlan(state, now);
            case Command.ScheduleToday ignored -> showSchedule(state, now.toLocalDate());
            case Command.ScheduleDate schedule -> showSchedule(state, schedule.date());
            case Command.Exit ignored -> renderer.exit();
        };
        return new CommandResponse(output, command instanceof Command.Exit);
    }

    /**
     * Resolves the allocated ID from the validated candidate before saving and publishing it.
     */
    private String addTask(Command.AddTask command, AppState state, LocalDateTime now) throws StorageException {
        Snapshot original = state.snapshot();
        Snapshot candidate = tasks.add(original, command.name(), command.deadline(), command.minutes(), now);
        Task added = candidate.tasks().stream().filter(task -> task.id() == original.nextTaskId())
                .findFirst().orElseThrow();
        saveAndCommit(candidate, state);
        return renderer.taskAdded(added);
    }

    /**
     * Retains the removed record for output after the service has validated its ID.
     */
    private String deleteTask(int id, AppState state) throws StorageException {
        Snapshot original = state.snapshot();
        Snapshot candidate = tasks.delete(original, id);
        Task removed = original.tasks().stream().filter(task -> task.id() == id).findFirst().orElseThrow();
        saveAndCommit(candidate, state);
        return renderer.taskDeleted(removed);
    }

    /**
     * Saves a validated weekly series before clearing the temporary plan or announcing success.
     */
    private String addCommitment(Command.AddCommitment command, AppState state) throws StorageException {
        Snapshot original = state.snapshot();
        Snapshot candidate = commitments.add(original, command.name(), command.day(), command.start(),
                command.minutes());
        Commitment added = candidate.commitments().stream()
                .filter(commitment -> commitment.id() == original.nextCommitmentId()).findFirst().orElseThrow();
        saveAndCommit(candidate, state);
        return renderer.commitmentAdded(added);
    }

    /**
     * Retains the validated original series so deletion output includes its stable ID and name.
     */
    private String deleteCommitment(int id, AppState state) throws StorageException {
        Snapshot original = state.snapshot();
        Snapshot candidate = commitments.delete(original, id);
        Commitment removed = original.commitments().stream()
                .filter(commitment -> commitment.id() == id).findFirst().orElseThrow();
        saveAndCommit(candidate, state);
        return renderer.commitmentDeleted(removed);
    }

    /**
     * Publishes only successfully saved candidates; storage failures propagate to the application.
     */
    private void saveAndCommit(Snapshot candidate, AppState state) throws StorageException {
        Objects.requireNonNull(candidate, "candidate");
        storage.save(candidate);
        state.commit(candidate);
    }

    /**
     * Replaces the complete temporary plan without changing or saving task estimates.
     */
    private String generatePlan(AppState state, LocalDateTime now) {
        Plan plan = planner.generate(state.snapshot(), now);
        state.replacePlan(plan);
        return renderer.plan(plan);
    }

    /**
     * Gives absent plans precedence over date-range validation and projects existing frozen plans.
     */
    private String showSchedule(AppState state, LocalDate date) {
        if (state.currentPlan().isEmpty()) {
            return renderer.noPlan();
        }
        return renderer.schedule(schedules.forDate(state.currentPlan().orElseThrow(), date));
    }
}
