// @@author nickpatt
package scheduleflow.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import scheduleflow.common.InputRules;
import scheduleflow.common.ValidationException;
import scheduleflow.model.Commitment;
import scheduleflow.model.Snapshot;
import scheduleflow.model.StudyHours;
import scheduleflow.model.StudyWindow;
import scheduleflow.model.Task;
import scheduleflow.planning.EarliestDeadlinePlanner;
import scheduleflow.planning.TimeRules;
import scheduleflow.storage.FileStorage;
import scheduleflow.storage.StateCodec;

/**
 * Verifies task candidates, deadline limits and stable identity with isolated storage and fixed time.
 */
@Isolated
class TaskServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 7, 0);
    private static final LocalDateTime DEADLINE = NOW.plusDays(1).withHour(18);
    private static final Commitment LECTURE = new Commitment(3, "Lecture", DayOfWeek.MONDAY,
            LocalTime.of(10, 0), 120);
    private final TaskService service = new TaskService();

    // @@author ernestgoh-god
    @Test
    void reviseRemaining_knownTask_replacesOnlyEstimateAndPreservesMetadata() {
        Task first = new Task(2, "First", DEADLINE, 30);
        Task target = new Task(8, "Target", DEADLINE, 60);
        Snapshot source = new Snapshot(List.of(first, target), List.of(LECTURE), 10, 11);

        Snapshot candidate = service.reviseRemaining(source, 8, 90);

        assertEquals(List.of(first, new Task(8, "Target", DEADLINE, 90)), candidate.tasks());
        assertEquals(source.commitments(), candidate.commitments());
        assertEquals(source.studyHours(), candidate.studyHours());
        assertEquals(10, candidate.nextTaskId());
        assertEquals(11, candidate.nextCommitmentId());
        assertEquals(List.of(first, target), source.tasks());
        assertSame(source, service.reviseRemaining(source, 8, 60));
    }

    @Test
    void reviseRemaining_invalidTaskOrEstimate_rejectsWithoutChangingSource() {
        Task task = new Task(2, "Target", DEADLINE, 60);
        Snapshot source = new Snapshot(List.of(task), List.of(LECTURE), 3, 4);

        assertEquals("state", assertThrows(NullPointerException.class,
                () -> service.reviseRemaining(null, 2, 30)).getMessage());
        assertEquals("Unknown task ID: T9", assertThrows(ValidationException.class,
                () -> service.reviseRemaining(source, 9, 30)).getMessage());
        for (int remaining : new int[] {0, -30, 45, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> service.reviseRemaining(source, 2, remaining));
        }
        assertEquals(List.of(task), source.tasks());
        assertEquals(3, source.nextTaskId());
    }

    @Test
    void reviseRemaining_savedCandidateReloadsAndChangesPlanningDemand(@TempDir Path directory) throws Exception {
        Task task = new Task(2, "Target", NOW.withHour(8).withMinute(30), 60);
        Snapshot source = new Snapshot(List.of(task), List.of(), 3, 1);
        Snapshot revised = service.reviseRemaining(source, 2, 90);
        FileStorage storage = new FileStorage(directory.resolve("data/scheduleflow.txt"), new StateCodec());

        storage.save(revised);
        Snapshot restored = storage.load();

        assertEquals(revised, restored);
        EarliestDeadlinePlanner planner = new EarliestDeadlinePlanner();
        assertEquals(30, planner.generate(source, NOW).unallocated().getFirst().minutes());
        assertEquals(60, planner.generate(restored, NOW).unallocated().getFirst().minutes());
    }
    // @@author nickpatt

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
        assertEquals(deadline, service.add(original, "Draft", deadline, 30, NOW.plusDays(1))
                .tasks().getFirst().deadline());
        assertEquals(Snapshot.empty(), original);
    }

    @Test
    void add_rejectedValues_preservePopulatedSnapshotAndRetryId() {
        Task existing = new Task(2, "Existing", DEADLINE, 60);
        Snapshot original = new Snapshot(List.of(existing), List.of(LECTURE), 5, 8);

        assertThrows(ValidationException.class, () -> service.add(original, "Bad/name", DEADLINE, 30, NOW));
        assertThrows(ValidationException.class, () -> service.add(original, "Draft", DEADLINE, 45, NOW));
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
                () -> service.add(original, "Private task", DEADLINE, 45, NOW),
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
        for (int minutes : new int[] {-30, 0, 1, 45, Integer.MAX_VALUE}) {
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
            assertEquals(deadline, service.add(Snapshot.empty(), "Draft", deadline, 30, NOW)
                    .tasks().getFirst().deadline());
        }
        LocalDateTime preciseNow = NOW.plusSeconds(1).plusNanos(1);
        assertThrows(ValidationException.class, () -> service.add(Snapshot.empty(), "Draft", NOW, 30, preciseNow));
        assertEquals(NOW.plusMinutes(1), service.add(Snapshot.empty(), "Draft", NOW.plusMinutes(1), 30, preciseNow)
                .tasks().getFirst().deadline());
    }

    @Test
    void add_leapDay_usesCalendarYearBoundary() {
        LocalDateTime leapNow = LocalDateTime.of(2028, 2, 29, 12, 0);
        LocalDateTime horizon = LocalDateTime.of(2029, 2, 28, 0, 0);

        assertEquals(horizon, service.add(Snapshot.empty(), "Draft", horizon, 30, leapNow)
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
                    edit.deadline().orElse(original.deadline()), original.remainingMinutes());
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
        invalid.add(new TaskEdit(Optional.empty(), Optional.empty(), true));
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
                assertEquals(deadline, service.edit(source, 2,
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
        assertEquals(movingDeadline, service.edit(source, 2, edit, NOW.plusDays(1)).tasks().get(1).deadline());
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
