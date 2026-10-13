package scheduleflow.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * Owns line-oriented console I/O and prompt flushing.
 */
public final class ConsoleUi {
    private final BufferedReader input;
    private final PrintWriter output;

    /**
     * Creates the ConsoleUi dependencies without performing I/O.
     */
    public ConsoleUi(BufferedReader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Writes and flushes the prompt without a newline.
     */
    public void prompt() {
        output.print("scheduleflow> ");
        output.flush();
    }

    /**
     * Reads a line, returning null only for end of input.
     *
     * @throws IOException if input cannot be read
     */
    public String readLine() throws IOException {
        return input.readLine();
    }

    /**
     * Writes text followed by a newline and flushes.
     */
    public void write(String text) {
        output.println(Objects.requireNonNull(text, "text"));
        output.flush();
    }
}
