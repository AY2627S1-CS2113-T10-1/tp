# Ernest Goh - Project Portfolio Page

## Overview

ScheduleFlow is a Java 25 command-line application for university students managing coursework and fixed commitments. Students can record remaining work, generate a study plan within their available hours, and return to saved tasks, settings and schedules in later sessions.

## Summary of Contributions

**Code contributed:** [RepoSense code view][reposense] | [GitHub profile][github].

My main contribution was implementing and extending persistence through `FileStorage`, `StateCodec` and `PlanCodec`. I also contributed task-command parsing, save/dispatch integration and configurable study-policy persistence, building on the team's task services, allocator and rendering components.

### Automatic saving and loading

- **What it does:** [`FileStorage`][file-storage] and [`StateCodec`][state-codec] save and restore tasks, commitments and independent next-ID counters using versioned UTF-8 text. Later extensions preserve study hours, policies, recurring templates and occurrence metadata.
- **Justification:** Students should be able to continue their work after closing the application without re-entering records or receiving reused IDs after deletion.
- **Highlights:** I implemented sibling temporary-file writing and atomic replacement, complete-file validation, and an empty first-launch state only when the file is definitely absent. Invalid or unreadable data is rejected without returning partial state. I later added Javadoc, explicit preconditions, internal assertions and opt-in `FINE` diagnostics that omit saved values. See [PR #24][pr24] and [PR #31][pr31]. The team's application and dispatcher provide startup and command-loop orchestration.

### Restoring frozen study plans

- **What it does:** [`PlanCodec`][plan-codec] stores a generated plan together with its complete source snapshot. Startup restores the original generation time, sessions, exact credited work, unallocated work and breaks without regenerating the schedule.
- **Justification:** Regenerating after a restart can move sessions because the current time has changed. Saving the result lets students return to the same schedule.
- **Highlights:** I extended the storage interface and startup/dispatch integration to save the source and result in one atomic replacement. I reconciled the saved-plan format with upstream recurrence changes and preserved supported legacy readers. Successful source mutations remove stale plans; failed saves preserve the previous live state and plan. See [PR #89][pr89] for issue [#68][issue68].

### Task commands and study-policy integration

- **Task progress and remaining estimates:** Added parsing for both progress forms and connected validated task candidates to saving and publication. Completed the remaining-time command and covered no-ops, rejected inputs, failed saves and saved restarts. This work uses Nickhil's task lifecycle services; Ka How completed the progress confirmation rendering. See [PR #77][pr77], [PR #84][pr84] and [PR #83][pr83].
- **Completion and policy controls:** Connected task completion and completed-task viewing to the console. Added policy set/show/reset commands, persisted settings in snapshots and plans, and supplied them to Gabriel's existing continuous-work allocator. Equal settings preserve the plan, and changed settings save before publication. See [PR #90][pr90].
- **Prepared multi-date parsing:** Added parsing and malformed-input tests for proposed continuous/daily unavailable-period commands in [PR #88][pr88]. Their dispatcher routes remain unfinished; this contribution prepares the grammar and does not claim a working multi-date console feature.

### Testing

Added JUnit 5 storage and codec regression coverage for Unicode and pipe-containing names, legacy/current formats, deleted-ID counters, exact work, corrupt data and frozen-plan round trips. [`FileStorageTest`][storage-tests] uses temporary folders and fresh readers to check byte-preserving loads, blocked-parent failures and cleanup after rejected replacement. [`StateCodecTest`][state-tests] and [`PlanCodecTest`][plan-tests] check conversion independently of filesystem access.

I also extended parser, dispatcher and application tests for task controls, policy settings, no-op preservation and failed-save rollback, and added packaged console scenarios for saved restarts. Tests use isolated data and fixed clocks where time affects behaviour.

For release hardening, I added a package-private filesystem-operation seam and real-file tests for temporary creation failure, partial writes, unsupported atomic moves, replacement I/O errors and failed cleanup. The tests traverse `FileStorage`'s transaction, preserve the previous frozen plan's bytes and result, verify cleanup when possible, and retain the original cause plus suppressed cleanup errors. Both snapshot and plan saves exercise write and unsupported-move faults. Production operations still call `Files` and require atomic replacement without a fallback. These are controlled failures on Windows, not proof of other providers or power-loss durability. Full impossible-plan validation remains pending Gabriel's `Planner.validate(Plan)` boundary and its storage integration; I have not claimed that release requirement complete. See my [release handoff and verification evidence][release-hardening].

### Documentation

- **User Guide:** Updated the [quick start][ug-start], [duration guidance][ug-duration], [task-list explanation][ug-list], [frozen-plan example][ug-frozen], [saving/restoration chapter][ug-save] and related recovery FAQs. Clarified stored estimates versus reserved capacity, data location, compatibility, backups and plan invalidation.
- **Developer Guide:** Wrote the [persistence design][dg-design], [atomic-save/restoration explanation][dg-implementation], [storage diagnostics and verification][dg-verification], [non-functional requirements][dg-nfr] and [manual recovery tests][dg-manual], and updated acknowledgements. Added the [storage class diagram][class-diagram] and [atomic plan-save sequence][sequence-diagram], with PlantUML sources and rendered SVGs. These assigned guide contributions are in [PR #107][pr107].

### Team-based contributions

- **Integration:** Reconciled saved-plan persistence with teammates' exact-work, recurrence, study-hours and policy changes. Added regression coverage for retaining their metadata and publishing source/plan changes together. See [PR #84][pr84], [PR #89][pr89] and [PR #90][pr90].
- **Authorship allocation:** Applied the team's agreed whole-file RepoSense allocation to the storage files, `Snapshot` and their assigned tests in [PR #94][pr94]. These tags record maintenance boundaries; Git history retains who originally contributed each change.

[reposense]: https://nus-cs2113-ay2627-s1.github.io/tp-dashboard/?search=ernestgoh-god&breakdown=true
[github]: https://github.com/ernestgoh-god
[file-storage]: ../../src/main/java/scheduleflow/storage/FileStorage.java
[state-codec]: ../../src/main/java/scheduleflow/storage/StateCodec.java
[plan-codec]: ../../src/main/java/scheduleflow/storage/PlanCodec.java
[storage-tests]: ../../src/test/java/scheduleflow/storage/FileStorageTest.java
[state-tests]: ../../src/test/java/scheduleflow/storage/StateCodecTest.java
[plan-tests]: ../../src/test/java/scheduleflow/storage/PlanCodecTest.java
[pr24]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/24
[pr31]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/31
[pr77]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/77
[pr83]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/83
[pr84]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/84
[pr88]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/88
[pr89]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/89
[pr90]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/90
[pr94]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/94
[pr107]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/107
[issue68]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/68
[ug-start]: ../UserGuide.md#quick-start
[ug-duration]: ../UserGuide.md#recording-duration-tasks-and-commitments
[ug-list]: ../UserGuide.md#listing-tasks
[ug-frozen]: ../UserGuide.md#planning-partway-through-the-day
[ug-save]: ../UserGuide.md#saving-and-restoring-data
[dg-design]: ../DeveloperGuide.md#persistence-and-the-snapshot-aggregate
[dg-implementation]: ../DeveloperGuide.md#atomic-persistence-and-restoring-frozen-plans
[dg-verification]: ../DeveloperGuide.md#storage-diagnostics-and-compatibility-verification
[dg-nfr]: ../DeveloperGuide.md#appendix-c-non-functional-requirements
[dg-manual]: ../DeveloperGuide.md#saving-restoration-failure-and-recovery
[class-diagram]: ../images/ernest-storage-classes.svg
[sequence-diagram]: ../images/ernest-atomic-plan-save.svg
[release-hardening]: ../release/ernest-storage-handoff.md
