# ScheduleFlow UI test plan

The application loop is unfinished. Parsing is covered by `CommandParserTest`,
and all text formatting by `TextRendererTest`; no process UI
feature scenario has passed.

Basic storage checkpoint: `StateCodec` and `FileStorage` are implemented with
isolated JUnit tests. UI-001 through UI-005 remain planned because their Main,
App and other console dependencies are unfinished. UI-004's unsupported-version
message matches the codec, so its fixture needs no output change. Storage unit
tests do not certify console restart or in-memory rollback.

`ui-scenarios.json` is the executable source of truth for IDs, aims, setup,
ordered commands, exact per-command output, exit codes and active/planned
status. Review it after every code update. These initial CLI cases are not
complete release coverage; expand them alongside the behaviours described in
the [User Guide](../docs/UserGuide.md) as features are implemented.

Run with Java 25 and Node.js 22+:

```text
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
node --test test/ui-runner.test.mjs
```

On Windows, the Gradle wrapper is `gradlew.bat`; on Unix it is `./gradlew`.
The runner invokes `shadowJar` itself and checks Java 25. If the environment's
default Gradle home is unwritable, set `GRADLE_USER_HOME` to your writable cache
before running. Build timeout: 180 seconds. Startup, each command and exit:
5 seconds each. The first failure terminates that application and the session.
Transcripts are generated in ignored `build/ui-transcripts/`.

## Exact comparison protocol

- UTF-8 stdin/stdout/stderr. Convert CRLF to LF only; retain all other spaces,
  punctuation, lines and ordering. No dynamic placeholders or regex matching.
- `startup` is literal text before the first `scheduleflow> ` prompt (normally
  empty). For startup failure, specify `exitCode` and no steps instead.
- Each `steps` entry sends its `input` plus LF, waits for `output` plus LF and
  the exact next prompt, and compares before sending any subsequent input.
  `input: null` closes stdin. An `exitCode` expects termination instead of a
  prompt; it must appear only on the last step. Set `newline: false` for a
  prompt-only response to an ignored blank line; `output` must then be empty.
  CRLF split across pipe chunks is buffered until comparison is possible.
- Stderr must be empty during feature scenarios. Unexpected output, stderr,
  exit or timeout fails the case. The transcript includes the failing position,
  expected/actual output, captured stderr and process exit.
- Every case starts in its own temporary directory. Optional `files` maps
  relative paths to exact UTF-8 initial file contents. Multiple `sessions`
  share that case directory for restart testing, but not a process; steps
  inside each session share one live process. Cleanup never touches real data.
- Lists supplied by `--commands` and `--expected-outputs` must have matching
  lengths. Their final command must exit with status 0 (or use null for EOF).

## Starter readiness

Commitment addition is now covered by `CommitmentServiceTest`, including
overlap rejection, adjacency, midnight, ID exhaustion and unchanged input
snapshots. A planner integration test verifies that an added commitment blocks
study slots. UI-002 remains planned: deletion, listing, console I/O, application
dispatch and persistence integration are still unfinished. No console or restart
acceptance is established by these service tests; fixture expectations remain unchanged.

All maintained cases are **planned pending implementation**. Preflight lists
every ownership-marked stub and each unexecuted case and returns **BLOCKED**
(exit 2). A build failure is FAILED (exit 1). Do not treat scaffold exceptions
as successful feature checks. Once components exist, review and activate the
cases; active failures must be fixed before commits. Foundation-only commits
may proceed with passing Java tests/checks and recorded BLOCKED UI status.

| ID | Coverage | Readiness |
| --- | --- | --- |
| UI-001 | Empty lists and absent plan | Planned |
| UI-002 | Adjacency, midnight and commitment counters/restart | Planned |
| UI-003 | EOF | Planned |
| UI-004 | Unsupported store version | Planned; error wording chosen for fixture |
| UI-005 | Syntax errors include usage and permit the next command | Planned; parser ready, console dependencies unfinished |

UI-004's error wording is a proposed exact regression baseline, not a new
behaviour requirement; the Save/Printing owners may update it when agreeing
their error text. No broad error wildcard is permitted.

Time-sensitive deadline, planning and schedule behaviours require fixed-clock
JUnit tests through the injected App/Planner APIs. Do not add a production
clock flag or change Main's API to make these fixtures work. Add a test-only
launcher and explicit fixtures if fixed-clock process tests are later needed.
Save-failure rollback checks require fake storage or deterministic failure
injection. Their release checks remain
unexecuted until the responsible modules are implemented.

The shared declarations, `CommandParser.parse` and all `TextRenderer` methods
are implemented. Review of UI-001 through UI-004 found that their application
and service dependencies still prevent activation. UI-005 adds the planned
process check for parsing errors and recovery, with exact usage hints. It also
requires the pending application loop and startup storage integration. Run all
ready active scenarios when those dependencies are implemented; parser unit
tests do not establish console recovery or unchanged persisted state.

Formatter coverage verifies exact empty/no-plan text, syntax-error usage hints,
all command formats in help, weekday groups, midnight endpoints, stable IDs,
stored durations, complete long/Unicode names and LF without trailing newlines.
Plan/schedule checks cover empty, complete and partial results (including zero
allocation), all unallocated reasons, whole-plan totals with long arithmetic,
all labels, session-specific minutes, cutoff notes, frozen metadata, supplied
row boundaries and plan-wide footers. The corrected schedule example uses the
existing planner and schedule service, but is not a process UI scenario.
No fixture is ready for activation solely because formatting is implemented;
the process dependencies above remain required.

Additional formatter JUnit checks cover years 0001/9999, a leap-day view whose
generation date differs from its selected date, and unchanged English output
under French/Arabic JVM defaults. Locale checks restore defaults and run in
isolation. These checks leave process fixtures and readiness unchanged.

Each fixture's `requiredComponents` lists the Type.method implementations
needed for that scenario. Preflight reports all unfinished methods, blocks
only dependent cases, and still runs ready active cases. Ad-hoc lists without
a dependency list conservatively require the full CLI. A ready planned case
must be reviewed and activated by its owner; planned is never a pass.

When Main.main is still an explicit stub, the runner also launches the real
packaged JAR in a separate empty temporary directory, closes stdin, and records
its actual startup output and exit. This is a diagnostic scaffold probe, never
a feature pass. Once Main exists, active scenarios run the real packaged JAR.
