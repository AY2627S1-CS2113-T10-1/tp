// @@author ka-how
package scheduleflow.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
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
     * Loads once and runs commands, returning zero for normal exit or one for startup/console failure.
     *
     * @return 0 for exit or EOF, or 1 for a storage-load, input or output failure
     */
    public int run() {
        LOGGER.log(Level.INFO, "Starting application session");
        AppState state;
        try {
            LOGGER.log(Level.FINE, "Loading saved snapshot");
            Snapshot loaded = storage.load();
            state = new AppState(loaded);
            storage.loadPlan(loaded).ifPresent(state::replacePlan);
            LOGGER.log(Level.FINE, "Saved snapshot loaded");
        } catch (StorageException exception) {
            LOGGER.log(Level.SEVERE, "Startup load failed", exception);
            writeResponse(renderer.error(exception.getMessage()));
            return 1;
        }
        try {
            return runCommands(state);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Command input failed", exception);
            writeResponse(renderer.error("Cannot read input: " + exception.getMessage()));
            return 1;
        }
    }

    /**
     * Continues after ordinary user errors and exits without saving on exit or end of input.
     * Unexpected programming exceptions propagate so defects are not disguised as user errors.
     *
     * @param state loaded live state for this session
     * @return 0 after an exit command or EOF, or 1 if console output fails
     * @throws IOException if the next input line cannot be read
     */
    private int runCommands(AppState state) throws IOException {
        LOGGER.log(Level.FINE, "Starting command loop");
        while (true) {
            try {
                ui.prompt();
            } catch (UncheckedIOException exception) {
                LOGGER.log(Level.SEVERE, "Console output failed", exception);
                return 1;
            }
            String line = ui.readLine();
            if (line == null) {
                LOGGER.log(Level.INFO, "Ending session after EOF");
                return writeResponse(renderer.exit()) ? 0 : 1;
            }
            if (line.isBlank()) {
                LOGGER.log(Level.FINE, "Ignoring blank input");
                continue;
            }
            try {
                LOGGER.log(Level.FINE, "Processing command input");
                CommandResponse response = dispatcher.execute(parser.parse(line), state);
                if (!writeResponse(response.output())) {
                    return 1;
                }
                LOGGER.log(Level.FINE, "Command response delivered");
                if (response.shouldExit()) {
                    LOGGER.log(Level.INFO, "Ending session after exit command");
                    return 0;
                }
            } catch (ParseException exception) {
                LOGGER.log(Level.WARNING, "Command syntax rejected", exception);
                if (!writeResponse(renderer.parseError(exception))) {
                    return 1;
                }
            } catch (ValidationException | StorageException exception) {
                LOGGER.log(Level.WARNING, "Command validation or save rejected", exception);
                if (!writeResponse(renderer.error(exception.getMessage()))) {
                    return 1;
                }
            }
        }
    }

    /**
     * Attempts a response once without trying to report an output failure to the failed writer.
     * Only the console call is caught; unchecked I/O failures from other components still propagate.
     * A response failure cannot undo a command that has already been saved.
     *
     * @param text fully rendered response
     * @return whether the console accepted and flushed the response
     */
    private boolean writeResponse(String text) {
        try {
            ui.write(text);
            return true;
        } catch (UncheckedIOException exception) {
            LOGGER.log(Level.SEVERE, "Console output failed", exception);
            return false;
        }
    }
}
// @@author
