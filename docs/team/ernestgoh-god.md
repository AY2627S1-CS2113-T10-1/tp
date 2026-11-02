# Ernest Goh Chin Wee's Project Portfolio Page

### Project: ScheduleFlow

<!-- @@author ernestgoh-god -->

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students
manage coursework, fixed commitments and study time. It generates study plans in
30-minute slots, explains work that cannot fit before its deadline, and saves
records and frozen plans for later sessions.

## Summary of Contributions

Given below are my contributions to the project.

- **New Feature: Added automatic saving and loading**
  ([PR #24][pr24], [PR #31][pr31]).
  - **What it does:** Saves and restores tasks, commitments and independent
    next-ID counters as versioned UTF-8 text. Extensions preserve study hours,
    policies, recurring templates and occurrence metadata.
  - **Justification:** Students can continue after closing the application
    without re-entering work or receiving reused IDs after deletion.
  - **Highlights:** Writes a sibling temporary file and atomically replaces the
    store. Validates the complete file and returns an empty first-launch state
    only when the file is definitely absent. Invalid or unreadable files never
    produce partial state. Added preconditions, assertions, Javadoc and opt-in
    diagnostics that omit saved values.

- **New Feature: Added frozen study-plan restoration** ([PR #89][pr89]).
  - **What it does:** Stores a plan and its complete source snapshot together,
    restoring the original generation time, sessions, exact credited work,
    unallocated work and breaks without regenerating the schedule.
  - **Justification:** Regenerating after a restart can move sessions as time
    changes. Restoring the saved result lets students resume the same plan.
  - **Highlights:** Extended storage and startup/dispatch integration to save
    source and result in one atomic replacement. Reconciled recurrence-format
    changes and retained legacy readers. Successful source changes remove stale
    plans; failed saves preserve the previous live state and plan.

- **Code contributed:** [RepoSense code dashboard][reposense]. My area comprises
  persistence through `FileStorage`, `StateCodec`, `PlanCodec` and the
  `Snapshot` aggregate, plus their tests. My other contributions include
  task-command parsing, save/dispatch integration and study-policy persistence,
  building on the team's task services, allocator and rendering.

- **Team contributions:**
  - **Integration:** Reconciled persistence with teammates' exact-work, recurrence,
    study-hours and policy changes, retaining their metadata and saving source
    and plan changes together ([PR #84][pr84], [PR #89][pr89], [PR #90][pr90]).
  - **Authorship allocation:** Applied the agreed whole-file RepoSense allocation
    to storage, `Snapshot` and assigned tests ([PR #94][pr94]). These tags record
    maintenance responsibilities; Git history retains original implementation
    credit.

- **Enhancements to existing features:**
  - **Task controls:** Added progress parsing and save/publication integration,
    completed remaining-time commands, and connected completion and completed
    views. Covered no-ops, rejected inputs, failed saves and saved restarts
    ([PR #77][pr77], [PR #83][pr83], [PR #84][pr84], [PR #90][pr90]). This uses
    Nickhil's lifecycle services; Ka How completed progress confirmation rendering.
  - **Study policies:** Added set/show/reset commands and persisted policies in
    snapshots and plans. Equal settings preserve a plan; changed settings save
    before publication and feed Gabriel's continuous-work allocator ([PR #90][pr90]).
  - **Unavailable-period parsing:** Added continuous/daily range parsing and
    malformed-input tests ([PR #88][pr88]). Darren subsequently completed the
    dispatcher routes and atomic batches ([PR #126][pr126]); my contribution was
    parsing and its tests.
  - **Save-failure hardening:** Added a filesystem-operation seam and real-file
    tests for temporary creation, partial writes, unsupported atomic moves,
    replacement errors and cleanup failures ([PR #124][pr124]). Both snapshot
    and plan saves preserve old bytes on rejection. Cleanup retains the original
    cause and suppressed errors; production saves still require atomic moves.
    Controlled Windows failures do not establish other-provider or power-loss
    durability.
  - **Saved-plan validation:** Contributed the storage integration in the
    [collaborative release follow-up][release-follow-up], connecting Gabriel's
    `Planner.validate(Plan)` to encoding and decoding. The integration reuses his
    planning rules to reject infeasible allocations and incorrect accounting,
    preserve causes and old bytes, and accept alternative valid frozen schedules.
    See [storage verification][dg-verification].
  - **Regression coverage:** [FileStorageTest][storage-tests] uses temporary
    folders and fresh readers; [StateCodecTest][state-tests] and
    [PlanCodecTest][plan-tests] isolate conversion from filesystem access.
    Coverage includes Unicode/pipe-containing names, legacy formats, deleted-ID
    counters, exact work, corrupt data, frozen-plan round trips and cleanup.
    Parser, dispatcher, application and packaged tests cover policy settings,
    rollback and restarts using isolated data and fixed clocks.
  - **Portability:** Corrected a startup process test to compare filesystem
    identity across macOS aliases and Windows short names while retaining exact
    error framing and data-preservation checks ([PR #109][pr109]).

- **Documentation:**
  - **User Guide:** Updated [quick start][ug-start],
    [duration guidance][ug-duration], [task listings][ug-list], the
    [frozen-plan example][ug-frozen], [saving/restoration][ug-save] and recovery
    FAQs. Clarified estimates versus reserved capacity, data location,
    compatibility, backups and plan invalidation. Updated the guide and its
    existing PDF for v2.1 ([PR #137][pr137]).
  - **Developer Guide:** Wrote [persistence design][dg-design],
    [atomic saving/restoration][dg-implementation],
    [storage verification][dg-verification], [non-functional requirements][dg-nfr]
    and [manual recovery tests][dg-manual], and updated acknowledgements.
    Created the original [storage class diagram][class-diagram] and
    [atomic-save sequence][sequence-diagram], with PlantUML sources and SVGs
    ([PR #107][pr107]); updated my diagram sources and renders for the failure
    seam ([PR #124][pr124]). Later team revisions refine notation and show
    validation before file changes.
  - **Website:** Refreshed the homepage, moved its quick start to a separate
    linked page, and added a program image using real console output
    ([PR #140][pr140]).

- **Community:**
  - **Reviews:** Reviewed rendering ([PR #22][pr22], [PR #87][pr87]),
    planner-policy compatibility ([PR #81 review][review81]), console
    documentation ([PR #100][pr100]), UML/source consistency
    ([PR #120 review][review120]), output-failure handling and CI
    ([PR #123 review][review123]), and release validation/documentation scope
    ([PR #134 review][review134]). My output-failure review checked that durable
    saves are retained after failed confirmations; the implementation is Ka How's.

<!-- @@author -->

[pr24]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/24
[pr31]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/31
[pr89]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/89
[reposense]: https://nus-cs2113-ay2627-s1.github.io/tp-dashboard/?search=ernestgoh-god&breakdown=true
[pr77]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/77
[pr83]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/83
[pr84]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/84
[pr90]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/90
[pr88]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/88
[pr124]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/124
[dg-verification]: ../DeveloperGuide.md#storage-diagnostics-and-compatibility-verification
[storage-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/storage/FileStorageTest.java
[state-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/storage/StateCodecTest.java
[plan-tests]: https://github.com/AY2627S1-CS2113-T10-1/tp/blob/master/src/test/java/scheduleflow/storage/PlanCodecTest.java
[ug-start]: ../UserGuide.md#quick-start
[ug-duration]: ../UserGuide.md#recording-duration-tasks-and-commitments
[ug-list]: ../UserGuide.md#listing-tasks
[ug-frozen]: ../UserGuide.md#planning-partway-through-the-day
[ug-save]: ../UserGuide.md#saving-and-restoring-data
[dg-design]: ../DeveloperGuide.md#persistence-and-the-snapshot-aggregate
[dg-implementation]: ../DeveloperGuide.md#atomic-persistence-and-restoring-frozen-plans
[dg-nfr]: ../DeveloperGuide.md#appendix-c-non-functional-requirements
[dg-manual]: ../DeveloperGuide.md#saving-restoration-failure-and-recovery
[class-diagram]: ../images/diagrams/ernest-storage-classes.svg
[sequence-diagram]: ../images/diagrams/ernest-atomic-plan-save.svg
[pr107]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/107
[pr94]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/94
[pr126]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/126
[release-follow-up]: https://github.com/AY2627S1-CS2113-T10-1/tp/commit/f8e2f010a90b88ff04d256b45515ed66fb62c5c1
[pr109]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/109
[pr137]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/137
[pr140]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/140
[pr22]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/22
[pr87]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/87
[pr100]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/100
[review81]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/81#pullrequestreview-5628104327
[review120]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/120#pullrequestreview-5710123704
[review123]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/123#pullrequestreview-5721762790
[review134]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/134#pullrequestreview-5726267913
