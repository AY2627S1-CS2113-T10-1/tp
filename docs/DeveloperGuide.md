# Developer Guide

## Keeping the guides up to date

After each implementation, review this guide and the [User Guide](UserGuide.md)
and update them as needed in the same change. Record changes to design,
implementation and testing here, and changes to commands, examples and
user-visible behaviour in the User Guide. Describe verified behaviour accurately
and clearly mark planned features. This requirement also applies to all AI
agents working on the project, as specified in the repository's root `AGENTS.md`.

## Saving and loading authorship

Scoped `// @@author ernestgoh-god` comments identify Ernest's storage
implementation and storage tests for RepoSense. Each marked section ends with
`// @@author`; shared foundation declarations outside these sections retain
their Git-based attribution. Keep these boundaries accurate when editing the
code. See the [RepoSense author-tag guide](https://reposense.org/RepoSense/ug/usingAuthorTags.html).
These tooling comments do not change saving/loading behaviour or its test cases.

## Task authorship

Scoped `// @@author nickpatt` comments identify Nickhil's task add/delete/list
implementation, defensive checks and opt-in diagnostics, together with his
regression tests. Each range ends with `// @@author`. These ranges follow the
Task role's four production files and verified contributions in commits
`83c8d7c` and `a9bb80b`; unchanged shared foundation code and tests retain their
Git-based attribution. CLI wiring remains attributed to its Printing owner.
These tooling comments change no commands, runtime behaviour or UI fixtures;
the existing Task JUnit coverage and packaged scenarios remain applicable.

## Duration verification (issue #13)

Duration recording belongs to the existing add operations; no additional
command or production API is needed. `InputRules.requireDuration` validates
positive multiples of 30 for both models, and `Commitment` additionally rejects
intervals crossing midnight. Existing task and commitment service tests cover
invalid durations and boundary values.

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

The sealed `Command` interface contains fifteen supported command records.
Records that only carry values use Java's generated canonical constructors;
redundant empty compact constructors have been removed. Records with required
reference fields retain their null checks. Parsing and the services still own
syntax and business validation respectively. `CommandParserTest` and
`ContractsTest` cover construction and dispatch vocabulary without changing
the public record components or constructor signatures.

### Planning and schedule projection

`EarliestDeadlinePlanner` creates chronological half-hour slots from the
captured command time through the rolling calendar-year horizon. It excludes
recurring commitments, sorts tasks by deadline then numeric ID, and assigns
each eligible task to its earliest free slots. Overdue and out-of-horizon
tasks keep their full work estimate as unallocated; eligible tasks retain any
sessions that fit before their deadline. Adjacent slots for one task merge
within a day. Source task estimates remain unchanged.

`ScheduleService` projects the frozen plan across the complete configured daily
window. Each slot is classified as busy, assigned to a task, before the
generation cutoff, or free, in that order. Merging preserves commitment and
task identities. `Plan`, `StudySession`, `UnallocatedWork`, `ScheduleEntry` and
`ScheduleView` validate their own fields and retain immutable result lists;
allocation relationships remain the planner's responsibility. Fixed-date
planner and projection tests verify deadline order, conservation of minutes,
cutoff rounding, clipping, midnight commitments and allowed schedule dates.

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

The codec reads versions 1 and 2. It writes version 2 when custom hours are
present, appending sorted `HOURS|TARGET|HH:mm|HH:mm` rows. Duplicate targets,
invalid dates, unknown weekdays and invalid intervals reject the whole file.
Snapshots without custom settings retain version 1 encoding. Existing version 1
files load with defaults; older applications reject files containing version 2.
Atomic replacement still saves tasks, commitments, counters and hours together.

JUnit coverage checks precedence/reset, immutable maps, input boundaries,
legacy and new codec round trips, malformed records, save rollback, preservation
through task/commitment changes, recurring allocation, clipping and rounded
cutoffs outside the old window. UI-015 verifies commands and persistence through
three isolated console sessions, including reset and plan invalidation.

### Task operations and defensive checks

`Task` owns structural validation: positive IDs, normalized nonblank names,
minute-precision deadlines in years 0001-9999 and positive 30-minute duration
multiples. It uses `InputRules`, the shared name/duration authority, and permits
overdue or out-of-horizon saved deadlines. `ValidationException` carries the
user-facing explanation and rejects a null message as programmer misuse.

`TaskService.add` checks the captured command time, accepting
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

### Basic saving and loading

`StateCodec` converts the existing `Snapshot` to and from plain text.
`FileStorage` loads UTF-8 or returns `Snapshot.empty()` for a definitely missing
file. Saving creates missing parent directories, writes a sibling temporary
file, then atomically replaces the target. There is no non-atomic fallback.
Constructors perform no I/O; existing constructor call sites remain supported.

Version 1 uses one record per line:

```text
SCHEDULEFLOW|1
NEXT|2|2
TASK|1|2000-01-02T09:00|60|Read
COMMITMENT|1|MONDAY|10:30|90|Class
```

`NEXT` holds the next task and commitment IDs, including after deletion.
Tasks store ID, ISO local deadline, remaining minutes and name. Commitments
store ID, full uppercase weekday, ISO local start time, duration and name.
Names are last so literal pipes need no escaping. Encoding preserves each
list's order and writes LF line endings. Decoding also accepts CRLF/CR and an
omitted final newline. Java's date/time parsers and existing domain constructors
validate the complete snapshot, including overlaps and counters. Past deadlines
remain valid; plans are never stored. Invalid data raises `StorageException`
without returning a partial snapshot or modifying the file.

`StateCodecTest` covers the format and validation. `FileStorageTest` demonstrates
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
estimate. BUSY retains its reference and name; FREE and UNPLANNED have only their
labels. Any UNPLANNED row adds the cutoff note. A nonempty remainder list adds a
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
and invalidate the temporary plan, consistently with successful mutations.

Tests cover all fifteen nonempty field subsets, invalid syntax, immutable source
snapshots, self-exclusion, multi-conflict ordering, adjacent and midnight bounds,
counter exhaustion, rollback/retry and changed allocation after regeneration.
UI-014 verifies the packaged command, Unicode edits, rejection recovery and restart.
New significant methods have scoped Darren50 author markers; existing shared CLI
methods retain their attribution. Changes outside the commitment domain are limited
to this command's transport, integration, output and acceptance tests.

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
consuming an ID or clearing the current plan. Shared public APIs are unchanged.

Help, lists, schedule and exit preserve state and make no save call. Planning
replaces only the temporary plan; partial and empty results remain usable.
Schedule requests project that frozen plan; absence takes precedence over date
validation. Exceptions from domain services remain their owners' validation.

`CommandDispatcherTest` uses fixed clocks, a counting clock and fake storage
to verify all routes, mutation transaction paths and retries, save ordering,
deadline boundaries, numeric IDs, partial/blocked plans, cutoff precision,
range validation, zone-aware today and frozen/replaced plans.

### Application lifecycle and executable entry point

`App.run` loads once and stops with status 1 before prompting if storage rejects
the load. Each loop prompts and reads a line, ignores blanks, parses, dispatches
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

* *Task* - An unfinished item with a deadline and a remaining-work estimate.
* *Commitment* - A busy interval repeated on one weekday until deleted.
* *Snapshot* - Immutable tasks, commitments, study hours and independent next-ID counters.
* *Plan* - Temporary allocations and unallocated work derived from a snapshot.
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
