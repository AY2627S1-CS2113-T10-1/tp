// @@author nickpatt
package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.model.TaskPriority;
import scheduleflow.planning.TimeRules;

/**
 * Verifies task candidates, deadline limits and stable identity without I/O or a live clock.
 */
@Isolated
class TaskServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final LocalDateTime DEADLINE = NOW.plusDays(1).withHour(18);
    private static final Commitment LECTURE = new Commitment(3, "Lecture", DayOfWeek.MONDAY,
            LocalTime.of(10, 0), 120);
    private final TaskService service = new TaskService();

    @Test
    void methods_nullDependencies_reportNamedPreconditions() {
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> service.add(null, "Draft", DEADLINE, 30, NOW)).getMessage());
        assertEquals("now", assertThrows(NullPointerException.class,
                () -> service.add(Snapshot.empty(), "Draft", DEADLINE, 30, null)).getMessage());
        assertEquals("state", assertThrows(NullPointerException.class, () -> service.delete(null, 1)).getMessage());
        assertEquals("state", assertThrows(NullPointerException.class, () -> service.list(null)).getMessage());
    }

    @Test
    void add_nextCommandDate_movesRollingHorizonForward() {
        LocalDateTime deadline = LocalDateTime.of(2027, 9, 22, 0, 0);
        Snapshot original = Snapshot.empty();

        assertThrows(ValidationException.class, () -> service.add(original, "Draft", deadline, 30, NOW));
        assertEquals(Optional.of(deadline), service.add(original, "Draft", deadline, 30, NOW.plusDays(1))
                .tasks().getFirst().deadline());
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_rejectedValues_preservePopulatedSnapshotAndRetryId() {
        Task existing = new Task(2, "Existing", DEADLINE, 60);
        Snapshot original = new Snapshot(List.of(existing), List.of(LECTURE), 5, 8);

        assertThrows(ValidationException.class, () -> service.add(original, "Bad/name", DEADLINE, 30, NOW));
        assertThrows(ValidationException.class, () -> service.add(original, "Draft", DEADLINE, 0, NOW));
        assertThrows(ValidationException.class, () -> service.add(original, "Draft", NOW, 30, NOW));
        assertEquals(new Snapshot(List.of(existing), List.of(LECTURE), 5, 8), original);
        assertEquals(5, service.add(original, "Retry", DEADLINE, 30, NOW).tasks().getLast().id());
    }

    @Test
    void methods_enabledLogging_recordsCandidateStagesWithoutPublishing() throws Throwable {
        List<LogRecord> records = captureLogs(TaskService.class, () -> {
            Snapshot added = service.add(Snapshot.empty(), "Private task", DEADLINE, 30, NOW);
            service.list(added);
            service.delete(added, 1);
        });

        assertEquals(List.of("Starting task addition", "Task fields validated; checking deadline range",
                "Task deadline validated; creating addition candidate",
                "Task addition candidate created; not yet saved or published",
                "Starting task listing; sorting by numeric ID", "Task listing completed; source snapshot unchanged",
                "Starting task deletion", "Task ID resolved; creating deletion candidate",
                "Task deletion candidate created; not yet saved or published"),
                records.stream().map(LogRecord::getMessage).toList());
        assertPrivateStageRecords(records);
    }

    @Test
    void methods_rejectedOperations_logRejectionWithoutFalseSuccess() throws Throwable {
        Snapshot original = new Snapshot(List.of(), List.of(LECTURE), 5, 8);
        for (Executable addition : List.<Executable>of(
                () -> service.add(original, "Private task/invalid", DEADLINE, 30, NOW),
                () -> service.add(original, "Private task", NOW, 30, NOW),
                () -> service.add(original, "Private task", DEADLINE, 0, NOW),
                () -> service.add(original, "Private task", NOW.plusYears(2), 30, NOW),
                () -> service.add(new Snapshot(List.of(), List.of(), Integer.MAX_VALUE, 1),
                        "Private task", DEADLINE, 30, NOW))) {
            List<LogRecord> records = captureLogs(TaskService.class,
                    () -> assertThrows(ValidationException.class, addition));
            assertEquals("Starting task addition", records.getFirst().getMessage());
            assertEquals("Task addition rejected; source snapshot unchanged", records.getLast().getMessage());
            assertTrue(records.stream().noneMatch(record -> record.getMessage().contains("candidate created")));
            assertPrivateStageRecords(records);
        }
        List<LogRecord> deletion = captureLogs(TaskService.class,
                () -> assertThrows(ValidationException.class, () -> service.delete(original, 1)));
        assertEquals(List.of("Starting task deletion",
                "Task deletion rejected; unknown ID and source snapshot unchanged"),
                deletion.stream().map(LogRecord::getMessage).toList());
        assertEquals(new Snapshot(List.of(), List.of(LECTURE), 5, 8), original);
    }

    @Test
    void validators_enabledLogging_recordsStagesWithoutInvalidCompletion() throws Throwable {
        List<LogRecord> valid = captureLogs(InputRules.class, () -> {
            InputRules.normalizeName("Private task");
            InputRules.requireDuration(30);
        });
        assertEquals(List.of("Starting name validation", "Name validation completed",
                "Starting duration validation", "Duration validation completed"),
                valid.stream().map(LogRecord::getMessage).toList());
        List<LogRecord> invalid = captureLogs(InputRules.class, () -> {
            assertThrows(ValidationException.class, () -> InputRules.normalizeName("Private task/invalid"));
            assertThrows(ValidationException.class, () -> InputRules.normalizeName("   "));
            assertThrows(ValidationException.class, () -> InputRules.requireDuration(45));
        });
        assertEquals(List.of("Starting name validation", "Name validation rejected; null or forbidden character",
                "Starting name validation", "Name validation rejected; blank name", "Starting duration validation",
                "Duration validation rejected; nonpositive or unaligned duration"),
                invalid.stream().map(LogRecord::getMessage).toList());
        List<LogRecord> model = captureLogs(Task.class, () -> {
            new Task(1, "Private task", DEADLINE, 30);
            assertThrows(ValidationException.class, () -> new Task(0, "Private task", DEADLINE, 30));
        });
        assertEquals(List.of("Starting task structural validation",
                "Task identity and name validated; checking deadline structure", "Task structural validation completed",
                "Starting task structural validation", "Task structural validation rejected"),
                model.stream().map(LogRecord::getMessage).toList());
        assertPrivateStageRecords(valid);
        assertPrivateStageRecords(invalid);
        assertPrivateStageRecords(model);
    }

    @Test
    void add_validTask_appendsAndPreservesOtherRecords() {
        Task existing = new Task(2, "Existing", DEADLINE, 30);
        Snapshot original = new Snapshot(List.of(existing), List.of(LECTURE), 5, 8);

        Snapshot candidate = service.add(original, "  Draft  ", DEADLINE, 180, NOW);

        assertEquals(List.of(existing, new Task(5, "Draft", DEADLINE, 180)), candidate.tasks());
        assertEquals(original.commitments(), candidate.commitments());
        assertEquals(6, candidate.nextTaskId());
        assertEquals(8, candidate.nextCommitmentId());
        assertEquals(new Snapshot(List.of(existing), List.of(LECTURE), 5, 8), original);
        assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
    }

    @Test
    void add_duplicateNames_allocatesDistinctIds() {
        Snapshot first = service.add(Snapshot.empty(), "Draft", DEADLINE, 30, NOW);
        Snapshot second = service.add(first, "Draft", DEADLINE, 60, NOW);

        assertEquals(List.of(new Task(1, "Draft", DEADLINE, 30), new Task(2, "Draft", DEADLINE, 60)),
                second.tasks());
        assertEquals(3, second.nextTaskId());
        assertEquals(1, first.tasks().size());
    }

    @Test
    void add_invalidNames_rejectsWithoutConsumingId() {
        Snapshot original = Snapshot.empty();
        for (String name : new String[] {null, "", "   ", "A/B", "A\tB", "A\nB"}) {
            assertThrows(ValidationException.class, () -> service.add(original, name, DEADLINE, 30, NOW));
            assertEquals(Snapshot.empty(), original);
        }
        assertEquals(1, service.add(original, "Valid", DEADLINE, 30, NOW).tasks().getFirst().id());
    }

    @Test
    void add_invalidDurations_rejectsWithoutChanges() {
        Snapshot original = Snapshot.empty();
        for (int minutes : new int[] {-30, 0, Integer.MIN_VALUE}) {
            assertThrows(ValidationException.class, () -> service.add(original, "Draft", DEADLINE, minutes, NOW));
            assertEquals(Snapshot.empty(), original);
        }
    }

    @Test
    void add_largeSlotMultiple_preservesWholeEstimate() {
        int minutes = Integer.MAX_VALUE - Integer.MAX_VALUE % TimeRules.SLOT_MINUTES;
        Snapshot candidate = service.add(Snapshot.empty(), "Large", DEADLINE, minutes, NOW);

        assertEquals(minutes, candidate.tasks().getFirst().remainingMinutes());
    }

    @Test
    void add_invalidDeadlines_rejectsWithoutChanges() {
        Snapshot original = Snapshot.empty();
        for (LocalDateTime deadline : new LocalDateTime[] {null, NOW.minusMinutes(1), NOW,
                TimeRules.horizonEnd(NOW).plusMinutes(1), DEADLINE.withSecond(1), DEADLINE.withNano(1),
                DEADLINE.withYear(0), DEADLINE.withYear(10000)}) {
            assertThrows(ValidationException.class, () -> service.add(original, "Draft", deadline, 30, NOW));
            assertEquals(Snapshot.empty(), original);
        }
    }

    @Test
    void add_deadlineBoundaries_acceptsNextMinuteAndExactHorizon() {
        for (LocalDateTime deadline : List.of(NOW.plusMinutes(1), TimeRules.horizonEnd(NOW))) {
            assertEquals(Optional.of(deadline), service.add(Snapshot.empty(), "Draft", deadline, 30, NOW)
                    .tasks().getFirst().deadline());
        }
        LocalDateTime preciseNow = NOW.plusSeconds(1).plusNanos(1);
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Draft", NOW, 30, preciseNow));
        assertEquals(Optional.of(NOW.plusMinutes(1)), service.add(Snapshot.empty(), "Draft", NOW.plusMinutes(1),
                30, preciseNow)
                .tasks().getFirst().deadline());
    }

    @Test
    void add_leapDay_usesCalendarYearBoundary() {
        LocalDateTime leapNow = LocalDateTime.of(2028, 2, 29, 12, 0);
        LocalDateTime horizon = LocalDateTime.of(2029, 2, 28, 0, 0);

        assertEquals(Optional.of(horizon), service.add(Snapshot.empty(), "Draft", horizon, 30, leapNow)
                .tasks().getFirst().deadline());
        assertThrows(ValidationException.class,
                () -> service.add(Snapshot.empty(), "Draft", horizon.plusMinutes(1), 30, leapNow));
    }

    @Test
    void add_exhaustedCounter_rejectsWithoutOverflow() {
        Snapshot penultimate = new Snapshot(List.of(), List.of(LECTURE), Integer.MAX_VALUE - 1, 8);
        Snapshot last = service.add(penultimate, "Last", DEADLINE, 30, NOW);

        assertEquals(Integer.MAX_VALUE - 1, last.tasks().getFirst().id());
        assertEquals(Integer.MAX_VALUE, last.nextTaskId());
        assertThrows(ValidationException.class, () -> service.add(last, "Overflow", DEADLINE, 30, NOW));
        assertEquals(1, last.tasks().size());
        assertEquals(Integer.MAX_VALUE, last.nextTaskId());
        Snapshot exhaustedEmpty = service.delete(last, Integer.MAX_VALUE - 1);
        assertThrows(ValidationException.class, () -> service.add(exhaustedEmpty, "Overflow", DEADLINE, 30, NOW));
    }

    @Test
    void delete_existingId_preservesOrderCountersAndCommitments() {
        Task first = new Task(10, "First", DEADLINE, 30);
        Task removed = new Task(2, "Removed", DEADLINE, 60);
        Task last = new Task(7, "Last", DEADLINE, 90);
        Snapshot original = new Snapshot(List.of(first, removed, last), List.of(LECTURE), 11, 8);

        Snapshot candidate = service.delete(original, 2);

        assertEquals(new Snapshot(List.of(first, last), List.of(LECTURE), 11, 8), candidate);
        assertEquals(List.of(first, removed, last), original.tasks());
        assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
    }

    @Test
    void delete_unknownId_reportsIdentifierWithoutChanges() {
        Snapshot original = service.add(Snapshot.empty(), "Draft", DEADLINE, 30, NOW);
        for (int id : new int[] {-1, 0, 2, Integer.MAX_VALUE}) {
            ValidationException exception = assertThrows(ValidationException.class, () -> service.delete(original, id));
            assertEquals("Unknown task ID: T" + id, exception.getMessage());
            assertEquals(List.of(new Task(1, "Draft", DEADLINE, 30)), original.tasks());
            assertEquals(2, original.nextTaskId());
        }
        assertThrows(ValidationException.class, () -> service.delete(Snapshot.empty(), 1));
    }

    @Test
    void delete_lastTask_nextAddDoesNotReuseId() {
        Snapshot first = service.add(Snapshot.empty(), "First", DEADLINE, 30, NOW);
        Snapshot deleted = service.delete(first, 1);
        Snapshot next = service.add(deleted, "Next", DEADLINE, 30, NOW);

        assertEquals(new Snapshot(List.of(), List.of(), 2, 1), deleted);
        assertEquals(2, next.tasks().getFirst().id());
        assertEquals(3, next.nextTaskId());
    }

    @Test
    void list_unsortedAndOverdueTasks_returnsImmutableNumericOrder() {
        Task ten = new Task(10, "Ten", DEADLINE, 90);
        Task two = new Task(2, "Overdue", NOW.minusYears(1), 60);
        Task seven = new Task(7, "Seven", DEADLINE, 30);
        Snapshot original = new Snapshot(List.of(ten, two, seven), List.of(LECTURE), 11, 8);

        List<Task> listed = service.list(original);

        assertEquals(List.of(two, seven, ten), listed);
        assertEquals(List.of(ten, two, seven), original.tasks());
        assertThrows(UnsupportedOperationException.class, () -> listed.clear());
        assertEquals(List.of(), service.list(Snapshot.empty()));
    }

    @Test
    void edit_eachFieldSubset_preservesMixedSnapshotPositionAndHours() {
        Snapshot source = editSource();
        Task original = source.tasks().get(1);
        for (TaskEdit edit : List.of(new TaskEdit(Optional.of("  Renamed  "), Optional.empty(), false),
                new TaskEdit(Optional.empty(), Optional.of(DEADLINE.plusDays(1)), false),
                new TaskEdit(Optional.of("  Renamed  "), Optional.of(DEADLINE.plusDays(1)), false))) {
            Snapshot candidate = service.edit(source, original.id(), edit, NOW);
            Task expected = new Task(original.id(), edit.name().orElse(original.name()),
                    edit.deadline().or(() -> original.deadline()), original.remainingMinutes(), original.priority());
            assertEquals(new Snapshot(List.of(source.tasks().getFirst(), expected, source.tasks().getLast()),
                    source.commitments(), source.nextTaskId(), source.nextCommitmentId(), source.studyHours()),
                    candidate);
            assertSame(source.studyHours(), candidate.studyHours());
            assertEquals(editSource(), source);
            assertSame(source.tasks().getFirst(), candidate.tasks().getFirst());
            assertSame(source.tasks().getLast(), candidate.tasks().getLast());
            assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
        }
    }

    @Test
    void edit_unchangedOrRenamedOldDeadline_doesNotRevalidateTemporalRange() {
        for (LocalDateTime deadline : List.of(NOW.minusYears(1), NOW, NOW.plusYears(2))) {
            Task task = new Task(1, "Draft", deadline, 60);
            Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
            assertSame(source, service.edit(source, 1,
                    new TaskEdit(Optional.of("  Draft  "), Optional.of(deadline), false), NOW));
            assertSame(source, service.edit(source, 1,
                    new TaskEdit(Optional.empty(), Optional.of(deadline), false), NOW));
            assertEquals(new Task(1, "Renamed", deadline, 60), service.edit(source, 1,
                    new TaskEdit(Optional.of("Renamed"), Optional.empty(), false), NOW).tasks().getFirst());
            assertEquals(new Task(1, "Renamed", deadline, 60), service.edit(source, 1,
                    new TaskEdit(Optional.of("Renamed"), Optional.of(deadline), false), NOW).tasks().getFirst());
        }
    }

    @Test
    void edit_invalidFullEdits_rejectsAtomicallyAndAllowsRetry() {
        Snapshot source = editSource();
        List<TaskEdit> invalid = new ArrayList<>();
        invalid.add(new TaskEdit(Optional.empty(), Optional.empty(), false));
        invalid.add(new TaskEdit(Optional.of("Valid"), Optional.of(DEADLINE), true));
        for (String name : List.of("", "   ", "Bad/name", "Bad\tname", "Bad\nname")) {
            invalid.add(new TaskEdit(Optional.of(name), Optional.of(DEADLINE.plusDays(1)), false));
        }
        for (LocalDateTime deadline : List.of(NOW.minusMinutes(1), NOW,
                TimeRules.horizonEnd(NOW).plusMinutes(1), DEADLINE.withSecond(1), DEADLINE.withNano(1),
                DEADLINE.withYear(0), DEADLINE.withYear(10000))) {
            invalid.add(new TaskEdit(Optional.of("Valid new name"), Optional.of(deadline), false));
        }
        for (TaskEdit edit : invalid) {
            assertThrows(ValidationException.class, () -> service.edit(source, 2, edit, NOW));
            assertEquals(editSource(), source);
        }
        assertEquals("Retry", service.edit(source, 2,
                new TaskEdit(Optional.of("Retry"), Optional.empty(), false), NOW).tasks().get(1).name());
    }

    @Test
    void edit_unknownIdsAndNullInputs_rejectsWithoutChangingSource() {
        Snapshot source = editSource();
        TaskEdit edit = new TaskEdit(Optional.of("Renamed"), Optional.empty(), false);
        for (int id : new int[] {-1, 0, 1, Integer.MAX_VALUE}) {
            assertEquals("Unknown task ID: T" + id, assertThrows(ValidationException.class,
                    () -> service.edit(source, id, edit, NOW)).getMessage());
        }
        assertThrows(ValidationException.class, () -> service.edit(Snapshot.empty(), 1, edit, NOW));
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> service.edit(null, 2, edit, NOW)).getMessage());
        assertEquals("edit", assertThrows(NullPointerException.class,
                () -> service.edit(source, 2, null, NOW)).getMessage());
        assertEquals("now", assertThrows(NullPointerException.class,
                () -> service.edit(source, 2, edit, null)).getMessage());
        assertEquals(editSource(), source);
    }

    @Test
    void edit_changedDeadline_checksPreciseNowAndInclusiveCalendarHorizon() {
        Snapshot source = editSource();
        for (LocalDateTime now : List.of(NOW, NOW.plusNanos(1), NOW.plusSeconds(59).plusNanos(999999999),
                LocalDateTime.of(2028, 2, 29, 12, 0))) {
            LocalDateTime nextMinute = now.withSecond(0).withNano(0).plusMinutes(1);
            for (LocalDateTime deadline : List.of(nextMinute, TimeRules.horizonEnd(now))) {
                assertEquals(Optional.of(deadline), service.edit(source, 2,
                        new TaskEdit(Optional.empty(), Optional.of(deadline), false), now)
                        .tasks().get(1).deadline());
            }
            for (LocalDateTime deadline : List.of(now.withSecond(0).withNano(0),
                    TimeRules.horizonEnd(now).plusMinutes(1))) {
                assertThrows(ValidationException.class, () -> service.edit(source, 2,
                        new TaskEdit(Optional.empty(), Optional.of(deadline), false), now));
            }
        }
        LocalDateTime movingDeadline = TimeRules.horizonEnd(NOW).plusDays(1);
        TaskEdit edit = new TaskEdit(Optional.empty(), Optional.of(movingDeadline), false);
        assertThrows(ValidationException.class, () -> service.edit(source, 2, edit, NOW));
        assertEquals(Optional.of(movingDeadline),
                service.edit(source, 2, edit, NOW.plusDays(1)).tasks().get(1).deadline());
    }

    @Test
    void edit_enabledLogging_distinguishesCandidateNoOpAndRejectionWithoutPrivateValues() throws Throwable {
        Snapshot source = editSource();
        List<LogRecord> records = captureLogs(TaskService.class, () -> {
            service.edit(source, 2, new TaskEdit(Optional.of("Private task"), Optional.empty(), false), NOW);
            service.edit(source, 2, new TaskEdit(Optional.of("Draft"), Optional.empty(), false), NOW);
            assertThrows(ValidationException.class, () -> service.edit(source, 2,
                    new TaskEdit(Optional.of("Private task/invalid"), Optional.empty(), false), NOW));
        });
        assertEquals(List.of("Starting task edit", "Task edit candidate created; not yet saved or published",
                "Starting task edit", "Task edit has no effective change; source snapshot returned",
                "Starting task edit", "Task edit rejected; source snapshot unchanged"),
                records.stream().map(LogRecord::getMessage).toList());
        assertPrivateStageRecords(records);
    }

    @Test
    void add_wholeMinuteBoundariesAndAbsentDeadline_preservesHoursAndCounter() {
        Snapshot source = Snapshot.empty().withStudyHours(editSource().studyHours());
        for (int minutes : new int[] {1, 45, Integer.MAX_VALUE}) {
            Snapshot dated = service.add(source, "Draft", DEADLINE, minutes, NOW);
            Snapshot undated = service.addWithoutDeadline(source, "  Draft  ", minutes);
            assertEquals(new Task(1, "Draft", DEADLINE, minutes), dated.tasks().getFirst());
            assertEquals(Task.withoutDeadline(1, "Draft", minutes), undated.tasks().getFirst());
            assertSame(source.studyHours(), dated.studyHours());
            assertSame(source.studyHours(), undated.studyHours());
            assertEquals(2, undated.nextTaskId());
            assertEquals(1, undated.nextCommitmentId());
        }
        for (int minutes : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(ValidationException.class, () -> service.addWithoutDeadline(source, "A", minutes));
        }
        assertThrows(ValidationException.class, () -> service.addWithoutDeadline(editSource(), "A", 1));
        assertThrows(ValidationException.class, () -> service.addWithoutDeadline(source, "Bad/name", 1));
        assertThrows(ValidationException.class, () -> service.add(source, "A", null, 1, NOW));
        assertEquals(List.of(), source.tasks());
    }

    @Test
    void lifecycle_mixedSnapshot_preservesMetadataPositionHoursAndExhaustedCounters() {
        Snapshot base = editSource();
        Task target = new Task(2, "Draft", Optional.of(NOW.minusYears(1)), 45, TaskPriority.HIGH);
        Snapshot source = new Snapshot(List.of(base.tasks().getFirst(), target, base.tasks().getLast()),
                base.commitments(), base.nextTaskId(), base.nextCommitmentId(), base.studyHours());
        Snapshot progressed = service.recordWork(source, 2, 15);
        Snapshot revised = service.reviseRemaining(progressed, 2, Integer.MAX_VALUE);
        Snapshot completed = service.complete(revised, 2);
        Snapshot reopened = service.reopen(completed, 2, 1);
        for (Snapshot candidate : List.of(progressed, revised, completed, reopened)) {
            assertSame(source.tasks().getFirst(), candidate.tasks().getFirst());
            assertSame(source.tasks().getLast(), candidate.tasks().getLast());
            assertEquals(source.commitments(), candidate.commitments());
            assertSame(source.studyHours(), candidate.studyHours());
            assertEquals(source.nextTaskId(), candidate.nextTaskId());
            assertEquals(source.nextCommitmentId(), candidate.nextCommitmentId());
            Task changed = candidate.tasks().get(1);
            assertEquals(target.id(), changed.id());
            assertEquals(target.name(), changed.name());
            assertEquals(target.deadline(), changed.deadline());
            assertEquals(target.priority(), changed.priority());
            assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
        }
        assertEquals(45, source.tasks().get(1).remainingMinutes());
        assertEquals(30, progressed.tasks().get(1).remainingMinutes());
        assertEquals(Integer.MAX_VALUE, revised.tasks().get(1).remainingMinutes());
        assertEquals(0, completed.tasks().get(1).remainingMinutes());
        assertEquals(1, reopened.tasks().get(1).remainingMinutes());
        assertEquals(0, service.recordWork(source, 2, 45).tasks().get(1).remainingMinutes());
    }

    @Test
    void progress_percentagesFloorAndLargeValues_creditExactReportedWork() {
        Snapshot source = service.addWithoutDeadline(Snapshot.empty(), "Draft", Integer.MAX_VALUE);
        for (int percentage : new int[] {0, 1, 50, 99, 100}) {
            int done = (int) ((long) Integer.MAX_VALUE * percentage / 100);
            assertEquals(Integer.MAX_VALUE - done,
                    service.recordSessionProgress(source, 1, Integer.MAX_VALUE, percentage)
                            .tasks().getFirst().remainingMinutes());
        }
        assertEquals(Integer.MAX_VALUE - 1,
                service.recordSessionProgress(source, 1, 3, 50).tasks().getFirst().remainingMinutes());
        Snapshot small = service.addWithoutDeadline(Snapshot.empty(), "Small", 45);
        assertEquals(15, service.recordSessionProgress(small, 1, 60, 50).tasks().getFirst().remainingMinutes());
        assertThrows(ValidationException.class, () -> service.recordSessionProgress(small, 1, 60, 100));
        assertEquals(45, small.tasks().getFirst().remainingMinutes());
    }

    @Test
    void lifecycle_noOps_returnIdenticalSource() {
        Snapshot source = service.addWithoutDeadline(Snapshot.empty(), "Draft", 45);
        assertSame(source, service.recordWork(source, 1, 0));
        assertSame(source, service.recordSessionProgress(source, 1, 60, 0));
        assertSame(source, service.recordSessionProgress(source, 1, 1, 99));
        assertSame(source, service.reviseRemaining(source, 1, 45));
        Snapshot completed = service.complete(source, 1);
        assertSame(completed, service.complete(completed, 1));
        assertSame(source, service.edit(source, 1, new TaskEdit(Optional.empty(), Optional.empty(), true), NOW));
    }

    @Test
    void lifecycle_invalidInputs_rejectWithoutChangingSource() {
        Snapshot source = service.addWithoutDeadline(Snapshot.empty(), "Draft", 45);
        Snapshot complete = service.complete(source, 1);
        for (int done : new int[] {-1, Integer.MIN_VALUE, 46, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> service.recordWork(source, 1, done));
        }
        for (int remaining : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(ValidationException.class, () -> service.reviseRemaining(source, 1, remaining));
            assertThrows(ValidationException.class, () -> service.reopen(complete, 1, remaining));
        }
        for (int session : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(ValidationException.class, () -> service.recordSessionProgress(source, 1, session, 50));
        }
        for (int percentage : new int[] {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> service.recordSessionProgress(source, 1, 60, percentage));
        }
        assertThrows(ValidationException.class, () -> service.recordWork(complete, 1, 0));
        assertThrows(ValidationException.class, () -> service.recordWork(complete, 1, 1));
        assertThrows(ValidationException.class, () -> service.recordSessionProgress(complete, 1, 1, 0));
        assertThrows(ValidationException.class, () -> service.reviseRemaining(complete, 1, 45));
        assertThrows(ValidationException.class, () -> service.reopen(source, 1, 45));
        for (int id : new int[] {-1, 0, 2, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> service.recordWork(source, id, 0));
            assertThrows(ValidationException.class, () -> service.recordSessionProgress(source, id, 1, 0));
            assertThrows(ValidationException.class, () -> service.reviseRemaining(source, id, 1));
            assertThrows(ValidationException.class, () -> service.complete(source, id));
            assertThrows(ValidationException.class, () -> service.reopen(complete, id, 1));
        }
        assertEquals(45, source.tasks().getFirst().remainingMinutes());
        assertEquals(0, complete.tasks().getFirst().remainingMinutes());
    }

    @Test
    void lifecycle_nullSources_reportNamedPrecondition() {
        for (Executable operation : List.<Executable>of(
                () -> service.addWithoutDeadline(null, "Draft", 1),
                () -> service.recordWork(null, 1, 0),
                () -> service.recordSessionProgress(null, 1, 1, 0),
                () -> service.reviseRemaining(null, 1, 1),
                () -> service.complete(null, 1), () -> service.reopen(null, 1, 1))) {
            assertEquals("state", assertThrows(NullPointerException.class, operation).getMessage());
        }
    }

    @Test
    void edit_optionalDeadlineAndCompletion_preservesStatusAndPriority() {
        Task task = new Task(1, "Draft", Optional.of(NOW.minusYears(1)), 0, TaskPriority.LOW);
        Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1, editSource().studyHours());
        Snapshot cleared = service.edit(source, 1, new TaskEdit(Optional.of("Renamed"), Optional.empty(), true), NOW);
        assertEquals(new Task(1, "Renamed", Optional.empty(), 0, TaskPriority.LOW), cleared.tasks().getFirst());
        assertSame(source.studyHours(), cleared.studyHours());
        assertSame(cleared, service.edit(cleared, 1, new TaskEdit(Optional.empty(), Optional.empty(), true), NOW));
        Snapshot dated = service.edit(cleared, 1, new TaskEdit(Optional.empty(), Optional.of(DEADLINE), false), NOW);
        assertEquals(new Task(1, "Renamed", Optional.of(DEADLINE), 0, TaskPriority.LOW), dated.tasks().getFirst());
        assertThrows(ValidationException.class, () -> service.edit(cleared, 1,
                new TaskEdit(Optional.of("Invalid change"), Optional.of(NOW), false), NOW));
        assertEquals(task, source.tasks().getFirst());
    }

    @Test
    void setPriority_allTransitions_preservesAllOtherDataAndNoOpIdentity() {
        Snapshot base = editSource();
        for (TaskPriority from : TaskPriority.values()) {
            for (TaskPriority to : TaskPriority.values()) {
                for (int remaining : new int[] {0, 45, Integer.MAX_VALUE}) {
                    for (Optional<LocalDateTime> deadline : List.of(Optional.<LocalDateTime>empty(),
                            Optional.of(NOW.minusYears(1)), Optional.of(NOW.plusYears(2)))) {
                        Task target = new Task(2, "Private task", deadline, remaining, from);
                        Snapshot source = new Snapshot(List.of(base.tasks().getFirst(), target,
                                base.tasks().getLast()), base.commitments(), base.nextTaskId(),
                                base.nextCommitmentId(), base.studyHours());
                        Snapshot candidate = service.setPriority(source, 2, to);
                        Task expected = new Task(2, target.name(), deadline, remaining, to);
                        assertEquals(new Snapshot(List.of(base.tasks().getFirst(), expected,
                                base.tasks().getLast()), base.commitments(), base.nextTaskId(),
                                base.nextCommitmentId(), base.studyHours()), candidate);
                        assertSame(source.studyHours(), candidate.studyHours());
                        assertSame(source.tasks().getFirst(), candidate.tasks().getFirst());
                        assertSame(source.tasks().getLast(), candidate.tasks().getLast());
                        assertEquals(target, source.tasks().get(1));
                        assertThrows(UnsupportedOperationException.class, () -> candidate.tasks().clear());
                        if (from == to) {
                            assertSame(source, candidate);
                        }
                    }
                }
            }
        }
    }

    @Test
    void setPriority_unknownIds_rejectsWithoutChangingSource() {
        Snapshot source = editSource();
        for (int id : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            assertEquals("Unknown task ID: T" + id, assertThrows(ValidationException.class,
                    () -> service.setPriority(source, id, TaskPriority.HIGH)).getMessage());
        }
        assertThrows(ValidationException.class, () -> service.setPriority(Snapshot.empty(), 1, TaskPriority.HIGH));
        assertEquals(editSource(), source);
    }

    @Test
    void setPriority_diagnostics_distinguishesCandidateNoOpAndRejectionPrivately() throws Throwable {
        Snapshot source = editSource();
        List<LogRecord> records = captureLogs(TaskService.class, () -> {
            service.setPriority(source, 2, TaskPriority.HIGH);
            service.setPriority(source, 2, TaskPriority.NORMAL);
            assertThrows(ValidationException.class, () -> service.setPriority(source, 1, TaskPriority.LOW));
        });
        assertEquals(List.of("Starting task priority update",
                "Task priority candidate created; not yet saved or published", "Starting task priority update",
                "Task priority unchanged; source snapshot returned", "Starting task priority update",
                "Task priority update rejected; source snapshot unchanged"),
                records.stream().map(LogRecord::getMessage).toList());
        assertPrivateStageRecords(records);
    }

    @Test
    void queries_emptyScopes_returnImmutableEmptyLists() {
        for (TaskScope scope : TaskScope.values()) {
            for (List<Task> result : List.of(service.list(Snapshot.empty(), scope),
                    service.listByPriority(Snapshot.empty(), scope), service.search(Snapshot.empty(), "x", scope))) {
                assertEquals(List.of(), result);
                assertThrows(UnsupportedOperationException.class, () -> result.add(Task.withoutDeadline(1, "A", 1)));
            }
        }
        List<Task> overdue = service.overdue(Snapshot.empty(), NOW);
        assertEquals(List.of(), overdue);
        assertThrows(UnsupportedOperationException.class, () -> overdue.add(Task.withoutDeadline(1, "A", 1)));
    }

    @Test
    void queries_eachScope_preservesRecordsAndReturnsImmutableNumericIds() {
        Task large = new Task(Integer.MAX_VALUE - 1, "Match", Optional.empty(), 1, TaskPriority.LOW);
        Task ten = new Task(10, "MATCH", Optional.of(NOW), 0, TaskPriority.HIGH);
        Task two = new Task(2, "match", Optional.of(DEADLINE), 45, TaskPriority.NORMAL);
        Snapshot source = new Snapshot(List.of(large, ten, two), List.of(LECTURE), Integer.MAX_VALUE, 8,
                editSource().studyHours());
        for (TaskScope scope : TaskScope.values()) {
            List<Task> expected = switch (scope) {
                case ACTIVE -> List.of(two, large);
                case COMPLETED -> List.of(ten);
                case ALL -> List.of(two, ten, large);
            };
            assertEquals(expected, service.list(source, scope));
            assertEquals(expected, service.search(source, "  MaTcH  ", scope));
            assertThrows(UnsupportedOperationException.class, () -> service.list(source, scope).clear());
            assertThrows(UnsupportedOperationException.class, () -> service.search(source, "match", scope).clear());
        }
        assertEquals(List.of(two, ten, large), service.list(source));
        assertEquals(List.of(large, ten, two), source.tasks());
        assertSame(two, service.list(source, TaskScope.ACTIVE).getFirst());
    }

    @Test
    void listByPriority_allPrioritiesAndDeadlineTies_ordersWithinEachScopeWithoutMutation() {
        List<Task> tasks = new ArrayList<>();
        List<Task> expected = new ArrayList<>();
        for (TaskPriority priority : TaskPriority.values()) {
            int offset = priority.ordinal() * 100;
            List<Task> group = List.of(
                    new Task(offset + 2, "Early two", Optional.of(NOW), 1, priority),
                    new Task(offset + 10, "Early ten", Optional.of(NOW), 0, priority),
                    new Task(offset + 3, "Later", Optional.of(DEADLINE), 1, priority),
                    new Task(offset + 1, "Undated one", Optional.empty(), 0, priority),
                    new Task(offset + 11, "Undated eleven", Optional.empty(), 1, priority));
            expected.addAll(group);
            tasks.addAll(group);
        }
        Snapshot source = new Snapshot(tasks.reversed(), List.of(), 300, 1);
        List<Task> stored = List.copyOf(source.tasks());
        for (TaskScope scope : TaskScope.values()) {
            List<Task> scopedExpected = expected.stream().filter(task -> scope == TaskScope.ALL
                    || (scope == TaskScope.ACTIVE ? task.remainingMinutes() > 0 : task.remainingMinutes() == 0))
                    .toList();
            List<Task> result = service.listByPriority(source, scope);
            assertEquals(scopedExpected, result);
            assertThrows(UnsupportedOperationException.class, () -> result.clear());
        }
        assertEquals(stored, source.tasks());
    }

    @Test
    void search_unicodeAndPunctuation_matchesLiteralSubstringsOnly() {
        Task literal = Task.withoutDeadline(10, "Préparation 项目 [a.*]+? \\ Draft", 45);
        Task other = Task.withoutDeadline(2, "PREPARATION axxx DRAFT", 0);
        Snapshot source = new Snapshot(List.of(literal, other), List.of(), 11, 1);
        for (String query : List.of("PRÉPARATION", "项目", "[a.*]+?", "\\", "\u2003项目\u2003")) {
            assertEquals(List.of(literal), service.search(source, query, TaskScope.ALL));
        }
        assertEquals(List.of(other, literal), service.search(source, "draft", TaskScope.ALL));
        assertEquals(List.of(), service.search(source, "missing", TaskScope.ALL));
        assertEquals(List.of(), service.search(source, "^Draft$", TaskScope.ALL));
        assertEquals(List.of(literal, other), source.tasks());
    }

    @Test
    void search_turkishDefaultLocale_usesRootMappingAndRestoresDefaults() {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Task task = Task.withoutDeadline(1, "INDIGO İstanbul", 1);
            Snapshot source = new Snapshot(List.of(task), List.of(), 2, 1);
            assertEquals(List.of(task), service.search(source, "indigo", TaskScope.ACTIVE));
            assertEquals(List.of(task), service.search(source, "INDIGO", TaskScope.ACTIVE));
            assertEquals(List.of(task), service.search(source, "İSTANBUL", TaskScope.ACTIVE));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void overdue_exactCapturedTime_excludesCompletedUndatedAndFutureWithoutRounding() {
        Task exact = new Task(10, "Exact", NOW, 1);
        Task tie = new Task(2, "Tie", NOW, 45);
        Task old = new Task(7, "Old", NOW.minusYears(2), 1);
        Task future = new Task(1, "Future", NOW.plusMinutes(1), 1);
        Task completed = new Task(3, "Completed", NOW.minusYears(3), 0);
        Task undated = Task.withoutDeadline(4, "Undated", 1);
        Snapshot source = new Snapshot(List.of(exact, future, tie, completed, undated, old), List.of(), 11, 1);
        assertEquals(List.of(old), service.overdue(source, NOW.minusNanos(1)));
        for (LocalDateTime time : List.of(NOW, NOW.plusSeconds(1), NOW.plusMinutes(1).minusNanos(1))) {
            List<Task> result = service.overdue(source, time);
            assertEquals(List.of(old, tie, exact), result);
            assertThrows(UnsupportedOperationException.class, () -> result.clear());
        }
        assertEquals(List.of(old, tie, exact, future), service.overdue(source, NOW.plusMinutes(1)));
        assertEquals(List.of(exact, future, tie, completed, undated, old), source.tasks());
    }

    @Test
    void queries_blankAndNullInputs_reportDomainErrorsOrNamedPreconditions() {
        Snapshot source = editSource();
        for (String query : List.of("", "  ", "\t\n", "\u2003")) {
            assertEquals("Search query must not be blank.", assertThrows(ValidationException.class,
                    () -> service.search(source, query, TaskScope.ALL)).getMessage());
        }
        for (Executable operation : List.<Executable>of(
                () -> service.setPriority(null, 2, TaskPriority.HIGH),
                () -> service.list(null, TaskScope.ALL), () -> service.listByPriority(null, TaskScope.ALL),
                () -> service.search(null, "x", TaskScope.ALL), () -> service.overdue(null, NOW))) {
            assertEquals("state", assertThrows(NullPointerException.class, operation).getMessage());
        }
        for (Executable operation : List.<Executable>of(() -> service.list(source, null),
                () -> service.listByPriority(source, null), () -> service.search(source, "x", null))) {
            assertEquals("scope", assertThrows(NullPointerException.class, operation).getMessage());
        }
        assertEquals("priority", assertThrows(NullPointerException.class,
                () -> service.setPriority(source, 2, null)).getMessage());
        assertEquals("query", assertThrows(NullPointerException.class,
                () -> service.search(source, null, TaskScope.ALL)).getMessage());
        assertEquals("capturedNow", assertThrows(NullPointerException.class,
                () -> service.overdue(source, null)).getMessage());
        assertEquals(editSource(), source);
    }

    /** Creates an unsorted mixed snapshot with exhausted counters and both kinds of custom hours. */
    private Snapshot editSource() {
        StudyHours hours = new StudyHours(Map.of(
                "monday", new StudyWindow(LocalTime.of(9, 0), LocalTime.of(20, 0)),
                "2026-09-22", new StudyWindow(LocalTime.of(10, 0), LocalTime.of(18, 0))));
        return new Snapshot(List.of(new Task(10, "First", NOW.minusYears(1), 30),
                new Task(2, "Draft", DEADLINE, 90), new Task(7, "Last", NOW.plusYears(2), 60)),
                List.of(LECTURE), Integer.MAX_VALUE, Integer.MAX_VALUE, hours);
    }

    /**
     * Captures one logger's diagnostics and restores all changed settings even when the action fails.
     */
    private List<LogRecord> captureLogs(Class<?> source, Executable action) throws Throwable {
        Logger logger = Logger.getLogger(source.getName());
        Level originalLevel = logger.getLevel();
        boolean originalUseParents = logger.getUseParentHandlers();
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        try {
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            action.execute();
            return List.copyOf(records);
        } finally {
            logger.removeHandler(handler);
            handler.close();
            logger.setLevel(originalLevel);
            logger.setUseParentHandlers(originalUseParents);
        }
    }

    /**
     * Checks that opt-in stage logs omit domain values and exception payloads.
     */
    private void assertPrivateStageRecords(List<LogRecord> records) {
        assertTrue(!records.isEmpty());
        for (LogRecord record : records) {
            assertEquals(Level.FINE, record.getLevel());
            assertEquals(null, record.getParameters());
            assertEquals(null, record.getThrown());
            assertTrue(!record.getMessage().contains("Private task"));
            assertTrue(!record.getMessage().contains(DEADLINE.toString()));
        }
    }
}
// @@author
