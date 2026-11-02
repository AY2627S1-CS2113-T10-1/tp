# Koh Ka How's Project Portfolio Page

### Project: ScheduleFlow

<!-- @@author ka-how -->

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students
manage coursework, fixed commitments and study time. It creates study plans in
30-minute slots, reports work that cannot fit before its deadline, and saves
data and generated plans for later sessions.

## Summary of Contributions

Given below are my contributions to the project.

- **New Feature: Added console error recovery and session state management**
  ([PR #29][pr29]).
  - **What it does:** Runs startup and the command loop. Students can correct
    invalid commands and retry failed saves at the next prompt. `exit` and end
    of input close the session normally. Corrupt saved data stops startup
    before commands are accepted.
  - **Justification:** Students can fix input mistakes without restarting.
    Rejected commands leave their saved records and current plan unchanged.
  - **Highlights:** Separated console input/output, text formatting and session
    state. Used typed commands and responses, and supplied storage, clocks and
    streams for predictable tests. `AppState` updates the current data after a
    successful save, clears outdated plans and rejects plans based on different
    data. Coordinating error recovery across the team's parser, services and
    storage was the main challenge; unexpected programming errors remain visible.

- **New Feature: Added consistent task and schedule output**
  ([PR #22][pr22], [PR #87][pr87]).
  - **What it does:** Displays help, confirmations, task and commitment lists,
    progress, completion/reopening, daily schedules and `[BREAK]` rows. Explains
    work left unscheduled across the full plan.
  - **Justification:** Stable IDs, clear labels and explicit remaining work help
    students understand their workload and choose their next action.
  - **Highlights:** Kept text formatting separate from input/output, clock reads
    and data changes. Preserved Unicode names and schedule boundaries, made dates
    independent of system language, displayed midnight as `24:00`, and used
    `long` totals to avoid integer overflow. Showed actual work separately from
    reserved time: 45 minutes of work can occupy a 60-minute interval.

- **Code contributed:** [RepoSense code dashboard][reposense]. My area
  comprises `Main`, `App`, `AppState`, `Command`, `CommandResponse`, `ConsoleUi`
  and `TextRenderer`, plus their tests.

- **Project management:**
  - Set up the shared Java 25 project and interfaces so team members could work
    independently ([PR #1][pr1]).
  - Published **two releases**, [v1.0][release1] and [v2.0][release2], uploading
    `scheduleflow.jar` with release notes, launch instructions and limitations.
  - Created user stories for [viewing completed tasks][issue65] and
    [weekly recurring tasks][issue67], and tracked the team's
    [UG][issue97], [DG][issue96] and [PPP][issue98] deliverables.

- **Enhancements to existing features:**
  - **Console reliability:** Detected output write and flush failures that Java's
    writer otherwise hides. Stopped with status 1 before reading another command,
    without retrying failed output, and kept already-saved changes. Added 12
    regression tests using failing writers and separate Java processes
    ([PR #123][pr123]).
  - **Reopening completed tasks:** Connected the console command to the existing
    reopening service so students can correct mistaken completion. Kept task
    details and saved changes before clearing the outdated plan ([PR #91][pr91]).
  - **Regression coverage:** Added JUnit 5 tests for startup/restart, recovery
    after failed saves, rejection of outdated plans and exact output. Covered
    Unicode, empty results, midnight, leap days, non-English language settings
    and large totals using fixed clocks, temporary storage and restored JVM settings
    ([application tests][app-tests], [renderer tests][renderer-tests],
    [entry-point tests][main-tests]).
  - **Console acceptance tests:** Built a test runner that checks each command's
    response and records transcripts in isolated test folders ([PR #1][pr1]);
    maintained the [UI plan][ui-plan]. Added UI-035 for saved plans with impossible
    work totals and UI-036 for editing commitments after skipped occurrences.
    Checked rejection, retained data/plans and restarts.

- **Documentation:**
  - **User Guide:** Wrote the introduction, [command conventions][ug-format],
    [commitment-list examples][ug-commitments], [schedule labels][ug-labels],
    [worked schedule example][ug-example], [help/exit][ug-help] and
    [command summary][ug-summary]. Improved navigation and checked examples
    against console behaviour ([PR #106][pr106], [PR #134][pr134]).
  - **Developer Guide:** Wrote [console design][dg-design],
    [interaction/rendering][dg-implementation], setup, verification, scope and
    manual console testing; reorganized the guide around AB3 ([PR #100][pr100]).
    Added the [console class diagram][class-diagram] and
    [command-loop sequence diagram][sequence-diagram], then added the
    architecture overview and corrected notation across all eleven guide
    diagrams ([PR #120][pr120]).
  - Exported both guides as PDFs and checked their content, links and rendered
    layouts for portable offline documentation ([PR #119][pr119]).

- **Team-based tasks:**
  - Reviewed saved-plan checks and tests for overlaps, timing, task references
    and work totals ([PR #127 review][review127]). Commented on recurring-task
    tests across components and saved restarts
    ([PR #76 review][review76]).
  - Added contributor guidance requiring Javadoc and meaningful JUnit regression
    tests, and repaired team profile/portfolio navigation ([PR #22][pr22],
    [PR #101][pr101]).

- **Tools:** Updated the existing Ubuntu/macOS/Windows CI checks to run the
  Java 25 build, runner self-tests, console scenarios and packaged release
  checks, retaining reports after failures ([PR #123][pr123]). Fixed Shadow JAR
  distribution classpaths and enabled launcher assertions ([PR #35][pr35]).
  CI configuration is not evidence that every hosted platform passed.

<!-- @@author -->

[reposense]: https://NUS-CS2113-AY2627-S1.github.io/tp-dashboard/?search=ka-how&breakdown=true
[pr1]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/1
[pr22]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/22
[pr29]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/29
[pr35]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/35
[pr87]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/87
[pr91]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/91
[pr100]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/100
[pr101]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/101
[pr106]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/106
[pr119]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/119
[pr120]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/120
[pr123]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/123
[pr134]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/134
[review127]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/127#pullrequestreview-5725879665
[review76]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/76#pullrequestreview-5623696993
[release1]: https://github.com/AY2627S1-CS2113-T10-1/tp/releases/tag/v1.0
[release2]: https://github.com/AY2627S1-CS2113-T10-1/tp/releases/tag/v2.0
[issue65]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/65
[issue67]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/67
[issue96]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/96
[issue97]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/97
[issue98]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/98
[app-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/cli/AppTest.java
[renderer-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/cli/TextRendererTest.java
[main-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/MainTest.java
[ui-plan]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/test/ui-test-plan.md
[ug-format]: ../UserGuide.md#command-format
[ug-commitments]: ../UserGuide.md#listing-commitments
[ug-labels]: ../UserGuide.md#schedule-labels
[ug-example]: ../UserGuide.md#worked-schedule-example
[ug-help]: ../UserGuide.md#help-and-exit
[ug-summary]: ../UserGuide.md#command-summary
[dg-design]: ../DeveloperGuide.md#console-application-and-presentation
[dg-implementation]: ../DeveloperGuide.md#recoverable-console-interaction-and-deterministic-rendering
[class-diagram]: ../images/diagrams/ka-how-console-classes.svg
[sequence-diagram]: ../images/diagrams/ka-how-command-loop.svg
