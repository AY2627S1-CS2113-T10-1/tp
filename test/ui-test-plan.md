# ScheduleFlow UI test plan

All eight maintained process scenarios are active. They exercise the packaged
JAR in fresh temporary folders, with restart sessions sharing only their own
saved data. Review this plan and `ui-scenarios.json` after each coherent code
update. The fixtures are the executable source of truth for exact responses.

Run from the repository root with Java 25 and Node.js 22 or later:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
```

Use `./gradlew` on Unix. If needed, set `GRADLE_USER_HOME` to a writable cache.
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

| ID | Coverage | Status |
| --- | --- | --- |
| UI-001 | Missing file, empty lists and absent plan | Active |
| UI-002 | Adjacency, midnight and commitment IDs after deletion/restart | Active |
| UI-003 | EOF prints goodbye and exits 0 | Active |
| UI-004 | Unsupported store version stops before prompting | Active |
| UI-005 | Strict syntax, usage hints and recovery including `exit extra` | Active |
| UI-006 | Blank lines, duration/alignment/overlap errors, unknown IDs and retry IDs | Active |
| UI-007 | Unicode save/restart and temporary-plan loss | Active |
| UI-008 | Truncated saved record stops startup with status 1 | Active |

UI-001 through UI-005 were activated when their console dependencies became
implemented. Preflight still reports ownership-marked stubs and blocks only
dependent scenarios; ready active cases must run even if another component is
unfinished. Exit 0 means active scenarios passed, exit 1 means FAILED, and
exit 2 means BLOCKED. A planned or diagnostic scaffold case is never a pass.

## Fixed-clock acceptance and remaining checks

`CommandDispatcherTest` covers every command, single clock capture, every
save/commit transaction and failed retry, unchanged snapshots/counters/plans,
no saves for read-only commands, deadline limits, numeric ID ordering, partial
and blocked plans, cutoff seconds, range limits, injected timezone, frozen
plans and replacement. It checks the corrected earliest-slot schedule and
preserves stored estimates. Existing optimizer tests verify conservation and
nonoverlap across mixed inputs.

`AppTest` covers first launch without file creation, one startup load, startup
corruption with preserved bytes, parse/domain/save recovery, stable task IDs
across real file restarts, overdue tasks, rollback with existing file bytes,
empty-plan clipping/midnight output, invalidation, input IOException and
exit/EOF without saving. `ConsoleUiTest` asserts flushes and exact framing.
`MainTest` launches separate production-entry-point processes to check
nonempty argument rejection, UTF-8 despite ASCII defaults, EOF and real saved
restarts. The maintained UI cases verify the packaged manifest entry point.

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
