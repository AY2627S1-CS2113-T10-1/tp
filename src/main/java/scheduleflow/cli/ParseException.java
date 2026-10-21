//@@author ka-how
package scheduleflow.cli;

import java.util.Objects;

/**
 * Reports invalid command syntax together with the recognized command's usage.
 */
public final class ParseException extends Exception {
    /**
     * Stores the recognized command format to accompany the error message.
     */
    private final String usage;

    /**
     * Creates a parse failure with a nonnull message and usage hint.
     *
     * @param message explanation of the syntax failure
     * @param usage expected command format or help hint
     * @throws NullPointerException if either argument is null
     */
    public ParseException(String message, String usage) {
        super(Objects.requireNonNull(message, "message"));
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    /**
     * Returns the recognized command format or the help hint for an unknown command.
     */
    public String usage() {
        return usage;
    }
}
