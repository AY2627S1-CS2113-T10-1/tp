// @@author ka-how
package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.Plan;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;

/**
 * Checks transport values, exception details and side-effect-free wiring.
 */
class ContractsTest {
    @TempDir
    private Path directory;

    @Test
    void command_transportValues_doNotDuplicateBusinessValidation() {
        Command.AddTask command = new Command.AddTask(" unnormalized ", LocalDateTime.of(2020, 1, 1, 0, 0), 45);
        assertEquals(" unnormalized ", command.name());
        assertEquals(45, command.minutes());
        assertEquals(0, new Command.DeleteTask(0).id());
        assertEquals(0, new Command.ReopenTask(0, -1).id());
        assertEquals(-1, new Command.ReopenTask(0, -1).remainingMinutes());
        assertThrows(NullPointerException.class, () -> new Command.AddTask(null, command.deadline(), 30));
        assertThrows(NullPointerException.class, () -> new Command.ScheduleDate(null));
        assertEquals(0, new Command.RecordTaskWork(1, 0).doneMinutes());
        assertEquals(101, new Command.RecordTaskSession(1, 1, 101).percent());
        assertEquals(0, new Command.ReviseTaskRemaining(1, 0).remainingMinutes());
        assertEquals(31, Command.class.getPermittedSubclasses().length);
    }

    @Test
    void responseAndExceptions_preserveDetails() {
        assertEquals("ready", new CommandResponse("ready", false).output());
        assertThrows(NullPointerException.class, () -> new CommandResponse(null, false));
        ParseException parse = new ParseException("bad", "task list");
        assertEquals("bad", parse.getMessage());
        assertEquals("task list", parse.usage());
        assertThrows(NullPointerException.class, () -> new ParseException("bad", null));
        assertEquals("message",
                assertThrows(NullPointerException.class, () -> new ParseException(null, "task list")).getMessage());
        Exception cause = new Exception("disk");
        StorageException storage = new StorageException("save failed", cause);
        assertSame(cause, storage.getCause());
        assertEquals("save failed", storage.getMessage());
        assertEquals("load failed", new StorageException("load failed").getMessage());
    }

    @Test
    void constructors_buildDependenciesWithoutIoOrCallingStubs() {
        Path file = directory.resolve("absent/data/scheduleflow.txt");
        assertDoesNotThrow(() -> new FileStorage(file, new StateCodec()));
        assertEquals(false, Files.exists(file.getParent()));
        Storage storage = new Storage() {
            @Override
            public Snapshot load() {
                throw new AssertionError("Constructor must not load");
            }

            @Override
            public Optional<Plan> loadPlan(Snapshot source) {
                throw new AssertionError("Constructor must not load a plan");
            }

            @Override
            public void savePlan(Plan plan) {
                throw new AssertionError("Constructor must not save a plan");
            }

            @Override
            public void save(Snapshot state) {
                throw new AssertionError("Constructor must not save");
            }
        };
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                throw new AssertionError("Constructor must not read");
            }
        };
        StringWriter output = new StringWriter();
        ConsoleUi ui = new ConsoleUi(input, new PrintWriter(output));
        assertDoesNotThrow(() -> new App(storage, Clock.fixed(java.time.Instant.EPOCH, ZoneOffset.UTC), ui));
        assertEquals("", output.toString());
    }
}
// @@author
