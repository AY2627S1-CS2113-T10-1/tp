# Developer Guide

## Keeping the guides up to date

After each implementation, review this guide and the [User Guide](UserGuide.md)
and update them as needed in the same change. Record changes to design,
implementation and testing here, and changes to commands, examples and
user-visible behaviour in the User Guide. Describe verified behaviour accurately
and clearly mark planned features. This requirement also applies to all AI
agents working on the project, as specified in the repository's root `AGENTS.md`.

## Acknowledgements

{list here sources of all reused/adapted ideas, code, documentation, and third-party libraries -- include links to the original source as well}

## Design & implementation

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
the dispatcher will resolve its date when application integration is implemented.

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
parser input is rejected; ignoring blank lines belongs to the future App loop.

Parsing does not create domain records or validate nonblank names, duration
multiples, commitment alignment/end times, future deadlines, overlaps or ID
existence. Those remain the responsibility of the existing domain constructors
and services. No other member's implementation or shared public API is changed.
The existing command records and `ParseException` satisfy the parsing contract.
Application integration remains deferred.

`CommandParserTest` verifies every command, all weekdays, multiword/Unicode names,
whitespace, strict leap dates and year boundaries, times, field rejection,
numeric limits, case sensitivity, usage hints and unresolved `today`. These
tests use fixed values and require no disk data or running application.

### Text rendering

`TextRenderer` formats help, task and commitment success messages, lists, plan
results, schedules, errors, the absent-plan message and goodbye. It returns
LF-separated strings without a trailing newline and performs no I/O, clock reads
or state changes. Task rows
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
dispatcher will handle absent plans during application integration.

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
the conflicting commitment's ID and name. Adjacent intervals and duplicate
names at nonoverlapping times are allowed.

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
Deletion, listing and full CLI integration remain unfinished.

## Product scope
### Target user profile

{Describe the target user profile}

### Value proposition

{Describe the value proposition: what problem does it solve?}

## User Stories

|Version| As a ... | I want to ... | So that I can ...|
|--------|----------|---------------|------------------|
|v1.0|new user|see usage instructions|refer to them when I forget how to use the application|
|v2.0|user|find a to-do item by name|locate a to-do without having to go through the entire list|

## Non-Functional Requirements

{Give non-functional requirements}

## Glossary

* *glossary item* - Definition

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
```

For the parser alone, use
`.\gradlew.bat test --tests scheduleflow.cli.CommandParserTest`.
For the formatter alone, use
`.\gradlew.bat test --tests scheduleflow.cli.TextRendererTest`.
On Unix, replace `.\gradlew.bat` with `./gradlew`. If the default Gradle cache
is unwritable, set `GRADLE_USER_HOME` to an accessible cache.

The process UI cases in [the test plan](../test/ui-test-plan.md) remain planned
until their application and storage dependencies are implemented. Parser and
formatter unit tests establish their own behavior; a BLOCKED UI run (exit 2)
does not establish console recovery, transaction rollback or release acceptance. The runner uses
isolated temporary data and writes evidence under `build/ui-transcripts/`.
