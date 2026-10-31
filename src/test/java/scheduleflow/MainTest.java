// @@author ka-how
package scheduleflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises process status, UTF-8 production wiring, saved restarts and startup diagnostics in isolated JVMs.
 */
class MainTest {
    @TempDir
    private Path directory;

    @Test
    void main_nullArgumentArray_rejectsBeforeProcessExit() {
        assertEquals("args", assertThrows(NullPointerException.class, () -> Main.main(null)).getMessage());
    }

    @Test
    void main_unexpectedArguments_rejectsBeforeReadingCorruptStore() throws Exception {
        Path data = directory.resolve("data/scheduleflow.txt");
        Files.createDirectories(data.getParent());
        byte[] original = "SCHEDULEFLOW|99\n".getBytes(StandardCharsets.UTF_8);
        Files.write(data, original);
        ProcessResult result = launch("exit\n", List.of("--help"));
        assertEquals(1, result.status());
        assertEquals("Error: Usage: java -jar scheduleflow.jar (no arguments)\n", result.output());
        assertEquals("", result.error());
        assertArrayEquals(original, Files.readAllBytes(data));
    }

    @Test
    void main_utf8CommandsAndRestart_preservesUnicodeWithAsciiJvmDefault() throws Exception {
        ProcessResult first = launch("commitment add n/学习 Café day/TUE start/1000 d/30\nplan\nexit\n", List.of());
        assertEquals(0, first.status());
        assertEquals("", first.error());
        assertTrue(first.output().contains("Added C1: 学习 Café | Tuesday 10:00 - 10:30 (30 min)"));
        assertTrue(first.output().contains("No tasks to schedule."));
        assertTrue(Files.readString(directory.resolve("data/scheduleflow.txt"), StandardCharsets.UTF_8)
                .contains("学习 Café"));
        ProcessResult second = launch("commitment list\nschedule today\nexit\n", List.of());
        assertEquals(0, second.status());
        assertEquals("", second.error());
        assertTrue(second.output().contains("C1. 10:00 - 10:30 | 学习 Café"));
        assertTrue(second.output().contains("=== Schedule for"));
        assertTrue(second.output().contains("Generated at:"));
    }

