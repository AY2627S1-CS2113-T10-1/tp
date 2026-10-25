// @@author ka-how
package scheduleflow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.cli.App;
import scheduleflow.cli.ConsoleUi;
import scheduleflow.cli.TextRenderer;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
import scheduleflow.storage.Storage;

/**
 * Provides the production entry point with UTF-8 I/O, file storage and the system clock.
 */
public final class Main {
    /**
     * Records process startup and termination when diagnostic logging is enabled.
     */
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    /**
     * Retains the console logger namespace so its launch-time configuration survives garbage collection.
     */
    private static final Logger CLI_LOGGER = Logger.getLogger("scheduleflow.cli");

    /**
     * Prevents instantiation of the executable entry point.
     */
    private Main() {
    }

    /**
     * Starts the application with data/scheduleflow.txt and exits with App's status.
     * Rejects command line arguments before loading saved data.
     * Keeps diagnostics opt-in unless a logging configuration or namespace level is supplied.
     *
     * @param args command line arguments, which must be empty for a normal launch
     * @throws NullPointerException if the argument array is null
     */
    public static void main(String[] args) {
        Objects.requireNonNull(args, "args");
        configureDiagnostics();
        LOGGER.log(Level.FINE, "Starting application dependencies");
        ConsoleUi ui = new ConsoleUi(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        if (args.length != 0) {
            LOGGER.log(Level.FINE, "Rejected unexpected command line arguments");
            ui.write(new TextRenderer().error("Usage: java -jar scheduleflow.jar (no arguments)"));
            System.exit(1);
            return;
        }
        Storage storage = new FileStorage(Path.of("data", "scheduleflow.txt"), new StateCodec());
        int status = new App(storage, Clock.systemDefaultZone(), ui).run();
        assert status == 0 || status == 1 : "Application status must indicate success or failure";
        LOGGER.log(Level.FINE, "Application stopped with status {0}", status);
        System.exit(status);
    }

    /**
     * Keeps default console launches quiet while respecting explicit Java logging configuration.
     * Changes only the console namespace; other component loggers retain their existing settings.
     */
    private static void configureDiagnostics() {
        if (System.getProperty("java.util.logging.config.file") != null
                || System.getProperty("java.util.logging.config.class") != null) {
            return;
        }
        if (CLI_LOGGER.getLevel() == null && Logger.getLogger("scheduleflow").getLevel() == null) {
            CLI_LOGGER.setLevel(Level.OFF);
        }
    }
}
// @@author
