// @@author ka-how
package scheduleflow.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns line-oriented console I/O and prompt flushing.
 */
public final class ConsoleUi {
    /**
     * Records I/O stages without including command text or response contents.
     */
    private static final Logger LOGGER = Logger.getLogger(ConsoleUi.class.getName());

    /**
     * Supplies complete input lines and signals end of input with {@code null}.
     */
    private final BufferedReader input;
    /**
     * Receives prompts and responses, flushed after each write.
     */
    private final PrintWriter output;

    /**
     * Creates the ConsoleUi dependencies without performing I/O.
     *
     * @param input reader used by the command loop
     * @param output writer used for prompts and responses
     * @throws NullPointerException if either dependency is null
     */
    public ConsoleUi(BufferedReader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Writes and flushes the prompt without a newline.
     *
     * @throws UncheckedIOException if the console writer reports an output failure
     */
    public void prompt() {
        LOGGER.log(Level.FINE, "Writing command prompt");
        output.print("scheduleflow> ");
        flushOutput();
        LOGGER.log(Level.FINE, "Command prompt flushed");
    }

    /**
     * Reads a line, returning null only for end of input.
     *
     * @return the next line without its line terminator, or null at end of input
     * @throws IOException if input cannot be read
     */
    public String readLine() throws IOException {
        LOGGER.log(Level.FINE, "Reading command input");
        String line = input.readLine();
        LOGGER.log(Level.FINE, line == null ? "Reached end of input" : "Command input read");
        return line;
    }

    /**
     * Writes text followed by a newline and flushes.
     *
     * @param text response without a final newline
     * @throws NullPointerException if the text is null
     * @throws UncheckedIOException if the console writer reports an output failure
     */
    public void write(String text) {
        Objects.requireNonNull(text, "text");
        LOGGER.log(Level.FINE, "Writing command response");
        output.println(text);
        flushOutput();
        LOGGER.log(Level.FINE, "Command response flushed");
    }

    /**
     * Flushes and exposes failures that {@link PrintWriter} otherwise suppresses.
     * The writer's error flag does not retain the original checked exception.
     *
     * @throws UncheckedIOException if writing or flushing has failed
     */
    private void flushOutput() {
        if (output.checkError()) {
            throw new UncheckedIOException(new IOException("Cannot write console output."));
        }
    }
}
// @@author
