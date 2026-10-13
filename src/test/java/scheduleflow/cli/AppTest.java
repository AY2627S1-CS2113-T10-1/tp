package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.model.Snapshot;
import scheduleflow.model.Task;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;
import scheduleflow.storage.Storage;
import scheduleflow.storage.StorageException;

/**
 * Verifies startup, recoverable errors and restart through the real parser, dispatcher and services.
 */
@Isolated("Diagnostic checks temporarily change the application logger configuration.")
class AppTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final String ADD_TASK = "task add n/Draft due/2026-09-22 1800 d/30";
    @TempDir
    private Path directory;

    @Test
    void run_diagnostics_traceStagesAndDoNotClaimFailedSaveWasCommitted() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        storage.failOnSave = 2;
        List<LogRecord> records = captureLogs(() -> {
            Result result = run(ADD_TASK + "\n" + ADD_TASK + "\nexit\n", storage);
            assertEquals(0, result.status());
            assertTrue(result.output().contains("Added T1: Draft"));
            assertTrue(result.output().contains("Error: Save rejected"));
            assertFalse(result.output().contains("Added T2:"));
        });
        List<String> stages = records.stream().map(LogRecord::getMessage).toList();
        assertTrue(stages.containsAll(List.of("Starting application session", "Loading saved snapshot",
                "Saved snapshot loaded", "Starting command loop", "Writing command prompt",
                "Command prompt flushed", "Reading command input", "Command input read",
                "Starting command parsing", "Command words recognized; validating syntax",
                "Checking complete field pattern", "Command time captured", "Validating task addition",
                "Writing command response", "Command response flushed", "Command response delivered",
                "Ending session after exit command")));
        assertTrue(stages.indexOf("Saving validated candidate")
                < stages.indexOf("Candidate saved; publishing live state"));
        assertTrue(stages.indexOf("Candidate saved; publishing live state")
                < stages.indexOf("Saved snapshot published and temporary plan cleared"));
        assertTrue(stages.indexOf("Saved candidate committed") < stages.indexOf("Formatting task addition response"));
        assertTrue(stages.contains("Response formatting completed"));
        assertEquals(1, stages.stream().filter("Saved candidate committed"::equals).count());
        assertTrue(stages.contains("Candidate save failed; live state was not published"));
        assertTrue(stages.contains("Command validation or save rejected"));
        assertEquals(1, storage.saved.tasks().size());
        assertEquals(2, storage.saved.nextTaskId());
        assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.FINE)));
        assertLogged(records, Level.INFO, "Starting application session");
        assertLogged(records, Level.INFO, "Ending session after exit command");
        assertLogged(records, Level.WARNING, "Command validation or save rejected");
        assertFalse(records.stream().anyMatch(record -> record.getLevel().equals(Level.SEVERE)));
        assertFalse(stages.stream().anyMatch(message -> message.contains("Draft") || message.contains(ADD_TASK)));
    }

    @Test
    void run_diagnostics_traceSyntaxStartupAndInputFailures() {
        List<LogRecord> syntax = captureLogs(() -> assertEquals(0,
                run("exit extra\nexit\n", new FakeStorage(Snapshot.empty())).status()));
        assertLogged(syntax, Level.WARNING, "Command syntax rejected");
        FakeStorage rejectedLoad = new FakeStorage(Snapshot.empty());
        rejectedLoad.failLoad = true;
        List<LogRecord> startup = captureLogs(() -> assertEquals(1, run("exit\n", rejectedLoad).status()));
        assertLogged(startup, Level.SEVERE, "Startup load failed");
        assertFalse(startup.stream().anyMatch(record -> record.getMessage().equals("Starting command loop")));
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("Input disconnected");
            }
        };
        List<LogRecord> failure = captureLogs(() -> assertEquals(1,
                run(input, new FakeStorage(Snapshot.empty())).status()));
        assertLogged(failure, Level.SEVERE, "Command input failed");
    }

    @Test
    void run_warningThreshold_retainsFailuresAndFiltersSessionAndStageEvents() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        storage.failOnSave = 1;
        List<LogRecord> recoverable = captureLogs(Level.WARNING, () -> {
            Result result = run("exit extra\n" + ADD_TASK + "\nexit\n", storage);
            assertEquals(0, result.status());
            assertTrue(result.output().contains("Error: This command accepts no arguments"));
            assertTrue(result.output().contains("Error: Save rejected"));
            assertTrue(result.output().endsWith("Goodbye for now!\n"));
        });
        assertEquals(2, recoverable.size());
        assertLogged(recoverable, Level.WARNING, "Command syntax rejected");
        assertLogged(recoverable, Level.WARNING, "Command validation or save rejected");
        assertEquals(Snapshot.empty(), storage.saved);

        FakeStorage rejectedLoad = new FakeStorage(Snapshot.empty());
        rejectedLoad.failLoad = true;
        List<LogRecord> fatal = captureLogs(Level.WARNING,
                () -> assertEquals(1, run("exit\n", rejectedLoad).status()));
        assertEquals(1, fatal.size());
        assertLogged(fatal, Level.SEVERE, "Startup load failed");
    }

    @Test
    void run_firstLaunch_listsEmptyWithoutCreatingData() {
        Path file = directory.resolve("data/scheduleflow.txt");
        Result result = run("task list\ncommitment list\nschedule today\nexit\n",
                new FileStorage(file, new StateCodec()));
        assertEquals(0, result.status());
        assertEquals("scheduleflow> No tasks found.\nscheduleflow> No commitments found.\n"
                + "scheduleflow> No current plan. Run plan to generate one.\nscheduleflow> Goodbye for now!\n",
                result.output());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void run_exitAndEof_loadOnceAndNeverSave() {
        for (String input : List.of("exit\n", "")) {
            FakeStorage storage = new FakeStorage(Snapshot.empty());
            Result result = run(input, storage);
            assertEquals(0, result.status());
            assertEquals("scheduleflow> Goodbye for now!\n", result.output());
            assertEquals(1, storage.loadCalls);
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void run_blankLines_skipsParsingAndDispatch() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        Result result = run("\n   \n\t\n", storage);
        assertEquals(0, result.status());
        assertEquals("scheduleflow> scheduleflow> scheduleflow> scheduleflow> Goodbye for now!\n", result.output());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void run_loadFailure_stopsBeforePromptOrInput() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        storage.failLoad = true;
        BufferedReader input = new BufferedReader(new StringReader("exit\n")) {
            @Override
            public String readLine() {
                throw new AssertionError("Failed startup must not accept input");
            }
        };
        Result result = run(input, storage);
        assertEquals(1, result.status());
        assertEquals("Error: Load rejected\n", result.output());
        assertEquals(1, storage.loadCalls);
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void run_corruptFile_preservesBytesAndRejectsWholeLoad() throws Exception {
        Path file = directory.resolve("scheduleflow.txt");
        for (String text : List.of("SCHEDULEFLOW|99\nNEXT|1|1\n", "SCHEDULEFLOW|1\nNEXT|1|1\nTASK|1\n",
                "SCHEDULEFLOW|1\nNEXT|1|1\nTASK|1|2026-09-22T18:00|30|Invalid counter\n")) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            Result result = run("exit\n", new FileStorage(file, new StateCodec()));
            assertEquals(1, result.status());
            assertTrue(result.output().startsWith("Error: "));
            assertFalse(result.output().contains("scheduleflow>"));
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test
    void run_syntaxErrors_preserveSnapshotAndPlanAndContinue() {
        Snapshot original = new Snapshot(List.of(new Task(2, "Existing", NOW.plusDays(1), 30)), List.of(), 3, 1);
        for (String invalid : List.of("task add n/A d/30", "task add n/A n/B due/2026-09-22 1800 d/30",
                "task add due/2026-09-22 1800 n/A d/30", "task add n/A due/2026-09-22 1800 d/30 x/extra",
                "task delete 1", "task list extra", "exit extra")) {
            FakeStorage storage = new FakeStorage(original);
            Result result = run("plan\n" + invalid + "\nschedule today\nexit\n", storage);
            assertEquals(0, result.status());
            assertTrue(result.output().contains("Error: "));
            assertTrue(result.output().contains("\nExpected: "));
            assertTrue(result.output().contains("08:00 - 08:30 | [TASK] T2: Existing (30 min)"));
            assertFalse(result.output().contains("No current plan"));
            assertSame(original, storage.saved);
            assertEquals(0, storage.saveCalls);
        }
    }

    @Test
    void run_domainErrors_printOneErrorAndContinueWithoutSuccess() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        Result result = run("task add n/Past due/2026-09-21 0700 d/30\n"
                + "task add n/Bad due/2026-09-22 1800 d/45\n"
                + "commitment add n/Bad day/MON start/1015 d/30\n"
                + "task delete T9\ncommitment delete C9\ntask list\nexit\n", storage);
        assertEquals(0, result.status());
        assertEquals(5, result.output().split("Error: ", -1).length - 1);
        assertFalse(result.output().contains("Expected:"));
        assertFalse(result.output().contains("Added "));
        assertFalse(result.output().contains("Deleted "));
        assertFalse(result.output().contains("Exception"));
        assertTrue(result.output().contains("No tasks found."));
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void run_saveFailure_preservesPlanAndRetriesSameIdWithoutFalseSuccess() {
        Snapshot original = new Snapshot(List.of(new Task(2, "Existing", NOW.plusDays(1), 30)), List.of(), 3, 1);
        FakeStorage storage = new FakeStorage(original);
        storage.failOnSave = 1;
        Result result = run("plan\n" + ADD_TASK + "\nschedule today\n" + ADD_TASK
                + "\nschedule today\nexit\n", storage);
        assertEquals(0, result.status());
        assertTrue(result.output().contains("Error: Save rejected\nscheduleflow> === Schedule for Monday"));
        assertTrue(result.output().contains("08:00 - 08:30 | [TASK] T2: Existing (30 min)"));
        assertEquals(1, result.output().split("Added T3: Draft", -1).length - 1);
        assertTrue(result.output().indexOf("Error: Save rejected") < result.output().indexOf("Added T3: Draft"));
        assertTrue(result.output().contains("No current plan. Run plan to generate one."));
        assertEquals(4, storage.saved.nextTaskId());
        assertEquals(2, storage.saveCalls);
    }

    @Test
    void run_failedSave_preservesExistingFileBytesAndPlan() throws Exception {
        Snapshot original = new Snapshot(List.of(new Task(2, "Existing", NOW.plusDays(1), 30)), List.of(), 3, 1);
        Path file = directory.resolve("scheduleflow.txt");
        FileStorage delegate = new FileStorage(file, new StateCodec());
        delegate.save(original);
        byte[] bytes = Files.readAllBytes(file);
        Storage failing = new Storage() {
            @Override
            public Snapshot load() throws StorageException {
                return delegate.load();
            }

            @Override
            public void save(Snapshot state) throws StorageException {
                throw new StorageException("Save rejected before replacement");
            }
        };
        Result result = run("plan\n" + ADD_TASK + "\nschedule today\nexit\n", failing);
        assertEquals(0, result.status());
        assertTrue(result.output().contains("Error: Save rejected before replacement"));
        assertFalse(result.output().contains("Added T3"));
        assertTrue(result.output().contains("08:00 - 08:30 | [TASK] T2: Existing (30 min)"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(original, delegate.load());
    }

    @Test
    void run_emptyPlan_displaysClippedCommitmentsAndInvalidatesAfterDeletion() {
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        Result result = run("commitment add n/Morning day/MON start/0730 d/90\n"
                + "commitment add n/Late day/MON start/2330 d/30\nplan\ncommitment list\nschedule today\n"
                + "commitment delete C1\nschedule today\nexit\n", storage);
        assertEquals(0, result.status());
        assertTrue(result.output().contains("No tasks to schedule."));
        assertTrue(result.output().contains("C2. 23:30 - 24:00 | Late"));
        assertTrue(result.output().contains("08:00 - 09:00 | [BUSY] C1: Morning\n09:00 - 22:00 | [FREE]"));
        assertTrue(result.output().contains("Deleted commitment C1: Morning\nscheduleflow> No current plan."));
        assertEquals(3, storage.saveCalls);
    }

    @Test
    void run_restart_preservesCountersAndClearsPlan() throws Exception {
        Path file = directory.resolve("data/scheduleflow.txt");
        Result first = run(ADD_TASK + "\nplan\ntask delete T1\nplan\nexit\n", new FileStorage(file, new StateCodec()));
        assertEquals(0, first.status());
        assertEquals(2, new FileStorage(file, new StateCodec()).load().nextTaskId());
        Result second = run("schedule today\n" + ADD_TASK + "\ntask list\nexit\n",
                new FileStorage(file, new StateCodec()));
        assertEquals(0, second.status());
        assertTrue(second.output().startsWith("scheduleflow> No current plan. Run plan to generate one."));
        assertTrue(second.output().contains("Added T2: Draft"));
        assertEquals(2, new FileStorage(file, new StateCodec()).load().tasks().getFirst().id());
    }

    @Test
    void run_loadedOverdueTask_remainsVisibleAndPlansWithoutSaving() throws Exception {
        Snapshot overdue = new Snapshot(List.of(new Task(1, "Overdue", NOW.minusDays(1), 60)), List.of(), 2, 1);
        Path file = directory.resolve("scheduleflow.txt");
        FileStorage storage = new FileStorage(file, new StateCodec());
        storage.save(overdue);
        byte[] bytes = Files.readAllBytes(file);
        Result result = run("task list\nplan\nschedule today\nexit\n", storage);
        assertEquals(0, result.status());
        assertTrue(result.output().contains("T1 | Overdue | 60 min"));
        assertTrue(result.output().contains("T1: Overdue | Unallocated: 60 min | Reason: deadline passed"));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void run_inputFailure_reportsErrorAndStopsWithoutSave() {
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("Input disconnected");
            }
        };
        FakeStorage storage = new FakeStorage(Snapshot.empty());
        Result result = run(input, storage);
        assertEquals(1, result.status());
        assertEquals("scheduleflow> Error: Cannot read input: Input disconnected\n", result.output());
        assertEquals(0, storage.saveCalls);
    }

    @Test
    void run_programmingException_propagatesInsteadOfPretendingUserError() {
        Storage broken = new Storage() {
            @Override
            public Snapshot load() {
                throw new IllegalStateException("Storage defect");
            }

            @Override
            public void save(Snapshot state) {
                throw new AssertionError("Must not save after defective load");
            }
        };
        assertThrows(IllegalStateException.class, () -> run("exit\n", broken));
    }

    /**
     * Captures application diagnostics in memory and restores shared logger settings even on failure.
     * The class is isolated so other tests cannot observe the temporary configuration.
     *
     * @param session controlled session or assertions to execute with diagnostics enabled
     * @return log records emitted during the session
     */
    private List<LogRecord> captureLogs(Runnable session) {
        return captureLogs(Level.FINE, session);
    }

    /**
     * Captures only records at the selected minimum severity and restores shared logger settings.
     *
     * @param minimum least severe level accepted by the logger and handler
     * @param session controlled session or assertions to execute with diagnostics enabled
     * @return log records passing the selected threshold
     */
    private List<LogRecord> captureLogs(Level minimum, Runnable session) {
        Logger logger = Logger.getLogger("scheduleflow");
        Level originalLevel = logger.getLevel();
        boolean originalUseParents = logger.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        try {
            handler.setLevel(minimum);
            logger.setLevel(minimum);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            session.run();
            return records;
        } finally {
            logger.removeHandler(handler);
            handler.close();
            logger.setLevel(originalLevel);
            logger.setUseParentHandlers(originalUseParents);
        }
    }

    /**
     * Requires a diagnostic to retain both its classification and original exception details.
     *
     * @param records captured diagnostics to inspect
     * @param level expected severity
     * @param message expected stage description
     */
    private void assertLogged(List<LogRecord> records, Level level, String message) {
        LogRecord record = records.stream().filter(value -> value.getLevel().equals(level)
                && value.getMessage().equals(message)).findFirst().orElseThrow();
        if (level.intValue() >= Level.WARNING.intValue()) {
            assertTrue(record.getThrown() instanceof Exception);
        }
    }
    /**
     * Runs a complete session with fixed time and isolated input/output.
     */
    private Result run(String input, Storage storage) {
        return run(new BufferedReader(new StringReader(input)), storage);
    }

    /**
     * Allows controlled reader failures and normalizes only platform line endings.
     */
    private Result run(BufferedReader input, Storage storage) {
        StringWriter output = new StringWriter();
        int status = new App(storage, CLOCK, new ConsoleUi(input, new PrintWriter(output))).run();
        return new Result(status, output.toString().replace("\r\n", "\n"));
    }

    /**
     * Holds observable status and complete console output from one session.
     */
    private record Result(int status, String output) {
    }

    /**
     * Preserves the last saved snapshot and rejects a chosen save attempt deterministically.
     */
    private static final class FakeStorage implements Storage {
        private Snapshot saved;
        private int loadCalls;
        private int saveCalls;
        private boolean failLoad;
        /**
         * Selects one failing save; zero leaves every save enabled.
         */
        private int failOnSave;

        /**
         * Supplies the isolated startup snapshot.
         */
        private FakeStorage(Snapshot initial) {
            saved = initial;
        }

        @Override
        public Snapshot load() throws StorageException {
            loadCalls++;
            if (failLoad) {
                throw new StorageException("Load rejected");
            }
            return saved;
        }

        @Override
        public void save(Snapshot state) throws StorageException {
            saveCalls++;
            if (saveCalls == failOnSave) {
                throw new StorageException("Save rejected");
            }
            saved = state;
        }
    }
}
