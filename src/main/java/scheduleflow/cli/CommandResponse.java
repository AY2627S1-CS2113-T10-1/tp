//@@author ka-how
package scheduleflow.cli;

import java.util.Objects;

/**
 * Carries formatted output and whether command execution requests normal termination.
 *
 * @param output response text without console framing
 * @param shouldExit whether the command loop should terminate normally
 */
public record CommandResponse(String output, boolean shouldExit) {
    /**
     * Requires actual output text; unfinished commands must never fabricate a response.
     *
     * @throws NullPointerException if the output is null
     */
    public CommandResponse {
        Objects.requireNonNull(output, "output");
    }
}
