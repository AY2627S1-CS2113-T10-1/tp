//@@author ka-how
package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Verifies exact prompt framing, flushed output and line/EOF/error handling without system streams.
 */
class ConsoleUiTest {
    @Test
    void write_nullText_rejectsWithoutWritingOrFlushing() {
        FlushWriter output = new FlushWriter();
        ConsoleUi ui = new ConsoleUi(new BufferedReader(new StringReader("")), new PrintWriter(output));
        assertEquals("text", assertThrows(NullPointerException.class, () -> ui.write(null)).getMessage());
        assertEquals("", output.toString());
        assertEquals(0, output.flushes);
    }

    @Test
    void promptAndWrite_flushExactTextAndNewlines() {
        FlushWriter output = new FlushWriter();
        ConsoleUi ui = new ConsoleUi(new BufferedReader(new StringReader("")), new PrintWriter(output));
        ui.prompt();
        assertEquals("scheduleflow> ", output.toString());
        assertEquals(1, output.flushes);
        ui.write("First\n学习");
        assertEquals("scheduleflow> First\n学习" + System.lineSeparator(), output.toString());
        assertEquals(2, output.flushes);
    }

    @Test
    void readLine_blankUnicodeAndEof_preservesLines() throws Exception {
        ConsoleUi ui = new ConsoleUi(new BufferedReader(new StringReader("\n学习\r\nlast")),
                new PrintWriter(new StringWriter()));
        assertEquals("", ui.readLine());
        assertEquals("学习", ui.readLine());
        assertEquals("last", ui.readLine());
        assertNull(ui.readLine());
    }

    @Test
    void readLine_inputFailure_propagatesIOException() {
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("Input disconnected");
            }
        };
        ConsoleUi ui = new ConsoleUi(input, new PrintWriter(new StringWriter()));
        assertEquals("Input disconnected", assertThrows(IOException.class, ui::readLine).getMessage());
    }

    /**
     * Records flushes separately from buffered output so tests do not rely on timing.
     */
    private static final class FlushWriter extends StringWriter {
        private int flushes;

        @Override
        public void flush() {
            flushes++;
        }
    }
}
