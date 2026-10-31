# Koh Ka How - Project Portfolio Page

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students
manage coursework tasks, fixed commitments and study time. It generates study
plans in 30-minute slots, reports work that cannot fit, and saves tasks,
commitments and generated plans for later sessions.

## Summary of Contributions

**Code contributed:** [Personal RepoSense code view][reposense] | [GitHub profile][github].
I implemented the application entry point, console loop, session state,
command and response records, console I/O and output rendering in `Main`,
`App`, `AppState`, `Command`, `CommandResponse`, `ConsoleUi` and `TextRenderer`.

### Recoverable console interaction

- **What it does:** Connects startup, command input and responses into a working
  console session. Students can correct syntax, validation and save errors at
  the next prompt; `exit` and end of input terminate normally.
- **Justification:** Students can recover from mistakes without restarting or
  re-entering their saved work. Invalid saved data stops startup with an error
  before accepting commands.
- **Highlights:** `Main` supplies explicit UTF-8 streams, storage and a clock.
  `App` coordinates the loop through the team's parser and dispatcher;
  `Command` records and `CommandResponse` carry requests and results.
  `AppState` publishes saved snapshots, clears outdated plans after changes,
  and rejects plans derived from different data. Injected dependencies let
  tests exercise failure paths without the developer's saved files.
- **Output-failure hardening:** [Console I/O][console-source] now checks failed
  writes and flushes. The [application loop][app-source] returns status 1 before
  another command read and never tries to print another error to failed output.
  Startup-error and goodbye writes follow the same rule; `Main` also handles
  failure while reporting invalid process arguments. Already-saved changes
  remain durable, so students can restart and inspect them before retrying.

### Consistent task, commitment and schedule output

- **What it does:** Formats help, confirmations, lists, errors, plan summaries
  and daily schedules. Displays task progress, completion/reopening and
  `[BREAK]` rows, with whole-plan unallocated-work explanations.
- **Justification:** Stable IDs, clear schedule labels and explicit remaining
  work help students understand their workload and choose their next action.
- **Highlights:** Rendering performs no console I/O, clock reads or state
  changes. It preserves Unicode names and schedule boundaries, fixes
  date/weekday formatting across default locales, displays commitment midnight
  endpoints as `24:00`, and uses `long` totals for large workloads.

### Testing

Extended the [three-platform CI workflow][ci-workflow] to run the full Java 25
build and packaging, Node runner self-tests, active console scenarios and the
assertions-enabled JAR/distribution checks sequentially. Each OS retains its
JUnit/Checkstyle reports and console/release transcripts even after failure;
saved data and diagnostic logs are excluded. This makes packaging and console
regressions visible alongside unit-test failures. Hosted execution is pending;
a local check does not establish that all three hosted platforms passed.

Wrote JUnit 5 tests for the application entry point, console loop, live state,
command contracts, console I/O and rendering. [Application tests][app-tests]
cover startup, restart and recovery from invalid input or failed saves;
[state tests][state-tests] check plan invalidation and stale-plan rejection.
[Rendering tests][renderer-tests] assert exact output for empty results,
Unicode names, non-English default locales, midnight, leap days and large
duration totals. Tests use fixed clocks, temporary storage and restored JVM
locale settings to remain deterministic and independent.

Added controlled write/flush failures in [console tests][console-tests],
single-attempt output-failure and real-save/reload cases in the application
tests, and isolated child-JVM stdout failures in [entry-point tests][main-tests].
The tests check no command read after failed output, status 1, quiet default
stderr and propagation of unrelated unchecked storage I/O defects. The updated
[class view][current-class-diagram] and [sequence view][current-sequence-diagram]
show the writer check and termination boundary; these controlled
tests do not claim actual broken-pipe or cross-platform evidence.

### Documentation

- **User Guide:** Wrote the introduction, [command conventions][ug-format],
  [commitment-list explanation and examples][ug-commitments],
  [schedule labels][ug-labels], [worked schedule example][ug-example],
  [help and exit instructions][ug-help], and [command reference][ug-summary].
  Maintained the page metadata, contents, section headings and navigation.
- **Developer Guide:** Wrote [console design][dg-design] and
  [recoverable interaction/rendering][dg-implementation], including a
  [console class diagram][class-diagram] and
  [command-loop sequence diagram][sequence-diagram]. Also documented
  [setup][dg-setup], [build/console verification][dg-verification],
  [product scope][dg-scope] and [manual console tests][dg-manual], and
  reorganized the guide around the AB3 structure. These changes are in
  [PR #100][pr100].

### Team-based tasks

- **Issue tracking:** Created user stories for [viewing completed tasks][issue65]
  and [weekly recurring tasks][issue67]. Created documentation tasks for
  [Developer Guide updates][issue96], [User Guide updates][issue97] and
  [team portfolios][issue98] to track the team's deliverables.
- **Release:** Published [ScheduleFlow v1.0][release] and uploaded its executable
  `scheduleflow.jar`, with release notes, Java 25 launch instructions and
  usage limitations.
- **Documentation upkeep:** Corrected the [About Us table and links][issue18].
  Added contributor guidance requiring Javadoc for declaration documentation
  and meaningful JUnit regression tests for changes to Java behaviour.

[reposense]: https://NUS-CS2113-AY2627-S1.github.io/tp-dashboard/?search=ka-how&breakdown=true
[github]: https://github.com/ka-how
[pr100]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/100
[app-tests]: ../../src/test/java/scheduleflow/cli/AppTest.java
[state-tests]: ../../src/test/java/scheduleflow/cli/AppStateTest.java
[renderer-tests]: ../../src/test/java/scheduleflow/cli/TextRendererTest.java
[ci-workflow]: ../../.github/workflows/gradle.yml
[app-source]: ../../src/main/java/scheduleflow/cli/App.java
[console-source]: ../../src/main/java/scheduleflow/cli/ConsoleUi.java
[console-tests]: ../../src/test/java/scheduleflow/cli/ConsoleUiTest.java
[main-tests]: ../../src/test/java/scheduleflow/MainTest.java
[current-class-diagram]: ../images/ka-how-console-classes.svg
[current-sequence-diagram]: ../images/ka-how-command-loop.svg
[issue18]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/18
[issue65]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/65
[issue67]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/67
[issue96]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/96
[issue97]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/97
[issue98]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/98
[release]: https://github.com/AY2627S1-CS2113-T10-1/tp/releases/tag/v1.0
[ug-format]: ../UserGuide.md#command-format
[ug-commitments]: ../UserGuide.md#listing-commitments
[ug-labels]: ../UserGuide.md#schedule-labels
[ug-example]: ../UserGuide.md#worked-schedule-example
[ug-help]: ../UserGuide.md#help-and-exit
[ug-summary]: ../UserGuide.md#command-summary
[dg-design]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#console-application-and-presentation
[dg-implementation]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#recoverable-console-interaction-and-deterministic-rendering
[class-diagram]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/images/ka-how-console-classes.svg
[sequence-diagram]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/images/ka-how-command-loop.svg
[dg-setup]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#setting-up-getting-started
[dg-verification]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#build-packaging-and-console-verification
[dg-scope]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#appendix-a-product-scope
[dg-manual]: https://github.com/ka-how/tp/blob/b6c0c83c742fbdae635ab69e493e2c69200aac80/docs/DeveloperGuide.md#launch-help-exit-and-console-behavior
