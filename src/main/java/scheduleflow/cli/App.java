//@@author ka-how
package scheduleflow.cli;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.ScheduleService;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskService;

/**
 * Owns startup, the command loop and recoverable user errors; returns a process status.
 */
public final class App {
    /**
     * Records lifecycle stages and recoverable failures without logging raw input.
     * Uses {@code INFO} for sessions, {@code WARNING} for recoverable errors and
     * {@code SEVERE} for failures that stop the session; detailed stages remain at {@code FINE}.
     */
    private static final Logger LOGGER = Logger.getLogger(App.class.getName());

    /**
     * Loads startup data and supplies persistence to the dispatcher.
     */
    private final Storage storage;
    /**
     * Exchanges prompt and response text with the user.
     */
    private final ConsoleUi ui;
    /**
     * Converts each nonblank input line to a command.
     */
    private final CommandParser parser;
    /**
     * Produces consistent error and termination responses.
     */
    private final TextRenderer renderer;
    /**
     * Executes commands against this session's live state.
     */
    private final CommandDispatcher dispatcher;

    /**
     * Creates the App dependencies without performing I/O.
     *
     * @param storage persistence used at startup and for successful mutations
     * @param clock time source captured by command execution
     * @param ui console input and output
     * @throws NullPointerException if any dependency is null
     */
    public App(Storage storage, Clock clock, ConsoleUi ui) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.ui = Objects.requireNonNull(ui, "ui");
        parser = new CommandParser();
        renderer = new TextRenderer();
        dispatcher = new CommandDispatcher(new TaskService(), new CommitmentService(), storage,
                new EarliestDeadlinePlanner(), new ScheduleService(), renderer, Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Loads once and runs commands, returning zero for normal exit or one for startup/input failure.
     *
     * @return 0 for exit or EOF, or 1 for a storage-load or input failure
     */
    public int run() {
        LOGGER.log(Level.INFO, "Starting application session");
        AppState state;
        try {
            LOGGER.log(Level.FINE, "Loading saved snapshot");
            // @@author ernestgoh-god
            Snapshot loaded = storage.load();
            state = new AppState(loaded);
            storage.loadPlan(loaded).ifPresent(state::replacePlan);
            // @@author ka-how
            LOGGER.log(Level.FINE, "Saved snapshot loaded");
        } catch (StorageException exception) {
            LOGGER.log(Level.SEVERE, "Startup load failed", exception);
            ui.write(renderer.error(exception.getMessage()));
            return 1;
        }
        try {
            return runCommands(state);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Command input failed", exception);
            ui.write(renderer.error("Cannot read input: " + exception.getMessage()));
            return 1;
        }
    }

    /**
     * Continues after ordinary user errors and exits without saving on exit or end of input.
     * Unexpected programming exceptions propagate so defects are not disguised as user errors.
     *
     * @param state loaded live state for this session
     * @return 0 after an exit command or EOF
     * @throws IOException if the next input line cannot be read
     */
    private int runCommands(AppState state) throws IOException {
        LOGGER.log(Level.FINE, "Starting command loop");
        while (true) {
            ui.prompt();
            String line = ui.readLine();
            if (line == null) {
                LOGGER.log(Level.INFO, "Ending session after EOF");
                ui.write(renderer.exit());
                return 0;
            }
            if (line.isBlank()) {
                LOGGER.log(Level.FINE, "Ignoring blank input");
                continue;
            }
            try {
                LOGGER.log(Level.FINE, "Processing command input");
                CommandResponse response = dispatcher.execute(parser.parse(line), state);
                ui.write(response.output());
                LOGGER.log(Level.FINE, "Command response delivered");
                if (response.shouldExit()) {
                    LOGGER.log(Level.INFO, "Ending session after exit command");
                    return 0;
                }
            } catch (ParseException exception) {
                LOGGER.log(Level.WARNING, "Command syntax rejected", exception);
                ui.write(renderer.parseError(exception));
            } catch (ValidationException | StorageException exception) {
                LOGGER.log(Level.WARNING, "Command validation or save rejected", exception);
                ui.write(renderer.error(exception.getMessage()));
            }
        }
    }
}
