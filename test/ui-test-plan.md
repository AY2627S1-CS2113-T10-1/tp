# ScheduleFlow UI test plan

Issue #40 progress grammar, service candidates, Printing confirmations and
dispatcher transactions have JUnit coverage. UI-030 exercises both progress
forms, exact rounded credit, zero credit, invalid requests, plan invalidation
and retained completion across two saved restarts. Fixed-clock dispatcher
tests verify no-op plan preservation and failed-save rollback/retry.

Issue #41 remaining-work parsing, service candidates, renderer output,
saved-candidate reload, planning demand, successful dispatch, no-op behavior and
save-failure rollback have JUnit coverage. UI-019 checks the packaged command's
exact output, invalid values, plan invalidation, replanning and saved data after
two restarts. Save failure is covered by an isolated dispatcher fake because
process-level fault injection is unavailable.

All twenty-eight maintained process scenarios are active. They exercise the packaged
JAR in fresh temporary folders, with restart sessions sharing only their own
saved data. Review this plan and `ui-scenarios.json` after each coherent code
update. The fixtures are the executable source of truth for exact responses.

The defensive checks and optional stage diagnostics preserve these exact
responses. The original eight scenarios retain their existing expectations;
normal launches must still produce empty stderr, including `WARNING` and
`SEVERE` error paths. JUnit covers API misuse and captures enabled logging
separately with isolated logger configuration; production-entry-point tests
also check warning-only file logging and unchanged console responses.

Storage-only regression coverage also round-trips task and commitment service
candidates through fresh file readers, retaining values and both ID sequences
after deletion. Repeated loading of valid overdue records preserves the original
file bytes. These direct JUnit checks complement the active console scenarios
and Printing's restart and rollback tests.

Issue #68's UI-031 starts with a fixed version 6 file, checks the same schedule
on two process launches, then confirms a successful task deletion clears it.
Fixed-clock JUnit additionally generates and saves a plan through the command,
compares its schedule before and after restart, verifies failed plan saves keep
the prior plan, and rejects corrupt plan rows before the first prompt. Codec
tests cover exact credited work, optional deadlines, large rounded capacities,
recurrence metadata, reserved breaks, literal pipes and legacy snapshots/plans.
UI-032 restores a version 6 recurring-task plan with partial-slot work and checks
that progress invalidates the saved plan without losing template metadata.
Fixed-clock tests also generate new recurring occurrences and save their source
and plan together; injected plan-save failure preserves IDs, cursors and the old plan.

Save's defensive-check tests also verify null snapshots are rejected before
file changes, and capture opt-in storage diagnostics for success, corrupt input
and cleanup after failed replacement. The original eight fixtures retain their exact
expectations: storage uses `FINE` logging and default launches remain quiet.
Assertions supplement runtime validation; they do not replace file-error checks.

Commitment hardening retains the original eight fixtures and their expected responses.
Isolated JUnit checks cover add/delete/list diagnostic stages, rejected operations
without false success, private-value omission and named null preconditions.
Existing interval and immutable-candidate tests exercise internal assertions;
the packaged scenarios still check quiet defaults and unchanged validation text.

