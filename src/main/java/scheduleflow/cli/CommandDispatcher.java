//@@author ka-how
package scheduleflow.cli;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.common.ValidationException;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
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
    /**
     * Records command stages and save boundaries without including record names.
     */
    private static final Logger LOGGER = Logger.getLogger(CommandDispatcher.class.getName());

    /**
     * Validates task mutations and supplies ordered task lists.
     */
    private final TaskService tasks;
    /**
     * Validates weekly-series mutations and supplies ordered commitments.
     */
    private final CommitmentService commitments;
    /**
     * Persists candidates before they become live state.
     */
    private final Storage storage;
    /**
     * Generates temporary plans from the current snapshot and captured time.
     */
    private final Planner planner;
    /**
     * Projects an existing plan for a requested date.
     */
    private final ScheduleService schedules;
    /**
     * Formats results after their state transitions succeed.
     */
    private final TextRenderer renderer;
    /**
     * Supplies the single time capture for each command execution.
     */
    private final Clock clock;

    /**
     * Creates the CommandDispatcher dependencies without performing I/O.
     *
     * @param tasks task operations
     * @param commitments weekly-series operations
     * @param storage candidate persistence
     * @param planner temporary-plan generation
     * @param schedules frozen-plan projection
     * @param renderer response formatter
     * @param clock command time source
     * @throws NullPointerException if any dependency is null
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
     * @param command parsed command to execute
     * @param state live state to read or update
     * @return formatted response and termination decision
     * @throws StorageException if a candidate cannot be saved; live state remains unchanged
     * @throws NullPointerException if the command or state is null
     */
    public CommandResponse execute(Command command, AppState state) throws StorageException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting command execution: {0}", command.getClass().getSimpleName());
        LocalDateTime now = LocalDateTime.now(clock);
        LOGGER.log(Level.FINE, "Command time captured");
        String output = switch (command) {
            case Command.Help ignored -> renderer.help();
            case Command.SetHours hours -> changeHours(hours.target(), state.snapshot().studyHours()
                    .withWindow(hours.target(), new StudyWindow(hours.start(), hours.end())), state);
            case Command.ResetHours hours -> resetHours(hours.target(), state);
            case Command.ShowHours hours -> renderer.hours(hours.target(),
                    state.snapshot().studyHours().forTarget(hours.target()));
            case Command.AddTask add -> addTask(add, state, now);
            case Command.ListTasks ignored -> renderer.tasks(tasks.list(state.snapshot()));
            case Command.DeleteTask delete -> deleteTask(delete.id(), state);
            // @@author ernestgoh-god
            case Command.RecordTaskWork ignored -> throw new ValidationException(
                    "Task progress is awaiting task-service integration.");
            case Command.RecordTaskSession ignored -> throw new ValidationException(
                    "Task progress is awaiting task-service integration.");
            // @@author ka-how
            case Command.AddCommitment add -> addCommitment(add, state);
            case Command.EditCommitment edit -> editCommitment(edit, state);
            case Command.ListCommitments ignored -> renderer.commitments(commitments.list(state.snapshot()));
            case Command.DeleteCommitment delete -> deleteCommitment(delete.id(), state);
            case Command.GeneratePlan ignored -> generatePlan(state, now);
            case Command.ScheduleToday ignored -> showSchedule(state, now.toLocalDate());
            case Command.ScheduleDate schedule -> showSchedule(state, schedule.date());
            case Command.Exit ignored -> renderer.exit();
        };
        CommandResponse response = new CommandResponse(output, command instanceof Command.Exit);
        LOGGER.log(Level.FINE, "Command execution completed");
        return response;
    }

    // @@author GabrielYuSP
    /** Saves hours before clearing a plan, preserving the live settings and plan if saving fails. */
    private String changeHours(String target, StudyHours hours, AppState state) throws StorageException {
        saveAndCommit(state.snapshot().withStudyHours(hours), state);
        return renderer.hours(target, hours.forTarget(target)) + "\nSaved study hours. Run plan to regenerate.";
    }

    /** Confirms a reset and its inherited hours only after saving succeeds. */
    private String resetHours(String target, AppState state) throws StorageException {
        StudyHours hours = state.snapshot().studyHours().reset(target);
        saveAndCommit(state.snapshot().withStudyHours(hours), state);
        return renderer.hoursReset(target, hours);
    }

    // @@author ka-how
    /**
     * Resolves the allocated ID from the validated candidate before saving and publishing it.
     */
    private String addTask(Command.AddTask command, AppState state, LocalDateTime now) throws StorageException {
        LOGGER.log(Level.FINE, "Validating task addition");
        Snapshot original = state.snapshot();
        Snapshot candidate = tasks.add(original, command.name(), command.deadline(), command.minutes(), now);
        Task added = candidate.tasks().stream().filter(task -> task.id() == original.nextTaskId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Task candidate is missing the allocated ID."));
        saveAndCommit(candidate, state);
        return renderer.taskAdded(added);
    }

    /**
     * Retains the removed record for output after the service has validated its ID.
     */
    private String deleteTask(int id, AppState state) throws StorageException {
        LOGGER.log(Level.FINE, "Validating task deletion");
        Snapshot original = state.snapshot();
        Snapshot candidate = tasks.delete(original, id);
        Task removed = original.tasks().stream().filter(task -> task.id() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("Validated task deletion has no original record."));
        saveAndCommit(candidate, state);
        return renderer.taskDeleted(removed);
    }

    /**
     * Saves a validated weekly series before clearing the temporary plan or announcing success.
     */
    private String addCommitment(Command.AddCommitment command, AppState state) throws StorageException {
        LOGGER.log(Level.FINE, "Validating commitment addition");
        Snapshot original = state.snapshot();
        Snapshot candidate = commitments.add(original, command.name(), command.day(), command.start(),
                command.minutes(), command.startDate(), command.endDate());
        Commitment added = candidate.commitments().stream()
                .filter(commitment -> commitment.id() == original.nextCommitmentId()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Commitment candidate is missing the allocated ID."));
        saveAndCommit(candidate, state);
        return renderer.commitmentAdded(added);
    }

    //@@author Darren50
    /**
     * Saves the complete edit before publishing it, invalidating the plan or reporting success.
     */
    private String editCommitment(Command.EditCommitment command, AppState state) throws StorageException {
        Snapshot candidate = commitments.edit(state.snapshot(), command.id(), command.name(), command.day(),
                command.start(), command.minutes(), command.startDate(), command.endDate());
        Commitment edited = candidate.commitments().stream()
                .filter(commitment -> commitment.id() == command.id()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Validated commitment edit is missing its target."));
        saveAndCommit(candidate, state);
        return renderer.commitmentEdited(edited);
    }
    //@@author ka-how

    /**
     * Retains the validated original series so deletion output includes its stable ID and name.
     */
    private String deleteCommitment(int id, AppState state) throws StorageException {
        LOGGER.log(Level.FINE, "Validating commitment deletion");
        Snapshot original = state.snapshot();
        Snapshot candidate = commitments.delete(original, id);
        Commitment removed = original.commitments().stream()
                .filter(commitment -> commitment.id() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("Validated commitment deletion has no original record."));
        saveAndCommit(candidate, state);
        return renderer.commitmentDeleted(removed);
    }

    /**
     * Publishes only successfully saved candidates; storage failures propagate to the application.
     *
     * @param candidate validated data to persist
     * @param state live state to publish into after saving
     * @throws StorageException if saving fails, leaving the live state and plan unchanged
     */
    private void saveAndCommit(Snapshot candidate, AppState state) throws StorageException {
        Objects.requireNonNull(candidate, "candidate");
        Snapshot original = state.snapshot();
        Optional<Plan> originalPlan = state.currentPlan();
        LOGGER.log(Level.FINE, "Saving validated candidate");
        try {
            storage.save(candidate);
        } catch (StorageException exception) {
            LOGGER.log(Level.FINE, "Candidate save failed; live state was not published", exception);
            throw exception;
        }
        assert state.snapshot() == original && state.currentPlan().equals(originalPlan)
                : "Saving must not publish state or invalidate the plan";
        LOGGER.log(Level.FINE, "Candidate saved; publishing live state");
        state.commit(candidate);
        assert state.snapshot() == candidate && state.currentPlan().isEmpty()
                : "A saved mutation must publish its snapshot and clear the plan";
        LOGGER.log(Level.FINE, "Saved candidate committed");
    }

    /**
     * Replaces the complete temporary plan without changing or saving task estimates.
     */
    private String generatePlan(AppState state, LocalDateTime now) {
        LOGGER.log(Level.FINE, "Generating temporary plan");
        Plan plan = Objects.requireNonNull(planner.generate(state.snapshot(), now), "generated plan");
        state.replacePlan(plan);
        LOGGER.log(Level.FINE, "Temporary plan published; formatting result");
        return renderer.plan(plan);
    }

    /**
     * Gives absent plans precedence over date-range validation and projects existing frozen plans.
     */
    private String showSchedule(AppState state, LocalDate date) {
        LOGGER.log(Level.FINE, "Checking current plan for schedule request");
        if (state.currentPlan().isEmpty()) {
            LOGGER.log(Level.FINE, "No current plan available");
            return renderer.noPlan();
        }
        LOGGER.log(Level.FINE, "Projecting frozen plan for requested date");
        return renderer.schedule(schedules.forDate(state.currentPlan().orElseThrow(), date));
    }
}