    @Test
    void main_firstLaunchEof_exitsNormallyWithoutCreatingStore() throws Exception {
        ProcessResult result = launch("", List.of());
        assertEquals(0, result.status());
        assertEquals("scheduleflow> Goodbye for now!\n", result.output());
        assertEquals("", result.error());
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void main_failedPrompt_exitsWithFailureAndQuietStderr() throws Exception {
        ProcessResult result = launch("", List.of(), List.of("-ea"), FailedOutputMain.class);

        assertEquals(1, result.status());
        assertEquals("", result.output());
        assertEquals("", result.error());
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void main_failedArgumentErrorOutput_exitsBeforeLoadingAndPreservesBytes() throws Exception {
        Path data = directory.resolve("data/scheduleflow.txt");
        Files.createDirectories(data.getParent());
        byte[] original = "SCHEDULEFLOW|99\n".getBytes(StandardCharsets.UTF_8);
        Files.write(data, original);

        ProcessResult result = launch("", List.of("--help"), List.of("-ea"), FailedOutputMain.class);

        assertEquals(1, result.status());
        assertEquals("", result.output());
        assertEquals("", result.error());
        assertArrayEquals(original, Files.readAllBytes(data));
    }

    @Test
    void main_defaultDiagnostics_recoverableAndFatalErrorsKeepStderrEmpty() throws Exception {
        ProcessResult recoverable = launch("exit extra\nexit\n", List.of());
        assertEquals(0, recoverable.status());
        assertEquals("scheduleflow> Error: This command accepts no arguments\nExpected: exit\n"
                + "scheduleflow> Goodbye for now!\n", recoverable.output());
        assertEquals("", recoverable.error());
        Path data = directory.resolve("data/scheduleflow.txt");
        Files.createDirectories(data.getParent());
        byte[] original = "SCHEDULEFLOW|99\n".getBytes(StandardCharsets.UTF_8);
        Files.write(data, original);
        ProcessResult fatal = launch("exit\n", List.of());
        assertEquals(1, fatal.status());
        assertEquals("Error: Unsupported file version: 99\n", fatal.output());
        assertEquals("", fatal.error());
        assertArrayEquals(original, Files.readAllBytes(data));
    }

    @Test
    void main_warningFileConfiguration_preservesSeverityAndConsoleFraming() throws Exception {
        Path configuration = directory.resolve("logging.properties");
        Files.writeString(configuration, String.join("\n",
                "handlers=java.util.logging.FileHandler", ".level=INFO", "scheduleflow.cli.level=WARNING",
                "java.util.logging.FileHandler.level=WARNING",
                "java.util.logging.FileHandler.pattern=diagnostics-%u.log",
                "java.util.logging.FileHandler.encoding=UTF-8",
                "java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter"), StandardCharsets.UTF_8);
        ProcessResult result = launch("exit extra\nexit\n", List.of(),
                List.of("-Djava.util.logging.config.file=" + configuration));
        assertEquals(0, result.status());
        assertEquals("scheduleflow> Error: This command accepts no arguments\nExpected: exit\n"
                + "scheduleflow> Goodbye for now!\n", result.output());
        assertEquals("", result.error());
        String log = Files.readString(directory.resolve("diagnostics-0.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("Command syntax rejected"));
        assertTrue(log.contains("ParseException: This command accepts no arguments"));
        assertFalse(log.contains("Starting application session"));
        assertFalse(log.contains("Starting command parsing"));
    }

    @Test
    void main_loggingConfigurationClass_preservesWarningDiagnostics() throws Exception {
        ProcessResult result = launch("exit extra\nexit\n", List.of(),
                List.of("-ea", "-Djava.util.logging.config.class=" + WarningConfiguration.class.getName()));

        assertWarningDiagnostics(result);
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void main_preconfiguredCliLogger_preservesWarningDiagnostics() throws Exception {
        ProcessResult result = launch("exit extra\nexit\n", List.of("cli"), List.of("-ea"), ConfiguredMain.class);

        assertWarningDiagnostics(result);
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void main_preconfiguredApplicationLogger_preservesWarningDiagnostics() throws Exception {
        ProcessResult result = launch("exit extra\nexit\n", List.of("application"), List.of("-ea"),
                ConfiguredMain.class);

        assertWarningDiagnostics(result);
        assertFalse(Files.exists(directory.resolve("data")));
    }

    @Test
    void main_corruptSavedPlan_exitsBeforePromptAndPreservesBytes() throws Exception {
        Path data = directory.resolve("data/scheduleflow.txt");
        Files.createDirectories(data.getParent());
        byte[] original = ("SCHEDULEFLOW|6\nNEXT|1|1|1\n"
                + "PLAN|2026-11-02T07:00|2027-11-02T00:00|1|0|0\n").getBytes(StandardCharsets.UTF_8);
        Files.write(data, original);

        ProcessResult result = launch("exit\n", List.of(), List.of("-ea"));

        assertEquals(1, result.status());
        assertEquals("Error: Saved plan row counts do not match its records.\n", result.output());
        assertEquals("", result.error());
        assertArrayEquals(original, Files.readAllBytes(data));
    }

    @Test
    void main_directoryAtStorePath_exitsBeforePromptAndPreservesDirectory() throws Exception {
        Path data = directory.resolve("data/scheduleflow.txt");
        Files.createDirectories(data);
        Path marker = data.resolve("keep.txt");
        byte[] original = "Existing directory contents\n".getBytes(StandardCharsets.UTF_8);
        Files.write(marker, original);

        ProcessResult result = launch("exit\n", List.of(), List.of("-ea"));

        assertEquals(1, result.status());
        List<String> outputLines = result.output().lines().toList();
        assertEquals(1, outputLines.size());
        String errorPrefix = "Error: Cannot load saved data: ";
        assertTrue(outputLines.getFirst().startsWith(errorPrefix));
        Path reportedData = Path.of(outputLines.getFirst().substring(errorPrefix.length()));
        assertEquals(errorPrefix + reportedData + "\n", result.output());
        assertTrue(reportedData.isAbsolute());
        // Child JVMs may retain Windows short names or resolve macOS working-directory aliases.
        assertTrue(Files.isSameFile(data, reportedData), "Reported path must identify the rejected save target");
        assertEquals("", result.error());
        assertTrue(Files.isDirectory(data));
        assertArrayEquals(original, Files.readAllBytes(marker));
    }

    /**
     * Checks that explicit warning logging preserves the public response while filtering lower-severity stages.
     *
     * @param result completed process with a rejected exit followed by a valid exit
     */
    private void assertWarningDiagnostics(ProcessResult result) {
        assertEquals(0, result.status());
        assertEquals("scheduleflow> Error: This command accepts no arguments\nExpected: exit\n"
                + "scheduleflow> Goodbye for now!\n", result.output());
        assertTrue(result.error().contains("WARNING: Command syntax rejected"));
        assertTrue(result.error().contains("ParseException: This command accepts no arguments"));
        assertFalse(result.error().contains("Starting application session"));
        assertFalse(result.error().contains("Starting command parsing"));
    }

    /**
     * Launches the actual entry point using Java 25 and redirects output to avoid pipe-buffer deadlocks.
     */
    private ProcessResult launch(String input, List<String> arguments) throws Exception {
        return launch(input, arguments, List.of());
    }

    /**
     * Launches an isolated process with optional JVM configuration and the supplied application input.
     *
     * @param input complete UTF-8 session input
     * @param arguments application arguments supplied after the main class
     * @param vmArguments JVM options supplied before the classpath
     * @return process status and captured console streams
     * @throws Exception if process startup, input or output collection fails
     */
    private ProcessResult launch(String input, List<String> arguments, List<String> vmArguments) throws Exception {
        return launch(input, arguments, vmArguments, Main.class);
    }

    /**
     * Runs the production entry point directly or through test-only logging setup with a fixed English locale.
     *
     * @param input complete UTF-8 session input
     * @param arguments arguments for the selected entry point
     * @param vmArguments JVM options supplied before the classpath
     * @param entryPoint production main class or logging setup launcher
     * @return process status and captured console streams
     * @throws Exception if process startup, input or output collection fails
     */
    private ProcessResult launch(String input, List<String> arguments, List<String> vmArguments,
            Class<?> entryPoint) throws Exception {
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path testClasses = Path.of(MainTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = classes + File.pathSeparator + testClasses;
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Dfile.encoding=US-ASCII", "-Dstdout.encoding=US-ASCII",
                "-Duser.language=en", "-Duser.country=US"));
        command.addAll(vmArguments);
        command.addAll(List.of("-cp", classpath, entryPoint.getName()));
        command.addAll(arguments);
        Path output = directory.resolve("stdout.txt");
        Path error = directory.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                writer.write(input);
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Application did not terminate");
            return new ProcessResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n"), Files.readString(error, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Configures warning output in memory; constructing a console handler does not emit any records.
     *
     * @param logger logger whose explicit level must survive production startup
     */
    private static void configureWarningLogger(Logger logger) {
        logger.setLevel(Level.WARNING);
        logger.setUseParentHandlers(false);
        logger.addHandler(new ConsoleHandler());
    }

    /**
     * Supplies class-based logging configuration; Java logging loads this public type by name in the child JVM.
     */
    public static final class WarningConfiguration {
        /**
         * Installs an inherited root warning level without assigning either application namespace a level.
         */
        public WarningConfiguration() {
            configureWarningLogger(Logger.getLogger(""));
        }
    }

    /**
     * Sets an application logger before invoking the actual entry point in the child process.
     */
    public static final class ConfiguredMain {
        /**
         * Keeps the configured child logger reachable throughout the process.
         */
        private static final Logger CLI_LOGGER = Logger.getLogger("scheduleflow.cli");
        /**
         * Keeps the configured application logger reachable throughout the process.
         */
        private static final Logger APPLICATION_LOGGER = Logger.getLogger("scheduleflow");

        /**
         * Prevents instantiation of this test launcher.
         */
        private ConfiguredMain() {
        }

        /**
         * Configures the selected logger and starts Main without application arguments.
         *
         * @param args exactly one selector: {@code cli} or {@code application}
         */
        public static void main(String[] args) {
            Logger logger = switch (args[0]) {
                case "cli" -> CLI_LOGGER;
                case "application" -> APPLICATION_LOGGER;
                default -> throw new IllegalArgumentException("Unknown test logger selector: " + args[0]);
            };
            configureWarningLogger(logger);
            Main.main(new String[0]);
        }
    }

    /**
     * Injects failed stdout only inside a child JVM, leaving the test runner's shared streams untouched.
     */
    public static final class FailedOutputMain {
        /**
         * Prevents instantiation of this test launcher.
         */
        private FailedOutputMain() {
        }

        /**
         * Installs a deterministic failed output stream before invoking the production entry point.
         *
         * @param args arguments forwarded unchanged to production Main
         */
        public static void main(String[] args) {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("Output disconnected");
                }
            }, true, StandardCharsets.UTF_8));
            Main.main(args);
        }
    }

    /**
     * Holds the observable exit code and both process streams.
     */
    private record ProcessResult(int status, String output, String error) {
    }
}
// @@author
