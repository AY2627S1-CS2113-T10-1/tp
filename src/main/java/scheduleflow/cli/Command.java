//@@author ka-how
package scheduleflow.cli;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Defines the complete command vocabulary as immutable transport values.
 */
public sealed interface Command {
    // @@author GabrielYuSP
    /** Requests replacement of one recurring weekday or date-specific window. */
    record SetHours(String target, LocalTime start, LocalTime end) implements Command {
        /** Requires transport values; domain validation happens during execution. */
        public SetHours {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    /** Requests the effective window for a weekday or date. */
    record ShowHours(String target) implements Command {
        /** Requires a target for lookup. */
        public ShowHours {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Removes the selected override, restoring inherited hours. */
    record ResetHours(String target) implements Command {
        /** Requires a target for reset. */
        public ResetHours {
            Objects.requireNonNull(target, "target");
        }
    }

    // @@author ka-how
    /**
     * Requests command help.
     */
    record Help() implements Command {
    }

    /**
     * Carries parsed task fields without domain or future-deadline validation.
     */
    record AddTask(String name, LocalDateTime deadline, int minutes) implements Command {
        /**
         * Requires text and a deadline, leaving duration and deadline rules to the task service.
         *
         * @throws NullPointerException if the name or deadline is null
         */
        public AddTask {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    /**
     * Requests all unfinished tasks.
     */
    record ListTasks() implements Command {
    }

    /**
     * Carries the numeric suffix of a task ID.
     */
    record DeleteTask(int id) implements Command {
    }

    /**
     * Carries parsed weekly commitment fields without overlap validation.
     */
    record AddCommitment(String name, DayOfWeek day, LocalTime start, int minutes) implements Command {
        /**
         * Requires the nonnumeric fields, leaving interval and overlap rules to the service.
         *
         * @throws NullPointerException if the name, day or start time is null
         */
        public AddCommitment {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(start, "start");
        }
    }

    /**
     * Requests all recurring weekly commitments.
     */
    record ListCommitments() implements Command {
    }

    /**
     * Carries the numeric suffix of a commitment ID.
     */
    record DeleteCommitment(int id) implements Command {
    }

    /**
     * Requests replacement of the temporary plan.
     */
    record GeneratePlan() implements Command {
    }

    /**
     * Defers resolving today until dispatch captures the clock.
     */
    record ScheduleToday() implements Command {
    }

    /**
     * Requests a schedule projection for an explicit date.
     */
    record ScheduleDate(LocalDate date) implements Command {
        /**
         * Requires an explicit date, leaving plan-range validation to the schedule service.
         *
         * @throws NullPointerException if the date is null
         */
        public ScheduleDate {
            Objects.requireNonNull(date, "date");
        }
    }

    /**
     * Requests normal exit without another save.
     */
    record Exit() implements Command {
    }
}
