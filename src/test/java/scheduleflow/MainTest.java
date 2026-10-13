package scheduleflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Launches the actual entry point using Java 25 and redirects output to avoid pipe-buffer deadlocks.
     */
    private ProcessResult launch(String input, List<String> arguments) throws Exception {
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Dfile.encoding=US-ASCII", "-Dstdout.encoding=US-ASCII", "-cp", classes.toString(),
                "scheduleflow.Main"));
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
