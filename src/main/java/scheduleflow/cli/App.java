package scheduleflow.cli;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import scheduleflow.commitment.CommitmentService;
import scheduleflow.common.ValidationException;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.ScheduleService;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;
import scheduleflow.task.TaskService;

/**
 * Owns startup, the command loop and recoverable user errors; returns a process status.
 */
public final class App {
    private final Storage storage;
    private final ConsoleUi ui;
    private final CommandParser parser;
    private final TextRenderer renderer;
    private final CommandDispatcher dispatcher;

    /**
     * Creates the App dependencies without performing I/O.
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
     */
    public int run() {
        AppState state;
        try {
            state = new AppState(storage.load());
        } catch (StorageException exception) {
            ui.write(renderer.error(exception.getMessage()));
            return 1;
        }
        try {
            return runCommands(state);
        } catch (IOException exception) {
            ui.write(renderer.error("Cannot read input: " + exception.getMessage()));
            return 1;
        }
    }

    /**
     * Continues after ordinary user errors and exits without saving on exit or end of input.
     * Unexpected programming exceptions propagate so defects are not disguised as user errors.
     */
    private int runCommands(AppState state) throws IOException {
        while (true) {
            ui.prompt();
            String line = ui.readLine();
            if (line == null) {
                ui.write(renderer.exit());
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                CommandResponse response = dispatcher.execute(parser.parse(line), state);
                ui.write(response.output());
                if (response.shouldExit()) {
                    return 0;
                }
            } catch (ParseException exception) {
                ui.write(renderer.parseError(exception));
            } catch (ValidationException | StorageException exception) {
                ui.write(renderer.error(exception.getMessage()));
            }
        }
    }
}
