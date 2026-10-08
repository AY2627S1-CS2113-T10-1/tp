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

### Printing stage 1: commands and parsing

`CommandParser.parse` implements all command formats in the User Guide and
returns the existing nested `Command` records. The parser has no clock, storage
or application-state dependency. `schedule today` produces `Command.ScheduleToday`;
the dispatcher will resolve its date when stage 3 is implemented.

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
The existing command records and `ParseException` already satisfy stage 1.
Text rendering and application integration remain deferred to stages 2 and 3.

`CommandParserTest` verifies every command, all weekdays, multiword/Unicode names,
whitespace, strict leap dates and year boundaries, times, field rejection,
numeric limits, case sensitivity, usage hints and unresolved `today`. These
tests use fixed values and require no disk data or running application.


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
On Unix, replace `.\gradlew.bat` with `./gradlew`. If the default Gradle cache
is unwritable, set `GRADLE_USER_HOME` to an accessible cache.

The process UI cases in [the test plan](../test/ui-test-plan.md) remain planned
until their application and storage dependencies are implemented. Parser unit
tests establish stage 1 behavior; a BLOCKED UI run (exit 2) does not establish
console recovery, transaction rollback or release acceptance. The runner uses
isolated temporary data and writes evidence under `build/ui-transcripts/`.
