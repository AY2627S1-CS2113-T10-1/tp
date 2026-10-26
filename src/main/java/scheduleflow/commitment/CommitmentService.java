// @@author Darren50
package scheduleflow.commitment;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.TimeRules;

/**
 * Creates weekly commitment candidates without saving or changing live state.
 */
public final class CommitmentService {
    /**
     * Records opt-in stages without names, schedules or exception payloads.
     * Candidate creation is not a claim that data has been saved or published.
     */
    private static final Logger LOGGER = Logger.getLogger(CommitmentService.class.getName());
    private static final int MINUTES_PER_HOUR = 60;
    private static final int MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;
    private static final int MAX_SUPPORTED_YEAR = 9999;

    /**
     * Creates the CommitmentService dependencies without performing I/O.
     */
    public CommitmentService() {
    }

    /**
     * Blocks one continuous interval by splitting it at midnight into dated one-time commitments.
     * Adds every segment or none, so a rejected request leaves the source unchanged and consumes no IDs.
     * The candidate is not saved or published; the caller saves it before changing live state.
     *
     * @param state immutable source snapshot to preserve on rejection
     * @param name description shared by the generated commitments
     * @param start inclusive aligned local start, in years 0001 through 9999
     * @param end exclusive aligned local end strictly after start, in years 0001 through 9999
     * @return candidate with one commitment per covered date, assigned consecutive IDs in date order
     * @throws NullPointerException if state, start or end is null
     * @throws ValidationException if a field is invalid, IDs are insufficient or any segment overlaps
     */
    public Snapshot addContinuousBlock(Snapshot state, String name, LocalDateTime start, LocalDateTime end) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        LOGGER.fine("Starting continuous unavailable addition");
        try {
            String normalized = InputRules.normalizeName(name);
            requireSupportedYear(start.toLocalDate());
            requireSupportedYear(end.toLocalDate());
            requireAligned(start.toLocalTime());
            requireAligned(end.toLocalTime());
            if (!end.isAfter(start)) {
                throw new ValidationException("Unavailable interval end must be after its start.");
            }
            LocalDate firstDate = start.toLocalDate();
            LocalDate lastDate = end.toLocalDate();
            // An exclusive end at midnight covers nothing on its own date, so that date gets no segment.
            boolean endsAtMidnight = end.toLocalTime().equals(LocalTime.MIDNIGHT);
            long count = ChronoUnit.DAYS.between(firstDate, lastDate) + (endsAtMidnight ? 0 : 1);
            return appendDatedBlocks(state, normalized, firstDate, count, date -> {
                LocalTime from = date.equals(firstDate) ? start.toLocalTime() : LocalTime.MIDNIGHT;
                int toMinute = date.equals(lastDate) ? minuteOfDay(end.toLocalTime()) : MINUTES_PER_DAY;
                return new DailyInterval(from, toMinute - minuteOfDay(from));
            });
        } catch (ValidationException exception) {
            LOGGER.fine("Unavailable addition rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Blocks the same same-day window on every date of an inclusive range using one-time commitments.
     * Adds every date's commitment or none, so a rejected request leaves the source unchanged.
     *
     * @param state immutable source snapshot to preserve on rejection
     * @param name description shared by the generated commitments
     * @param firstDate inclusive first date in years 0001 through 9999
     * @param lastDate inclusive last date on or after firstDate, in years 0001 through 9999
     * @param start aligned local start of each daily interval
     * @param end aligned local end strictly after start on the same day
     * @return candidate with one commitment per date, assigned consecutive IDs in date order
     * @throws NullPointerException if state, either date or either time is null
     * @throws ValidationException if a field is invalid, IDs are insufficient or any date overlaps
     */
    public Snapshot addDailyBlocks(Snapshot state, String name, LocalDate firstDate, LocalDate lastDate,
            LocalTime start, LocalTime end) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(firstDate, "firstDate");
        Objects.requireNonNull(lastDate, "lastDate");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        LOGGER.fine("Starting daily unavailable addition");
        try {
            String normalized = InputRules.normalizeName(name);
            requireSupportedYear(firstDate);
            requireSupportedYear(lastDate);
            requireAligned(start);
            requireAligned(end);
            if (firstDate.isAfter(lastDate)) {
                throw new ValidationException("Unavailable date range must start on or before its end.");
            }
            if (!start.isBefore(end)) {
                throw new ValidationException("Daily unavailable start must be before end on the same day.");
            }
            DailyInterval window = new DailyInterval(start, minuteOfDay(end) - minuteOfDay(start));
            long count = ChronoUnit.DAYS.between(firstDate, lastDate) + 1;
            return appendDatedBlocks(state, normalized, firstDate, count, date -> window);
        } catch (ValidationException exception) {
            LOGGER.fine("Unavailable addition rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Describes the part of one calendar date blocked by an unavailable request.
     *
     * @param start inclusive start time on that date
     * @param minutes positive length ending no later than midnight
     */
    private record DailyInterval(LocalTime start, int minutes) {
    }

    /**
     * Creates one dated commitment for each of {@code count} consecutive dates and appends them together.
     * Checks ID capacity before creating any record, and checks each date's overlap against stored
     * commitments in date order, so the first conflicting date determines the error message.
     *
     * @param state immutable source snapshot
     * @param name normalized shared description
     * @param firstDate first generated date
     * @param count number of consecutive dates to block, at least one
     * @param intervalOn blocked interval for each generated date
     * @return candidate with all generated commitments appended and the counter advanced by {@code count}
     * @throws ValidationException if too few IDs remain or any generated commitment overlaps
     */
    private Snapshot appendDatedBlocks(Snapshot state, String name, LocalDate firstDate, long count,
            Function<LocalDate, DailyInterval> intervalOn) {
        assert count >= 1 : "A validated unavailable request must cover at least one date";
        // The final assignable ID is MAX_VALUE - 1 because the counter itself must still fit in an int.
        if (count > (long) Integer.MAX_VALUE - state.nextCommitmentId()) {
            throw new ValidationException("Commitment IDs are exhausted.");
        }
        List<Commitment> commitments = new ArrayList<>(state.commitments());
        int nextId = state.nextCommitmentId();
        LocalDate date = firstDate;
        for (long index = 0; index < count; index++) {
            if (index > 0) {
                // Stepping only between generated dates never moves past the last supported date.
                date = date.plusDays(1);
            }
            DailyInterval interval = intervalOn.apply(date);
            Commitment block = new Commitment(nextId++, name, date.getDayOfWeek(), interval.start(),
                    interval.minutes(), date, date);
            rejectOverlaps(block, state.commitments());
            commitments.add(block);
        }
        LOGGER.fine("Unavailable segments validated; creating addition candidate");
        Snapshot candidate = state.withCommitments(commitments, nextId);
        assert candidate.commitments().size() == state.commitments().size() + count
                && candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.studyHours().equals(state.studyHours())
                : "Unavailable addition must append only the generated commitments";
        LOGGER.fine("Unavailable addition candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Rejects dates outside the four-digit years supported by commands and saved data.
     */
    private static void requireSupportedYear(LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > MAX_SUPPORTED_YEAR) {
            throw new ValidationException("Commitment dates must use years 0001 to 9999.");
        }
    }

    /**
     * Requires a whole half-hour boundary, including zero seconds and nanoseconds from Java callers.
     */
    private static void requireAligned(LocalTime time) {
        if (time.getSecond() != 0 || time.getNano() != 0 || time.getMinute() % TimeRules.SLOT_MINUTES != 0) {
            throw new ValidationException("Unavailable times must be on half-hour boundaries.");
        }
    }

    /**
     * Converts a validated time to minutes after midnight.
     */
    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * MINUTES_PER_HOUR + time.getMinute();
    }

    /**
     * Creates a candidate after checking overlap and counter overflow.
     * Preserves the original snapshot so callers can save before publishing the change.
     *
     * @param state immutable source snapshot
     * @param name description normalized by the commitment constructor
     * @param day weekday on which the series repeats
     * @param start half-hour-aligned local start time
     * @param minutes positive multiple of 30 that ends no later than midnight
     * @return a new snapshot with the series appended and only its counter advanced
     * @throws NullPointerException if state is null
     * @throws ValidationException if the commitment is invalid, overlaps an existing series or IDs are exhausted
     */
    public Snapshot add(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes) {
        return add(state, name, day, start, minutes, null, null);
    }

    /**
     * Creates a series with paired inclusive dates, or no date limits when both are null.
     * Equal dates create a one-time commitment that blocks only that calendar date.
     */
    public Snapshot add(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes,
            LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment addition");
        try {
            return createAddition(state, name, day, start, minutes, startDate, endDate);
        } catch (ValidationException exception) {
            LOGGER.fine("Commitment addition rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Validates fields and overlap before constructing an unsaved addition candidate.
     * Keeps the public operation's rejection logging separate from candidate construction.
     */
    private Snapshot createAddition(Snapshot state, String name, DayOfWeek day, LocalTime start, int minutes,
            LocalDate startDate, LocalDate endDate) {
        if (state.nextCommitmentId() == Integer.MAX_VALUE) {
            throw new ValidationException("Commitment IDs are exhausted.");
        }
        Commitment added = new Commitment(state.nextCommitmentId(), name, day, start, minutes, startDate, endDate);
        LOGGER.fine("Commitment fields validated; checking weekly overlaps");
        rejectOverlaps(added, state.commitments());
        List<Commitment> commitments = new ArrayList<>(state.commitments());
        LOGGER.fine("Weekly overlaps checked; creating addition candidate");
        commitments.add(added);
        Snapshot candidate = state.withCommitments(commitments, state.nextCommitmentId() + 1);
        assert candidate.commitments().getLast().equals(added)
                && candidate.commitments().subList(0, candidate.commitments().size() - 1).equals(state.commitments())
                : "Addition must append exactly one series and preserve existing records";
        assert candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId() + 1
                : "Addition must preserve tasks and advance only the commitment counter";
        LOGGER.fine("Commitment addition candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Reports every conflicting series in chronological order without changing stored order.
     * Retains the existing error format when only one series conflicts.
     *
     * @param added validated proposed series
     * @param existing stored series to check, including intervals outside study hours
     * @throws ValidationException if any stored series overlaps the proposed interval
     */
    private void rejectOverlaps(Commitment added, List<Commitment> existing) {
        List<Commitment> conflicts = existing.stream().filter(added::overlaps)
                .sorted(Comparator.comparing(Commitment::start).thenComparingInt(Commitment::id)).toList();
        if (conflicts.isEmpty()) {
            return;
        }
        String details = conflicts.stream()
                .map(commitment -> commitment.displayId() + ": " + commitment.name())
                .collect(Collectors.joining("; "));
        throw new ValidationException("Commitment overlaps " + details);
    }

    /**
     * Replaces selected fields of a weekly series while retaining its ID and stored position.
     * Null fields mean omitted values; an explicitly blank name remains invalid.
     * Even an identical valid edit produces a candidate for the normal save/invalidation transaction.
     *
     * @param state immutable source snapshot
     * @param commitmentId identifier of the series to edit
     * @param name replacement name, or null to preserve it
     * @param day replacement weekday, or null to preserve it
     * @param start replacement start, or null to preserve it
     * @param minutes replacement duration, or null to preserve it
     * @return validated candidate preserving tasks, other series and both counters
     * @throws NullPointerException if state is null
     * @throws ValidationException if no fields are supplied, the ID is unknown or the result is invalid
     */
    public Snapshot edit(Snapshot state, int commitmentId, String name, DayOfWeek day,
            LocalTime start, Integer minutes) {
        return edit(state, commitmentId, name, day, start, minutes, null, null);
    }

    /**
     * Replaces paired date limits when supplied; omitted dates retain the original range.
     * Equal dates make the result one-time, and a one-time commitment's weekday changes only with its date.
     */
    public Snapshot edit(Snapshot state, int commitmentId, String name, DayOfWeek day,
            LocalTime start, Integer minutes, LocalDate startDate, LocalDate endDate) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment edit");
        try {
            if (commitmentId <= 0) {
                throw new ValidationException("Commitment ID must be positive.");
            }
            if (name == null && day == null && start == null && minutes == null
                    && startDate == null && endDate == null) {
                throw new ValidationException("Provide at least one commitment field to edit.");
            }
            return createEdit(state, commitmentId, name, day, start, minutes, startDate, endDate);
        } catch (ValidationException exception) {
            LOGGER.fine("Commitment edit rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /**
     * Resolves omitted fields and validates the complete replacement before creating a candidate.
     * Excludes only the target ID from overlap checks, including when the time stays unchanged.
     */
    private Snapshot createEdit(Snapshot state, int id, String name, DayOfWeek day,
            LocalTime start, Integer minutes, LocalDate startDate, LocalDate endDate) {
        Commitment original = state.commitments().stream().filter(commitment -> commitment.id() == id)
                .findFirst().orElseThrow(() -> new ValidationException("Unknown commitment ID: C" + id));
        boolean keepsDates = startDate == null && endDate == null;
        if (original.isOneTime() && day != null && keepsDates) {
            throw new ValidationException(original.displayId()
                    + " is a one-time commitment; use on/YYYY-MM-DD to change its date.");
        }
        LocalDate first = keepsDates ? original.startDate() : startDate;
        LocalDate last = keepsDates ? original.endDate() : endDate;
        // A one-time result has no other occurrences, so earlier weekly skips no longer apply.
        Set<LocalDate> skippedDates = first != null && first.equals(last) ? Set.of() : original.skippedDates();
        Commitment edited = new Commitment(id, name == null ? original.name() : name,
                day == null ? original.day() : day, start == null ? original.start() : start,
                minutes == null ? original.durationMinutes() : minutes, first, last, skippedDates);
        LOGGER.fine("Replacement fields validated; checking other weekly series");
        rejectOverlaps(edited, state.commitments().stream().filter(commitment -> commitment.id() != id).toList());
        List<Commitment> replacements = new ArrayList<>(state.commitments());
        replacements.set(replacements.indexOf(original), edited);
        Snapshot candidate = state.withCommitments(replacements, state.nextCommitmentId());
        assert candidate.commitments().size() == state.commitments().size()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                && candidate.nextTaskId() == state.nextTaskId() && candidate.tasks().equals(state.tasks())
                : "Edit must preserve record counts, tasks and both counters";
        LOGGER.fine("Commitment edit candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Removes one calendar occurrence while preserving the recurring series and its stable ID.
     *
     * @param state immutable source snapshot
     * @param commitmentId identifier of the recurring series
     * @param date occurrence date to make available
     * @return candidate with the date added to the series' immutable skip set
     * @throws NullPointerException if state or date is null
     * @throws ValidationException if the ID or date does not identify an unskipped occurrence,
     *     or the commitment is one-time and must be deleted instead
     */
    public Snapshot skip(Snapshot state, int commitmentId, LocalDate date) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(date, "date");
        LOGGER.fine("Starting commitment occurrence skip");
        try {
            return createSkip(state, commitmentId, date);
        } catch (ValidationException exception) {
            LOGGER.fine("Commitment occurrence skip rejected; source snapshot unchanged");
            throw exception;
        }
    }

    /** Resolves and replaces one series without changing its stored position or shared counters. */
    private Snapshot createSkip(Snapshot state, int commitmentId, LocalDate date) {
        if (commitmentId <= 0) {
            throw new ValidationException("Commitment ID must be positive.");
        }
        Commitment original = state.commitments().stream()
                .filter(commitment -> commitment.id() == commitmentId).findFirst()
                .orElseThrow(() -> new ValidationException("Unknown commitment ID: C" + commitmentId));
        if (original.isOneTime()) {
            throw new ValidationException(original.displayId()
                    + " is a one-time commitment; use commitment delete instead.");
        }
        if (!original.recursOn(date)) {
            throw new ValidationException(original.displayId() + " has no occurrence on " + date + ".");
        }
        if (original.skippedDates().contains(date)) {
            throw new ValidationException(original.displayId() + " is already skipped on " + date + ".");
        }
        HashSet<LocalDate> skippedDates = new HashSet<>(original.skippedDates());
        skippedDates.add(date);
        Commitment replacement = new Commitment(original.id(), original.name(), original.day(), original.start(),
                original.durationMinutes(), original.startDate(), original.endDate(), skippedDates);
        List<Commitment> replacements = new ArrayList<>(state.commitments());
        replacements.set(replacements.indexOf(original), replacement);
        Snapshot candidate = state.withCommitments(replacements, state.nextCommitmentId());
        assert candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                : "Skipping one occurrence must preserve tasks and both counters";
        LOGGER.fine("Commitment occurrence skip candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Removes a known weekly series from a candidate without reusing its ID.
     * Leaves source records and both counters unchanged until the caller publishes the candidate.
     *
     * @param state immutable source snapshot
     * @param commitmentId positive numeric suffix of the series identifier
     * @return a new snapshot without that series, retaining both counters
     * @throws NullPointerException if state is null
     * @throws ValidationException if the ID is nonpositive or does not identify a stored commitment
     */
    public Snapshot delete(Snapshot state, int commitmentId) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment deletion");
        if (commitmentId <= 0) {
            LOGGER.fine("Commitment deletion rejected; nonpositive ID");
            throw new ValidationException("Commitment ID must be positive.");
        }
        List<Commitment> remaining = state.commitments().stream()
                .filter(commitment -> commitment.id() != commitmentId).toList();
        if (remaining.size() == state.commitments().size()) {
            LOGGER.fine("Commitment deletion rejected; unknown ID");
            throw new ValidationException("Unknown commitment ID: C" + commitmentId);
        }
        // Snapshot guarantees unique IDs, so a successful deletion must remove exactly one series.
        assert remaining.size() == state.commitments().size() - 1 : "Deletion must remove exactly one commitment";
        LOGGER.fine("Commitment ID resolved; creating deletion candidate");
        Snapshot candidate = state.withCommitments(remaining, state.nextCommitmentId());
        assert candidate.commitments().stream().noneMatch(commitment -> commitment.id() == commitmentId)
                : "Deletion must remove the requested series";
        assert candidate.tasks().equals(state.tasks()) && candidate.nextTaskId() == state.nextTaskId()
                && candidate.nextCommitmentId() == state.nextCommitmentId()
                : "Deletion must preserve tasks and both counters";
        LOGGER.fine("Commitment deletion candidate created; not yet saved or published");
        return candidate;
    }

    /**
     * Returns an immutable view ordered by weekday, start and numeric ID.
     * Preserves stored order and identifiers; empty snapshots produce an empty list.
     *
     * @param state immutable source snapshot
     * @return all stored series in weekly chronological order
     * @throws NullPointerException if state is null
     */
    public List<Commitment> list(Snapshot state) {
        Objects.requireNonNull(state, "state");
        LOGGER.fine("Starting commitment listing; sorting by weekday, start and ID");
        List<Commitment> commitments = state.commitments().stream().sorted(Comparator.comparing(Commitment::day)
                .thenComparing(Commitment::start).thenComparingInt(Commitment::id)).toList();
        assert commitments.size() == state.commitments().size() : "Listing must retain every stored series";
        LOGGER.fine("Commitment listing completed; source snapshot unchanged");
        return commitments;
    }
}
// @@author
