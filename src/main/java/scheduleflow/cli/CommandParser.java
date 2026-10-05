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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the complete case-sensitive command grammar without inspecting application state.
 */
public final class CommandParser {
    private static final String HELP_USAGE = "Type help to see available commands";
    private static final String TASK_ADD_USAGE = "task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES";
    private static final String TASK_DELETE_USAGE = "task delete TNUMBER";
    private static final String COMMITMENT_ADD_USAGE = "commitment add n/NAME day/DAY start/HHmm d/MINUTES";
    private static final String COMMITMENT_DELETE_USAGE = "commitment delete CNUMBER";
    private static final String SCHEDULE_USAGE = "schedule today OR schedule YYYY-MM-DD";
    private static final String DATE_TOKEN = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String TIME_TOKEN = "[0-9]{4}";
    private static final String POSITIVE_INT_TOKEN = "[1-9][0-9]*";

    // Excluding slash prevents extra prefixes from being swallowed into a multiword name.
    private static final String NAME_TOKEN = "[^/\\p{javaISOControl}]*";
    private static final Pattern TASK_ADD_PATTERN = Pattern.compile(
            "task +add +n/(?<name>" + NAME_TOKEN + ") +due/(?<date>" + DATE_TOKEN
                    + ") +(?<time>" + TIME_TOKEN + ") +d/(?<minutes>" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern COMMITMENT_ADD_PATTERN = Pattern.compile(
            "commitment +add +n/(?<name>" + NAME_TOKEN + ") +day/(?<day>MON|TUE|WED|THU|FRI|SAT|SUN)"
                    + " +start/(?<time>" + TIME_TOKEN + ") +d/(?<minutes>" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern TASK_DELETE_PATTERN = Pattern.compile(
            "task +delete +T(?<id>" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern COMMITMENT_DELETE_PATTERN = Pattern.compile(
            "commitment +delete +C(?<id>" + POSITIVE_INT_TOKEN + ")");
    private static final Pattern SCHEDULE_PATTERN = Pattern.compile("schedule +(?<date>today|" + DATE_TOKEN + ")");
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

    /**
     * Parses one nonblank line, translating syntax, number and date errors to ParseException.
     * Leaves domain validation and resolving today's date to command execution.
     * The application skips blank lines; direct blank input is an unrecognized command.
     */
    public Command parse(String line) throws ParseException {
        String input = Objects.requireNonNull(line, "line").strip();
        String command = identifyCommand(input);
        String usage = findUsage(command);
        if (input.codePoints().anyMatch(Character::isISOControl)) {
            throw new ParseException("Use ordinary spaces instead of embedded control characters", usage);
        }
        try {
            return switch (command) {
                case "help" -> parseNoArguments(input, command, new Command.Help());
                case "task add" -> parseAddTask(input);
                case "task list" -> parseNoArguments(input, command, new Command.ListTasks());
                case "task delete" -> new Command.DeleteTask(Integer.parseInt(
                        match(TASK_DELETE_PATTERN, input, usage).group("id")));
                case "commitment add" -> parseAddCommitment(input);
                case "commitment list" -> parseNoArguments(input, command, new Command.ListCommitments());
                case "commitment delete" -> new Command.DeleteCommitment(Integer.parseInt(
                        match(COMMITMENT_DELETE_PATTERN, input, usage).group("id")));
                case "plan" -> parseNoArguments(input, command, new Command.GeneratePlan());
                case "schedule" -> parseSchedule(input);
                case "exit" -> parseNoArguments(input, command, new Command.Exit());
                default -> throw new ParseException("Unknown command", HELP_USAGE);
            };
        } catch (DateTimeParseException exception) {
            throw new ParseException("Invalid date or time", usage);
        } catch (NumberFormatException exception) {
            throw new ParseException("Number must fit in a positive 32-bit integer", usage);
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

    private String findUsage(String command) {
        return switch (command) {
            case "task add" -> TASK_ADD_USAGE;
            case "task delete" -> TASK_DELETE_USAGE;
            case "commitment add" -> COMMITMENT_ADD_USAGE;
            case "commitment delete" -> COMMITMENT_DELETE_USAGE;
            case "schedule" -> SCHEDULE_USAGE;
            case "help", "task list", "commitment list", "plan", "exit" -> command;
            default -> HELP_USAGE;
        };
    }

    private Command parseNoArguments(String input, String command, Command result) throws ParseException {
        if (!input.replaceAll(" +", " ").equals(command)) {
            throw new ParseException("This command accepts no arguments", command);
        }
        return result;
    }

    private Command.AddTask parseAddTask(String input) throws ParseException {
        Matcher fields = match(TASK_ADD_PATTERN, input, TASK_ADD_USAGE);
        LocalDateTime deadline = LocalDateTime.parse(fields.group("date") + " " + fields.group("time"),
                DEADLINE_FORMAT);
        requireSupportedYear(deadline.toLocalDate(), TASK_ADD_USAGE);
        return new Command.AddTask(fields.group("name").strip(), deadline, Integer.parseInt(fields.group("minutes")));
    }

    private Command.AddCommitment parseAddCommitment(String input) throws ParseException {
        Matcher fields = match(COMMITMENT_ADD_PATTERN, input, COMMITMENT_ADD_USAGE);
        DayOfWeek day = switch (fields.group("day")) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new ParseException("Invalid weekday", COMMITMENT_ADD_USAGE);
        };
        return new Command.AddCommitment(fields.group("name").strip(), day,
                LocalTime.parse(fields.group("time"), TIME_FORMAT), Integer.parseInt(fields.group("minutes")));
    }

    private Command parseSchedule(String input) throws ParseException {
        String value = match(SCHEDULE_PATTERN, input, SCHEDULE_USAGE).group("date");
        if (value.equals("today")) {
            return new Command.ScheduleToday();
        }
        LocalDate date = LocalDate.parse(value, DATE_FORMAT);
        requireSupportedYear(date, SCHEDULE_USAGE);
        return new Command.ScheduleDate(date);
    }

    /**
     * Requires a match of the entire input, including the final field or argument.
     */
    private Matcher match(Pattern pattern, String input, String usage) throws ParseException {
        Matcher fields = pattern.matcher(input);
        if (!fields.matches()) {
            throw new ParseException("Invalid command format or missing fields", usage);
        }
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
