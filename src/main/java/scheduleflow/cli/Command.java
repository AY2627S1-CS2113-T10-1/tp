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
    /**
     * Requests command help.
     */
    record Help() implements Command {
        /**
         * Creates a help request without consulting application state.
         */
        public Help {
        }
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
        /**
         * Creates a request for stored tasks without reading them.
         */
        public ListTasks {
        }
    }

    /**
     * Carries the numeric suffix of a task ID.
     */
    record DeleteTask(int id) implements Command {
        /**
         * Retains the supplied ID for validation by the task service.
         */
        public DeleteTask {
        }
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
        /**
         * Creates a weekly-series list request without reading application state.
         */
        public ListCommitments {
        }
    }

    /**
     * Carries the numeric suffix of a commitment ID.
     */
    record DeleteCommitment(int id) implements Command {
        /**
         * Retains the supplied ID for validation by the commitment service.
         */
        public DeleteCommitment {
        }
    }

    /**
     * Requests replacement of the temporary plan.
     */
    record GeneratePlan() implements Command {
        /**
         * Creates a plan-generation request without capturing time or allocating work.
         */
        public GeneratePlan {
        }
    }

    /**
     * Defers resolving today until dispatch captures the clock.
     */
    record ScheduleToday() implements Command {
        /**
         * Creates a request whose date will be resolved from the dispatcher's clock.
         */
        public ScheduleToday {
        }
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
        /**
         * Creates a termination request without saving data or exiting the process.
         */
        public Exit {
        }
    }
}
