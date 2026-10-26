// @@author GabrielYuSP
package scheduleflow.model;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import scheduleflow.common.ValidationException;

/** Stores immutable windows keyed by lowercase full weekday names or ISO calendar dates. */
public record StudyHours(Map<String, StudyWindow> windows) {
    /** Copies and validates all settings without consulting the clock. */
    public StudyHours {
        Objects.requireNonNull(windows, "windows");
        windows.forEach((target, window) -> {
            requireTarget(target);
            Objects.requireNonNull(window, "window");
        });
        windows = Map.copyOf(windows);
    }

    /** Resolves a date override, then its weekday setting, then the default window. */
    public StudyWindow forDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return windows.getOrDefault(date.toString(), forTarget(date.getDayOfWeek().name().toLowerCase(Locale.ROOT)));
    }

    /** Returns effective hours for a weekday or date, including inherited defaults. */
    public StudyWindow forTarget(String target) {
        requireTarget(target);
        if (Character.isDigit(target.charAt(0))) {
            return forDate(LocalDate.parse(target));
        }
        return windows.getOrDefault(target, StudyWindow.DEFAULT);
    }

    /** Returns a candidate with one setting replaced, leaving all others unchanged. */
    public StudyHours withWindow(String target, StudyWindow window) {
        requireTarget(target);
        Map<String, StudyWindow> updated = new HashMap<>(windows);
        updated.put(target, Objects.requireNonNull(window, "window"));
        return new StudyHours(updated);
    }

    /** Removes only the selected override so its inherited window becomes effective again. */
    public StudyHours reset(String target) {
        requireTarget(target);
        Map<String, StudyWindow> updated = new HashMap<>(windows);
        updated.remove(target);
        return new StudyHours(updated);
    }

    /** Rejects noncanonical weekdays, impossible dates and years outside 0001-9999. */
    private static void requireTarget(String target) {
        if (target != null) {
            try {
                if (target.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    if (LocalDate.parse(target).getYear() > 0) {
                        return;
                    }
                } else if (target.equals(target.toLowerCase(Locale.ROOT))) {
                    DayOfWeek.valueOf(target.toUpperCase(Locale.ROOT));
                    return;
                }
            } catch (DateTimeException | IllegalArgumentException exception) {
                throw new ValidationException("Use a lowercase full weekday or a valid YYYY-MM-DD date.");
            }
        }
        throw new ValidationException("Use a lowercase full weekday or a valid YYYY-MM-DD date.");
    }
}
// @@author
