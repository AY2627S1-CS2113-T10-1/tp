# Developer Guide

## Keeping the guides up to date

After each implementation, review this guide and the [User Guide](UserGuide.md)
and update them as needed in the same change. Record changes to design,
implementation and testing here, and changes to commands, examples and
user-visible behaviour in the User Guide. Describe verified behaviour accurately
and clearly mark planned features. This requirement also applies to all AI
agents working on the project, as specified in the repository's root `AGENTS.md`.

## Code authorship

RepoSense tags follow the whole-file allocation in version 2 of the supplied
authorship redistribution plan, dated 2026-10-25. The previous scoped author
comments have been removed from production and test Java files. This change
includes Ka How's (`ka-how`) and Ernest's (`ernestgoh-god`) allocations; other
files use Git-based attribution until their owners' tags are applied.

Ka How's files are relative to the corresponding `scheduleflow/` source root:

- Production (`src/main/java/scheduleflow/`): `Main.java`, `cli/App.java`,
  `cli/AppState.java`, `cli/Command.java`, `cli/CommandResponse.java`,
  `cli/ConsoleUi.java` and `cli/TextRenderer.java`.
- Tests (`src/test/java/scheduleflow/`): `MainTest.java`, `cli/AppStateTest.java`,
  `cli/AppTest.java`, `cli/CommandTest.java`, `cli/ConsoleUiTest.java`,
  `cli/ContractsTest.java` and `cli/TextRendererTest.java`.

Each listed file starts with exactly one `// @@author ka-how` before its package
declaration and ends with one bare `// @@author` after its final closing brace.
There are no interior author tags. The entire file, including nested command
records, imports, helpers and documentation, belongs to this allocation.
`CommandParser`, `ParseException` and `CommandDispatcher` are outside Ka How's
allocation even though they share the `cli` package.

Ernest's files are relative to the corresponding `scheduleflow/` source root:

- Production (`src/main/java/scheduleflow/`): `model/Snapshot.java`,
  `storage/FileStorage.java`, `storage/PlanCodec.java`, `storage/StateCodec.java`,
  `storage/Storage.java` and `storage/StorageException.java`.
- Tests (`src/test/java/scheduleflow/`): `model/SnapshotTest.java`,
  `storage/FileStorageTest.java`, `storage/PlanCodecTest.java` and
  `storage/StateCodecTest.java`.

Each of Ernest's ten files starts with one `// @@author ernestgoh-god` before
its package declaration and ends with one bare `// @@author` after its final
closing brace. There are no interior tags. The entire Snapshot aggregate is
included, along with all storage contracts, validation, codecs and helpers.
Imports of another member's types do not split a file's ownership. Application
startup, AppState, command parsing and dispatch remain outside this allocation.

