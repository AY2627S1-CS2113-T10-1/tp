package scheduleflow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
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
    private Main() {
    }

    /**
     * Starts the application with data/scheduleflow.txt and exits with App's status.
     * Rejects command line arguments before loading saved data.
     */
    public static void main(String[] args) {
        ConsoleUi ui = new ConsoleUi(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        if (args.length != 0) {
            ui.write(new TextRenderer().error("Usage: java -jar scheduleflow.jar (no arguments)"));
            System.exit(1);
            return;
        }
        Storage storage = new FileStorage(Path.of("data", "scheduleflow.txt"), new StateCodec());
        System.exit(new App(storage, Clock.systemDefaultZone(), ui).run());
    }
}
