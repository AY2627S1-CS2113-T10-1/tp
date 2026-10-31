# Darren - Project Portfolio Page

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students plan coursework around deadlines and fixed commitments. Students record tasks and commitments, generate a study plan within their available hours, and continue from saved data in later sessions.

## Summary of Contributions

**Code contributed:** [RepoSense code view][reposense] | [GitHub profile][github].

My main contribution was the commitment feature: the `Commitment` model and `CommitmentService` operations for adding, editing, skipping, deleting and listing fixed commitments, including date-bounded and one-time commitments. I also implemented the service side of multi-date unavailable periods and maintain the command dispatcher under the team's whole-file allocation.

### Recurring commitments

- **What it does:** [`CommitmentService`][service] adds, deletes and lists weekly commitments such as lectures and tutorials. Each addition gets a stable `C` ID that is never reused, and listing groups commitments by weekday in start-time order.
- **Justification:** The planner can only produce a realistic schedule if it knows when the student is already busy.
- **Highlights:** Every operation returns a new candidate snapshot and never changes its source, so the dispatcher can save before publishing. Overlap errors report every conflicting commitment in start-time order instead of only the first, so students can fix a clash in one attempt. Adjacent intervals are allowed. I added opt-in `FINE` diagnostics that omit names and schedules, assertions for candidate invariants, and Javadoc. See [PR #23][pr23], [PR #26][pr26], [PR #34][pr34] and [PR #70][pr70].

### Editing, date bounds and skipped occurrences

- **What it does:** `commitment edit` changes any subset of fields while keeping the ID. Optional `from/` and `until/` dates limit a series to a semester. `commitment skip` cancels one occurrence without touching the rest of the series.
- **Justification:** Before editing existed, changing a lecture time meant deleting and re-adding it, which changed its ID. Semester-bounded classes otherwise blocked study time forever, and one cancelled class blocked a whole week's slot.
- **Highlights:** An edit validates the complete replacement and excludes only the target from overlap checks. Overlap checking compares actual calendar occurrences, so two semesters can reuse the same weekly slot, and a skipped date frees that time for another commitment. Date bounds and skips extended the storage format compatibly; older files remain readable. See [PR #71][pr71], [PR #78][pr78] and [PR #79][pr79].

### One-time commitments and unavailable periods

- **What it does:** `commitment add n/NAME on/YYYY-MM-DD ...` blocks time on a single date, for example a project meeting or an afternoon when the student is unavailable.
- **Justification:** Many fixed events happen only once; entering them as weekly series would block every week.
- **Highlights:** A one-time commitment is a commitment whose start and end dates are equal, so planning, schedule display and overlap checks reuse the existing date-bounded logic without a new command type or storage version. I compared this with a separate kind flag in the [Developer Guide design considerations][dg-design-considerations]. See [PR #80][pr80].
- **Multi-date unavailable periods:** For issue [#48][issue48], I implemented `addContinuousBlock` and `addDailyBlocks`, which expand a continuous or daily range into one-time commitments, validate the whole batch and reject it if any date conflicts. See [PR #86][pr86]. I subsequently connected both transports through `CommandDispatcher`: the confirmation is prepared before one complete save, live state is published only after that save, and failures preserve the previous plan and retry IDs. The six packaged range scenarios cover restart, independent deletion, invalid input, later-date conflicts, midnight boundaries and leap day.

### Testing

I wrote most of [`CommitmentServiceTest`][service-tests] and [`CommitmentTest`][model-tests], which cover validation boundaries, overlap and adjacency, date bounds, skips, one-time rules, conflict ordering, counter exhaustion, candidate immutability and range expansion. `CommandDispatcherTest` additionally verifies whole-batch prepare/save/publish ordering, save-failure retry, plan and ID preservation, unrelated snapshot data, later-segment rollback and insufficient ID capacity. I also added fixed-date integration tests showing that recorded task and commitment durations survive saving and partial allocation ([PR #30][pr30]), and packaged console scenarios for commitment edits and unavailable ranges across restarts.

### Documentation

- **User Guide:** Maintain the [commitment chapter][ug-commitments], including adding, one-time commitments, editing, skipping and deleting, plus [marking a study period unavailable][ug-unavailable] and [blocking multiple dates][ug-planned].
- **Developer Guide:** Wrote the [commitment and dispatch design][dg-design], the [commitment implementation walkthrough][dg-implementation], [transaction verification][dg-verification] and [commitment manual tests][dg-manual], plus the manual-testing command index. Added the [commitment/dispatch class diagram][class-diagram] and [commitment edit sequence diagram][sequence-diagram], with PlantUML sources and rendered SVGs.

### Team-based contributions

- **Reviews:** Reviewed and approved teammates' PRs, including text rendering ([PR #22][pr22]), task operations ([PR #25][pr25]) and console/dispatch integration ([PR #29][pr29]).
- **Authorship allocation:** Applied the team's agreed whole-file RepoSense tags to my assigned commitment and dispatcher files in [PR #93][pr93]. These tags record maintenance boundaries; Git history keeps who originally contributed each change.

[reposense]: https://nus-cs2113-ay2627-s1.github.io/tp-dashboard/?search=Darren50&breakdown=true
[github]: https://github.com/Darren50
[service]: ../../src/main/java/scheduleflow/commitment/CommitmentService.java
[service-tests]: ../../src/test/java/scheduleflow/commitment/CommitmentServiceTest.java
[model-tests]: ../../src/test/java/scheduleflow/model/CommitmentTest.java
[pr22]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/22
[pr23]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/23
[pr25]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/25
[pr26]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/26
[pr29]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/29
[pr30]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/30
[pr34]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/34
[pr70]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/70
[pr71]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/71
[pr78]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/78
[pr79]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/79
[pr80]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/80
[pr86]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/86
[pr93]: https://github.com/AY2627S1-CS2113-T10-1/tp/pull/93
[issue48]: https://github.com/AY2627S1-CS2113-T10-1/tp/issues/48
[ug-commitments]: ../UserGuide.md#commitments
[ug-unavailable]: ../UserGuide.md#marking-a-study-period-unavailable
[ug-planned]: ../UserGuide.md#blocking-multiple-dates-planned
[dg-design]: ../DeveloperGuide.md#commitments-and-command-execution
[dg-implementation]: ../DeveloperGuide.md#dated-commitments-and-save-before-publication-dispatch
[dg-design-considerations]: ../DeveloperGuide.md#design-considerations-1
[dg-verification]: ../DeveloperGuide.md#commitmentdispatch-transaction-verification
[dg-manual]: ../DeveloperGuide.md#commitments-and-unavailable-periods
[class-diagram]: ../images/darren-commitment-dispatch-classes.svg
[sequence-diagram]: ../images/darren-commitment-edit.svg