These tags record the agreed allocation, not who originally wrote each section.
They do not rewrite Git history, reassign historical TODOs or change behaviour.
See the [RepoSense author-tag guide](https://reposense.org/RepoSense/ug/usingAuthorTags.html).
For tag-only changes, verify the exact file list and marker boundaries and compare
all 74 Java files against the plan's tag-independent fingerprints. Existing JUnit
coverage and UI fixtures remain applicable; no new behavioural tests are needed.

## Duration verification (issue #13)

Duration recording belongs to the existing add operations; no additional
command or production API is needed. `InputRules.requireDuration` validates
positive multiples of 30 for commitments and legacy saved TASK rows; `Commitment`
also rejects intervals crossing midnight. Tasks now use `requireTaskMinutes` for
nonnegative exact whole minutes, with service additions requiring positive work.
Task and commitment tests cover their deliberately different boundaries.

Two additional `CommitmentServiceTest` integration checks exercise the existing
task, commitment, storage and planner APIs together with fixed dates and isolated
files. A saved/reloaded 90-minute task retains its full estimate when only 30
minutes fit around a 90-minute commitment; the plan reports the other 60 minutes.
Saving the plan source preserves both estimates. A second check confirms a
reloaded 90-minute weekly commitment blocks exactly that interval on successive
Mondays. These direct service checks complement, but do not replace, console
acceptance. Consult the UI test plan for process-level readiness and coverage.

## Acknowledgements

The build uses [Gradle](https://gradle.org/),
[Shadow](https://gradleup.com/shadow/) for executable JAR packaging,
[JUnit 5](https://junit.org/junit5/) for Java tests and
[Checkstyle](https://checkstyle.org/) for coding-standard checks. Versions are
declared in `build.gradle` and `gradle/wrapper/gradle-wrapper.properties`.
The repository-local skills attribute the SE Education coding and Git
conventions and CS2113 code-quality guidelines used by this project.

## Design & implementation

### Command transport

The sealed `Command` interface contains implemented command transports and
planned transports, including the two unwired unavailable-range operations.
Records that only carry values use Java's generated canonical constructors;
redundant empty compact constructors have been removed. Records with required
reference fields retain their null checks. Parsing and the services still own
syntax and business validation respectively. `CommandParserTest` and
`ContractsTest` cover construction and dispatch vocabulary without changing
the public record components or constructor signatures.

### Planning and schedule projection

`EarliestDeadlinePlanner` creates chronological half-hour slots from the
captured command time through the rolling calendar-year horizon. It excludes
recurring commitments, skips zero-remaining completed tasks, sorts dated tasks by
deadline, priority then numeric ID, followed by undated tasks by priority/ID, and assigns
each eligible task to its earliest free slots. Overdue and out-of-horizon
tasks keep their full work estimate as unallocated; eligible tasks retain any
sessions that fit before their deadline. Undated tasks use the same finite horizon
without overdue/outside-horizon classification. Adjacent slots for one task merge
within a day. Source task estimates remain unchanged.

`ScheduleService` projects the frozen plan across the complete configured daily
window. Each slot is classified as busy, assigned to a task, before the
generation cutoff, or free, in that order. Merging preserves commitment and
task identities. `Plan`, `StudySession`, `UnallocatedWork`, `ScheduleEntry` and
`ScheduleView` validate their own fields and retain immutable result lists;
allocation relationships remain the planner's responsibility. Fixed-date
planner and projection tests verify deadline order, conservation of minutes,
cutoff rounding, clipping, midnight commitments and allowed schedule dates.

### Unavailable study periods (issue #48)

A student marks an unavailable period using the existing one-time commitment
command: `commitment add n/Unavailable on/YYYY-MM-DD start/HHmm d/MINUTES`.
The name is an ordinary user description, not a reserved keyword. Equal
inclusive date bounds represent the single unavailable occurrence, and
`Commitment.occursOn(date)` supplies the same date membership check to the
allocator and schedule projection. There is no separate availability model,
new command or storage format for this workflow.

A period may overlap previously allocated task sessions: commitment overlap
validation checks fixed commitments, not temporary task allocations. The
existing save-before-publication dispatcher invalidates the old plan after a
successful addition. Regenerating allocates tasks around the blocked interval,
preserves task estimates and reports any work that no longer fits before its
deadline. The schedule displays the period as `[BUSY]` with its ordinary
commitment ID. Deleting that ID saves and invalidates again; replanning can use
the released interval. Previously returned immutable plan objects stay frozen.

The existing half-hour alignment, positive duration, same-day interval and
fixed-commitment overlap rules apply. Adjacent task sessions can end at the
block's start or begin at its end. Blocking every available slot produces an
unallocated-work result rather than scheduling after the task's deadline.
Hours overrides continue to control the enclosing study window.

Optimizer-owned JUnit acceptance tests exercise adding a block over a planned
session, regenerating, deletion and restoration, partial and complete loss of
deadline capacity, frozen schedule projection and no recurrence the next week.
A temporary-file test reloads the block and date-specific hours before planning,
then verifies persisted deletion releases the interval. Existing domain and
UI-018 coverage retains invalid-input and overlap checks. UI-021 exercises this
workflow's save/restart and add/delete plan invalidation through existing console
commands; exact task allocation uses fixed-time JUnit tests rather than the live
clock in the packaged application. This single-date workflow needs no new
parser or renderer. Multi-date operations below are separate, unfinished
extensions; this branch remains independent of the #55 study-policy feature.

#### Multi-date unavailable periods: implementation handoff (planned)

**Status:** transport records, exact planned console fixtures and tests for the
resulting commitment representation are supplied. The commitment service,
parser and confirmation renderer are implemented and unit-tested. Dispatch
remains an unwired throwing skeleton, so the two range commands are NOT yet
usable from the console. The following grammar and output remain the proposed
implementation contract for the team.

Continuous mode uses an inclusive start and exclusive end:

```text
scheduleflow> unavailable continuous n/Trip from/2026-10-26 1500 until/2026-10-28 1100
Blocked 3 unavailable periods.
C1 | 2026-10-26 | 15:00 - 24:00 | Trip
C2 | 2026-10-27 | 00:00 - 24:00 | Trip
C3 | 2026-10-28 | 00:00 - 11:00 | Trip
Saved unavailable periods. Run plan to regenerate.
```

Daily mode uses inclusive dates and an end-exclusive window on each date:

```text
scheduleflow> unavailable daily n/Appointment from/2026-10-26 until/2026-10-28 start/1500 end/1700
Blocked 3 unavailable periods.
C1 | 2026-10-26 | 15:00 - 17:00 | Appointment
C2 | 2026-10-27 | 15:00 - 17:00 | Appointment
C3 | 2026-10-28 | 15:00 - 17:00 | Appointment
Saved unavailable periods. Run plan to regenerate.
```

Examples start with an empty store independently. IDs come from the existing
commitment counter and are assigned by date. Singular confirmation is
`Blocked 1 unavailable period.`; plural is `Blocked N unavailable periods.`.
Rows use `CNUMBER | YYYY-MM-DD | HH:mm - HH:mm | NAME` and LF separators, with
no trailing newline from the renderer. Display a midnight endpoint as `24:00`.
The console adds the final newline and prompt as usual.

Use ordinary one-time commitments, not a new availability DTO or grouping ID.
Every generated record has equal date bounds and the same normalized name.
Each ID can be independently edited/deleted with existing commitment commands.
Deleting one ID does not remove the rest of the request. There is no batch
undo, recurring rule or automatically linked group in this version.

Both modes require half-hour-aligned values with zero seconds/nanoseconds,
valid calendar dates in years 0001-9999, and ordinary existing name rules.
These commitments can be outside the current planning horizon, just like other
commitments; only generated plan dates are projected. No new numeric defaults
are introduced. Daily mode requires `firstDate <= lastDate` and `start < end`
on the same day. It does not support overnight windows or a typed `2400` endpoint;
use continuous mode with the next date at `0000` for an overnight interval.
Continuous mode permits a same-day interval and requires `start < end`. Split
at midnight, retaining only positive segments. An exclusive end at midnight
does not create a zero-length record on that end date. Include leap days and
avoid arithmetic past year 9999 when stepping dates.

**Owner implementation order:**

1. **Darren (`Darren50`), implemented:** `CommitmentService.addContinuousBlock`
   and `addDailyBlocks`. Validate the request and calculate the required ID
   count before expansion. The last assignable ID remains `2147483646`; the
   next counter may be `2147483647`. Build a candidate locally using the existing
   dated-add logic; return it only if the whole request succeeds. No disk I/O,
   live publication, clock reads, partial results or mutation of the source.
   Both operations validate in the order above, compute the date count
   arithmetically (continuous mode omits an end date reached exactly at
   midnight), check ID capacity, then create one `Commitment` per date with
   equal date bounds through a shared private expansion helper. Each segment is
   checked against stored commitments with the existing `rejectOverlaps`, in
   date order, and the generated records are appended with one
   `Snapshot.withCommitments` call. `CommitmentServiceTest` covers every
   Darren row of the JUnit matrix below, plus null references and logging.
   Preserve tasks, their counter, custom hours and other commitments.
2. **Ernest (`ernestgoh-god`), implemented parsing:**
   `CommandParser.parseUnavailable` routes both modes from `parse`, returning
   `Command.AddContinuousBlock` or `Command.AddDailyBlocks`. Complete,
   case-sensitive patterns preserve stripped Unicode names and parse strict
   four-digit times and calendar dates in years 0001-9999. Missing, repeated,
   reordered, extra and mixed-mode fields reject with the selected mode's
   usage; unknown modes show both alternatives. Invalid dates/times and year
   zero retain the existing parser messages. Semantic range, alignment and
   conflict validation remains in the commitment service. Deterministic
   `CommandParserTest` cases cover valid transport values, leap and boundary
   dates, malformed fields and mode-specific errors.
3. **Ernest, coordinating shared dispatch with Ka How:** replace the two
   explicit throwing switch cases. Call the selected service, identify added
   records from the candidate's IDs, save the complete candidate exactly once,
   then publish/invalidate the old plan and format success. Never save or
   publish after validation failure. Save failure retains snapshot, counters,
   current plan and settings; retry starts from the same next ID.
4. **Ka How (`ka-how`), renderer implemented:**
   `TextRenderer.unavailablePeriodsAdded(List<Commitment>)` formats newly added
   chronological records only. It preserves supplied order and prints the exact
   singular/plural count, ID/date/time rows, midnight as `24:00` and regeneration
   guidance. Null lists/elements, empty batches and weekly records are rejected.
   `TextRendererTest` covers the continuous example, a daily service batch across
   leap day, unrelated-record exclusion, Unicode and boundary years. Add the two
   command formats to help after dispatch work. Existing schedule
   output continues to show `[BUSY]` and the generated IDs; no new allocation
   logic belongs in Printing.
5. **Ernest, saving:** round-trip the whole batch through the existing version 3
   format (or version 4 if other records have skipped dates). No new schema is
   needed. Verify restart, one-save rollback/retry and preservation after task,
   commitment and hours changes. Do not persist the original request separately.
6. **Gabriel (`GabrielYuSP`):** retain planner/projection acceptance coverage
   for these ordinary dated segments. Current tests manually supply the
   expected continuous and daily segments through the existing service; they
   establish representation compatibility, not success of the unfinished APIs.
   Add service-to-planner tests once the batch methods are implemented.

Conflict policy: reject the complete request if any generated segment overlaps
an existing commitment on its actual occurrence date. Planned task sessions do
not cause a conflict. Adjacency is valid and skipped weekly occurrences do not
block the date. Check generated dates in order; for the first conflicting date,
reuse the existing conflict list in start-time/numeric-ID order and the message
`Commitment overlaps CNUMBER: NAME[; ...]`. No earlier segment may be saved or
published and no IDs may be consumed by a rejected request.

Required domain messages (the renderer adds `Error: `):

| Invalid value | Exact message |
| --- | --- |
| Continuous end at/before start | `Unavailable interval end must be after its start.` |
| Daily first date after last | `Unavailable date range must start on or before its end.` |
| Daily start at/after end, including overnight | `Daily unavailable start must be before end on the same day.` |
| Unaligned time, including seconds/nanoseconds supplied through Java | `Unavailable times must be on half-hour boundaries.` |
| Too few remaining IDs | `Commitment IDs are exhausted.` |
| Blank/invalid name | Reuse `InputRules.normalizeName` and its existing messages |

Null API references must raise named `NullPointerException` preconditions once
implemented. Use existing date validation for out-of-range years. Resolve name,
date validity, alignment, ordering, ID capacity and then overlap in that order;
the fixtures use one invalid category per command. Malformed syntax uses
`Invalid unavailable command format` plus the selected mode's exact usage.
Invalid date/time syntax uses existing `Invalid date or time` with that usage;
year zero uses existing `Year must be between 0001 and 9999` with usage. Unknown
modes use a usage listing both alternatives. No success or count output may be
printed before the save succeeds.

**Acceptance files and activation:**
[`test/ui-unavailable-ranges.planned.json`](../test/ui-unavailable-ranges.planned.json)
contains exact per-command expected output for UI-022 through UI-027. All six
are `planned`; do not treat their unexecuted expectations or a stub exception
as a pass. Parsing alone does not activate console cases while dispatch is
unfinished. The main release fixture remains fully active. Once dependencies are
implemented, change the relevant case status to `active`, run it with
`node .agents/skills/test-ui/scripts/run-ui-tests.mjs --fixtures test/ui-unavailable-ranges.planned.json`,
and move verified cases into the main fixture in the same change. If the file
contains only planned cases, exit 2 means no active scenarios were executable.

Owners must add these deterministic JUnit cases alongside their implementation:

| Owner / SUT test | Inputs and required assertions |
| --- | --- |
| Darren / `CommitmentServiceTest` continuous example | Oct 26 15:00 to Oct 28 11:00 produces durations 540, 1440, 660, IDs in order, equal date bounds and preserved source/settings |
| Darren / continuous boundaries | Same-day creates one record; Oct 26 23:00 to Oct 27 00:00 creates only Oct 26; a 00:00 start is included; reject equal/reversed/unaligned endpoints |
| Darren / daily example | Oct 26-28 15:00-17:00 creates three 120-minute dated records; one-date range creates exactly one |
| Darren / daily boundaries | Include Feb 29 in 2028-02-28 to 2028-03-01; support the date 9999-12-31 without advancing past it; reject reverse dates, zero/overnight windows and unaligned times |
| Darren / conflicts | First or later segment conflicts with weekly or one-time records; reject the entire request; skipped weekly dates and adjacent times succeed |
| Darren / ID boundaries | Two records starting at next ID 2147483645 succeed with next counter 2147483647; three records reject without changes; exhausted counter rejects either mode |
| Ernest / `CommandParserTest` | Both exact commands produce their transport records; check leap dates, year limits, Unicode names, field ordering, duplicates, missing/extra fields, mixed modes and strict four-digit times |
| Ka How / `TextRendererTest` | Exact singular/plural counts, chronological ID/date rows, Unicode names, `24:00`, LF and no trailing newline; include only new rows; formatter performs no I/O |
| Ernest + Ka How / `CommandDispatcherTest` | Use fixed time and fake Storage: both modes save once, publish once and invalidate an existing plan; validation saves zero times; injected save failure preserves snapshot/plan/counters and retry IDs; renderer success is not reached on failure |
| Ernest / `FileStorageTest`, `StateCodecTest` | Save/reload all segments and unrelated skipped commitments/custom hours; deletion of one generated ID persists independently; current version 3/4 round trips preserve all values |
| Gabriel / `EarliestDeadlinePlannerTest` | Fixed Oct 26 14:00, 180-minute task due Oct 28 12:00: continuous example schedules 60 minutes on Oct 26 and 60 on Oct 28, leaves 60 unallocated; daily mode preserves gaps on every date |
| Gabriel / `ScheduleServiceTest` | Project first/intermediate/final continuous dates and daily windows; clip to configured hours, omit out-of-range occurrences, retain frozen old plans and permit study at exclusive ends |

Run the affected JUnit tests, Java 25 build/Checkstyle and active console cases
after each integration. Issue #48 is closed for the working single-date blocking
workflow. Track the unfinished multi-date commands as follow-up work and keep
their console cases planned until dispatch and acceptance tests pass. Partial
PRs may reference #48 for context without using a closing keyword.

### Exact work and slot capacity

Exact work and slot capacity are distinct: `StudySession.workMinutes` carries
positive exact work while `minutes()` remains the reserved interval capacity.
The original three-argument constructor defaults work to capacity.
`UnallocatedWork` carries an optional deadline, exact `int minutes` and rounded
`long capacityMinutes`; its original concrete-deadline constructor remains.
Demand is `30 * ceil(R / 30)` using long arithmetic. Allocation consumes
`min(remaining, 30)` exact work per slot, never subtracting below zero; merged
sessions retain that exact sum. For R=45, 60 allocated capacity means 45 planned
work; 30 capacity leaves 15 exact work and 30 unallocated capacity. Source
estimates are never decremented. Totals must use long streams. Only the final
session for a task can contain padding, and zero-work sessions are not created.

### Continuous work and fixed breaks (issue #55)

The console supports `policy set max/MINUTES break/MINUTES`, `policy show`
and `policy reset`. `StudyPolicy` validates positive multiples of 30 minutes.
`Snapshot.studyPolicy()` is an explicit optional: absent means unrestricted
planning, preserving legacy behavior without inventing numeric defaults.
The no-argument planner reads the stored policy on each generation. The explicit
`EarliestDeadlinePlanner(StudyPolicy)` constructor remains available as a fallback
for API clients whose snapshot has no policy; a stored policy takes precedence.

Allocation now walks slots chronologically, choosing dated unfinished tasks by
deadline, priority and numeric ID, then undated tasks by priority and ID. This
retains deadline/ID order for equal-priority dated tasks while allowing one work
counter to cover different tasks and commitments. Fixed
commitments count even outside study windows and before the rounded generation
cutoff. The counter starts at midnight on the generation date; activity before
that date and past task study are not reconstructed. Across future dates,
calendar midnight does not itself reset the counter: actual unoccupied time does.

Tasks may immediately follow commitments while the combined work total is below
the limit. At the limit, another task slot must wait for the configured consecutive
rest. Short idle gaps retain accumulated work; any new work interrupts partial
rest. Fixed commitments are never moved or shortened, even when they push the
combined work total above the limit. The allocator does not reserve rest in
anticipation of a future commitment. After an overrun of any size, one configured
break suffices; proportional break scaling is deferred. Natural idle time,
including time outside study hours, can satisfy all or part of the required rest.

`Plan.breaks()` contains chronological immutable `ScheduleEntry` rows with
`SlotKind.BREAK` and empty reference/name fields. Its original five-argument
constructor remains available and supplies an empty break list. Relationships
between sessions, breaks and commitments remain the planner's responsibility.
Only eligible slots withheld from pending, deadline-eligible tasks are marked
as breaks; natural rest and trailing idle time remain ordinary free/unplanned
time. An interrupted break can therefore appear as separate partial rows, and
a break may end at a deadline even if the task ultimately remains unallocated.
`ScheduleService` projects frozen break rows without recomputing the policy.

`CommandParser` transports integer minutes without constructing domain settings.
The dispatcher creates `StudyPolicy`, obtains a policy-only candidate and formats
the response before saving. Changed settings are saved before publication and
plan invalidation. Equal sets and an already-unrestricted reset return the same
snapshot, skip saving and preserve the current plan. Failures preserve both the
stored settings and saved plan. `policy show` is read-only. All task, commitment,
hours and recurrence candidates preserve the optional setting.

The existing continuous-work algorithm and BREAK renderer retain their original
owners. Ernest's scoped changes supply commands, settings persistence and the
connection from the source snapshot to the planner's existing work counter.
Fixed-time JUnit verifies configured console generation and exact breaks,
constructor fallback, cross-task/commitment limits, overnight rest and failure
rollback. UI-028 verifies set/show/reset, invalid values and saved restarts;
UI-029 restores a fixed policy-bearing plan to verify packaged BREAK output and
invalidation without relying on the live clock. Actual allocation under settings
is verified by fixed-clock application and planner tests.

### Configurable study hours

`StudyWindow` validates positive same-day intervals on half-hour boundaries.
`StudyHours` holds an immutable map keyed by full lowercase weekday names or
canonical ISO dates. Resolution is date override, then weekday, then
`StudyWindow.DEFAULT` (08:00–22:00). Resets remove only the selected key.
Overnight windows and 24:00 endpoints are unsupported; the latest endpoint is 23:30.

`Snapshot` now includes `studyHours`; its original four-argument constructor
remains available and supplies defaults. Task and commitment services carry the
settings into all candidates. `SetHours`, `ShowHours` and `ResetHours` commands
use the agreed `hours TARGET /start HH:mm /end HH:mm` grammar, full lowercase
weekday names and strict ISO dates. The dispatcher saves a changed snapshot
before publication and plan invalidation; failed saves retain the old plan.
Reset dispatch uses a separate confirmation from setting hours. The renderer
identifies whether the restored window comes from a recurring weekday setting
or the default and prints its endpoints. Dispatcher tests cover both sources
and resetting an unset date; UI-015 checks the exact reset confirmations.

Both slot allocation and schedule projection resolve the window from the
snapshot. `TimeRules.eligibleStart` has a window-aware overload; the original
overload retains default behavior. `StudySession` and `ScheduleEntry` validate
same-day, positive, aligned intervals; the planner enforces configured hours.
`ScheduleView` carries and validates its effective window, retaining the original
four-argument constructor for default-window callers. Plans freeze their source
settings, so later time or unrelated snapshots do not alter projections.

The codec reads versions 1 through 5. New saves use at least version 2 for
extended `TASK2` rows, version 3 when a commitment has date bounds and version 4
when it has skipped occurrences. Version 3 stores inclusive start and end dates
on every commitment row, using `-` for unbounded series; version 4 adds skipped
dates. Recurrence requires version 5 as described below. Sorted
`HOURS|TARGET|HH:mm|HH:mm` rows retain custom study hours.
Duplicate targets, invalid dates, unknown weekdays and invalid intervals reject
the whole file. Legacy version 1 and earlier version 2 files with `TASK` rows
remain readable. Older applications cannot read `TASK2` records.
Atomic replacement still saves tasks, commitments, counters and hours together.

JUnit coverage checks precedence/reset, immutable maps, input boundaries,
legacy and new codec round trips, malformed records, save rollback, preservation
through task/commitment changes, recurring allocation, clipping and rounded
cutoffs outside the old window. UI-015 verifies commands and persistence through
three isolated console sessions, including reset and plan invalidation.

### Task operations and defensive checks

`Task` owns structural validation: positive IDs, normalized nonblank names,
optional minute-precision deadlines in years 0001-9999 and nonnegative whole-minute
remaining work (zero is retained completion). It uses `InputRules` and permits
overdue or out-of-horizon saved deadlines. `ValidationException` carries the
user-facing explanation and rejects a null message as programmer misuse.

`TaskService.add` requires positive work and checks the captured command time, accepting
`now < deadline <= TimeRules.horizonEnd(now)`. The horizon moves with each
command date, including the February 29 adjustment. Additions append to the
stored task order and advance only the task counter. The last allocatable ID
is `2147483646`; the next counter can be `2147483647`, at which point additions
are rejected before incrementing. Duplicate names are valid. Deletion removes
one known ID without resetting counters; listing returns an immutable copy in
numeric ID order and includes overdue work without reducing estimates.

Services reject null snapshots or command times with named
`NullPointerException` preconditions. Invalid domain values and unknown IDs use
`ValidationException`; runtime checks also work with assertions disabled.
Assertions check normalization and candidate postconditions: preserved records,
the exact add/delete count change and unchanged unrelated counters/commitments.
They diagnose implementation defects rather than replace user-input validation.
Neither services nor models save candidates, read the clock or invalidate plans;
the dispatcher saves before publishing and reporting success.

Task-owned types document their contracts with Javadoc. `TaskService`, `Task`
and `InputRules` log processing stages and rejected validation at `FINE`,
without names, deadlines, durations or exception payloads. Candidate completion
means only that a candidate is ready, not that it has been saved. Default
console launches remain quiet; the application owns session severity. No
logging handlers or global settings are installed by these types.

For Task diagnostics, use a local logging configuration outside the repository:

```properties
handlers=java.util.logging.FileHandler
.level=OFF
scheduleflow.task.level=FINE
scheduleflow.model.Task.level=FINE
scheduleflow.common.InputRules.level=FINE
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=scheduleflow-task-%u.log
java.util.logging.FileHandler.encoding=UTF-8
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

Launch with
`java -ea -Djava.util.logging.config.file=logging.properties -jar scheduleflow.jar`.
Gradle tests already enable assertions. Task JUnit tests cover structural rules,
deadline boundaries and rolling horizons, duplicate names, numeric ordering,
identity exhaustion, immutable mixed snapshots and retry IDs. Logging tests use
isolated in-memory handlers, restore settings in `finally` and check stage order,
privacy and absence of false completion after rejection. Run the owned tests with
`./gradlew test --tests 'scheduleflow.task.*' --tests 'scheduleflow.common.*' --tests 'scheduleflow.model.TaskTest'`.
The existing packaged UI fixtures require no changed output expectations.

### Task detail editing (issue #39)

`TaskService.edit(state, id, TaskEdit, now)` implements immutable partial name and
deadline edits. `TaskEdit` uses nonnull `Optional` containers; omitted fields retain
their values. Empty edits, unknown IDs and simultaneous `clearDeadline` plus a
replacement deadline raise `ValidationException`. Clearing alone explicitly
removes the deadline, including on completed or overdue tasks.
The full replacement is validated by `Task`. Only an actually changed deadline
must satisfy `now < deadline <= TimeRules.horizonEnd(now)`. Renaming overdue or
out-of-horizon work, or explicitly retaining its existing deadline, is valid.

Edits preserve completion, priority, occurrence provenance, exact stored position,
ID, remaining estimate, other records, recurrence definitions/cursors, all counters
(including exhausted counters) and configured study hours. A valid
edit with no effective change after normalization returns the original snapshot
instance. The API does not save, publish or invalidate plans. FINE diagnostics
omit private values; assertions check candidate preservation.

`Command.EditTask` transports a partial edit and `CommandParser` accepts
`task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]`. At least one field is required,
in name/deadline order. `TextRenderer.taskEdited` is implemented and reports the
stored ID, name, deadline and estimate without mutating or saving anything.
The dispatcher prepares the response before saving, then publishes changed
candidates only after save succeeds. A normalized no-op returns a confirmation
without saving or invalidating the current plan. Failed syntax, validation or
saving retains the old snapshot, counters, file and plan.

Deterministic `TaskServiceTest` and `TaskEditTest` cases cover all field subsets,
atomic rejection, no-ops, immutable mixed snapshots and custom hours, precise
command times, structural deadline limits, inclusive/moving/leap-day horizons,
null preconditions and private diagnostics. Parser, renderer and dispatcher tests
cover strict grammar, exact output, no-op publication, failed-save retry and
replanning against edited details. UI-021 covers a real saved rename, rejection,
invalidation and restart with an overdue task, avoiding real-date assumptions.

### Weekly recurring tasks (issue #67)

`RecurringTaskService` integrates weekly recurrence with PR #84's merged exact-work,
optional-deadline, priority and lifecycle model.
`RecurringTask` stores an independent positive R ID, normalized name, first
deadline, optional inclusive final date, positive half-hour estimate and persisted
last-processed deadline. `TaskOccurrence` retains the template ID and original
deadline; release is always one week before that original deadline. Ordinary
Ordinary task additions accept positive whole minutes, including 45; completed
records retain zero internally. Template estimates remain positive multiples of
30. Generated tasks subsequently support exact remaining revisions and domain
progress/completion/reopening while retaining provenance.
The four-argument Task and four/five-argument Snapshot constructors remain usable
for shipped callers and legacy data, defaulting recurrence to empty.

`task recur add`, `task recur list` and `task recur delete` are parsed, dispatched
and rendered at the console. The first deadline establishes weekday/time; only
`every/WEEKLY` is supported. Creation materializes all future due dates through
the rolling horizon (including its boundary), subject to an optional last date.
Future templates beyond the horizon are retained without premature tasks.
Materialization skips missed deadlines arithmetically rather than backfilling;
the saved cursor also covers skipped dates. Pending tasks get T IDs in deadline,
then numeric-template-ID order. The complete batch must fit before any ID is
allocated. Replanning, a backwards clock change, task deletion and restarting
never rewind the cursor or recreate a processed occurrence.

Snapshot validates distinct logical occurrences, cursor coverage and the separate
R counter, including retained provenance after a template is deleted. Task edits,
remaining revisions, deletions, commitment changes and study-hours changes retain
the recurrence definitions/counters. Editing one generated task does not edit
its template or release. Template deletion stops only new generation and retains
all already-generated tasks. The allocator checks the original release against
slot start while retaining current study hours, dated/skipped commitments and
continuous-work policy. No second allocation algorithm is introduced.

`plan` first materializes a candidate and generates its Plan, validates the source
association and formats the result before publication. It atomically saves the
complete candidate and frozen plan together through `Storage.savePlan`, then
commits any changed source and publishes the plan. This avoids an intermediate
snapshot-only save that could discard the previous plan if the second save failed.
Planner/null/stale-result/rendering/save failure preserves the previous snapshot,
cursors, IDs and plan. Every successful `plan` saves its result, including when
materialization makes no source changes.

#### Recurrence storage format

For snapshot-only saves without a policy, version 5 is selected if a template,
occurrence provenance or allocated R counter requires it. Otherwise writes use version 2, 3 or 4 according to commitment
fields; version 2 is the minimum for `TASK2`. Versions 1 through 4 remain readable.
Loading old versions does not write or migrate files automatically.
Version 5 stores names last (including literal pipes) and uses these rows:

```text
SCHEDULEFLOW|5
NEXT|3|1|2
TASK2|1|NONE|45|HIGH|Undated draft
OCCURRENCE|2|2026-11-02T18:00|45|NORMAL|1|2026-11-02T18:00|Weekly quiz
RECURRING|1|2026-11-02T18:00|60|2026-11-02|2026-11-02T18:00|Weekly quiz
```

`NEXT` adds next R ID after the existing T/C counters. Ordinary tasks still use
`TASK2|id|deadline-or-NONE|exact-remaining-int|priority|name`. Occurrence rows have
eight fields including the discriminator:
`OCCURRENCE|id|deadline-or-NONE|exact-remaining-int|priority|templateId|originalDeadline|name`.
Zero remaining retains completion; priority is HIGH, NORMAL or LOW. Original
deadlines remain concrete even if the current deadline is cleared internally.
Templates use first deadline, positive half-hour minutes, last
date and processing cursor, with `-` for absent last date/cursor. V5 commitments
use the widest existing v4 layout, including bounds and skipped dates; hours rows
are unchanged. Even an empty state retains v5 after allocating an R ID, so IDs
are never reused. Invalid/duplicate/counter-inconsistent records reject the whole
load. Old executables cannot read v5; back up data before upgrading or downgrading.

Fixed-time model/service/planner tests cover weekly bounds, ordering, arithmetic
catch-up, leap years, release times, exhaustion, no-op identity and recurrence
preservation. Storage tests cover v1-v4 compatibility, v5 Unicode/pipe round trips,
corruption, deleted templates and saved cursors. Dispatcher tests cover one-save
transactions, read-only lists, failed-save retry, cursor-only publication and
planner failure. UI-022 exercises actual template commands and counters through
restart with a deliberately beyond-horizon future template. UI-023 uses restored
historical occurrences to prove replanning/restart do not recreate deleted tasks.
Fixed-clock JUnit tests cover materialization of currently eligible new tasks.

#### Remaining Task stories

The #39/#67 path is implemented end to end, including console editing, template
commands, generation and persistence. PR #84 is merged: exact work, optional
deadlines, stored priorities and domain completion/reopening coexist with
recurrence in the unified model and v5 codec. Issue #40 console progress is
connected through the existing parser and dispatcher with its implemented
Printing confirmation. Issue #56 connects reopening through `task reopen`.
Deadline clearing, undated addition, priority operations and search are not wired console
features. Do not restore throwing Task edit/recurrence renderers or discard the
existing commitment/hours formats.

### Task progress and lifecycle

The canonical model is `Task(id, name, Optional<LocalDateTime>, remainingMinutes,
TaskPriority, Optional<TaskOccurrence>)`. The five-argument optional-deadline
constructor creates an ordinary task without provenance. The original
four-argument concrete-date constructor defaults
priority to NORMAL and still rejects null deadlines; there is no ambiguous
four-argument Optional overload. Use `Task.withoutDeadline(id, name, minutes)`
or the canonical constructor for explicit absence, never a sentinel date.
HIGH/NORMAL/LOW are stored and used only for planner ties here; priority operations
are not wired at the console. Weekly templates are implemented as described
above; queries remain deferred.

`TaskService` exposes these pure candidate APIs:

- `addWithoutDeadline(state, name, minutes)` requires positive whole minutes,
  appends the next ID and does not need a clock.
- `recordWork(state, id, done)` requires an active task and
  `0 <= done <= remaining`; it subtracts reported work, not elapsed time.
- `recordSessionProgress(state, id, sessionMinutes, percentage)` requires positive
  int minutes and integer 0..100, then credits floor of their long product / 100.
  Overreported credit rejects atomically. Percent means a fraction of that
  session, not a whole-task completion percentage.
- `reviseRemaining(state, id, remaining)` requires active work and a positive
  estimate; increases are allowed and are not credited as work done.
- `complete(state, id)` retains the record and ID with zero remaining.
- `reopen(state, id, positiveRemaining)` requires a completed task and preserves
  all metadata, including an overdue or absent deadline.

Zero credited work and equal revisions return the identical source snapshot;
completion is idempotent with the same identity guarantee. Completed tasks reject
progress even with zero credit, and require explicit reopening before revision.
Every candidate preserves stored position, priority, optional deadline, occurrence
provenance, unrelated records, recurrence definitions/cursors, all counters and
custom study hours. No API saves, publishes, invalidates plans, reads a clock or replans.
Callers honor no-op identity to avoid unnecessary saves/invalidation.
New lifecycle methods add no diagnostics;
existing structural/edit diagnostics retain privacy protections.

`taskProgressUpdated(Task,int)` reports the stable ID/name, actual credited
minutes and resulting remaining estimate. It does not recompute percentages or
claim an unchanged value was saved. Both progress forms use the existing
candidate transaction: changed candidates save before publication; zero-credit
no-ops preserve the snapshot and plan. Negative renderer credit is programmer
misuse and raises `IllegalArgumentException`; task validation stays in TaskService.
`taskCompleted(Task)` prints `Completed TNUMBER: NAME` and `taskReopened(Task)`
prints `Reopened TNUMBER: NAME | Remaining: N min` for validated lifecycle
results. The completion and reopening formatters are connected to `task complete`
and `task reopen` respectively.
`taskEdited(Task)` is wired for issue #39's concrete-deadline/name edits.
`taskRemainingUpdated(Task)` formats issue #41's active remaining-time command.
Weekly recurrence commands, completion and reopening are implemented at the console.
Existing renderer compatibility changes only display `No deadline` in add/list
output and undated remainder rows, use an undated capacity-shortfall reason, and
sum exact session work in plan summaries. Existing schedule interval durations
still show reserved wall-clock capacity, not exact credited work; slot-aligned
output remains unchanged. `task list completed` provides the completed-only view.

Deterministic JUnit covers progress boundaries, overflow-safe floor arithmetic,
all identity no-ops, invalid/unknown/null inputs, completion/reopening, metadata,
source immutability, exhausted counters and custom hours. Planner cases verify
45/60 full and partial allocation, split padding, mixed optional deadlines and
completed tasks, priority ties, deterministic results and long conservation.
Codec/file tests cover explicit legacy migration, extended round trips, read-only
loads and corruption. Renderer tests cover exact progress/lifecycle text, zero
and partial credit, completion, maximum credit, Unicode, null inputs and framing.
Dispatcher tests cover successful progress publication, exact rounded credit,
replanning, no-ops, save failure and retry. UI-030 verifies both progress forms,
invalid requests, plan invalidation and retained completion across two restarts.
Completion is connected through `task complete`; reopening is connected through
`task reopen` with the transaction and acceptance coverage described below.

### Completion and completed-task view (issues #52 and #65)

`Command.CompleteTask` and `ListCompletedTasks` add exact, case-sensitive grammar
for `task complete TNUMBER` and `task list completed`. Parsing validates the ID
shape and overflow; `TaskService.complete` retains domain validation and returns
an immutable zero-work candidate. The dispatcher formats the existing
`TextRenderer.taskCompleted` confirmation, saves a changed candidate, then
publishes it. Repeated completion returns the same source and does not save or
invalidate a plan. Failed saves preserve the snapshot and its saved plan.

The completed view filters the service's numeric-ID list and shares row formatting
with the original full list. No data or plan changes occur during listing.
JUnit covers grammar boundaries, exact list text, save ordering, failed retries,
plan exclusion and no-ops. UI-033 covers completion, validation and saved restart.
Issue #56 adds the separate reopening command described below.

### Reopening completed tasks (issue #56)

`Command.ReopenTask(id, remainingMinutes)` transports
`task reopen TNUMBER remaining/MINUTES`. The parser requires positive decimal
32-bit integer tokens with no signs or leading zeroes, matches the full input
and reports that command's usage for malformed fields or overflow. It does not
inspect task state. `TaskService.reopen` already implements the domain behavior:
only completed tasks accept an explicit positive estimate, because completion
retains zero work rather than a previous estimate to restore.

The dispatcher uses that existing service and `TextRenderer.taskReopened` without
changing their signatures or the storage format. It prepares the confirmation,
saves the candidate once, then publishes it and invalidates the current plan.
Validation or save failure preserves the original snapshot, counters and saved
and live plans. Reopening preserves the task's stored position, name, priority,
optional deadline and occurrence provenance, as well as unrelated records,
templates, cursors, settings and all counters, including exhausted counters.
Overdue and undated completed records can be reopened without inventing a deadline.
The next `plan` includes the restored work under the existing allocation rules;
overdue work remains unallocated. The completed-only view excludes reopened tasks.

Parser JUnit tests cover spacing, exact minutes, integer boundaries, malformed
fields, case, overflow and usage hints. Dispatcher tests cover save-before-publication,
failed-save rollback and retry, rejection without saving, metadata preservation,
boundary estimates, completed-view exclusion and fixed-clock replanning of
45 work minutes into 60 minutes of capacity. Existing lifecycle and renderer
tests remain applicable. UI-034 checks exact packaged responses, rejected requests
preserving a frozen plan, successful invalidation, overdue planning totals,
progress after reopening and two saved restarts with isolated data.

### Basic saving and loading

`StateCodec` converts the existing `Snapshot` to and from plain text.
`FileStorage` loads UTF-8 or returns `Snapshot.empty()` for a definitely missing
file. Saving creates missing parent directories, writes a sibling temporary
file, then atomically replaces the target. There is no non-atomic fallback.
Constructors perform no I/O; existing constructor call sites remain supported.

Legacy version 1 uses one record per line (still read without changing values):

```text
SCHEDULEFLOW|1
NEXT|2|2
TASK|1|2000-01-02T09:00|60|Read
COMMITMENT|1|MONDAY|10:30|90|Class
```

`NEXT` holds the next task and commitment IDs, including after deletion.
Legacy TASK rows store ID, ISO local deadline, positive half-hour minutes and
name; they load with NORMAL priority. Commitments
store ID, full uppercase weekday, ISO local start time, duration and name.
Names are last so literal pipes need no escaping. Encoding preserves each
list's order and writes LF line endings. Decoding also accepts CRLF/CR and an
omitted final newline. Java's date/time parsers and existing domain constructors
validate the complete snapshot, including overlaps and counters. Past deadlines
remain valid. Invalid data raises `StorageException`
without returning a partial snapshot or modifying the file.

New writes without recurrence, bounded or skipped commitments use this version 2 layout:

```text
SCHEDULEFLOW|2
NEXT|4|2
TASK2|1|2026-11-20T18:00|45|NORMAL|Draft
TASK2|2|NONE|0|NORMAL|Completed|name with pipe
TASK2|3|NONE|2147483647|NORMAL|Undated
COMMITMENT|1|MONDAY|10:30|90|Class
HOURS|2026-11-20|09:00|18:00
HOURS|monday|08:00|20:00
```

`TASK2|id|deadline-or-NONE|exact-remaining-int|priority|name` uses uppercase
`NONE` only for an absent deadline, zero only for completion, and a required
HIGH/NORMAL/LOW priority. Names stay last and may contain pipes. The distinct
TASK2 discriminator avoids confusing legacy v2 names containing pipes with new
fields. Version 2 also accepts the previous TASK rows unchanged, including their
positive-half-hour validation; version 1 does not accept TASK2 or HOURS. Mixed
legacy/extended rows still share normal duplicate-ID/counter validation. `TASK2`
rows are also valid in versions 3 and 4, which add commitment bounds and skips,
and in version 5, which adds recurring templates and occurrence rows as described
above. Tasks and commitments retain stored order; HOURS rows sort by target.
Loading legacy data is read-only; the next snapshot save writes extended
version 2, 3, 4 or 5. Generating a plan instead writes version 6, or version 8
with a stored policy, as described below. FileStorage constructors and atomic replacement behavior are unchanged.

`StateCodecTest` covers the format and validation, including `TASK2` records
alongside bounded version 3 and skipped version 4 commitments. Planner tests
cover exact credited minutes and retained completion under the optional break
policy. `FileStorageTest` demonstrates
a save followed by a fresh reader, replacement, missing/corrupt files, and
deterministic filesystem failures with JUnit `@TempDir`. Run these tests with
`.\gradlew.bat test --tests "scheduleflow.storage.*"` from the repository root.
Unsupported-atomic-move and cleanup-failure injection remain untested.

Additional `FileStorageTest` coverage saves and reloads candidates from the
existing task and commitment services using fixed dates and temporary files.
It checks mixed additions/deletions, Unicode names, remaining minutes and both
ID sequences after deleting all records. A read-only restoration case checks
overdue records, stored order and byte-for-byte preservation of a valid CRLF
file without a final newline. These tests call storage directly, not the CLI.

Console integration loads once at startup, then saves
each candidate before `AppState.commit` and success output. `AppTest` verifies
restart and rollback alongside the storage tests. Use one writer per file;
atomic replacement does not promise durability after power loss.

### Stored study policy format (issue #55)

A snapshot with configured settings writes `SCHEDULEFLOW|7`, the full version 5
source layout plus exactly one `POLICY|maxContinuousMinutes|breakMinutes` row.
Missing, duplicate, nonpositive, unaligned and overflowing values reject the whole
file. Old snapshots load with an empty policy without rewriting data. Resetting
settings returns snapshot-only writes to versions 2-5 as appropriate.
Policy-bearing frozen plans use version 8 with the full version 7 source layout;
version 6 plans and all earlier supported formats remain readable. Constructors
retain their existing overloads and default to an empty policy. `withTasks`,
`withCommitments`, `withStudyHours` and recurring-task operations retain settings.
Only a policy command changes them.

### Automatic saved schedules (issue #68)

`PlanCodec` writes `SCHEDULEFLOW|6` without a study policy or `SCHEDULEFLOW|8`
with one. Version 5 remains available for recurring-task snapshots. The embedded
source uses the full version 5 layout (version 7 when a policy is present):
three NEXT counters, extended tasks, occurrence provenance, templates,
processing cursors, commitment bounds/skips and study hours. One
`PLAN|generatedAt|horizonEnd|sessionCount|unallocatedCount|breakCount` row follows,
then the counted rows:

```text
SESSION|taskId|start|end|exactWorkMinutes
UNALLOCATED|taskId|deadline-or-NONE|exactMinutes|capacityMinutes|reason|name
BREAK|start|end
```

Session work preserves partial-slot padding, and unallocated capacity is a long
so rounding an `Integer.MAX_VALUE` estimate cannot overflow. Names remain last
so literal pipes survive. Decoding validates the source, row counts, references,
ordering, horizon and individual planning-record contracts without regenerating
or rescheduling. Snapshot versions 1-5 and saved-plan version 6 remain readable.
The earlier branch's version 5
saved plans are distinguished by their two NEXT counters and read using the old
version 4 source layout; new saves use version 6 or 8 for plans.

`FileStorage.savePlan` writes the source and result in one atomic replacement.
`save` removes any prior plan while saving a mutation. At startup, `App` loads
and validates the complete file before restoring the matching plan; it does not
read the clock or materialize recurrence during restoration. Existing sessions,
generation time, unallocated work and reserved breaks are reused exactly.
Keep one running instance per data file; atomic replacement does not provide
coordination between concurrent writers.

`PlanCodecTest` covers exact work, optional deadlines, retained completion,
recurrence/provenance/counters, large capacity, breaks, pipe names, legacy files
and malformed rows. File and fixed-clock application tests cover one-file
restoration, read-only restart, materialization, mutation invalidation and failed
save rollback. UI-031 checks frozen schedules across packaged-process restarts;
UI-032 checks saved recurrence metadata with exact work and progress invalidation.

### Storage defensive checks and diagnostics

The Save-owned types document their inputs, failure contracts and private
validation helpers with Javadoc. `FileStorage.save` and `StateCodec.encode`
reject null snapshots with a named `NullPointerException` before filesystem
changes. Invalid saved text still raises `StorageException`, preserving the
original parsing or I/O cause where available; failed cleanup remains a
suppressed exception on the original save failure. `Snapshot` continues to
validate IDs, counters and weekly overlaps with runtime exceptions.

Java assertions check the codec's one-record-per-line invariant and the save
temporary file's distinct sibling location. They detect implementation defects,
not user-input errors, and perform no I/O or mutations. Gradle tests and `run`
enable assertions; use `java -ea -jar scheduleflow.jar` for a packaged launch.
Null, format and filesystem checks also work when assertions are disabled.

`FileStorage` and `StateCodec` use `java.util.logging` at `FINE` level for load,
decode, encode, directory preparation, temporary writing, atomic replacement
and cleanup. Success is recorded only after that stage succeeds. Storage-layer
failures are also `FINE` diagnostics; the application owns session severity and
user-facing error reporting. This avoids duplicate warnings and keeps default
console output unchanged. Storage log records omit paths, saved values and
exception payloads; the thrown exception retains its diagnostic details.
Constructors neither log nor perform I/O.

For storage-only diagnostics, use this local `logging.properties` file in a
launch folder outside the repository:

```properties
handlers=java.util.logging.FileHandler
.level=INFO
scheduleflow.storage.level=FINE
scheduleflow.cli.level=OFF
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=scheduleflow-storage-%u.log
java.util.logging.FileHandler.encoding=UTF-8
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

Launch with
`java -ea -Djava.util.logging.config.file=logging.properties -jar scheduleflow.jar`.
No logging handlers or global settings are installed by storage itself.

`FileStorageTest` captures logs in memory to verify stage ordering, missing and
invalid files, I/O failures, cleanup and absence of false success or stored
values in diagnostics. It restores logger settings in `finally` and uses
`@Isolated` to avoid leaking configuration to concurrent tests. Null-input
regressions check preserved bytes and no directory creation. Codec and snapshot
tests retain coverage of malformed text, counters, overlaps and immutable data.
Unsupported atomic moves and cleanup-failure injection remain untested.

### Java documentation

Use Javadoc for comments documenting types, constructors, methods and fields,
including private declarations and test helpers. Document purpose, constraints
and failure conditions; include parameter, return and exception tags when they
clarify the contract. If parameter tags are used, cover every parameter. The
Java standard's exceptions for obvious accessors, exact overrides and test
methods still apply, but any declaration documentation must use Javadoc.

Keep implementation explanations inside method bodies, local-variable comments,
ownership TODO markers and tooling directives as ordinary comments. Review
comment placement and accuracy alongside Checkstyle before committing. These
rules are required by the root `AGENTS.md` and do not change command behaviour.

### Commands and parsing

Issue #40 parser preparation recognizes `task progress TNUMBER done/MINUTES`
and `task progress TNUMBER session/MINUTES percent/PERCENT`. It returns
`RecordTaskWork` or `RecordTaskSession` transport values without reading state.
The dispatcher uses the Task service to validate and calculate a candidate,
then derives actual credited minutes from the old and new remaining estimates.
It requests a Printing confirmation before saving or publishing the candidate.
`TextRenderer.taskProgressUpdated` now supplies the confirmation, so changed
candidates save before publication; zero-credit candidates avoid an unnecessary
save or plan invalidation. Fixed-clock dispatcher tests cover actual credit,
successful publication, no-ops, failed-save preservation and retry. UI-030 covers
the existing parsed forms through the packaged application and saved restarts.

Issue #41 parses `task remaining TNUMBER remaining/MINUTES` into
`ReviseTaskRemaining` without reading state. The parser requires positive decimal
tokens; `TaskService.reviseRemaining` checks the ID and requires a positive
whole-minute estimate on an active task. It preserves the task's priority and
optional deadline, replaces only the estimate at its stored position, preserves
both counters, commitments and study hours, and
returns the original snapshot for an unchanged estimate. The dispatcher prepares
the candidate and its confirmation before saving. Changed candidates save
before live publication and plan invalidation; unchanged candidates report the
stored value without saving or clearing the plan. `TextRenderer.taskRemainingUpdated`
formats the stable ID, name and new minutes without claiming that an unchanged
value was saved again. A failed save preserves the live snapshot and plan.
Parser, service, renderer and dispatcher JUnit tests cover syntax, values,
successful publication, no-op behavior and rollback. The Task regression saves
a revised candidate to an isolated file, reloads it and verifies higher planning
demand. UI-019 checks exact console responses, plan invalidation, replanning and
two restarts against a fixed overdue task.

`CommandParser.parse` implements all command formats in the User Guide and
returns the existing nested `Command` records. The parser has no clock, storage
or application-state dependency. `schedule today` produces `Command.ScheduleToday`;
the dispatcher resolves its date from the time captured for command execution.

After stripping outer whitespace, the parser recognizes the command words and
matches the entire input. Field patterns allow ordinary spaces and multiword
Unicode names, while excluding slash from names so duplicate, reordered and
unknown prefixes cannot be swallowed as name text. Embedded control characters
are rejected. Quotes remain literal characters. Names are stripped at their ends
without changing internal spaces.

Dates and times use strict `uuuu-MM-dd`, `HHmm` and `uuuu-MM-dd HHmm` formatters
with `Locale.ROOT`. Fixed-width ASCII tokens and an explicit year-zero rejection
limit dates to years 0001–9999. Positive decimal integer tokens reject signs,
leading zeroes and non-ASCII digits; overflow is translated to `ParseException`.
Each parse failure includes the recognized command's exact usage, or
`Type help to see available commands` for an unknown command. A direct blank
parser input is rejected; the App loop ignores blank lines before parsing.

Parsing does not create domain records or validate nonblank names, duration
multiples, commitment alignment/end times, future deadlines, overlaps or ID
existence. Those remain the responsibility of the existing domain constructors
and services. No other member's implementation or shared public API is changed.
The existing command records and `ParseException` satisfy the parsing contract.
Application integration uses these existing parser and service boundaries.

`CommandParserTest` verifies every command, all weekdays, multiword/Unicode names,
whitespace, strict leap dates and year boundaries, times, field rejection,
numeric limits, case sensitivity, usage hints and unresolved `today`. These
tests use fixed values and require no disk data or running application.

### Text rendering

`TextRenderer` formats help, task and commitment success messages, lists, plan
results, schedules, errors, the absent-plan message and goodbye. It returns
LF-separated strings without a trailing newline and performs no direct console
I/O, clock reads or state changes. Optional stage diagnostics use the logger
configuration described below. Task rows
preserve the service's supplied order and stored duration. Commitment groups
appear Monday through Sunday, preserve supplied order within each group and
omit empty weekdays. Names remain visible in full, including Unicode.

Date/time formatters use `uuuu-MM-dd HH:mm` and `HH:mm` with `Locale.ROOT`;
weekday names explicitly use English. Commitment endpoints use
`Commitment.endMinuteOfDay()` so midnight displays as `24:00` without wrapping.

Plan summaries distinguish empty task lists, complete results and partial
allocation (including zero scheduled minutes). Scheduled and unallocated totals
use `mapToLong` over all plan rows. A private helper formats the unallocated task
rows and maps all three reasons to the required English text; schedule footers
reuse it without filtering by the selected date.

Schedules display `ScheduleView` metadata and entries in their supplied order.
TASK minutes come from that row's endpoints, rather than the task's whole
estimate. BUSY retains its reference and name; FREE, UNPLANNED and BREAK have
only their labels. BREAK is renderable through the planning API; the default
console planner uses the stored study policy when present. Any UNPLANNED row adds the
cutoff note. A nonempty remainder list adds a
blank line and the whole-plan footer. The formatter never allocates, clips,
merges, repairs or reorders schedule rows, and never recalculates generation
time or date ranges. Range validation remains with `ScheduleService`; the
dispatcher handles absent plans before schedule projection.

`TextRendererTest` checks messages, fields/order, long/Unicode names, midnight,
empty/complete/partial plans, all reasons, totals above `Integer.MAX_VALUE`,
multiple planning dates, all schedule labels, session durations, preserved row
boundaries, frozen metadata, footers and LF/no trailing newline. Fixed records
avoid live clocks and saved data. The corrected schedule example also runs
through the existing planner and projection APIs before formatting.


### Recurring commitment addition

`CommitmentService.add` creates an immutable candidate snapshot. It reuses
`Commitment` for name, weekday, duration and interval validation, checks overlaps
against the full weekly intervals (including outside study hours), and reports
every conflicting commitment's ID and name in start-time order, with numeric ID
as a tie-breaker. The private overlap check collects matches before rejecting;
it never reorders the source snapshot. Single-conflict wording is unchanged,
and multiple conflicts are separated by semicolons. Adjacent intervals and duplicate
names at nonoverlapping times are allowed.

Regression tests verify multi-conflict ordering, adjacency and other-day exclusion,
full-day intervals, duplicate names, unchanged snapshots and unconsumed retry IDs.
UI-013 checks the same rejection through the packaged application and a saved restart.

The service uses the stored next commitment ID, preserves task records and their
counter, and increments only the candidate's commitment counter. A next ID of
`Integer.MAX_VALUE` rejects addition because the next counter cannot be represented.
Neither success nor failure changes the original snapshot. The dispatcher must
save the candidate before publishing it through `AppState.commit`; persistence
and plan invalidation are not performed by this service.

`CommitmentServiceTest` covers normal additions, all weekdays, adjacency,
overlap shapes and diagnostics, midnight/full-day commitments, invalid inputs,
ID exhaustion, unchanged source data and discarded-candidate retries. A fixed-date
planner integration test checks that an added commitment blocks study slots.
Run `.\gradlew.bat test --tests scheduleflow.commitment.CommitmentServiceTest`.
The dispatcher and console tests also exercise these operations.

### Commitment editing (issue #42)

`CommitmentService.edit` accepts an existing numeric ID and nullable replacement
fields: name, weekday, start and boxed duration. Null means omitted; a blank name
is not an omission. It rejects empty edits and unknown/nonpositive IDs, resolves
omissions from the original record, and constructs one validated replacement.
The existing model and overlap helper remain the validation authorities. Only
the target ID is excluded from conflict checks. Stored position, other records,
tasks and both counters are preserved, even when the next ID is exhausted.
No delete/add intermediate state or storage-format change is required.
The edit candidate explicitly carries the source study-hours settings; using the
legacy four-argument snapshot constructor here would silently restore defaults.
A regression test round-trips both weekday and dated settings after editing.
UI-014 also checks settings survive an edit and a fresh process restart.

`Command.EditCommitment` transports partial values. The parser accepts subsets
in `n/`, `day/`, `start/`, `d/` order with the existing strict weekday/time/integer
rules. It rejects repeated, reordered and unknown fields. Domain validation,
including empty-edit rejection, remains in the service. The dispatcher resolves
the replacement before the existing save-and-commit transaction and reports it
through `TextRenderer.commitmentEdited`. Even identical-value valid edits save
and invalidate the saved plan, consistently with successful mutations.

Tests cover all fifteen nonempty field subsets, invalid syntax, immutable source
snapshots, self-exclusion, multi-conflict ordering, adjacent and midnight bounds,
counter exhaustion, rollback/retry and changed allocation after regeneration.
UI-014 verifies the packaged command, Unicode edits, rejection recovery and restart.
Changes outside the commitment domain are limited to this command's transport,
integration, output and acceptance tests.

### Recurring commitment date bounds (issue #64)

`Commitment` optionally stores a paired inclusive `startDate` and `endDate`.
Both null means an unbounded legacy series. A bounded range must be ordered and
contain at least one occurrence of the commitment weekday. `occursOn` is the
single calendar-membership check used by allocation and schedule projection.
Overlap requires intersecting times, the same weekday and at least one shared
occurrence; therefore separate semesters may safely reuse a weekly time slot.

Addition and editing accept the ordered optional suffix
`from/YYYY-MM-DD until/YYYY-MM-DD`. The parser requires both fields and strict
four-digit dates. Omitted edit bounds preserve the stored range. Success and list
output show inclusive dates only for bounded series, retaining existing text for
unbounded records. Version 3 storage writes two endpoint fields on every
commitment row and uses `-|-` for unbounded rows in the same file. Versions 1 and
2 remain readable, and stores without recurrence or bounded/skipped commitments
use version 2 on new saves.

JUnit checks model boundaries, disjoint and intersecting semester overlaps,
candidate immutability, parser syntax, version 3 round trips, planning and daily
projection. UI-016 verifies validation, editing, restart persistence, conflict
reporting and unconsumed retry IDs through the packaged application.

### Skipping one recurring occurrence (issue #66)

`Commitment` stores an immutable set of skipped calendar dates. `recursOn`
checks the weekday and optional inclusive bounds without exceptions, while
`occursOn` additionally excludes skipped dates. The constructor rejects a
skipped date outside the series or on another weekday. Editing retains the set,
so an edit that would move an existing exception outside the resulting series
is rejected instead of silently losing it.

`CommitmentService.skip` resolves the stable ID, rejects non-occurrence and
duplicate dates, and replaces the record at its original stored position. The
candidate preserves tasks, study hours and both ID counters. Printing saves the
candidate before publishing it or returning `Skipped CNUMBER on DATE: NAME`;
therefore save failure leaves the live snapshot, current plan and saved data
unchanged. The command grammar is `commitment skip CNUMBER on/YYYY-MM-DD`.

Overlap checks search the finite range whenever either series is bounded and
require an unskipped shared occurrence. Two unbounded series always share a
future occurrence because each stores only finitely many skipped dates. This
allows another bounded commitment to use a cancelled date without weakening
conflict checks on the remaining dates. The planner and schedule projection
already use `occursOn`, so they require no separate exception logic.

Version 4 storage adds one comma-separated skipped-date field to every
commitment row, using `-` when a series has no exceptions. Dates are encoded in
chronological order. Versions 1 through 3 remain readable, and the encoder
selects version 2, 3 or 4 based on commitment fields unless recurrence requires
version 5, while retaining `TASK2`
rows in every new save. Model, service,
parser, dispatcher, renderer, codec and planning regressions cover valid,
invalid and rollback paths. UI-017 verifies exact output, restart persistence,
released overlap and unconsumed IDs through the packaged JAR.

### One-time commitments (issue #53)

A one-time commitment is a `Commitment` whose inclusive `startDate` equals its
`endDate`; its `day` is that date's weekday. `Commitment.isOneTime()` exposes the
distinction. No record component, service operation, command type or storage
version was added: the design reuses the issue #64 date-range model, so
`occursOn` already blocks only that date in `EarliestDeadlinePlanner` and
`ScheduleService`, and `overlaps` already compares the actual occurrence with
recurring series (including skipped dates) and other one-time commitments.
Version 3 rows such as `COMMITMENT|1|FRIDAY|14:00|60|2026-10-30|2026-10-30|NAME`
store it; skipped dates require at least version 4 and recurrence requires version 5.
Older stores remain readable unchanged.

The alternative of an explicit kind flag would need a new record component,
constructor overloads and another format extension while behaving identically. The
trade-off is that a weekly series entered with the same `from/` and `until/`
date is shown as one-time, which matches its single occurrence. The packaged
UI-017 expectation for that input was updated accordingly.

`CommandParser` accepts `on/YYYY-MM-DD` in the `day/` position of
`commitment add` and `commitment edit`, and maps it to an ordinary
`AddCommitment` or `EditCommitment` with the derived weekday and equal dates.
Combining `on/` with `from/`/`until/` is a parse error; `day/` and `on/` are
mutually exclusive in the grammar. Existing save-before-publish dispatch and
ID allocation therefore apply unchanged: rejected input consumes no ID.

`CommitmentService` adds the domain rules that differ from weekly series.
`skip` rejects one-time commitments, and the constructor rejects a one-time
record whose only date is skipped, so corrupt saved data cannot hold a
commitment that never occurs. `edit` rejects `day/` alone on a one-time
commitment because its weekday follows its date, and discards skipped dates
when an edit produces a one-time result. `TextRenderer` shows
`One-time on DATE (Weekday) HH:mm - HH:mm (N min)` in add/edit confirmations,
and `commitments` lists one-time records in a separate section ordered by date
after the weekly groups, preserving the service's start/ID order within a date.

Model, service, parser, renderer, dispatcher (failed-save rollback and retry),
codec, planner and schedule tests cover exact-date blocking, overlap with both
kinds, adjacency, invalid input and restart persistence. UI-018 verifies the
packaged command flow, validation messages, restart, edit, delete and ID reuse
rules.

### Commitment deletion and listing

`CommitmentService.delete` removes an entire weekly series by its stable numeric
ID, returning an immutable candidate while preserving tasks, other commitments,
their stored order and both ID counters. Nonpositive and unknown IDs raise
`ValidationException`. Deleting the last series does not reset the counter.
`list` returns an immutable Monday-to-Sunday view sorted by start time and then
numeric ID, without reordering the snapshot or renumbering records. Valid
snapshots cannot contain two commitments at the same weekday and start time,
because their positive durations would overlap; the ID comparator is a final
ordering safeguard.

Defensive checks distinguish invalid user IDs from programmer errors (null
snapshots). Runtime validation works independently of Java assertions.

Additional JUnit tests cover deletion by ID, missing/invalid IDs, counter retention,
immutable sorted lists and null preconditions. Fixed-date planner checks verify
that deleting a weekly series releases time on successive Mondays. A `@TempDir`
storage integration test saves, deletes and reloads a series, checking that its ID
is not reused. These direct service checks do not certify CLI restart or dispatch.

### Commitment defensive checks and diagnostics

Commitment-owned APIs document parameters, results and failure contracts.
Services reject null snapshots with a named `NullPointerException`; invalid
fields, overlaps, exhausted IDs and invalid deletion IDs raise
`ValidationException`. The model distinguishes a nonpositive ID, missing weekday
and missing start time, in that validation order. Name/duration and interval
validation follow those checks. These runtime checks remain active without assertions.
The commitment service constructor performs no logging or I/O. The model
delegates name and duration checks to the shared validators, which own their
separate opt-in diagnostics; it installs no logging handlers.

Assertions verify addition preserves existing series and advances only the
commitment counter, deletion removes the requested series while preserving tasks
and both counters, and listing retains every series. The model checks that a
computed endpoint stays within its validated day. These checks detect internal
defects; they do not replace input validation or modify data. Gradle `run` and
`test` enable assertions; use `java -ea -jar scheduleflow.jar` for a packaged run.

`CommitmentService` uses opt-in `FINE` logs for add, overlap checking, candidate
creation, deletion, listing and rejected validation. Messages omit names,
schedules, IDs and exception payloads. Candidate completion explicitly does not
claim persistence or publication. The service installs no logging configuration;
normal console responses remain unchanged.

For commitment-only diagnostics, put this configuration in a local
`logging.properties` outside the repository:

```properties
handlers=java.util.logging.FileHandler
.level=OFF
scheduleflow.commitment.level=FINE
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=scheduleflow-commitment-%u.log
java.util.logging.FileHandler.encoding=UTF-8
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

Launch with
`java -ea -Djava.util.logging.config.file=logging.properties -jar scheduleflow.jar`.
JUnit captures diagnostic order, rejection without false success and absence of
private values with isolated in-memory handlers, restoring logger settings in
`finally`. Existing boundary and immutable-candidate tests exercise the
assertions. Run `./gradlew test --tests 'scheduleflow.commitment.*' --tests 'scheduleflow.model.CommitmentTest'`.

### Command dispatch and live-state transactions

`CommandDispatcher.execute` routes every nested `Command` type and captures
`LocalDateTime.now(clock)` exactly once, including for `schedule today`.
Mutation services validate and return candidates. The dispatcher resolves the
added or removed record, calls `Storage.save`, then calls `AppState.commit` and
formats success. A failed save propagates without publishing the candidate,
consuming an ID or clearing the current plan. Existing command and service
interfaces are preserved; issue #68 adds `loadPlan` and `savePlan` to Storage.

Help, lists, schedule and exit preserve state and make no save call. Planning
saves and replaces the frozen plan; partial and empty results remain usable.
Schedule requests project that frozen plan; absence takes precedence over date
validation. Exceptions from domain services remain their owners' validation.

`CommandDispatcherTest` uses fixed clocks, a counting clock and fake storage
to verify all routes, mutation transaction paths and retries, save ordering,
deadline boundaries, numeric IDs, partial/blocked plans, cutoff precision,
range validation, zone-aware today and frozen/replaced plans.

### Application lifecycle and executable entry point

`App.run` loads the snapshot and any matching saved plan before prompting, and
stops with status 1 if storage rejects either load. Each loop prompts and reads a line, ignores blanks, parses, dispatches
and prints the response. Parse errors include usage; domain and save errors
print one error and allow another command. Input failure returns 1; exit and
EOF return 0 with goodbye and no save. Unexpected programming exceptions
propagate. Constructors do no I/O, and process termination stays in `Main`.

`ConsoleUi` flushes the exact `scheduleflow> ` prompt without a newline and
flushes each response with a final newline. `Main` supplies explicit UTF-8
streams, `Clock.systemDefaultZone()` and `FileStorage` at
`data/scheduleflow.txt` relative to the launch folder. Extra arguments print
`Error: Usage: java -jar scheduleflow.jar (no arguments)` and exit 1 before
loading. The existing Shadow JAR manifest already names `scheduleflow.Main`.
Build with `.\gradlew.bat shadowJar`; output is `build/libs/scheduleflow.jar`.

`AppTest` covers empty/corrupt startup, load counts, syntax/domain/save recovery,
unchanged file bytes on a rejected save, stable restart IDs, overdue tasks,
empty-plan clipping/midnight output, invalidation and input failure.
`ConsoleUiTest` checks framing, flushes, Unicode lines, EOF and read errors.
`MainTest` runs separate Java processes in temporary folders to check status,
argument rejection, UTF-8 under ASCII JVM defaults and persisted restarts.
Active process UI cases run the packaged JAR with exact per-command assertions.

### Defensive boundaries and diagnostics

Public console APIs reject null dependencies and arguments with named
`NullPointerException` messages. `TextRenderer` also rejects null list elements
before assembling text, and `ParseException` requires a message and usage hint.
These checks remain active without Java assertions. Domain validation stays in
the existing services; command records continue to carry values without
duplicating their business rules.

`AppState.replacePlan` rejects a plan whose source differs from the current
snapshot with `IllegalArgumentException`, preserving any existing plan. An
equal snapshot from another instance is accepted. The dispatcher rejects a null
planner result before publication. Missing records after validated service
operations raise descriptive `IllegalStateException` failures rather than an
unexplained lookup error. These programming failures propagate to the caller.

Assertions check complete grammar matches, save-before-publication ordering,
plan invalidation and live-plan source consistency. Gradle `test` and `run`
enable assertions; use `java -ea -jar scheduleflow.jar` for a packaged launch.
Assertions supplement runtime checks and never replace input validation.

`java.util.logging` classifies diagnostics by their effect on the session:

- `FINE` records detailed input, parsing, dispatch, validation, saving,
  publication and formatting stages.
- `INFO` records session startup and normal termination after exit or EOF.
- `WARNING` records rejected syntax, domain validation and save failures that
  allow the user to try another command.
- `SEVERE` records startup-load and input failures that stop the session.

A failed save never produces a successful publication record. Failure records
retain their original exception, so a file handler can record its cause and
stack trace alongside the severity.

For a default executable launch, `Main` sets the `scheduleflow.cli` logger
namespace to `OFF`, preserving exact stdout and empty stderr even for errors.
An explicit `java.util.logging.config.file` or `java.util.logging.config.class`
setting, or a configured `scheduleflow`/`scheduleflow.cli` namespace level,
takes precedence. Other component logger settings are preserved. Code that
embeds `App` directly uses the caller's normal Java logging configuration.
Stage messages omit raw command text, names and formatted output; failure
records may include the exception and its diagnostic details.

To enable diagnostics without interleaving them with console responses, create
a local `logging.properties` file in the launch folder:

```properties
handlers=java.util.logging.FileHandler
.level=INFO
scheduleflow.level=FINE
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=scheduleflow-%u.log
java.util.logging.FileHandler.encoding=UTF-8
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

Launch with
`java -ea -Djava.util.logging.config.file=logging.properties -jar scheduleflow.jar`.
To record only warnings and failures, change both `scheduleflow.level` and
`java.util.logging.FileHandler.level` to `WARNING`; `SEVERE` records are still
included while `INFO` and `FINE` are filtered out.
Keep this local configuration and generated logs outside the repository.
Constructors emit no stage logs or I/O.

Regression tests cover null inputs/elements, stale and equal-source plans,
invalid planner results, preserved state on rejection and diagnostic order on
successful and failed transactions. `AppTest` captures records in memory,
restores logger settings in `finally` and uses `@Isolated` to protect other
tests. It checks severity classification, retained exceptions and filtering at
the `WARNING` threshold. `MainTest` checks quiet default launches for recoverable
and fatal errors, and an explicit warning-only file configuration. Existing
exact-output tests and all maintained active packaged scenarios verify normal
console behaviour. The User Guide's command and error behaviour remains valid.

### Supplied contract and Save-module differences

The supplied implementation contract's section 7 specifies Base64 names before
the date fields, three-letter weekdays, canonical numeric/date tokens, sorted
encoded rows and `FileChannel.force(true)`. The committed Save module uses
plain names last, full weekday names, Java token parsers, snapshot order and
`Files.writeString` before atomic replacement. Printing preserves that existing
module and its public APIs; the format documented above and tested restarts
use the committed codec. Contract-format interoperability and forced flushing
require coordination with the Save owner and are outside this printer change.

## Product scope
### Target user profile

University students who need to fit unfinished coursework around weekly
classes and other recurring commitments, and who are comfortable entering
commands in a terminal.

### Value proposition

Turn remaining-work estimates, deadlines and weekly busy intervals into a
study schedule. Report work that cannot fit so students can revise their
estimates or commitments before the deadline.

## User Stories

|Version| As a ... | I want to ... | So that I can ...|
|--------|----------|---------------|------------------|
|Current|new user|see command help|refer to the supported syntax when I need it|
|Current|student|add and list tasks with deadlines and estimates|keep track of unfinished coursework|
|Current|student|add and remove recurring commitments|reserve my weekly busy periods|
|Current|student with project meetings and other fixed events|add one-time commitments|keep those periods unavailable for studying|
|Current|student|generate a plan and view a date's schedule|find available time before my deadlines|
|Current|student|see unallocated work|recognize when my available time is insufficient|
|Current|returning user|restore saved tasks and commitments|continue without reentering my data|

## Non-Functional Requirements

- Run with Java 25 and UTF-8 source, saved data and console streams.
- Keep runtime operation local and offline, with no external service required.
- Save each successful mutation by atomic replacement before publishing it in
  memory or printing success. Use one running instance per saved-data file.
- Reject invalid input and corrupt saved data without publishing partial state.
- Keep normal diagnostics quiet; allow opt-in logs through Java logging.
- Verify behavior with deterministic JUnit tests and isolated console sessions.
- Atomic replacement requires filesystem support and does not guarantee
  durability after power loss.

## Glossary

* *Task* - A retained item with an optional deadline and exact remaining work; zero means complete.
* *Commitment* - A busy interval repeated on one weekday, optionally within
  inclusive dates, or a one-time busy interval on a single date.
* *Snapshot* - Immutable tasks, commitments, study hours and independent next-ID counters.
* *Plan* - Frozen allocations and unallocated work derived from a snapshot, saved until a successful mutation.
* *Planning horizon* - The calendar range ending at midnight on the same date
  next year, with February 29 adjusted to February 28 when necessary.
* *Slot* - A 30-minute interval within the daily study window.

## Instructions for manual testing

### JUnit tests

Add or update JUnit 5 tests alongside new or changed Java behaviour, including
regression tests for bug fixes. Assert observable behaviour for relevant valid,
invalid and boundary inputs. Documentation-only changes require a coverage
review rather than redundant tests. The root and test `AGENTS.md` files make
these requirements part of the agent workflow.

Tests mirror production paths and packages: the SUT
`src/main/java/scheduleflow/cli/TextRenderer.java` is tested by
`src/test/java/scheduleflow/cli/TextRendererTest.java`, both in
`scheduleflow.cli`. Matching packages permit package-private access without
making production members public. Use the `SutNameTest` class naming convention;
the Java standard permits test names such as `tasks_empty_returnsExactMessage`.

Keep test time and data controlled. `TextRendererTest` includes exact-output
checks for years 0001 and 9999, a leap-day view generated on a different date,
and French/Arabic default locales. Its locale test restores all default locale
categories in `finally`; JUnit's `@Isolated` annotation prevents the class from
running concurrently with other tests while JVM defaults are changed.

### Build and console checks

Run checks from the repository root with Java 25 and Node.js 22 or later:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
node --test test/release.test.mjs
```

For the parser alone, use
`.\gradlew.bat test --tests scheduleflow.cli.CommandParserTest`.
For the formatter alone, use
`.\gradlew.bat test --tests scheduleflow.cli.TextRendererTest`.
On Unix, replace `.\gradlew.bat` with `./gradlew`. If the default Gradle cache
is unwritable, set `GRADLE_USER_HOME` to an accessible cache.

The process UI cases in [the test plan](../test/ui-test-plan.md) are active.
They verify empty startup, commitment transactions/restart, EOF, syntax/domain
recovery, Unicode and corrupt startup through the packaged JAR. Fixed-clock
JUnit cases verify task and planning acceptance through injected APIs. The
runner uses isolated temporary data and writes evidence under
`build/ui-transcripts/`. No production clock flag was added. Unsupported atomic
replacement and injected temporary-write failures remain unexecuted release
checks; existing storage tests cover deterministic replacement failure.

### Release packaging and verification

`build` runs the Java tests and Checkstyle and creates
`build/libs/scheduleflow.jar` through `shadowJar`. The JAR's manifest entry
point is `scheduleflow.Main`; its runtime classes are included so it needs
only Java 25 when distributed. Test dependencies stay outside the JAR.

Assertions are explicitly enabled for Gradle `test` and `run`.
`applicationDefaultJvmArgs = ['-ea']` also enables them in generated Windows
and Unix launchers. A standalone JAR launch needs
`java -ea -jar scheduleflow.jar`; JVM assertion settings are not embedded in
the JAR itself.

The lean `jar` task remains disabled. Normal `startScripts` use the Shadow
JAR as their classpath, and the main distribution includes that same JAR in
`lib`. Without this wiring, `distZip` and `distTar` contain launchers referring
to a missing lean JAR and fail to start. Shadow distributions continue to use
the executable JAR as well.

After `build`, run `node --test test/release.test.mjs`. It requires Java 25,
launches the actual JAR with assertions for all maintained active fixtures,
and extracts the normal ZIP in temporary storage. It verifies that the ZIP
contains the exact release JAR, both launcher scripts default to `-ea`, and
the current platform's launcher reaches the prompt and exits normally without
creating data. The script writes a separate transcript under
`build/ui-transcripts/` and prints its exact path. The local console skill
also runs the same fixtures without `-ea`, checking that user validation works
independently of assertions. Keep both transcripts before running `clean`.

Packaged cases additionally cover restored overdue and out-of-horizon tasks,
numeric listing, deletion/restart, exhausted ID sequences, overlapping saved
commitments and duration totals beyond a 32-bit integer. These complement the
fixed-clock JUnit planning and transaction checks; they do not inject a clock
into the production entry point or exercise every filesystem provider failure.
`EarliestDeadlinePlannerTest` additionally checks a partial allocation split
across two study days and every future study window through the final date of
a February 29 horizon. Both use fixed dates, verify the unscheduled remainder
and confirm that stored estimates remain unchanged.