Run from the repository root with Java 25 and Node.js 22 or later:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
node --test test/release.test.mjs
```

Use `./gradlew` on Unix. If needed, set `GRADLE_USER_HOME` to a writable cache.
The three-OS CI workflow now runs the same sequence with Java 25 and Node 22,
after printing runtime versions. It selects the OS-appropriate wrapper and
retains JUnit/Checkstyle reports and console/release transcripts on failure.
The active fixture list contains 34 release scenarios. The separate range-plan
file is retained as an empty valid fixture after UI-022 through UI-027 moved into
the main suite. Adding these CI steps does not by itself certify hosted matrix
results. Runtime saved data and diagnostic logs are not CI artifacts.

In a restricted Windows environment, place `TEMP` and `TMP` in an ignored,
writable workspace folder for the process runner. A local Gradle init script
may set the test JVM's `java.io.tmpdir` to that folder; these verification
settings and generated evidence must not enter commits.
The restricted Windows sandbox can also delay Java's native random seeding
for the first temporary save file beyond the runner's 5-second command limit.
The final process checks passed outside that sandbox with unchanged command
timeouts and expected output; Java 25 and isolated workspace data were retained.

## Exact comparison protocol

- Use UTF-8 stdin, stdout and stderr. Normalize CRLF to LF only; retain every
  other space, punctuation mark, line and ordering. No dynamic placeholders.
- `startup` is literal output before the first `scheduleflow> ` prompt, normally
  empty. Startup failure specifies `exitCode` with no steps.
- Each step sends its input plus LF and asserts the exact response and next
  prompt before sending another command. A final `exitCode` expects process
  termination. `input: null` closes stdin. For ignored blank lines, use empty
  output and `newline: false` to assert a prompt-only response.
- Stderr must be empty. Unexpected output, exit, timeout or stderr fails the
  case. Stop at the first failure, fix it and run a new session.
- Every case uses an isolated temporary directory. Optional `files` contain
  exact UTF-8 startup data. Restart sessions share that directory, not a process.
  Cleanup never touches the developer's data.
- Build timeout is 180 seconds; startup, command and exit timeout is 5 seconds.
  The runner builds current code with `shadowJar` and checks Java 25.
- Transcripts in ignored `build/ui-transcripts/` contain build output, input,
  stdout, stderr and process status. Use the exact printed transcript path.
  Preserve evidence before running Gradle `clean`.

## Active packaged scenarios

Task editing and weekly task recurrence have parser/dispatcher/renderer
implementations and fixed-clock JUnit coverage. The maintained process fixtures
are listed below; their inputs use historical saved tasks or empty task lists
to avoid near-future deadlines expiring. Eligible occurrence generation,
original releases, rolling horizons, no-op edits, failures and retry IDs use
fixed-clock JUnit tests. No production clock flag is introduced.
The inherited scenarios retain their expected outputs. PR #84's domain
lifecycle and exact-minute changes are merged and preserved alongside #39/#67.

Task edit, deadline clear/absence, progress/session percentages, revised remaining,
completion and reopening APIs have deterministic JUnit coverage. The two progress
forms have exact renderer output, dispatcher transaction tests and UI-030
acceptance. Changed candidates save once before live publication; zero-credit
no-ops preserve the plan without saving. Completion is connected and exercised
by UI-033; reopening is connected through `task reopen` and exercised by UI-034.

Compatibility checks cover optional deadlines in existing add/list/remainder
output, exact plan work totals (not rounded capacity), 45-minute work versus
60-minute reservation, partial 30/15 allocation, completed-task exclusion, long
arithmetic, custom hours, identity no-ops and legacy/v2-v5 save round trips.
Tasks now accept positive whole-minute additions; commitments retain the positive
30-minute rule, including UI-006's rejected commitment `d/45`. All existing
slot-aligned fixture responses remain unchanged. UI-020 retains PR #84's
optional/completed restored-data compatibility case and exact-minute restart checks.

| ID | Coverage | Status |
| --- | --- | --- |
| UI-030 | Minute/session progress, rounded and zero credit, invalid values, completion, plan invalidation and two saved restarts | Active |
| UI-021 | Unavailable study period: custom hours, add/delete plan invalidation, restart and released interval | Active |
| UI-020 | Restored optional/completed tasks, exact work and saved restart | Active |
| UI-019 | Remaining-time revision, validation, plan invalidation and two saved restarts | Active |
| UI-018 | One-time commitments: exact-date overlap, validation, restart, edit by date and deletion | Active |
| UI-017 | One-date recurrence skips, validation, version 4 restart and released overlap | Active |
| UI-001 | Missing file, empty lists and absent plan | Active |
| UI-002 | Adjacency, midnight and commitment IDs after deletion/restart | Active |
| UI-003 | EOF prints goodbye and exits 0 | Active |
| UI-004 | Unsupported store version stops before prompting | Active |
| UI-005 | Strict syntax, usage hints and recovery including `exit extra` | Active |
| UI-006 | Blank lines, duration/alignment/overlap errors, unknown IDs and retry IDs | Active |
| UI-007 | Unicode save/restart and plan invalidation after deletion | Active |
| UI-031 | Saved version 6 schedule survives restart and is invalidated by task deletion | Active |
| UI-032 | Saved recurrence and exact work survive restart; progress invalidates the plan | Active |
| UI-033 | Complete tasks, view only completed records, reject unknown IDs and retain completion across restart | Active |
| UI-034 | Reopen completed tasks with exact estimates, preserve a plan on rejection, invalidate after saving, replan and retain work across two restarts | Active |
| UI-008 | Truncated saved record stops startup with status 1 | Active |
| UI-009 | Restored overdue/out-of-horizon tasks, numeric listing, deletion and restart | Active |
| UI-010 | Exhausted task and commitment ID sequences reject additions without overflow | Active |
| UI-011 | Overlapping saved commitments reject the whole store before prompting | Active |
| UI-012 | Unallocated duration totals beyond a signed 32-bit integer | Active |
| UI-013 | Multiple commitment conflicts in time order, saved-data retention and retry ID | Active |
| UI-014 | Partial commitment edits, unchanged IDs, rejection, plan invalidation and Unicode restart | Active |
| UI-015 | Study hours precedence, validation, resets, plan invalidation and saved restarts | Active |
| UI-016 | Inclusive recurring-commitment bounds, actual-occurrence overlap and version 3 restart | Active |

UI-021 verifies issue #48 through the existing one-time commitment commands.
It configures a date's study window, generates an empty plan, adds an unavailable
period and checks plan invalidation. Restart confirms the period and hours are
saved; deletion clears another plan, and a further restart confirms release and
that IDs are not reused. These process checks do not claim to verify task
relocation: fixed-time `EarliestDeadlinePlannerTest` acceptance tests cover
reallocation around a newly blocked study session, deletion restoring time,
partial and fully blocked deadline capacity, and a real saved/reloaded block
with date-specific hours. `ScheduleServiceTest` checks BUSY projection, frozen
old plans and the absence of the one-time block the following week.
Existing UI-018 covers malformed inputs, conflicts and rejected-addition IDs.

UI-015 asserts reset confirmations name the restored source and its endpoints.
UI-016 adds nonoverlapping semester series at the same weekly time, rejects a
real in-range clash and an interval without the selected weekday, edits the
inclusive bounds, and confirms version 3 persistence and retry ID stability.
UI-017 skips one bounded Monday occurrence, rejects duplicate, wrong-date and
unknown-ID requests, confirms version 4 restart persistence, allows another
series to use the released occurrence and retains the rejected-addition ID.
Its single-date replacement now displays as one-time because issue #53 treats
equal `from/`/`until/` dates as a one-time commitment.
UI-018 adds a weekly series and dated one-time commitments, rejects overlaps
with both kinds, malformed date/time/year, invalid duration, mixed `on/` and
range syntax and skipping a one-time entry, then checks adjacency, the separate
list section, restart persistence, `on/` edits, deletion and unconsumed IDs.
Plan and schedule blocking for one-time dates is covered by fixed-clock JUnit
tests because packaged runs cannot control the current date.
UI-019 starts with a fixed overdue task so its unallocated totals stay stable
regardless of the launch date. It verifies the revised estimate is visible to
planning and survives two separate application restarts.
Usage-hint expectations in UI-005 and UI-014 include the new `on/` syntax.

The original eight scenarios retain their exact expectations. UI-009 through
UI-012 add release acceptance without a production clock flag. Their saved
deadlines are deliberately far past or beyond the current planning horizon.
UI-013 adds two commitments in reverse time order and rejects a spanning interval.
Restarting confirms the original entries remain; retrying in the gap uses the
unconsumed ID. Existing dispatcher tests cover plan retention after rejected mutations.
Model JUnit checks distinguish invalid IDs, missing weekdays and missing starts.
UI-014 edits individual and combined fields, rejects conflicts and incomplete/invalid
commands, and confirms midnight output, saved replacements and unchanged next IDs.
It also sets custom study hours before editing and verifies they survive the edit
and restart. A JUnit regression additionally preserves date-specific overrides.
Fixed-clock dispatcher tests additionally cover edits and identical-value edits through
save failure/retry, plan preservation on rejection and allocation after regeneration.
The separately invoked `test/release.test.mjs` runs all maintained cases with
`-ea`, checks the normal ZIP includes the exact JAR, and launches its native
script with default assertions. Run it after `build`; it does not build
artifacts itself. Its exact transcript path is printed under
`build/ui-transcripts/`, alongside the local skill runner's evidence.

UI-001 through UI-005 were activated when their console dependencies became
implemented. Preflight still reports ownership-marked stubs and blocks only
dependent scenarios; ready active cases must run even if another component is
unfinished. Exit 0 means active scenarios passed, exit 1 means FAILED, and
exit 2 means BLOCKED. A planned or diagnostic scaffold case is never a pass.

## Multi-date unavailability (follow-up to issue #48)

Both range modes are parsed, expanded into complete commitment candidates,
formatted before saving, persisted once and published only after that save.
The active fixture contains all six exact range scenarios. The old separate
`test/ui-unavailable-ranges.planned.json` file remains valid but empty so tools
or older links do not encounter malformed JSON.

| ID | Acceptance coverage | Status |
| --- | --- | --- |
| UI-022 | Continuous three-date expansion, confirmation, invalidation and restart | Active |
| UI-023 | Daily inclusive windows, confirmation, restart and independent deletion | Active |
| UI-024 | Invalid ordering, alignment, overnight windows, syntax and retained retry IDs | Active |
| UI-025 | Conflict on a later continuous segment rejects the whole batch | Active |
| UI-026 | Conflict on a later daily window rejects the whole batch | Active |
| UI-027 | Midnight-exclusive and same-day continuous ends, adjacency and leap-day daily expansion | Active |

`CommandDispatcherTest` checks prepare-before-save ordering, one whole-batch
save, plan invalidation only after success, failed-save retry with identical IDs,
later-segment conflict rollback, preservation of tasks/settings/recurrence and
ID-capacity rejection. `CommitmentServiceTest` covers expansion, boundary dates,
alignment, overlap and counter limits. Parser and renderer tests retain their
exact grammar and output coverage. The packaged scenarios add restart and
independent deletion acceptance through the real JAR.

## Issue #40 console acceptance

Progress grammar, candidate transactions, exact work and Printing output are
implemented. UI-030 uses a restored overdue task to avoid live-date assumptions.
It verifies direct credit, floor rounding for a session, zero credit, rejection
of excess credit and unknown IDs, invalidation after changes and persisted
partial/completed estimates. Completion is connected through `task complete`;
reopening (#56) is connected through `task reopen` and covered by UI-034.
Save failure and unchanged-plan identity are checked with fixed-clock dispatcher
JUnit tests rather than process fault injection.

| ID | Coverage | Status |
| --- | --- | --- |
| UI-030 | Minute/session progress, exact credit, save/restart and plan invalidation | Active |

## Issue #55 console acceptance

UI-028 exercises policy set/show/reset, nonpositive and unaligned durations,
unchanged settings and three saved sessions. UI-029 starts with a fixed version 8
plan and checks frozen BREAK rows, policy restoration and invalidation after a
changed setting. These process cases use deterministic text and isolated data.
Fixed-clock application and planner tests verify that console settings actually
control allocation, including exact break times and source-policy precedence.
Existing configured-planner tests retain their cross-task, fixed-commitment,
interrupted-break, deadline and overnight-rest coverage. Dispatcher fakes verify
save failure preserves old settings/source/plan and no-ops make no save call.

| ID | Coverage | Status |
| --- | --- | --- |
| UI-028 | Set/show/reset policy, invalid values, no-ops and saved restarts | Active |
| UI-029 | Frozen policy schedule, BREAK rows, restart and policy-change invalidation | Active |


## Fixed-clock acceptance and remaining checks

`CommandDispatcherTest` covers every command, single clock capture, every
save/commit transaction and failed retry, unchanged snapshots/counters/plans,
no saves for read-only commands, deadline limits, numeric ID ordering, partial
and blocked plans, cutoff seconds, range limits, injected timezone, frozen
plans and replacement. It checks the corrected earliest-slot schedule and
preserves stored estimates. Existing optimizer tests verify conservation and
nonoverlap across mixed inputs.
Release JUnit checks also cover a task split across days with a partial
remainder and allocation through the last study window of a leap-day horizon.

`AppTest` covers first launch without file creation, one startup load, startup
corruption with preserved bytes, parse/domain/save recovery, stable task IDs
across real file restarts, overdue tasks, rollback with existing file bytes,
empty-plan clipping/midnight output, invalidation, input IOException and
exit/EOF without saving. `ConsoleUiTest` asserts flushes and exact framing.
`MainTest` launches separate production-entry-point processes to check
nonempty argument rejection, UTF-8 despite ASCII defaults, EOF and real saved
restarts. It also checks corrupt plan metadata and a directory at the saved-file
path exit before prompting without changing existing data. Child JVM logging
probes exercise class-based configuration and explicitly configured CLI and
application logger levels, preserving command output and warning filtering.
These additional startup checks enable assertions and keep logger state inside
their own processes. The maintained UI cases verify the packaged manifest entry
point; their active status and expected command output remain unchanged.

Output-failure regressions use deterministic JUnit writers instead of fragile
process broken pipes. `ConsoleUiTest` fails writes and flushes separately;
`AppTest` checks failed prompt/no read, failed response/no next command,
single-attempt startup/input/command-error reporting and failed goodbye. A real
temporary save followed by output failure retains its task, next ID and plan
invalidation. `MainTest` injects failed stdout inside child JVMs for prompt and
argument-error paths, asserting status 1 and quiet stderr. An unrelated unchecked
storage I/O defect must still propagate. The process fixtures need no output
changes and do not claim real device-failure or other-platform coverage.

Time-sensitive task/planning checks use fixed clocks through injected APIs;
there is no production clock flag. `FileStorageTest` covers deterministic
filesystem and replacement failures. Injected temporary-write failures and
unsupported-atomic-move release checks remain unexecuted; fake-storage rollback
is not evidence of those provider failures.

The supplied contract's section 7 uses a different persistence format and
forced flushing from the committed Save module. These fixtures use the actual
committed codec (plain names last and full weekday names). Printing preserves
that module; contract-format interoperability remains a Save-owner handoff.
See the Developer Guide for the exact differences.

## Issues #52 and #65 console acceptance

UI-033 starts with an overdue saved task, verifies an empty completed view,
rejects an unknown completion ID, completes the task, lists it, regenerates an
empty plan and repeats completion. Restart retains the zero-work record.
Deterministic dispatcher tests verify the no-op preserves plan identity and
injected save failure preserves the old disk/source/plan before retry.

## Issue #56 console acceptance

UI-034 restores a completed overdue task and a fixed version 6 plan. It rejects
an unknown ID, missing and zero estimates, and overflow, then verifies the
original schedule remains accessible. Reopening with 45 minutes clears that
plan and removes the task from the completed-only view; reopening an active
task is rejected. A restart retains the exact estimate and absent plan.
Replanning reports the restored overdue work, then progress credits it back to
completion and reopening with one minute persists through another restart.
Fixed-clock dispatcher JUnit tests verify actual eligible allocation, absent and
overdue deadlines, priority and occurrence preservation, settings, counters,
save ordering, and failed-save rollback/retry. Existing service and renderer
tests cover their unchanged lifecycle contracts. Process tests do not inject
filesystem failures; those remain covered at the storage and dispatcher boundaries.
