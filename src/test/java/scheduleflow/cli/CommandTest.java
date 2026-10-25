package scheduleflow.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** Checks the real transport contracts without treating unfinished range operations as working features. */
class CommandTest {
    @Test
    void addContinuousBlock_transport_preservesValuesAndLeavesDomainValidationToService() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 15);
        LocalDateTime end = start.minusDays(1);
        Command.AddContinuousBlock command = new Command.AddContinuousBlock(" Trip ", start, end);
        assertEquals(" Trip ", command.name());
        assertEquals(start, command.start());
        assertEquals(end, command.end());
        assertThrows(NullPointerException.class, () -> new Command.AddContinuousBlock(null, start, end));
        assertThrows(NullPointerException.class, () -> new Command.AddContinuousBlock("Trip", null, end));
        assertThrows(NullPointerException.class, () -> new Command.AddContinuousBlock("Trip", start, null));
    }

    @Test
    void addDailyBlocks_transport_preservesValuesAndRejectsMissingReferences() {
        LocalDate first = LocalDate.of(2026, 10, 26);
        LocalDate last = first.minusDays(1);
        LocalTime start = LocalTime.of(17, 15);
        LocalTime end = LocalTime.of(15, 0);
        Command.AddDailyBlocks command = new Command.AddDailyBlocks(" ", first, last, start, end);
        assertEquals(" ", command.name());
        assertEquals(first, command.firstDate());
        assertEquals(last, command.lastDate());
        assertEquals(start, command.start());
        assertEquals(end, command.end());
        assertThrows(NullPointerException.class, () -> new Command.AddDailyBlocks(null, first, last, start, end));
        assertThrows(NullPointerException.class, () -> new Command.AddDailyBlocks("A", null, last, start, end));
        assertThrows(NullPointerException.class, () -> new Command.AddDailyBlocks("A", first, null, start, end));
        assertThrows(NullPointerException.class, () -> new Command.AddDailyBlocks("A", first, last, null, end));
        assertThrows(NullPointerException.class, () -> new Command.AddDailyBlocks("A", first, last, start, null));
    }
}
