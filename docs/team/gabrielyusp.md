# Gabriel Yu - Project Portfolio Page

## Overview

ScheduleFlow is a Java 25 command-line application that helps university students plan coursework around deadlines and fixed commitments. It generates study schedules, reports work that cannot fit, and saves plans and settings for later sessions.

## Summary of Contributions

**Code contributed:** [RepoSense code view](https://nus-cs2113-ay2627-s1.github.io/tp-dashboard/?search=GabrielYuSP&breakdown=true) | [GitHub profile](https://github.com/GabrielYuSP).

I implemented the deadline-aware planner and daily schedule projection, then extended planning to support configurable study hours and continuous-work breaks. I also added regression coverage and documentation for planning around unavailable periods.

### Deadline-aware task planning

- **What it does:** [`EarliestDeadlinePlanner`](../../src/main/java/scheduleflow/planning/EarliestDeadlinePlanner.java) assigns unfinished tasks to eligible half-hour slots in deadline order. It respects study windows, fixed commitments and task deadlines, and reports any work that cannot fit.
- **Justification:** Students need to see realistic capacity before deadlines rather than a schedule that silently places work too late.
- **Highlights:** Planning preserves each task's saved estimate, distinguishes exact work from reserved slot capacity, and handles overdue, out-of-horizon and partially allocated tasks. I added deterministic coverage for allocation order, cutoff rounding, deadline boundaries, commitment blocking and work conservation in [`EarliestDeadlinePlannerTest`](../../src/test/java/scheduleflow/planning/EarliestDeadlinePlannerTest.java). See the [planner implementation](https://github.com/AY2627S1-CS2113-T10-1/tp/commit/1644a96).

### Study hours, schedule projection and breaks

- **What it does:** [`StudyHours`](../../src/main/java/scheduleflow/model/StudyHours.java) and [`StudyWindow`](../../src/main/java/scheduleflow/model/StudyWindow.java) support default hours, weekday settings and date overrides. [`ScheduleService`](../../src/main/java/scheduleflow/planning/ScheduleService.java) projects a saved plan into daily busy, task, break, unplanned and free entries. [`StudyPolicy`](../../src/main/java/scheduleflow/planning/StudyPolicy.java) configures when the planner reserves breaks after continuous work.
- **Justification:** A useful plan must reflect when a student is actually available and make required rest time visible.
- **Highlights:** Date-specific hours take precedence over weekday settings, which take precedence over the default. The saved plan remains a frozen result until replanning or a successful change. Fixed-time tests cover hours precedence, schedule labels, break placement and continuous-work limits. See the [hours and date-override implementation](https://github.com/AY2627S1-CS2113-T10-1/tp/commit/9216291), [daily projection](https://github.com/AY2627S1-CS2113-T10-1/tp/commit/dd5910c) and [continuous-work policy](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/81).

### Unavailable study periods

For issue [#48](https://github.com/AY2627S1-CS2113-T10-1/tp/issues/48), I added fixed-time planner and projection tests for blocking study time with an ordinary one-time commitment. The tests and UI acceptance notes cover replanning around the block, loss of deadline capacity and the effects of deleting or restoring the commitment. See [PR #82](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/82) and the [unavailable-period test plan](../../test/ui-test-plan.md).

I also documented a proposed contract for multi-date unavailable commands. That console workflow remains explicitly planned rather than fully wired; see the [Developer Guide handoff](../DeveloperGuide.md#multi-date-unavailable-periods-implementation-handoff-planned).

### Documentation

- **User Guide:** Added explanations of saved plans, partial allocations, exact task work versus reserved slot capacity, recurrence release timing, and how to investigate insufficient study time. See [study plans](../UserGuide.md#study-plans), [unallocated work](../UserGuide.md#unallocated-work), [weekly recurring tasks](../UserGuide.md#weekly-recurring-tasks) and the related [troubleshooting guidance](../UserGuide.md#what-if-my-work-does-not-fit-before-its-deadline).
- **Developer Guide:** Documented planning and schedule-projection boundaries, the deadline-constrained allocation example, fixed-time and manual verification, and planning terminology. Added class and sequence diagrams for the planning workflow. See [planning design](../DeveloperGuide.md#planning-schedule-projection-and-recurrence), the [allocation trace](../DeveloperGuide.md#deadline-constrained-allocation-trace), [planning tests](../DeveloperGuide.md#fixed-time-planning-and-recurrence-verification) and the [glossary](../DeveloperGuide.md#appendix-d-glossary). These guide updates are in [PR #102](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/102).

### Team-based contributions

- **Planning features:** Integrated deadline allocation with study-hour settings and continuous-work policy, and added deterministic regression coverage for interactions with fixed commitments. See [PR #81](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/81) and [PR #82](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/82).
- **Authorship allocation:** Applied the team's agreed whole-file RepoSense tags to my assigned planning, study-hours and related test files, and documented the allocation. This records the agreed ownership boundaries; it does not rewrite historical authorship. See [PR #95](https://github.com/AY2627S1-CS2113-T10-1/tp/pull/95).

### Frozen-plan feasibility validation

- **Change and benefit:** Added [`Planner.validate(Plan)`](../../src/main/java/scheduleflow/planning/Planner.java)
  so impossible allocations are rejected without changing a student's estimates
  or recreating their schedule. The generator validates each result before returning it.
- **Implementation:** Uses frozen time/source, long work totals, original recurrence
  release, effective hours and half-open interval checks. Accepts feasible alternative
  placements and legacy breaks without requiring the greedy result.
- **Coverage:** [`PlannerTest`](../../src/test/java/scheduleflow/planning/PlannerTest.java)
  tests partial work, overlap, accounting and time boundaries. Existing allocator
  tests exercise validation on real generated results. Updated planning guide
  sections, glossary and class/sequence views. Persistence integration belongs to
  Ernest and remains a separate dependency; no PR has been created for this change.
