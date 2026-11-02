# Darren's Project Portfolio Page

### Project: ScheduleFlow

<!-- @@author Darren50 -->

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students
manage coursework, fixed commitments and study time. It generates study plans in
30-minute slots, explains work that cannot fit before its deadline, and saves
records and frozen plans for later sessions.

## Summary of Contributions

Given below are my contributions to the project.

- **New Feature: Added recurring commitments**
  ([PR #23][pr23], [PR #26][pr26], [PR #34][pr34], [PR #70][pr70]).
  - **What it does:** Adds, deletes and lists weekly commitments such as lectures
    and tutorials. Records receive stable `C` IDs that are never reused, and
    listing groups them by weekday and start time.
  - **Justification:** A realistic study plan must reserve time when the student
    is already busy.
  - **Highlights:** Designed immutable candidate snapshots for save-before-
    publication dispatch. Reports every overlap in start-time order while
    allowing adjacent intervals. Added defensive contracts, Javadoc and opt-in
    diagnostics that expose execution stages without leaking record details.

- **New Feature: Added commitment editing date bounds and skipped occurrences**
  ([PR #71][pr71], [PR #78][pr78], [PR #79][pr79]).
  - **What it does:** Edits selected fields without changing the ID, bounds a
    series with `from/` and `until/`, and cancels one occurrence with
    `commitment skip`.
  - **Justification:** Students can correct class times, limit classes to a
    semester and reclaim cancelled sessions without deleting a whole series.
  - **Highlights:** Validates the complete replacement and excludes only its
    original record from conflict checks. Calendar-aware overlap detection lets
    different semesters reuse a weekly slot and skipped dates become available.
    The extended storage format remains backward compatible.

- **New Feature: Added one-time commitments and multi-date unavailable periods**
  ([PR #80][pr80], [PR #86][pr86], [PR #126][pr126]).
  - **What it does:** Blocks a single date, a continuous interval across dates,
    or the same daily window over a date range.
  - **Justification:** Meetings, trips and appointments should reserve only
    their actual dates rather than repeating every week.
  - **Highlights:** Represents one-time events using equal start/end dates to
    reuse existing overlap, storage and planning rules. Continuous ranges split
    at midnight; daily ranges expand into dated records. Dispatch prepares and
    saves the whole batch before publication. Failure preserves state and IDs.

- **Code contributed:** [RepoSense code dashboard][reposense]. My area comprises
  `Commitment`, `CommitmentService` and `CommandDispatcher`, plus their tests.

- **Project management:**
  - Scoped and delivered commitment work through five tracked issues:
    [editing][issue42], [unavailable ranges][issue48],
    [one-time commitments][issue53], [semester date bounds][issue64] and
    [skipped occurrences][issue66]. Each was linked to an implementation PR
    with tests and documentation.
  - Developed the feature incrementally: core recurring operations first,
    defensive hardening and diagnostics next, followed by editing/date features
    and finally multi-date dispatch and acceptance coverage, keeping changes
    reviewable and exposing integration problems early.
  - Prepared the final product demonstration: planned the command sequence,
    created at least 20 realistic records, recorded the workflow and narrated
    tasks, commitments, availability, planning, progress and persistence.

- **Enhancements to existing features:**
  - **Command resilience:** Converted incomplete multi-date `unavailable`
    transports from runtime failures into ordinary validation responses. The
    dispatcher now rejects them without changing saved commitments, IDs or the
    active plan, with regression and UI-plan coverage ([PR #113][pr113]).
  - **Regression coverage:** Added [service tests][service-tests] and
    [model tests][model-tests] for validation, adjacency, conflict ordering,
    date bounds, skips, one-time events, counter exhaustion, immutability and
    range expansion. [Dispatcher tests][dispatcher-tests] check batch
    prepare/save/publish order, failed-save retries, plan/ID preservation,
    unrelated snapshot data, later-segment rollback and insufficient ID
    capacity.
  - **Integration testing:** Added fixed-date tests showing that task and
    commitment durations survive saving and partial allocation ([PR #30][pr30]).
    Added packaged scenarios for edits and unavailable ranges across restarts,
    independent deletion, invalid input, later-date conflicts, midnight
    boundaries and leap day.

- **Documentation:**
  - **User Guide:** Maintained the [commitment chapter][ug-commitments], including
    adding, editing, skipping, deleting and one-time events, plus
    [unavailable study periods][ug-unavailable] and
    [blocking multiple dates][ug-ranges].
  - **Developer Guide:** Wrote [commitment/dispatch design][dg-design],
    the [implementation walkthrough][dg-implementation],
    [transaction verification][dg-verification] and
    [manual tests][dg-manual], including the command index. Added the
    [class diagram][class-diagram] and [edit sequence][sequence-diagram]
    with PlantUML sources and rendered SVGs. Explained the one-time-event
    representation in the [design considerations][dg-design-considerations].

- **Team-based tasks:**
  - Reviewed and approved text rendering ([PR #22 review][review22]), task
    operations ([PR #25 review][review25]) and console/dispatch integration
    ([PR #29 review][review29]), checking contracts used by commitment dispatch.
  - Performed final documentation reviews for repaired guide/portfolio links and
    shared navigation ([PR #141 review][review141]), Nick's release portfolio
    clarification ([PR #142 review][review142]) and Gabriel's editable portfolio
    deliverable ([PR #143 review][review143]).
  - Applied the team's agreed RepoSense ownership markers to the commitment and
    dispatcher files ([PR #93][pr93]). These markers identify substantial
    maintenance areas while Git history preserves the original author of each
    change.

<!-- @@author -->

[pr23]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/23
[pr26]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/26
[pr34]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/34
[pr70]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/70
[pr71]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/71
[pr78]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/78
[pr79]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/79
[pr80]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/80
[pr86]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/86
[pr93]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/93
[pr113]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/113
[pr126]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/126
[issue42]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/42
[issue48]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/48
[issue53]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/53
[issue64]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/64
[issue66]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/66
[reposense]: https://nus-cs2113-ay2627-s1.github.io/tp-dashboard/?search=Darren50&breakdown=true
[service-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/commitment/CommitmentServiceTest.java
[model-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/model/CommitmentTest.java
[dispatcher-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/cli/CommandDispatcherTest.java
[pr30]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/30
[ug-commitments]: ../UserGuide.md#commitments
[ug-unavailable]: ../UserGuide.md#marking-a-study-period-unavailable
[ug-ranges]: ../UserGuide.md#blocking-multiple-dates
[dg-design]: ../DeveloperGuide.md#commitments-and-command-execution
[dg-implementation]: ../DeveloperGuide.md#dated-commitments-and-save-before-publication-dispatch
[dg-verification]: ../DeveloperGuide.md#commitmentdispatch-transaction-verification
[dg-manual]: ../DeveloperGuide.md#commitments-and-unavailable-periods
[class-diagram]: ../images/diagrams/darren-commitment-dispatch-classes.svg
[sequence-diagram]: ../images/diagrams/darren-commitment-edit.svg
[dg-design-considerations]: ../DeveloperGuide.md#commitment-design-considerations
[review22]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/22#pullrequestreview-5450577434
[review25]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/25#pullrequestreview-5477097175
[review29]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/29#pullrequestreview-5502347763
[review141]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/141#pullrequestreview-5727957456
[review142]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/142#pullrequestreview-5728892130
[review143]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/143#pullrequestreview-5728893449
