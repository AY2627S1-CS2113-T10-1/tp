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
    /** Carries an inclusive start and exclusive end for a continuous unavailable interval. */
    record AddContinuousBlock(String name, LocalDateTime start, LocalDateTime end) implements Command {
        /** Requires transport references; the commitment service will validate values and ordering. */
        public AddContinuousBlock {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    /** Carries a same-day window repeated over an inclusive range of dates. */
    record AddDailyBlocks(String name, LocalDate firstDate, LocalDate lastDate,
            LocalTime start, LocalTime end) implements Command {
        /** Requires transport references while leaving domain validation to the commitment service. */
        public AddDailyBlocks {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(firstDate, "firstDate");
            Objects.requireNonNull(lastDate, "lastDate");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }
    // @@author

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

    // @@author ernestgoh-god
    /** Carries a replacement remaining-work estimate for a stored task. */
    record ReviseTaskRemaining(int id, int remainingMinutes) implements Command {
    }

    /** Carries reported whole minutes of completed task work. */
    record RecordTaskWork(int id, int doneMinutes) implements Command {
    }

    /** Carries a reported session duration and its completed-work percentage. */
    record RecordTaskSession(int id, int sessionMinutes, int percent) implements Command {
    }
    // @@author ka-how

    /**
     * Carries parsed weekly commitment fields without overlap validation.
     * Equal start and end dates describe a one-time commitment on that date.
     */
    record AddCommitment(String name, DayOfWeek day, LocalTime start, int minutes,
            LocalDate startDate, LocalDate endDate) implements Command {
        //@@author Darren50
        /** Preserves callers adding an unbounded weekly series. */
        public AddCommitment(String name, DayOfWeek day, LocalTime start, int minutes) {
            this(name, day, start, minutes, null, null);
        }
        //@@author ka-how
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

    //@@author Darren50
    /**
     * Carries a partial weekly-series edit; null fields are omitted rather than cleared.
     * The service validates the ID, presence of changes and resulting complete interval.
     */
    record EditCommitment(int id, String name, DayOfWeek day, LocalTime start, Integer minutes,
            LocalDate startDate, LocalDate endDate) implements Command {
        /** Preserves callers editing fields without changing date limits. */
        public EditCommitment(int id, String name, DayOfWeek day, LocalTime start, Integer minutes) {
            this(id, name, day, start, minutes, null, null);
        }
    }

    /** Carries one calendar occurrence to remove from a recurring commitment. */
    record SkipCommitment(int id, LocalDate date) implements Command {
        /** Requires the occurrence date while leaving ID and recurrence validation to the service. */
        public SkipCommitment {
            Objects.requireNonNull(date, "date");
        }
    }
    //@@author ka-how

    /**
     * Requests all recurring weekly and one-time commitments.
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
