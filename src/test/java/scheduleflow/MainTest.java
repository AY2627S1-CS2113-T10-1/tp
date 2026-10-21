//@@author ka-how
package scheduleflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises process status, UTF-8 production wiring and real saved restarts without exiting JUnit.
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
        assertTrue(second.output().contains("No current plan. Run plan to generate one."));
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
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Dfile.encoding=US-ASCII", "-Dstdout.encoding=US-ASCII"));
        command.addAll(vmArguments);
        command.addAll(List.of("-cp", classes.toString(), "scheduleflow.Main"));
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
     * Holds the observable exit code and both process streams.
     */
    private record ProcessResult(int status, String output, String error) {
    }
}
