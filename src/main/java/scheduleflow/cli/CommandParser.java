//@@author ka-how
package scheduleflow.cli;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import scheduleflow.task.TaskEdit;

/**
 * Parses the complete case-sensitive command grammar without inspecting application state.
 */
public final class CommandParser {
    /**
     * Records recognition and syntax-checking stages without logging raw command text.
     */
    private static final Logger LOGGER = Logger.getLogger(CommandParser.class.getName());

    private static final String HELP_USAGE = "Type help to see available commands";
    private static final String TASK_ADD_USAGE = "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES";
    private static final String TASK_DELETE_USAGE = "task delete TNUMBER";
    // @@author ernestgoh-god
    private static final String TASK_LIST_USAGE = "task list OR task list completed";
    private static final String TASK_COMPLETE_USAGE = "task complete TNUMBER";
    private static final String POLICY_USAGE = "policy set max/MINUTES break/MINUTES OR policy show OR policy reset";
    // @@author ka-how
    // @@author nickpatt
    private static final String TASK_EDIT_USAGE = "task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]";
    private static final String TASK_RECUR_USAGE = "task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES"
            + " every/WEEKLY [until/YYYY-MM-DD] OR task recur list OR task recur delete RNUMBER";
    // @@author ka-how
    // @@author ernestgoh-god
    private static final String TASK_REMAINING_USAGE = "task remaining TNUMBER remaining/MINUTES";
    private static final String TASK_PROGRESS_USAGE =
            "task progress TNUMBER done/MINUTES OR task progress TNUMBER session/MINUTES percent/PERCENT";
    //@@author Darren50
    private static final String DATE_RANGE_USAGE = " [from/YYYY-MM-DD until/YYYY-MM-DD]";
    private static final String ONE_TIME_ADD_USAGE = "commitment add n/NAME on/YYYY-MM-DD start/HHmm d/MINUTES";
    // @@author ka-how
    private static final String COMMITMENT_ADD_USAGE = "commitment add n/NAME day/DAY start/HHmm d/MINUTES"
            + DATE_RANGE_USAGE
            //@@author
            + " OR " + ONE_TIME_ADD_USAGE;
    // @@author ka-how
    private static final String COMMITMENT_DELETE_USAGE = "commitment delete CNUMBER";
    //@@author Darren50
    private static final String COMMITMENT_EDIT_USAGE =
            "commitment edit CNUMBER [n/NAME] [day/DAY|on/YYYY-MM-DD] [start/HHmm] [d/MINUTES]" + DATE_RANGE_USAGE;
    private static final String COMMITMENT_SKIP_USAGE = "commitment skip CNUMBER on/YYYY-MM-DD";
    // @@author ka-how
    private static final String SCHEDULE_USAGE = "schedule today OR schedule YYYY-MM-DD";
    private static final String HOURS_USAGE = "hours DAY|YYYY-MM-DD [/start HH:mm /end HH:mm]"
            + " OR hours reset DAY|YYYY-MM-DD";
    // @@author ernestgoh-god
    private static final String UNAVAILABLE_CONTINUOUS_USAGE =
            "unavailable continuous n/NAME from/YYYY-MM-DD HHmm until/YYYY-MM-DD HHmm";
    private static final String UNAVAILABLE_DAILY_USAGE =
            "unavailable daily n/NAME from/YYYY-MM-DD until/YYYY-MM-DD start/HHmm end/HHmm";
    private static final String UNAVAILABLE_USAGE = UNAVAILABLE_CONTINUOUS_USAGE
            + " OR " + UNAVAILABLE_DAILY_USAGE;
    // @@author ka-how
    private static final String DATE_TOKEN = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String DATE_RANGE_PATTERN = "(?: +from/(?<from>" + DATE_TOKEN
            + ") +until/(?<until>" + DATE_TOKEN + "))?";
    //@@author Darren50
    /**
     * Selects a repeating weekday or, for a one-time commitment, one calendar date in the same position.
     */
    private static final String DAY_OR_DATE_PATTERN = "(?:day/(?<day>MON|TUE|WED|THU|FRI|SAT|SUN)"
            + "|on/(?<on>" + DATE_TOKEN + "))";
    // @@author ka-how
    private static final String TIME_TOKEN = "[0-9]{4}";
    private static final Pattern HOURS_PATTERN = Pattern.compile("hours +(?<reset>reset +)?(?<target>"
            + "monday|tuesday|wednesday|thursday|friday|saturday|sunday|" + DATE_TOKEN + ")"
            + "(?: +/start +(?<start>[0-9]{2}:[0-9]{2}) +/end +(?<end>[0-9]{2}:[0-9]{2}))?");
    private static final DateTimeFormatter HOURS_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final String POSITIVE_INT_TOKEN = "[1-9][0-9]*";
    // @@author ernestgoh-god
    private static final Pattern POLICY_SET_PATTERN = Pattern.compile(
            "policy +set +max/(?<max>0|" + POSITIVE_INT_TOKEN + ") +break/(?<break>0|" + POSITIVE_INT_TOKEN + ")");
    // @@author ka-how

