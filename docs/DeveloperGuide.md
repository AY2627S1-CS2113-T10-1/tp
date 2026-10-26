# Developer Guide

> **Guide status:** Draft for the upcoming v2.0 release, describing source
> revision `99a50dd` and the candidate `build/libs/scheduleflow.jar`. No v2.0
> tag or published JAR exists yet. The latest published release checked on
> 26 October 2026 is [v1.0](https://github.com/AY2627S1-CS2113-T10-1/tp/releases/tag/v1.0);
> its JAR does not include all enhancements described here. Reconcile this
> guide with the release tag and artifact before publication.

ScheduleFlow is a Java 25 console application for university students planning
coursework around fixed commitments. For commands and user examples, see the
[User Guide](UserGuide.md).

## Contents

- [Acknowledgements](#acknowledgements)
- [Setting up, getting started](#setting-up-getting-started)
- [Design](#design)
  - [System overview and collaboration boundaries](#system-overview-and-collaboration-boundaries)
  - [Console application and presentation](#console-application-and-presentation)
  - [Task model and command parsing](#task-model-and-command-parsing)
  - [Commitments and command execution](#commitments-and-command-execution)
  - [Planning, schedule projection and recurrence](#planning-schedule-projection-and-recurrence)
  - [Persistence and the Snapshot aggregate](#persistence-and-the-snapshot-aggregate)
- [Implementation](#implementation)
  - [Recoverable console interaction and deterministic rendering](#recoverable-console-interaction-and-deterministic-rendering)
  - [Exact task work and retained task lifecycle](#exact-task-work-and-retained-task-lifecycle)
  - [Dated commitments and save-before-publication dispatch](#dated-commitments-and-save-before-publication-dispatch)
  - [Slot allocation under study hours/policy and recurrence](#slot-allocation-under-study-hourspolicy-and-recurrence)
  - [Atomic persistence and restoring frozen plans](#atomic-persistence-and-restoring-frozen-plans)
- [Documentation, logging, testing and dev-ops](#documentation-logging-testing-and-dev-ops)
- [Appendix A: Product Scope](#appendix-a-product-scope)
- [Appendix B: User Stories](#appendix-b-user-stories)
- [Appendix C: Non-Functional Requirements](#appendix-c-non-functional-requirements)
- [Appendix D: Glossary](#appendix-d-glossary)
- [Appendix E: Instructions for Manual Testing](#appendix-e-instructions-for-manual-testing)
  - [Launch, help, exit and console behavior](#launch-help-exit-and-console-behavior)
  - [Tasks and input validation](#tasks-and-input-validation)
  - [Commitments and unavailable periods](#commitments-and-unavailable-periods)
  - [Planning, schedules, hours, policy and recurrence](#planning-schedules-hours-policy-and-recurrence)
  - [Saving, restoration, failure and recovery](#saving-restoration-failure-and-recovery)

## Acknowledgements

The build uses [Gradle](https://gradle.org/),
[Shadow](https://gradleup.com/shadow/) for executable JAR packaging,
[JUnit 5](https://junit.org/junit5/) for Java tests and
[Checkstyle](https://checkstyle.org/) for coding-standard checks. Versions are
declared in `build.gradle` and `gradle/wrapper/gradle-wrapper.properties`.
The repository-local skills attribute the SE Education coding and Git
conventions and CS2113 code-quality guidelines used by this project.

This guide adapts the organization and enhancement-explanation pattern of the
[AddressBook Level 3 Developer Guide](https://se-education.org/addressbook-level3/DeveloperGuide.html).
This is documentation-structure inspiration, not reuse of AB3 application
components. UML sources are maintained using
[PlantUML](https://plantuml.com/), following the
[SE Education tutorial](https://se-education.org/guides/tutorials/plantUml.html).

## Setting up, getting started

Import the repository into IntelliJ as a Gradle project and select SDK 25.
Keep Java source encoding at UTF-8. Use the committed Gradle wrapper; Node.js
22 or later is needed for process tests, not for running the application.
See [build and verification](#build-packaging-and-console-verification) for
commands and [manual testing](#appendix-e-instructions-for-manual-testing)
for isolated JAR launches.

## Design

The following views separate responsibilities in the existing packages; the
Implementation section traces the workflows that connect them.

### System overview and collaboration boundaries

| Area | Main entry points | Code owner |
| --- | --- | --- |
| Application shell and presentation | `Main`, `App`, `AppState`, `ConsoleUi`, `TextRenderer`, `Command`, `CommandResponse` | Ka How (`ka-how`) |
| Tasks and all command parsing | `TaskService`, `TaskEdit`, `Task`, `CommandParser`, `ParseException`, shared validators | Nickhil (`nickpatt`) |
| Commitments and all command dispatch | `CommitmentService`, `Commitment`, `CommandDispatcher` | Darren (`Darren50`) |
| Planning, hours, policy and recurrence | `planning/`, `StudyHours`, `StudyWindow`, `RecurringTaskService`, `RecurringTask`, `TaskOccurrence` | Gabriel (`GabrielYuSP`) |
| Persistence and aggregate validation | `storage/`, `Snapshot` | Ernest (`ernestgoh-god`) |

#### Code authorship

The whole-file `@@author` tags and the agreed version 2 allocation define
ownership, including nested types and helpers. Cross-component calls do not
split ownership. Tags record the allocation, not historical authorship. See
[source ownership](../src/main/java/scheduleflow/AGENTS.md),
[test ownership](../src/test/AGENTS.md) and the
[RepoSense tag reference](https://reposense.org/RepoSense/ug/usingAuthorTags.html).

### Console application and presentation

The shell connects text interaction to the domain without putting console I/O
inside services. The class view shows Ka How's seven production types and the
external collaborators needed to understand the command path. Associations
denote retained references; dashed dependencies denote creation or use.
`...` marks omitted parameters. Service, codec, clock and transport-use
dependencies are omitted to keep this view focused.

![Console shell classes, command transports and external collaborators](images/ka-how-console-classes.svg)

[PlantUML source](diagrams/ka-how-console-classes.puml)

[`Main`](../src/main/java/scheduleflow/Main.java) supplies UTF-8 streams,
`Clock.systemDefaultZone()` and `FileStorage` for `data/scheduleflow.txt` relative
to the launch folder. It creates `App` and turns its returned status into process
termination. `App` receives `Storage`, `Clock` and `ConsoleUi`, then constructs
the parser, renderer and dispatcher with their services. Constructors do no I/O;
loading begins in `run()`. `AppState` is local to that run, rather than an `App`
field, so command execution receives the session's live state explicitly.

[`ConsoleUi`](../src/main/java/scheduleflow/cli/ConsoleUi.java) owns line reads,
prompt/response writes and flushing.
[`TextRenderer`](../src/main/java/scheduleflow/cli/TextRenderer.java) returns
formatted text; it does not read the clock, perform console I/O or mutate state.
[`AppState`](../src/main/java/scheduleflow/cli/AppState.java) retains a `Snapshot`
and an optional matching `Plan`. `commit(saved)` publishes a saved candidate and
clears the plan. `replacePlan(plan)` accepts an equal source snapshot, rejecting
a stale source before changing state, even with assertions disabled.

[`Command`](../src/main/java/scheduleflow/cli/Command.java) is a sealed interface
whose nested records carry requests, for example `Help`, `AddTask` and
`ScheduleToday`. These values have no execution method: syntax belongs to
Nickhil's parser and domain rules to the services. `CommandResponse(output,
shouldExit)` carries the dispatcher's formatted result and termination decision
back to `App`. The diagram shows only representative records; the two unavailable
range transports exist but their console routes remain unfinished.

### Task model and command parsing

`TaskService` returns immutable mutation candidates. `Task`, `TaskPriority`,
`TaskEdit` and `InputRules` express task structure and validation.
`CommandParser` converts input to the nested `Command` records without
reading time, state or storage; `ParseException` carries the usage hint.

### Commitments and command execution

`Commitment` defines occurrence membership and overlap. `CommitmentService`
returns validated candidates; `CommandDispatcher` coordinates services,
storage and rendering, saving changes before publishing live state.

### Planning, schedule projection and recurrence

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
window. Each slot is classified as busy, assigned to a task, reserved for a
break, before the generation cutoff, or free, in that order. Merging preserves
commitment and task identities. `Plan`, `StudySession`, `UnallocatedWork`, `ScheduleEntry` and
`ScheduleView` validate their own fields and retain immutable result lists;
allocation relationships remain the planner's responsibility. Fixed-date
planner and projection tests verify deadline order, conservation of minutes,
cutoff rounding, clipping, midnight commitments and allowed schedule dates.

`Planner` is implemented by `EarliestDeadlinePlanner`. `TimeRules` defines
cutoff and horizon calculations. `StudyHours`/`StudyWindow` supply daily
availability and `StudyPolicy` supplies optional work/break limits. The
dispatcher calls `RecurringTaskService` before planning; the allocator does
not generate templates or occurrences itself.

### Persistence and the Snapshot aggregate

`FileStorage` implements `Storage`, delegating source text to `StateCodec`
and frozen-plan text to `PlanCodec`. `Snapshot` validates the complete
immutable aggregate, including IDs, counters, overlaps, recurrence, hours
and policy. It is distinct from the mutable session holder `AppState`.

## Implementation

These sections describe the important state transitions and failure
boundaries. Proposed console operations are explicitly labelled.

### Recoverable console interaction and deterministic rendering

A mistyped command should leave the student able to correct it in the same
session. The shell implements this by distinguishing recoverable command
failures from failures that prevent the session from continuing. Its starting
points are [`App.run()` and `runCommands()`](../src/main/java/scheduleflow/cli/App.java).

#### Startup and the command loop

`run()` calls `Storage.load()`, creates `AppState`, and requests
`Storage.loadPlan(loaded)`. A returned plan is checked through `replacePlan`.
Neither restoration nor rendering regenerates the schedule. A `StorageException`
during either load produces `TextRenderer.error(message)` and status 1 before
any prompt or command read. Starting empty is allowed only for a genuinely
missing store; corrupt data must not be silently replaced. File validation and
atomic replacement are explained in [persistence](#atomic-persistence-and-restoring-frozen-plans).

After successful startup, each iteration flushes the exact `scheduleflow> `
prompt, calls `ConsoleUi.readLine()`, and handles one complete line. Blank input
immediately begins the next iteration, without parsing, dispatching or saving.
For a nonblank line, the parser returns a `Command`; the dispatcher executes it
against the same `AppState`. `ConsoleUi.write(response.output())` adds the final
newline and flushes the response before `shouldExit()` is inspected.

![Command loop with success, recoverable errors, blank input and termination](images/ka-how-command-loop.svg)

[PlantUML source](diagrams/ka-how-command-loop.puml)

The diagram starts after restoration. Its success formatter is shorthand for
the method selected by the dispatcher (`help`, `taskAdded`, and so on).
Service calls, storage operations and publication are omitted; their ordering
is described in [command transactions](#command-dispatch-and-live-state-transactions).

For example, after adding `commitment add n/Lecture day/MON start/1000 d/60`
to an empty store, parsing supplies `Command.AddCommitment`. The dispatcher
obtains a candidate, saves and publishes it, then calls
`TextRenderer.commitmentAdded`. Only the returned `CommandResponse` reaches the
console. Entering the same overlapping commitment again yields a validation
error and another prompt. Correcting the interval to start at `1100` succeeds
with C2; the rejected attempt consumed no ID. The shell owns the interaction,
while the service and dispatcher establish candidate validation and rollback.

#### Failure boundaries and termination

| Event | Shell behavior | Session outcome |
| --- | --- | --- |
| `ParseException`, for example `help extra` | `parseError` prints `Error: MESSAGE` and `Expected: USAGE`; dispatch is not reached. | Next prompt. |
| `ValidationException`, for example an overlapping commitment | `error` prints one explanation; no success response is written. | Next prompt. |
| `StorageException` during command execution | `error` reports the rejected save; the dispatcher retains the previous live state and plan. | Next prompt; the user can retry. |
| `exit` | The dispatcher returns goodbye with `shouldExit = true`; `App` prints it once. | Status 0, no extra save or prompt. |
| EOF (`readLine()` returns `null`) | `App` writes `TextRenderer.exit()` without invoking parser or dispatcher. | Status 0, no save. |
| Input `IOException` | `run()` prints `Error: Cannot read input: MESSAGE`. | Status 1. |

Unexpected programming exceptions propagate. In particular, a null dependency,
a stale plan or an unfinished range route is not converted to an ordinary
input error. Extra process arguments are rejected by `Main` before loading,
with `Error: Usage: java -jar scheduleflow.jar (no arguments)` and status 1.
`App` returns status codes so tests and embedded callers need not terminate
their JVM; only `Main` calls `System.exit`.

#### Deterministic presentation

Renderer methods return LF-separated strings with no trailing newline.
`ConsoleUi` supplies the final platform newline; tests normalize CRLF only at
the process boundary. Explicit UTF-8 streams in `Main` preserve names such as
`学习 Café` even when the JVM's default encoding differs. Date/time formatters use
`uuuu-MM-dd`, `uuuu-MM-dd HH:mm` and `HH:mm` with `Locale.ROOT`; weekday labels
explicitly use English. Validated commitment endpoints at midnight display as
`24:00` instead of wrapping to `00:00`.

Formatting preserves the supplied task order and schedule entry boundaries.
Weekly commitments are grouped Monday to Sunday, followed by one-time entries
ordered by date; within each group the service's supplied ordering is retained.
The renderer does not allocate, clip or merge sessions. `schedule(view)` uses
the view's frozen generation time and rows, adds a cutoff note if UNPLANNED
appears, and reuses the whole-plan unallocated-work footer rather than filtering
it to the selected date.

Exact work and reserved time have different meanings: a 45-minute task may
occupy a 60-minute interval. Plan summaries sum `StudySession.workMinutes` and
`UnallocatedWork.minutes` using `long` totals; TASK schedule rows show the
wall-clock interval duration. Presentation must preserve that distinction,
including when totals exceed a signed 32-bit integer.

#### Design considerations

The following is an analysis of the implemented design. Keeping rendering as
returned text allows exact-output tests without redirecting global streams;
injected readers/writers, storage and clocks allow controlled session tests.
Printing directly inside each service would require fewer intermediate values,
but would mix domain work with console framing and make isolated tests harder.
The current design instead requires the dispatcher to choose the appropriate
formatter and return `CommandResponse` consistently.

Catching the three expected command exceptions keeps correction local to one
iteration. Catching every runtime exception would also keep the loop running,
but could conceal a broken internal invariant. Propagating unexpected failures
keeps them visible to developers. This boundary does not promise recovery from
an output-device failure: `PrintWriter` errors are not explicitly checked.

`AppTest` covers startup, syntax/domain/save recovery and failed-save retries;
`AppStateTest` covers source-plan consistency. `ConsoleUiTest` checks flushes and
framing, `MainTest` checks process status/UTF-8/quiet defaults, and
`TextRendererTest` checks exact text, midnight, large totals and locale changes.
Fixed clocks, temporary files and isolated logger/locale changes keep these
checks independent of the developer's saved data. See [console verification](#build-packaging-and-console-verification)
and [manual console tests](#launch-help-exit-and-console-behavior).

### Exact task work and retained task lifecycle

#### Task operations and defensive checks

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

#### Task detail editing

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

#### Task progress and lifecycle

The canonical model is `Task(id, name, Optional<LocalDateTime>, remainingMinutes,
TaskPriority, Optional<TaskOccurrence>)`. The five-argument optional-deadline
constructor creates an ordinary task without provenance. The original
four-argument concrete-date constructor defaults
priority to NORMAL and still rejects null deadlines; there is no ambiguous
four-argument Optional overload. Use `Task.withoutDeadline(id, name, minutes)`
or the canonical constructor for explicit absence, never a sentinel date.
HIGH/NORMAL/LOW are stored and used only for planner ties here; priority operations
are not wired at the console. Weekly templates are described in
[recurrence](#weekly-recurring-tasks); queries remain deferred.

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

#### Completion and completed-task view

`Command.CompleteTask` and `ListCompletedTasks` add exact, case-sensitive grammar
for `task complete TNUMBER` and `task list completed`. Parsing validates the ID
shape and overflow; `TaskService.complete` retains domain validation and returns
an immutable zero-work candidate. The dispatcher formats the existing
`TextRenderer.taskCompleted` confirmation, saves a changed candidate, then
publishes it. Repeated completion returns the same source and does not save or
invalidate a plan. Failed saves preserve the snapshot and its saved plan.

The completed view filters the service's numeric-ID list and shares row formatting
with the original full list. No data or plan changes occur during listing.

#### Reopening completed tasks

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

#### Commands and parsing

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

### Dated commitments and save-before-publication dispatch

#### Recurring commitment addition

`CommitmentService.add` creates an immutable candidate snapshot. It reuses
`Commitment` for name, weekday, duration and interval validation, checks overlaps
against the full weekly intervals (including outside study hours), and reports
every conflicting commitment's ID and name in start-time order, with numeric ID
as a tie-breaker. The private overlap check collects matches before rejecting;
it never reorders the source snapshot. Single-conflict wording is unchanged,
and multiple conflicts are separated by semicolons. Adjacent intervals and duplicate
names at nonoverlapping times are allowed.

`CommitmentService.add` uses the next C ID and advances only that counter.
IDs stop at `2147483646`; the exhausted next counter is `2147483647`.

#### Commitment editing

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

#### Recurring commitment date bounds

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

#### Skipping one recurring occurrence

`Commitment` stores an immutable set of skipped calendar dates. `recursOn`
checks the weekday and optional inclusive bounds without exceptions, while
`occursOn` additionally excludes skipped dates. The constructor rejects a
skipped date outside the series or on another weekday. Editing retains the set,
so an edit that would move an existing exception outside the resulting series
is rejected instead of silently losing it.

`CommitmentService.skip` resolves the stable ID, rejects non-occurrence and
duplicate dates, and replaces the record at its original stored position. The
candidate preserves tasks, study hours and both ID counters. The dispatcher
saves the candidate before publishing it or returning `Skipped CNUMBER on DATE: NAME`;
therefore save failure leaves the live snapshot, current plan and saved data
unchanged. The command grammar is `commitment skip CNUMBER on/YYYY-MM-DD`.

Overlap checks search the finite range whenever either series is bounded and
require an unskipped shared occurrence. Two unbounded series always share a
future occurrence because each stores only finitely many skipped dates. This
allows another bounded commitment to use a cancelled date without weakening
conflict checks on the remaining dates. The planner and schedule projection
already use `occursOn`, so they require no separate exception logic.

#### One-time commitments

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

#### Commitment deletion and listing

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

#### Unavailable study periods

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

#### Command dispatch and live-state transactions

`CommandDispatcher.execute` switches on the nested `Command` types and captures
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

#### Multi-date unavailable periods: implementation handoff (planned)

**Proposed console integration:** `AddContinuousBlock` and `AddDailyBlocks`
have parsed transports, service implementations and a batch formatter, but
their dispatcher routes throw `UnsupportedOperationException`. They are
not supported console commands and are omitted from help.

The proposal expands a continuous interval (exclusive end), or a daily
window across inclusive dates, into ordinary one-time commitments. The
integration must save the complete validated batch once before publication;
a conflict or failed save must consume no IDs and preserve the previous plan.
Use [the planned fixtures](../test/ui-unavailable-ranges.planned.json) for
the proposed grammar, confirmations and boundary cases. Keep them planned
until dispatch and transaction acceptance are implemented.

### Slot allocation under study hours/policy and recurrence

#### Exact work and slot capacity

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

#### Continuous work and fixed breaks

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

#### Configurable study hours

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

#### Weekly recurring tasks

`RecurringTaskService` integrates weekly recurrence with the exact-work,
optional-deadline, priority and lifecycle model.
`RecurringTask` stores an independent positive R ID, normalized name, first
deadline, optional inclusive final date, positive half-hour estimate and persisted
last-processed deadline. `TaskOccurrence` retains the template ID and original
deadline; release is always one week before that original deadline. Ordinary
task additions accept positive whole minutes, including 45; completed
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

### Atomic persistence and restoring frozen plans

#### Basic saving and loading

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

#### Stored study policy format

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

#### Automatic saved schedules

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
or rescheduling. Snapshot versions 1-5 and 7, and saved-plan versions 6 and 8 remain readable.
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

#### Storage defensive checks and diagnostics

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

#### Supplied contract and Save-module differences

The supplied implementation contract's section 7 specifies Base64 names before
the date fields, three-letter weekdays, canonical numeric/date tokens, sorted
encoded rows and `FileChannel.force(true)`. The committed Save module uses
plain names last, full weekday names, Java token parsers, snapshot order and
`Files.writeString` before atomic replacement. Printing preserves that existing
module and its public APIs; the format documented above and tested restarts
use the committed codec. Contract-format interoperability and forced flushing
require coordination with the Save owner and are outside this printer change.

## Documentation, logging, testing and dev-ops

Review both guides with each implementation, following [AGENTS.md](../AGENTS.md).
Use isolated saved data and fixed clocks where exact dates matter.

### Build, packaging and console verification

From the repository root, verify both the shell and Gradle JVM are Java 25:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
node --test test/release.test.mjs
```

On Unix use `./gradlew`. If necessary set `GRADLE_USER_HOME` to a writable cache.
`build` runs JUnit and Checkstyle and creates `build/libs/scheduleflow.jar` with
entry point `scheduleflow.Main`. Standalone launches require
`java -ea -jar scheduleflow.jar` to enable assertions; Gradle `test`, `run` and
distribution scripts enable them already. `startScripts` and normal
distributions use the same Shadow JAR; test dependencies are not shipped.

For focused shell checks, run `test --tests scheduleflow.MainTest --tests
scheduleflow.cli.AppTest --tests scheduleflow.cli.AppStateTest --tests
scheduleflow.cli.ConsoleUiTest --tests scheduleflow.cli.TextRendererTest` with
the wrapper. The [UI plan](../test/ui-test-plan.md) explains isolated data,
per-command comparisons and scenario readiness. The UI runner rebuilds the
current JAR and tests without assertions; `release.test.mjs` replays maintained
cases with `-ea` and extracts/launches the normal distribution ZIP. It checks
both scripts' assertion defaults but executes only the current OS's script.
Preserve the printed `build/ui-transcripts/` paths before running `clean`.

#### Diagnostics

Default `Main` launches silence `scheduleflow.cli` unless logging was explicitly
configured. Embedded `App` instances respect the caller's configuration. Stage
logs omit raw commands/output, but failure records retain exception details;
keep diagnostic files private and outside version control.

For opt-in file diagnostics, put this `logging.properties` in a disposable
launch folder and run
`java -ea -Djava.util.logging.config.file=logging.properties -jar scheduleflow.jar`:

```properties
handlers=java.util.logging.FileHandler
.level=INFO
scheduleflow.level=FINE
java.util.logging.FileHandler.level=FINE
java.util.logging.FileHandler.pattern=scheduleflow-%u.log
java.util.logging.FileHandler.encoding=UTF-8
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
```

`FINE` records stages, `INFO` session start/normal termination, `WARNING`
recoverable rejections and `SEVERE` startup/input failures. To retain warnings
and failures only, set both `scheduleflow.level` and the handler level to
`WARNING`. Constructors perform no I/O or logging configuration.

#### Diagram and guide maintenance

PlantUML sources are in `docs/diagrams`; committed SVGs in `docs/images` let
Markdown display the figures without a PlantUML extension. With Java 25 and a
local PlantUML JAR (verified with 1.2025.4), render from the repository root:

```powershell
java -jar "PATH_TO_PLANTUML_JAR" -charset UTF-8 -tsvg -o ../images docs/diagrams/ka-how-console-classes.puml docs/diagrams/ka-how-command-loop.puml
```

Replace `PATH_TO_PLANTUML_JAR` with its local path. The output directory is
relative to each source directory. The class source selects the bundled
Smetana layout engine, so no external Graphviz installation is required.
Commit each changed source and its rendered SVG together, check the diagram
against the code, and inspect text size and links in the rendered guide.
Review both guides after implementation; mark unfinished operations explicitly.
The release maintainer must replace the candidate status with the verified
tag/artifact before publishing. Cross-platform runtime checks and filesystem
provider fault injection need separate evidence; passing on Windows does not
establish those results.

### Task/parser validation and JUnit/documentation conventions

Tests mirror production packages under `src/test/java` and use JUnit 5.
See [test conventions](../src/test/AGENTS.md) for isolation and naming,
and [the Java standard](../.agents/skills/seedu-java-coding-standard/SKILL.md)
for Javadoc and quality requirements. `TaskServiceTest`, `TaskEditTest`,
`TaskRecurrenceTest`, `InputRulesTest` and `CommandParserTest` cover exact
progress, no-ops, deadlines, recurrence preservation and strict syntax.

Task diagnostics use `FINE` on `scheduleflow.task`, `scheduleflow.model.Task`
and `scheduleflow.common.InputRules`, omitting names, dates and durations.
Use the file-handler configuration in the console diagnostics section and
select those logger namespaces. Services install no handlers.

### Commitment/dispatch transaction verification

Fixed-clock `CommandDispatcherTest` and `CommitmentServiceTest` cover
candidate preservation, overlap/adjacency, date bounds, skips, counter
exhaustion, failed-save retries and saved-before-live ordering. Duration
integration tests save/reload 90-minute tasks and commitments and check
that planning preserves estimates.

#### Commitment defensive checks and diagnostics

Domain validation works without assertions; constructors perform no I/O or
logging. Commitment diagnostics use `FINE` on `scheduleflow.commitment`,
without names, schedules or IDs. Use the file-handler configuration in the
console diagnostics section, selecting this logger namespace.

### Fixed-time planning and recurrence verification

`EarliestDeadlinePlannerTest`, `ScheduleServiceTest` and
`RecurringTaskServiceTest` use fixed times to verify conservation of exact
work, rounded capacity, horizon/cutoff boundaries, hours/policy, frozen
projections, weekly releases and cursor behavior. Packaged runs use the live
clock and cannot establish those exact-time allocation assertions.

### Storage diagnostics and compatibility verification

`StateCodecTest`, `PlanCodecTest`, `SnapshotTest` and `FileStorageTest` cover
legacy/current formats, corruption, ID relationships, Unicode and real
temporary-file round trips. Fake-storage tests establish caller rollback,
not filesystem-provider behavior. Unsupported atomic moves and injected
cleanup failures remain untested. Storage diagnostics are described
[above](#storage-defensive-checks-and-diagnostics).

## Appendix A: Product Scope

### Target user profile

University students balancing coursework deadlines, project work, classes and
other fixed events, who are comfortable typing commands in a terminal. They
need to revise estimates as work progresses and see when available study time
is insufficient.

### Value proposition and boundaries

ScheduleFlow turns remaining-work estimates, deadlines and busy intervals into
a local study schedule. Stable IDs and saved settings let a student correct
entries and continue later without re-entering data. Whole-plan unallocated
work makes shortfalls visible instead of silently placing work past deadlines.

The CLI supports tasks and their retained completion/progress, weekly task
templates, recurring/one-time commitments, configurable daily hours and an
optional work/break policy. Planning uses half-hour capacity within a rolling
calendar year; exact task work can be any positive whole-minute estimate.
Schedules stay frozen until regenerated or invalidated by a successful change.

This release candidate is a single-user, local/offline application with one
running instance per data file. It has no calendar synchronization, GUI,
notifications or automatic progress timer. Multi-date unavailable commands
remain proposed console integration; see the [User Guide](UserGuide.md) for
the supported command surface.

## Appendix B: User Stories

|Version| As a ... | I want to ... | So that I can ...|
|--------|----------|---------------|------------------|
|Current|new user|see command help|refer to the supported syntax when I need it|
|Current|student|add and list tasks with deadlines and estimates|keep track of unfinished coursework|
|Current|student|add and remove recurring commitments|reserve my weekly busy periods|
|Current|student with project meetings and other fixed events|add one-time commitments|keep those periods unavailable for studying|
|Current|student|generate a plan and view a date's schedule|find available time before my deadlines|
|Current|student|see unallocated work|recognize when my available time is insufficient|
|Current|returning user|restore saved tasks and commitments|continue without reentering my data|

## Appendix C: Non-Functional Requirements

- Run with Java 25 and UTF-8 source, saved data and console streams.
- Keep runtime operation local and offline, with no external service required.
- Save each successful mutation by atomic replacement before publishing it in
  memory or printing success. Use one running instance per saved-data file.
- Reject invalid input and corrupt saved data without publishing partial state.
- Keep normal diagnostics quiet; allow opt-in logs through Java logging.
- Verify behavior with deterministic JUnit tests and isolated console sessions.
- Atomic replacement requires filesystem support and does not guarantee
  durability after power loss.

## Appendix D: Glossary

* *Task* - A retained item with an optional deadline and exact remaining work; zero means complete.
* *Commitment* - A busy interval repeated on one weekday, optionally within
  inclusive dates, or a one-time busy interval on a single date.
* *Snapshot* - Immutable tasks, commitments, study hours and independent next-ID counters.
* *Plan* - Frozen allocations and unallocated work derived from a snapshot, saved until a successful mutation.
* *Planning horizon* - The calendar range ending at midnight on the same date
  next year, with February 29 adjusted to February 28 when necessary.
* *Slot* - A 30-minute interval within the daily study window.

## Appendix E: Instructions for Manual Testing

Use the candidate JAR identified at the top of this guide, Java 25 and a
fresh disposable launch folder for each group. Copy `scheduleflow.jar` there
and run `java -ea -jar scheduleflow.jar`. IDs below assume empty saved data.
Enter only the commands, without the `scheduleflow>` prompt. After `exit`,
retain any evidence you need, then discard the disposable folder.

These are representative manual paths, not an exhaustive input matrix.
The [User Guide command summary](UserGuide.md#command-summary) gives the full
syntax and the [UI fixtures](../test/ui-scenarios.json) supply exact outputs
for additional reproducible cases. Unfinished range commands are excluded.

### Launch, help, exit and console behavior

Use Java 25 and a disposable folder containing the candidate JAR. Launch from
a terminal with `java -ea -jar scheduleflow.jar`, without extra arguments.
`-ea` enables assertions; Gradle/distribution launchers already enable them.
These paths assume empty saved data and default logging.

| Ordered action | Expected result |
| --- | --- |
| Launch, then enter `help`. | No banner; exact `scheduleflow> ` prompt, then `=== ScheduleFlow Command Help ===` and supported formats, followed by another prompt. |
| Press Enter on an empty line, then enter a spaces-only line. | Each line produces another prompt with no response text. |
| Enter `help extra`, then `help`. | First prints `Error: This command accepts no arguments` and `Expected: help`; the corrected command works. |
| Enter `exit extra`, then `exit`. | The extra argument is rejected with `Expected: exit`; corrected exit prints `Goodbye for now!` once, returns status 0 and produces no further prompt. |
| Check the disposable folder, then relaunch. | Help, blanks, rejected syntax and exit created no data file; startup is still empty. |
| Send EOF at the prompt (Ctrl+Z then Enter in a Windows terminal; Ctrl+D on an empty line in a Unix terminal). | Same goodbye and status 0; no save. |

In another empty launch folder, run `java -ea -jar scheduleflow.jar --help`.
Expect `Error: Usage: java -jar scheduleflow.jar (no arguments)`, status 1 and
no prompt. Use `echo $LASTEXITCODE` in PowerShell, or `echo $?` in a Unix shell,
immediately after the Java process to inspect its status.

For Unicode and domain-error recovery, start a fresh normal session and run:

```text
commitment add n/学习 Café day/TUE start/1000 d/30
commitment add n/Bad day/TUE start/1015 d/30
commitment list
exit
```

Expect C1 with its full Unicode name and 10:00–10:30 interval. The second command
reports a half-hour-alignment error and another prompt; the list contains only
C1. Relaunch and list again to check the same characters survive persistence.
Normal stdout contains no stack traces and stderr is empty. A terminal must
also use UTF-8 and a font supporting those characters to display them correctly.
Detailed syntax/domain cases are in [task testing](#tasks-and-input-validation)
and [commitment testing](#commitments-and-unavailable-periods); fatal startup
and rejected saves are covered in [storage testing](#saving-restoration-failure-and-recovery).
Exit before discarding the disposable folders; keep your real data untouched.

### Tasks and input validation

Choose `D` as tomorrow's local date in `YYYY-MM-DD`; replace every `D` below
before entering commands. This keeps deadlines in the rolling horizon.

| Ordered command | Expected result |
| --- | --- |
| `task add n/Draft due/D 1800 d/90` | Adds T1 with 90 minutes. |
| `task list` | Lists T1 and its stored deadline/estimate. |
| `task edit T1 n/Final draft` | Renames T1, retaining its ID and estimate. |
| `task remaining T1 remaining/60` | Reports 60 remaining minutes. |
| `task progress T1 done/15` | Credits 15, leaving 45. |
| `task progress T1 session/61 percent/25` | Credits 15, leaving 30. |
| `task progress T1 done/31` | Error; remaining work stays 30. |
| `task complete T1` | Retains T1 with zero remaining. |
| `task list completed` | Lists T1 with zero minutes. |
| `task reopen T1 remaining/45` | Retains T1 with 45 remaining; completed view excludes it. |
| `task delete T1`, then `task list` | Deletes T1; no tasks remain. |

Use [task command examples](UserGuide.md#tasks) for further syntax/boundary
variations. No timer runs and planning never subtracts completed work.

### Commitments and unavailable periods

Run in a separate empty folder. The fixed dates test occurrence membership,
not live-clock allocation.

| Ordered command | Expected result |
| --- | --- |
| `commitment add n/Lecture day/MON start/1000 d/60 from/2026-11-02 until/2026-11-30` | Adds bounded weekly C1. |
| `commitment edit C1 start/1030` | Retains C1, now 10:30–11:30. |
| `commitment add n/Clash day/MON start/1100 d/30` | Overlap error; C1 unchanged and no ID consumed. |
| `commitment skip C1 on/2026-11-02` | Skips that Monday only. |
| `commitment add n/Unavailable on/2026-11-02 start/1030 d/60` | Adds one-time C2 in the released interval. |
| `commitment list` | Weekly C1 shows its bounds/skip; separate dated section shows C2. |
| `commitment delete C2`, then `commitment delete C1` | Deletes both; listing is empty. |

To inspect BUSY projection, choose a date in a freshly generated plan and
repeat the [single-date blocking path](UserGuide.md#marking-a-study-period-unavailable).
After adding/deleting a block, schedule requests must report no current plan
until `plan` runs again. Do not enter the proposed multi-date commands.

### Planning, schedules, hours, policy and recurrence

Use another empty folder. Choose `D` as tomorrow and `W` as its full lowercase
weekday (for example `tuesday`); substitute them in the commands.

| Ordered command | Expected result |
| --- | --- |
| `schedule today` | No current plan; asks you to run `plan`. |
| `hours W /start 09:00 /end 17:00`, then `hours W` | Sets and shows recurring weekday hours. |
| `hours D /start 10:00 /end 12:00`, then `hours D` | Date override wins: 10:00–12:00. |
| `hours D /start 10:15 /end 12:00` | Alignment error; previous hours retained. |
| `policy set max/60 break/30`, then `policy show` | Shows the stored work/break limits. |
| `task add n/Study due/D 1200 d/90`, then `plan` | Generates a full or partial result without reducing the stored 90 minutes. |
| `schedule D`, then `schedule today` | Shows dates from the frozen plan, its generation time and applicable labels. Exact rows depend on launch time. |
| `schedule 0001-01-01` | Out-of-range error; current plan remains available. |
| `policy set max/60 break/30`, then `schedule D` | Reports unchanged policy; preserves the plan. |
| `policy reset`, then `schedule D` | Restores unrestricted planning; no current plan. |
| `hours reset D`, then `hours D` | Falls back to weekday 09:00–17:00. |
| `hours reset W`, then `hours W` | Restores default 08:00–22:00. |
| `task recur add n/Quiz due/D 1800 d/30 every/WEEKLY until/D` | Adds R1 and its one future T occurrence. |
| `task recur list`, then `task list` | Template has an R ID; occurrence has a separate T ID. |
| `task recur delete R1`, then `task list` | Stops generation; existing occurrence remains. |

A repeatable partial result can be checked in a fresh folder by adding
`task add n/Large due/D 0000 d/2147483647`, then running `plan`. Expect
unallocated work and unchanged stored minutes. For an exact complete/BREAK
schedule, use the controlled-clock
[User Guide example](UserGuide.md#worked-schedule-example) as a reference,
or replay the saved-plan fixtures UI-029/UI-031; the production JAR has no
clock-setting flag. Live tests cannot promise those same timestamps.

### Saving, restoration, failure and recovery

1. In a fresh folder add `commitment add n/Saved day/TUE start/1000 d/30`.
   Run `plan`, `schedule today`, then `exit`. Expect a saved data file.
2. Relaunch there; run `commitment list` and `schedule today`. Expect C1 and
   the same generation timestamp/rows for the same selected date. Across
   midnight use `schedule YYYY-MM-DD` for the original date. Exit and copy
   the file to a backup outside that launch folder.
3. In a second disposable folder put a copy at `data/scheduleflow.txt`;
   relaunch and confirm the same commitment and frozen plan.
4. With the app closed, edit only the second copy's header to
   `SCHEDULEFLOW|99`. Launch. Expect `Error: Unsupported file version: 99`,
   status 1 and no prompt; the invalid file must remain unchanged. Restore
   the backup and verify normal startup.
5. For save rejection, create a regular file named `data` in a third empty
   launch folder before starting. Run
   `commitment add n/Retry day/TUE start/1000 d/30`. Expect a save error,
   another prompt and no added commitment. `commitment list` stays empty.
   Exit, move that disposable `data` file aside and retry in a new session.
   Expect C1, confirming the failed attempt consumed no ID.

Automatic persistence has no save/load command. Repeat restart checks after
the task, settings and recurrence groups when exploring further. Permission
and unsupported-atomic-move failures vary by OS/provider; use the controlled
storage/dispatcher tests for the documented boundaries, and record any
unexecuted provider case rather than treating it as passed.