    /**
     * Matches name text while excluding slash so extra prefixes cannot be swallowed into a multiword name.
     */
    private static final String NAME_TOKEN = "[^/\\p{javaISOControl}]*";
    // @@author ernestgoh-god
    private static final Pattern UNAVAILABLE_CONTINUOUS_PATTERN = Pattern.compile(
            "unavailable +continuous +n/(?<name>" + NAME_TOKEN + ") +from/(?<from>" + DATE_TOKEN
                    + ") +(?<start>" + TIME_TOKEN + ") +until/(?<until>" + DATE_TOKEN
                    + ") +(?<end>" + TIME_TOKEN + ")");
    private static final Pattern UNAVAILABLE_DAILY_PATTERN = Pattern.compile(
            "unavailable +daily +n/(?<name>" + NAME_TOKEN + ") +from/(?<from>" + DATE_TOKEN
                    + ") +until/(?<until>" + DATE_TOKEN + ") +start/(?<start>" + TIME_TOKEN
                    + ") +end/(?<end>" + TIME_TOKEN + ")");
    // @@author ka-how
    private static final Pattern TASK_ADD_PATTERN = Pattern.compile(
            "task +add +n/(?<name>" + NAME_TOKEN + ") +due/(?<date>" + DATE_TOKEN
                    + ") +(?<time>" + TIME_TOKEN + ") +d/(?<minutes>" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern COMMITMENT_ADD_PATTERN = Pattern.compile(
            "commitment +add +n/(?<name>" + NAME_TOKEN + ") +" + DAY_OR_DATE_PATTERN
                    + " +start/(?<time>" + TIME_TOKEN + ") +d/(?<minutes>" + POSITIVE_INT_TOKEN + ")"
                    + DATE_RANGE_PATTERN);
    private static final Pattern TASK_DELETE_PATTERN = Pattern.compile(
            "task +delete +T(?<id>" + POSITIVE_INT_TOKEN + ")");
    // @@author ernestgoh-god
    private static final Pattern TASK_COMPLETE_PATTERN = Pattern.compile(
            "task +complete +T(?<id>" + POSITIVE_INT_TOKEN + ")");
    // @@author ka-how
    // @@author nickpatt
    private static final Pattern TASK_EDIT_PATTERN = Pattern.compile(
            "task +edit +T(?<id>" + POSITIVE_INT_TOKEN + ")"
                    + "(?: +n/(?<name>" + NAME_TOKEN + "))?"
                    + "(?: +due/(?<date>" + DATE_TOKEN + ") +(?<time>" + TIME_TOKEN + "))?");
    private static final Pattern TASK_RECUR_ADD_PATTERN = Pattern.compile(
            "task +recur +add +n/(?<name>" + NAME_TOKEN + ") +due/(?<date>" + DATE_TOKEN
                    + ") +(?<time>" + TIME_TOKEN + ") +d/(?<minutes>" + POSITIVE_INT_TOKEN + ")"
                    + " +every/WEEKLY(?: +until/(?<until>" + DATE_TOKEN + "))?");
    private static final Pattern TASK_RECUR_DELETE_PATTERN = Pattern.compile(
            "task +recur +delete +R(?<id>" + POSITIVE_INT_TOKEN + ")");
    // @@author ka-how
    // @@author ernestgoh-god
    private static final Pattern TASK_PROGRESS_WORK_PATTERN = Pattern.compile(
            "task +progress +T(?<id>" + POSITIVE_INT_TOKEN + ") +done/(?<done>0|" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern TASK_PROGRESS_SESSION_PATTERN = Pattern.compile(
            "task +progress +T(?<id>" + POSITIVE_INT_TOKEN + ") +session/(?<session>"
                    + POSITIVE_INT_TOKEN + ") +percent/(?<percent>0|" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern TASK_REMAINING_PATTERN = Pattern.compile(
            "task +remaining +T(?<id>" + POSITIVE_INT_TOKEN + ") +remaining/(?<minutes>"
                    + POSITIVE_INT_TOKEN + ")");
    // @@author ka-how
    private static final Pattern COMMITMENT_DELETE_PATTERN = Pattern.compile(
            "commitment +delete +C(?<id>" + POSITIVE_INT_TOKEN + ")");
    //@@author Darren50
    private static final Pattern COMMITMENT_SKIP_PATTERN = Pattern.compile(
            "commitment +skip +C(?<id>" + POSITIVE_INT_TOKEN + ") +on/(?<date>" + DATE_TOKEN + ")");
    // @@author ka-how
    private static final Pattern SCHEDULE_PATTERN = Pattern.compile("schedule +(?<date>today|" + DATE_TOKEN + ")");
    //@@author Darren50
    /**
     * Accepts any nonempty subset in the same field order as addition; the service rejects an empty edit.
     */
    private static final Pattern COMMITMENT_EDIT_PATTERN = Pattern.compile(
            "commitment +edit +C(?<id>" + POSITIVE_INT_TOKEN + ")"
                    + "(?: +n/(?<name>" + NAME_TOKEN + "))?"
                    + "(?: +" + DAY_OR_DATE_PATTERN + ")?"
                    + "(?: +start/(?<time>" + TIME_TOKEN + "))?"
                    + "(?: +d/(?<minutes>" + POSITIVE_INT_TOKEN + "))?" + DATE_RANGE_PATTERN);
    //@@author ka-how
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HHmm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    /**
     * Creates a stateless parser without performing I/O.
     */
    public CommandParser() {
    }

    // @@author ernestgoh-god
    /**
     * Parses either unavailable range mode without applying commitment service rules.
     *
     * @param line complete unavailable command using the grammar in the Developer Guide
     * @return continuous or daily transport command
     * @throws ParseException if syntax, dates or times are invalid
     */
    private Command parseUnavailable(String line) throws ParseException {
        String mode = unavailableMode(line);
        String usage = unavailableUsage(mode);
        try {
            return switch (mode) {
                case "continuous" -> parseContinuousBlock(line, usage);
                case "daily" -> parseDailyBlocks(line, usage);
                default -> throw new ParseException("Invalid unavailable command format", usage);
            };
        } catch (DateTimeParseException exception) {
            throw new ParseException("Invalid date or time", usage);
        }
    }

    /** Reads the unavailable mode word while leaving complete syntax checks to its pattern. */
    private String unavailableMode(String input) {
        String[] words = input.split(" +", 3);
        return words.length > 1 ? words[1] : "";
    }

    /** Selects the relevant mode's usage without accepting an unknown mode. */
    private String unavailableUsage(String mode) {
        return switch (mode) {
            case "continuous" -> UNAVAILABLE_CONTINUOUS_USAGE;
            case "daily" -> UNAVAILABLE_DAILY_USAGE;
            default -> UNAVAILABLE_USAGE;
        };
    }

    /** Builds a transport value from an entire continuous-mode match. */
    private Command.AddContinuousBlock parseContinuousBlock(String input, String usage) throws ParseException {
        Matcher fields = matchUnavailable(UNAVAILABLE_CONTINUOUS_PATTERN, input, usage);
        LocalDate firstDate = parseOptionalDate(fields.group("from"), usage);
        LocalDate lastDate = parseOptionalDate(fields.group("until"), usage);
        LocalTime start = LocalTime.parse(fields.group("start"), TIME_FORMAT);
        LocalTime end = LocalTime.parse(fields.group("end"), TIME_FORMAT);
        return new Command.AddContinuousBlock(fields.group("name").strip(),
                firstDate.atTime(start), lastDate.atTime(end));
    }

    /** Builds a transport value from an entire daily-mode match. */
    private Command.AddDailyBlocks parseDailyBlocks(String input, String usage) throws ParseException {
        Matcher fields = matchUnavailable(UNAVAILABLE_DAILY_PATTERN, input, usage);
        LocalDate firstDate = parseOptionalDate(fields.group("from"), usage);
        LocalDate lastDate = parseOptionalDate(fields.group("until"), usage);
        LocalTime start = LocalTime.parse(fields.group("start"), TIME_FORMAT);
        LocalTime end = LocalTime.parse(fields.group("end"), TIME_FORMAT);
        return new Command.AddDailyBlocks(fields.group("name").strip(), firstDate, lastDate, start, end);
    }

    /** Rejects an incomplete, reordered or mixed unavailable command before constructing a transport value. */
    private Matcher matchUnavailable(Pattern pattern, String input, String usage) throws ParseException {
        Matcher fields = pattern.matcher(input);
        if (!fields.matches()) {
            throw new ParseException("Invalid unavailable command format", usage);
        }
        assert fields.start() == 0 && fields.end() == input.length() : "Grammar must match the complete input";
        return fields;
    }
    // @@author
    // @@author ka-how

    /**
     * Parses one nonblank line, translating syntax, number and date errors to ParseException.
     * Leaves domain validation and resolving today's date to command execution.
     * The application skips blank lines; direct blank input is an unrecognized command.
     *
     * @param line complete input line
     * @return immutable command with syntactically valid transport values
     * @throws ParseException if syntax, a number or a date/time is invalid
     * @throws NullPointerException if the line is null
     */
    public Command parse(String line) throws ParseException {
        String input = Objects.requireNonNull(line, "line").strip();
        LOGGER.log(Level.FINE, "Starting command parsing");
        String command = identifyCommand(input);
        String usage = findUsage(command, input);
        LOGGER.log(Level.FINE, "Command words recognized; validating syntax");
        if (input.codePoints().anyMatch(Character::isISOControl)) {
            LOGGER.log(Level.FINE, "Rejected embedded control characters");
            throw new ParseException("Use ordinary spaces instead of embedded control characters", usage);
        }
        try {
            Command result = switch (command) {
                case "help" -> parseNoArguments(input, command, new Command.Help());
                case "task add" -> parseAddTask(input);
                // @@author ernestgoh-god
                case "task list" -> parseTaskList(input);
                case "task complete" -> new Command.CompleteTask(Integer.parseInt(
                        match(TASK_COMPLETE_PATTERN, input, usage).group("id")));
                case "policy" -> parsePolicy(input);
                // @@author ka-how
                case "task delete" -> new Command.DeleteTask(Integer.parseInt(
                        match(TASK_DELETE_PATTERN, input, usage).group("id")));
                // @@author nickpatt
                case "task edit" -> parseEditTask(input);
                case "task recur" -> parseRecurringTask(input);
                // @@author ernestgoh-god
                case "task progress" -> parseTaskProgress(input);
                case "task remaining" -> parseTaskRemaining(input);
                // @@author ka-how
                // @@author ernestgoh-god
                case "unavailable" -> parseUnavailable(input);
                // @@author ka-how
                case "commitment add" -> parseAddCommitment(input);
                case "commitment edit" -> parseEditCommitment(input);
                // @@author
                case "commitment skip" -> parseSkipCommitment(input);
                // @@author ka-how
                case "commitment list" -> parseNoArguments(input, command, new Command.ListCommitments());
                case "commitment delete" -> new Command.DeleteCommitment(Integer.parseInt(
                        match(COMMITMENT_DELETE_PATTERN, input, usage).group("id")));
                case "plan" -> parseNoArguments(input, command, new Command.GeneratePlan());
                case "schedule" -> parseSchedule(input);
                case "hours" -> parseHours(input);
                case "exit" -> parseNoArguments(input, command, new Command.Exit());
                default -> throw new ParseException("Unknown command", HELP_USAGE);
            };
            LOGGER.log(Level.FINE, "Command parsing completed: {0}", result.getClass().getSimpleName());
            return result;
        } catch (DateTimeParseException exception) {
            LOGGER.log(Level.FINE, "Rejected invalid date or time");
            throw new ParseException("Invalid date or time", usage);
        } catch (NumberFormatException exception) {
            LOGGER.log(Level.FINE, "Rejected integer overflow");
            throw new ParseException("Number must fit in a positive 32-bit integer", usage);
        } catch (ParseException exception) {
            LOGGER.log(Level.FINE, "Command syntax rejected");
            throw exception;
        }
    }

    /**
     * Reads only the command words, leaving field spacing and multiword names intact.
     */
    private String identifyCommand(String input) {
        String[] words = input.split(" +", 3);
        if ((words[0].equals("task") || words[0].equals("commitment")) && words.length > 1) {
            return words[0] + " " + words[1];
        }
        return words[0];
    }

    /**
     * Selects the expected syntax for recognized command words or the help hint.
     */
    private String findUsage(String command, String input) {
        return switch (command) {
            case "task add" -> TASK_ADD_USAGE;
            case "task delete" -> TASK_DELETE_USAGE;
            // @@author ernestgoh-god
            case "task list" -> TASK_LIST_USAGE;
            case "task complete" -> TASK_COMPLETE_USAGE;
            case "policy" -> POLICY_USAGE;
            // @@author ka-how
            // @@author nickpatt
            case "task edit" -> TASK_EDIT_USAGE;
            case "task recur" -> TASK_RECUR_USAGE;
            // @@author ka-how
            // @@author ernestgoh-god
            case "task progress" -> TASK_PROGRESS_USAGE;
            case "task remaining" -> TASK_REMAINING_USAGE;
            // @@author ka-how
            // @@author ernestgoh-god
            case "unavailable" -> unavailableUsage(unavailableMode(input));
            // @@author ka-how
            case "commitment add" -> COMMITMENT_ADD_USAGE;
            case "commitment edit" -> COMMITMENT_EDIT_USAGE;
            // @@author
            case "commitment skip" -> COMMITMENT_SKIP_USAGE;
            // @@author ka-how
            case "commitment delete" -> COMMITMENT_DELETE_USAGE;
            case "schedule" -> SCHEDULE_USAGE;
            case "hours" -> HOURS_USAGE;
            case "help", "commitment list", "plan", "exit" -> command;
            default -> HELP_USAGE;
        };
    }

    /**
     * Requires an exact command with ordinary spaces and no trailing arguments.
     */
    private Command parseNoArguments(String input, String command, Command result) throws ParseException {
        if (!input.replaceAll(" +", " ").equals(command)) {
            throw new ParseException("This command accepts no arguments", command);
        }
        return result;
    }

    // @@author ernestgoh-god
    /** Selects the existing full list or completed-only view without accepting other trailing words. */
    private Command parseTaskList(String input) throws ParseException {
        String normalized = input.replaceAll(" +", " ");
        if (normalized.equals("task list")) {
            return new Command.ListTasks();
        }
        if (normalized.equals("task list completed")) {
            return new Command.ListCompletedTasks();
        }
        throw new ParseException("Invalid command format or missing fields", TASK_LIST_USAGE);
    }

    /** Parses settings without allocating a policy, reading saved data or changing application state. */
    private Command parsePolicy(String input) throws ParseException {
        String normalized = input.replaceAll(" +", " ");
        if (normalized.equals("policy show")) {
            return new Command.ShowStudyPolicy();
        }
        if (normalized.equals("policy reset")) {
            return new Command.ResetStudyPolicy();
        }
        Matcher fields = match(POLICY_SET_PATTERN, input, POLICY_USAGE);
        return new Command.SetStudyPolicy(Integer.parseInt(fields.group("max")),
                Integer.parseInt(fields.group("break")));
    }
    // @@author ka-how

    /**
     * Extracts task fields after a complete grammar match, leaving business rules to the service.
     */
    private Command.AddTask parseAddTask(String input) throws ParseException {
        Matcher fields = match(TASK_ADD_PATTERN, input, TASK_ADD_USAGE);
        LocalDateTime deadline = LocalDateTime.parse(fields.group("date") + " " + fields.group("time"),
                DEADLINE_FORMAT);
        requireSupportedYear(deadline.toLocalDate(), TASK_ADD_USAGE);
        return new Command.AddTask(fields.group("name").strip(), deadline, Integer.parseInt(fields.group("minutes")));
    }

    // @@author nickpatt
    /** Preserves omitted fields and rejects malformed or reordered edits before execution. */
    private Command.EditTask parseEditTask(String input) throws ParseException {
        Matcher fields = match(TASK_EDIT_PATTERN, input, TASK_EDIT_USAGE);
        Optional<String> name = Optional.ofNullable(fields.group("name")).map(String::strip);
        Optional<LocalDateTime> deadline = Optional.empty();
        if (fields.group("date") != null) {
            LocalDateTime value = LocalDateTime.parse(fields.group("date") + " " + fields.group("time"),
                    DEADLINE_FORMAT);
            requireSupportedYear(value.toLocalDate(), TASK_EDIT_USAGE);
            deadline = Optional.of(value);
        }
        if (name.isEmpty() && deadline.isEmpty()) {
            throw new ParseException("Supply at least one task field to edit", TASK_EDIT_USAGE);
        }
        return new Command.EditTask(Integer.parseInt(fields.group("id")), new TaskEdit(name, deadline, false));
    }

    /** Parses the weekly-only recurrence vocabulary without inventing a weekday or reading a clock. */
    private Command parseRecurringTask(String input) throws ParseException {
        if (input.replaceAll(" +", " ").equals("task recur list")) {
            return new Command.ListRecurringTasks();
        }
        Matcher deletion = TASK_RECUR_DELETE_PATTERN.matcher(input);
        if (deletion.matches()) {
            return new Command.DeleteRecurringTask(Integer.parseInt(deletion.group("id")));
        }
        Matcher fields = match(TASK_RECUR_ADD_PATTERN, input, TASK_RECUR_USAGE);
        LocalDateTime first = LocalDateTime.parse(fields.group("date") + " " + fields.group("time"), DEADLINE_FORMAT);
        requireSupportedYear(first.toLocalDate(), TASK_RECUR_USAGE);
        LocalDate until = parseOptionalDate(fields.group("until"), TASK_RECUR_USAGE);
        return new Command.AddRecurringTask(fields.group("name").strip(), first,
                Integer.parseInt(fields.group("minutes")), Optional.ofNullable(until));
    }
    // @@author ka-how

    // @@author ernestgoh-god
    /** Parses either progress form without applying task-state validation. */
    private Command parseTaskProgress(String input) throws ParseException {
        Matcher work = TASK_PROGRESS_WORK_PATTERN.matcher(input);
        if (work.matches()) {
            return new Command.RecordTaskWork(Integer.parseInt(work.group("id")),
                    Integer.parseInt(work.group("done")));
        }
        Matcher session = TASK_PROGRESS_SESSION_PATTERN.matcher(input);
        if (session.matches()) {
            int percent = Integer.parseInt(session.group("percent"));
            if (percent > 100) {
                throw new ParseException("Percentage must be from 0 to 100", TASK_PROGRESS_USAGE);
            }
            return new Command.RecordTaskSession(Integer.parseInt(session.group("id")),
                    Integer.parseInt(session.group("session")), percent);
        }
        throw new ParseException("Invalid command format or missing fields", TASK_PROGRESS_USAGE);
    }

    /** Parses a replacement estimate without applying task-state or duration rules. */
    private Command.ReviseTaskRemaining parseTaskRemaining(String input) throws ParseException {
        Matcher fields = match(TASK_REMAINING_PATTERN, input, TASK_REMAINING_USAGE);
        return new Command.ReviseTaskRemaining(Integer.parseInt(fields.group("id")),
                Integer.parseInt(fields.group("minutes")));
    }
    // @@author ka-how

    /**
     * Extracts weekly-series fields and maps the supported three-letter weekday tokens.
     */
    private Command.AddCommitment parseAddCommitment(String input) throws ParseException {
        Matcher fields = match(COMMITMENT_ADD_PATTERN, input, COMMITMENT_ADD_USAGE);
        //@@author Darren50
        LocalDate date = parseOneTimeDate(fields, COMMITMENT_ADD_USAGE);
        if (date != null) {
            return new Command.AddCommitment(fields.group("name").strip(), date.getDayOfWeek(),
                    LocalTime.parse(fields.group("time"), TIME_FORMAT), Integer.parseInt(fields.group("minutes")),
                    date, date);
        }
        // @@author ka-how
        DayOfWeek day = parseWeekday(fields.group("day"), COMMITMENT_ADD_USAGE);
        return new Command.AddCommitment(fields.group("name").strip(), day,
                LocalTime.parse(fields.group("time"), TIME_FORMAT), Integer.parseInt(fields.group("minutes")),
                parseOptionalDate(fields.group("from"), COMMITMENT_ADD_USAGE),
                parseOptionalDate(fields.group("until"), COMMITMENT_ADD_USAGE));
    }

    /**
     * Maps the existing strict weekday vocabulary for both addition and editing.
     */
    private DayOfWeek parseWeekday(String token, String usage) throws ParseException {
        return switch (token) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new ParseException("Invalid weekday", usage);
        };
    }

    //@@author Darren50
    /**
     * Preserves omission explicitly so editing one field never resets the others.
     * Uses the existing strict numeric/time rules without duplicating domain validation.
     */
    private Command.EditCommitment parseEditCommitment(String input) throws ParseException {
        Matcher fields = match(COMMITMENT_EDIT_PATTERN, input, COMMITMENT_EDIT_USAGE);
        int id = Integer.parseInt(fields.group("id"));
        String name = fields.group("name") == null ? null : fields.group("name").strip();
        String day = fields.group("day");
        LocalTime time = fields.group("time") == null ? null : LocalTime.parse(fields.group("time"), TIME_FORMAT);
        Integer minutes = fields.group("minutes") == null ? null : Integer.valueOf(fields.group("minutes"));
        LocalDate date = parseOneTimeDate(fields, COMMITMENT_EDIT_USAGE);
        if (date != null) {
            return new Command.EditCommitment(id, name, date.getDayOfWeek(), time, minutes, date, date);
        }
        return new Command.EditCommitment(id, name, day == null ? null : parseWeekday(day, COMMITMENT_EDIT_USAGE),
                time, minutes, parseOptionalDate(fields.group("from"), COMMITMENT_EDIT_USAGE),
                parseOptionalDate(fields.group("until"), COMMITMENT_EDIT_USAGE));
    }

    /**
     * Parses the optional one-time {@code on/} date, which already fixes both weekday and date range.
     *
     * @param fields complete match containing optional {@code on}, {@code from} and {@code until} groups
     * @param usage syntax hint for the command being parsed
     * @return the one-time date, or null when the command uses {@code day/} or omits both
     * @throws ParseException if the date is outside the supported years or combined with a date range
     */
    private LocalDate parseOneTimeDate(Matcher fields, String usage) throws ParseException {
        LocalDate date = parseOptionalDate(fields.group("on"), usage);
        if (date != null && fields.group("from") != null) {
            throw new ParseException("Use on/ for one date or from/ and until/ for a weekly range, not both",
                    usage);
        }
        return date;
    }

    /** Parses an optional strict calendar date without inventing limits for legacy commands. */
    private LocalDate parseOptionalDate(String token, String usage) throws ParseException {
        if (token == null) {
            return null;
        }
        LocalDate date = LocalDate.parse(token, DATE_FORMAT);
        requireSupportedYear(date, usage);
        return date;
    }

    /** Parses one strict calendar date without applying recurrence membership rules. */
    private Command.SkipCommitment parseSkipCommitment(String input) throws ParseException {
        Matcher fields = match(COMMITMENT_SKIP_PATTERN, input, COMMITMENT_SKIP_USAGE);
        LocalDate date = LocalDate.parse(fields.group("date"), DATE_FORMAT);
        requireSupportedYear(date, COMMITMENT_SKIP_USAGE);
        return new Command.SkipCommitment(Integer.parseInt(fields.group("id")), date);
    }
    //@@author ka-how

    /**
     * Keeps {@code today} unresolved or parses an explicit date without consulting a clock.
     */
    private Command parseSchedule(String input) throws ParseException {
        String value = match(SCHEDULE_PATTERN, input, SCHEDULE_USAGE).group("date");
        if (value.equals("today")) {
            return new Command.ScheduleToday();
        }
        LocalDate date = LocalDate.parse(value, DATE_FORMAT);
        requireSupportedYear(date, SCHEDULE_USAGE);
        return new Command.ScheduleDate(date);
    }

    // @@author GabrielYuSP
    /** Parses explicit hours targets without reading state or validating interval ordering. */
    private Command parseHours(String input) throws ParseException {
        Matcher fields = match(HOURS_PATTERN, input, HOURS_USAGE);
        String target = fields.group("target");
        if (Character.isDigit(target.charAt(0))) {
            requireSupportedYear(LocalDate.parse(target, DATE_FORMAT), HOURS_USAGE);
        }
        if (fields.group("reset") != null) {
            if (fields.group("start") != null) {
                throw new ParseException("Reset accepts only a weekday or date", HOURS_USAGE);
            }
            return new Command.ResetHours(target);
        }
        if (fields.group("start") == null) {
            return new Command.ShowHours(target);
        }
        return new Command.SetHours(target, LocalTime.parse(fields.group("start"), HOURS_TIME_FORMAT),
                LocalTime.parse(fields.group("end"), HOURS_TIME_FORMAT));
    }

    // @@author ka-how
    /**
     * Requires a match of the entire input, including the final field or argument.
     */
    private Matcher match(Pattern pattern, String input, String usage) throws ParseException {
        Matcher fields = pattern.matcher(input);
        LOGGER.log(Level.FINE, "Checking complete field pattern");
        if (!fields.matches()) {
            throw new ParseException("Invalid command format or missing fields", usage);
        }
        assert fields.start() == 0 && fields.end() == input.length() : "Grammar must match the complete input";
        return fields;
    }

    /**
     * Rejects year zero, which strict uuuu parsing permits but the command contract excludes.
     */
    private void requireSupportedYear(LocalDate date, String usage) throws ParseException {
        if (date.getYear() < 1) {
            throw new ParseException("Year must be between 0001 and 9999", usage);
        }
    }
}
