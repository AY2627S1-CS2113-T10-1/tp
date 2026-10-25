---
title: ScheduleFlow User Guide
description: Manage tasks, recurring and one-time commitments, and study plans with the ScheduleFlow command line application.
---

# ScheduleFlow User Guide

ScheduleFlow is a command line application for university students balancing coursework deadlines, project work, and recurring classes. Add your tasks and weekly commitments, then generate a study plan that allocates work to available time before each deadline.

Study hours default to **08:00–22:00 every day**, including weekends. You can set recurring weekday hours and override individual dates. Work is scheduled in **30-minute slots**, and any work that does not fit is reported explicitly.

> **Implementation status:** All supported commands are connected to the console application. Changes save before success is printed, IDs survive restarts, and plans remain temporary. Java tests cover transactions and fixed-clock planning; packaged console tests cover startup, recovery and saved restarts.

## Contents

- [Quick start](#quick-start)
- [Command format](#command-format)
- [Tasks](#tasks)
  - [Adding a task](#adding-a-task)
  - [Recording progress](#recording-progress)
  - [Editing a task](#editing-a-task)
  - [Weekly recurring tasks](#weekly-recurring-tasks)
  - [Listing tasks](#listing-tasks)
  - [Deleting a task](#deleting-a-task)
- [Commitments](#commitments)
  - [Adding a commitment](#adding-a-commitment)
  - [Adding a one-time commitment](#adding-a-one-time-commitment)
  - [Editing a commitment](#editing-a-commitment)
  - [Skipping one occurrence](#skipping-one-occurrence)
  - [Listing commitments](#listing-commitments)
  - [Deleting a commitment](#deleting-a-commitment)
- [Study hours](#study-hours)
- [Study plans](#study-plans)
  - [Generating a plan](#generating-a-plan)
  - [Marking a study period unavailable](#marking-a-study-period-unavailable)
  - [Planning hours and date range](#planning-hours-and-date-range)
  - [Unallocated work](#unallocated-work)
- [Viewing the schedule](#viewing-the-schedule)
  - [Schedule labels](#schedule-labels)
  - [Worked schedule example](#worked-schedule-example)
  - [Planning partway through the day](#planning-partway-through-the-day)
- [Help and exit](#help-and-exit)
- [Saving and restoring data](#saving-and-restoring-data)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Command summary](#command-summary)

## Quick start

1. Install **Java 25**. Check your version in a terminal:

   ```shell
   java -version
   ```

2. Obtain `scheduleflow.jar` from the project's release distribution and place it in a dedicated folder.

3. Open a terminal in that folder and launch the application:

   ```shell
   java -ea -jar scheduleflow.jar
   ```

   `-ea` enables the program's internal assertions. The application takes no
   additional command line arguments. Generated distribution launchers enable
   assertions automatically.

4. At the `scheduleflow>` prompt, type `help` and press Enter. Do not type the prompt itself.

5. Add a task and a recurring commitment, generate a plan, then view today's schedule:

   ```text
   task add n/CS2113 draft due/2026-10-20 1800 d/180
   commitment add n/CS2113 lecture day/MON start/1000 d/120
   plan
   schedule today
   ```

   Replace the example deadline with a future date within the [allowed date range](#planning-hours-and-date-range) when necessary.

6. Enter `exit` when finished. Successful task, commitment and study-hours changes are already saved automatically.

## Command format

Enter each command on one line. In syntax examples, uppercase placeholders such as `NAME` and `MINUTES` are values you replace. Terminal transcripts include the `scheduleflow>` prompt; copy only the command after it.

| Input | Rule | Example |
| --- | --- | --- |
| Commands and prefixes | Case sensitive and lowercase | `task add`, `n/`, `due/`, `d/` |
| Fields | Fields must appear in the stated order; bracketed edit fields are optional | `n/NAME due/DATE_TIME d/MINUTES` |
| Names | Nonblank; may contain spaces and Unicode, but no `/`, tabs, newlines, or other control characters | `CS2113 draft` |
| Dates | Real dates in `YYYY-MM-DD` format; years `0001`–`9999` | `2026-10-06` |
| Task/commitment times | Four digits in 24-hour `HHmm` format, from `0000` to `2359` | `0930`, `1800` |
| Study hours | `HH:mm`, on half-hour boundaries; start before end within the same day | `09:00`, `22:30` |
| Deadlines | Date and time separated by a space; any minute is allowed | `2026-10-06 2359` |
| Task durations | Positive whole minutes; no sign or leading zeroes | `1`, `45`, `180` |
| Commitment durations | Positive whole minutes divisible by 30; no sign or leading zeroes | `30`, `90`, `180` |
| Commitment starts | Must be on the hour or half hour | `1000`, `1030` |
| Commitment weekdays | Exactly `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`, or `SUN` | `MON` |
| Study-hours weekdays | Full lowercase weekday names | `tuesday` |
| Task IDs | `T` followed by a positive integer, without leading zeroes | `T1`, `T12` |
| Recurring task template IDs | `R` followed by a positive integer, separate from occurrence IDs | `R1`, `R12` |
| Commitment IDs | `C` followed by a positive integer, without leading zeroes | `C1`, `C12` |

Use one or more ordinary spaces between fields. Outer whitespace is trimmed, and blank lines are ignored. Names are trimmed at both ends while retaining internal spaces. Quotation marks have no special meaning and are unnecessary. Duplicate names are allowed.

IDs identify records, not list positions. Bare numbers such as `1`, zero-padded IDs such as `T01`, and IDs with the wrong prefix are rejected. Task and commitment IDs have separate sequences starting at `T1` and `C1`. Deleted IDs are never reused, including after a restart; a failed add does not consume an ID. Numeric inputs must fit within a signed 32-bit integer, and an exhausted ID sequence rejects further additions.

Missing required fields, repeated, unknown, reordered, or extra fields are rejected. Commands such as `help`, `task list`, `plan`, and `exit` accept no arguments. Deletion and schedule commands also reject trailing input.

Impossible dates such as `2026-02-30` are rejected rather than adjusted to another
date. Parsing keeps `schedule today` as a request for today's schedule; its date
will be resolved when the command executes. Checks such as a future deadline,
commitment duration divisibility and whether an ID exists belong to domain validation
after parsing.

Examples below are independent unless a sequence is explicitly described. Use existing IDs and suitable future dates in your own session. List spacing and explanatory error wording may vary; identifiers, values, and behaviour follow the rules described in this guide.

## Tasks

Task addition, editing, deletion and weekly recurring templates are available at
the console. You can revise a task's remaining-work estimate with `task remaining`.
You can record completed work with either form of `task progress`.
Deadline clearing, explicit completion/reopening and adding without a deadline
are implemented as internal Java service APIs, but have no console commands.
Console addition still requires a deadline, and editing accepts only a name and/or
concrete deadline. Progress saves changed estimates and retains a completed
record when its remaining work reaches zero.

The domain retains completed records with zero remaining, preserves IDs and
requires positive work to reopen them. Session percentage progress credits the
floor of session minutes times percentage / 100; no timer subtracts work.
Explicit reopening is not yet available from the console. Loaded undated tasks
display `No deadline`; loaded completed tasks remain listed with `0 min` and
receive no planning sessions or unallocated rows. No separate status/completed
list rendering or priority commands are available yet.

### Recording duration (tasks and commitments)

Enter duration when adding a task or commitment using `d/MINUTES`; there is no
separate duration command or timer. For example, `d/90` records 90 minutes.
Tasks accept positive whole minutes, including `d/1` and `d/45`. Commitments
still require positive multiples of 30, so commitment `d/45` is rejected.
Both additions reject `d/0`.

For a task, duration is the estimated remaining work, which may be split into
several sessions. For a commitment, it is the length of each weekly occurrence
from its start time, and that occurrence must not cross midnight. Planning
does not reduce either stored value. Saving and loading preserve both values.

A task's duration is the exact remaining-work estimate; zero in restored data
means retained completion. Generating a plan or letting time pass does not
reduce that estimate. Slot capacity rounds up: 45 minutes needs 60 minutes of
schedule space, but remains 45 minutes of actual work. If only 30 minutes fits,
the unallocated work is 15 minutes (requiring another 30-minute slot).

Use [progress](#recording-progress) to subtract completed work from an estimate.
A percentage describes the reported session, not the whole task.

To revise a task's remaining-work estimate without recording completed work, use:

```text
scheduleflow> task remaining T1 remaining/90
Remaining time for T1: CS2113 draft | 90 min
```

Use an existing task ID and a positive whole-minute estimate; `remaining/45`
is accepted. A changed estimate saves automatically and clears the current
plan; run `plan` again to reflect it. Repeating the same value
reports the stored estimate without saving or clearing the plan. Unknown IDs,
invalid values, attempts to revise a completed task and failed saves leave
saved data and the current plan unchanged.

### Recording progress

```text
task progress TNUMBER done/MINUTES
task progress TNUMBER session/MINUTES percent/PERCENT
```

For an existing task with 90 minutes remaining:

```text
scheduleflow> task progress T1 done/30
Recorded progress for T1: CS2113 draft | Credited: 30 min | Remaining: 60 min
scheduleflow> task progress T1 session/61 percent/25
Recorded progress for T1: CS2113 draft | Credited: 15 min | Remaining: 45 min
```

Direct progress accepts zero or more whole minutes up to the remaining estimate.
A session requires positive whole minutes and an integer percentage from 0 to
100. Credit is rounded down to whole minutes: 25% of 61 minutes credits 15.
Both forms report the actual credit and new estimate; no timer runs automatically.
Use ordinary decimal numbers without signs or leading zeroes.

A changed estimate saves automatically and clears the current plan. Run `plan`
again to schedule the remaining work. Zero credit, including a percentage that
rounds to zero, reports the unchanged estimate without saving or clearing the
plan. Unknown IDs, overreported work, invalid input and failed saves preserve
the existing data and plan. Saved progress survives restarting.

Crediting all remaining work retains the task with `0 min`; further progress
is rejected until it is reopened through the Java API. There is no console
reopening command yet. Progress also works on generated recurring-task
occurrences without changing their template or original release date.

### Adding a task

**Command:** `task add`

```text
task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES
```

The deadline must be strictly later than the current local date and time and no later than midnight at the start of the same calendar date next year. The task duration must be positive whole minutes. Deadlines do not need to fall on a half-hour boundary.

```text
scheduleflow> task add n/CS2113 draft due/2026-10-20 1800 d/180
Added T1: CS2113 draft
Due: 2026-10-20 18:00 | Duration: 180 min
```

A missing deadline is rejected. For example:

```text
scheduleflow> task add n/CS2113 draft d/180
Error: Invalid command format or missing fields
Expected: task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES
```

A successful addition saves the task and clears any current plan. Run `plan` again to include it.

Rejected task additions leave existing tasks, commitments, ID counters and the
current plan unchanged. Correct the input and retry; the failed attempt does not
consume an ID. Optional [Task diagnostics](DeveloperGuide.md#task-operations-and-defensive-checks)
record validation and candidate-processing stages in a separate log file, without
task names, deadlines or durations. Normal launches keep the same console output.

### Listing tasks

```text
task list
```

Lists all stored tasks, including overdue tasks, in ascending numeric ID order. Each row shows the ID, name, duration, and deadline.

```text
scheduleflow> task list
=== Actionable Tasks ===
ID | Name                | Duration | Deadline
T1 | CS2113 draft        | 180 min  | 2026-10-20 18:00
T2 | EE2026 preparation  | 90 min   | 2026-10-21 12:00
Total: 2 task(s).
```

An empty list displays:

```text
No tasks found.
```

The listed duration is the stored work estimate, not the unallocated remainder of the latest plan.

### Editing a task

```text
task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]
```

Supply at least one field. When both are supplied, `n/` must precede `due/`.
Omitted fields retain their values. Use an existing ID from `task list`:

```text
scheduleflow> task edit T1 n/CS2113 final draft due/2026-11-20 1800
Edited T1: CS2113 final draft
Due: 2026-11-20 18:00 | Duration: 180 min
```

Replace example dates with suitable future dates. A changed deadline must be
strictly after the command time and at or before the rolling calendar-year
limit. Renaming an overdue task is allowed; keeping its unchanged old deadline
is also allowed. Deadlines cannot currently be removed. Use `task remaining T1
remaining/90` to revise the estimate rather than adding an unsupported `d/` edit.

Edits preserve the task ID, list position, estimate, other records and all ID
counters. A changed edit is saved before it clears the current plan; run `plan`
again. An unchanged normalized edit reports the same details without saving or
clearing the plan. Invalid edits or failed saves apply nothing.

### Weekly recurring tasks

```text
task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES every/WEEKLY [until/YYYY-MM-DD]
task recur list
task recur delete RNUMBER
```

A template describes unfinished work repeated each week, not a fixed busy
commitment. Its first deadline fixes the weekday and time of all occurrences.
The first deadline must be in the future. Estimates remain positive multiples
of 30 minutes. Optional `until/` is an inclusive last due date, on or after the
first due date; omit it for no end date. Only `every/WEEKLY` is supported.

```text
scheduleflow> task recur add n/Weekly quiz due/2026-11-02 1800 d/60 every/WEEKLY until/2026-11-30
Added R1: Weekly quiz
Weekly from: 2026-11-02 18:00 | Duration: 60 min | Until: 2026-11-30
```

Use future dates in your own session. `task recur list` displays the stable
`R` IDs and definitions; an empty list prints `No recurring tasks found.`
Each materialized occurrence gets its own `T` ID and appears in `task list`.
Adding a template materializes future deadlines through the rolling planning
horizon. Each `plan` command extends generation as that horizon moves forward.
Templates may start beyond the current horizon; their first tasks appear once
eligible. A task is never scheduled before one week before its original due
date, even if you later edit that occurrence's deadline.

Generation skips ungenerated deadlines already passed; it does not backfill
missed weeks. Saved cursors prevent restart, replanning or deleting an occurrence
from recreating it. Templates, provenance and all counters survive saved
restarts and unrelated task/commitment/study-hours changes. Template IDs are
never reused. Failed validation, planning or saving leaves live state unchanged.

`task recur delete R1` stops future generation but **retains every already
generated task**, including future occurrences. Remove unwanted occurrences
separately with `task delete TNUMBER`. Editing a generated task affects only
that task, not its template or original release time. Template editing and
daily/monthly recurrence are not supported.

Generated tasks use the same exact remaining-work and lifecycle model as ordinary
tasks. Their estimates can be revised with `task remaining`, and completed work
can be recorded with `task progress`; both preserve their provenance. Explicit
completion and reopening remain internal Java APIs.

### Deleting a task

```text
task delete TNUMBER
```

Deletes the task permanently. Use this command to remove completed or unwanted
work. Use `task edit` to correct a name/deadline without replacing the ID.
There is no undo. To retain a completed record instead of deleting it, use
`task progress` to credit all its remaining work.

```text
scheduleflow> task delete T1
Deleted task T1: CS2113 draft
```

The ID must belong to an existing task. A successful deletion saves the change and clears the current plan. A rejected deletion leaves both unchanged.

## Commitments

A recurring commitment reserves the same period every week. It can continue until
deleted, or use inclusive start and end dates for a semester or other limited period.
Add separate entries for commitments on different weekdays. A one-time commitment,
such as a project meeting, reserves a period on a single calendar date.

Commitment addition, editing, deletion and listing run directly at the console. Each
successful change saves automatically and clears the current plan.

### Adding a commitment

```text
commitment add n/NAME day/DAY start/HHmm d/MINUTES [from/YYYY-MM-DD until/YYYY-MM-DD]
```

```text
scheduleflow> commitment add n/CS2113 lecture day/MON start/1000 d/120
Added C1: CS2113 lecture | Monday 10:00 - 12:00 (120 min)
```

This blocks 10:00–12:00 every Monday.

To limit the series to a semester, provide both dates:

```text
scheduleflow> commitment add n/CS2113 lecture day/MON start/1000 d/120 from/2026-08-10 until/2026-11-16
Added C1: CS2113 lecture | Monday 10:00 - 12:00 (120 min) | 2026-08-10 to 2026-11-16
```

Both endpoints are inclusive. The range must contain at least one occurrence of
the chosen weekday. Supply both `from/` and `until/`, in that order, or omit both
for an unbounded weekly series. Study time is blocked only on matching weekdays
inside the range.

- The start must be on `:00` or `:30`, and the duration must be a positive multiple of 30 minutes.
- A commitment must end by midnight on its selected day. Ending exactly at midnight is allowed and displayed as `24:00`; entering `2400` as a start time is invalid.
- Commitments may occur outside study hours. A full-day commitment starting at `0000` with duration `1440` is valid.
- Commitments on the same weekday must not overlap when their actual occurrences share a date, even outside study hours. Separate semester ranges may reuse the same weekly time. An overlap error identifies every conflicting commitment by ID and name, in start-time order.
- Adjacent commitments are allowed: Monday 10:00–12:00 and Monday 12:00–13:00 do not overlap. The same time on different weekdays is also allowed.

A successful addition saves the commitment and clears the current plan.

If an addition overlaps multiple entries, the error lists them together, for example:
`Error: Commitment overlaps C10: Lecture; C2: Seminar`.
Adjacent entries and commitments on other weekdays are not included in that list.
Correct the proposed interval and retry; rejected additions do not consume an ID.

Rejected additions leave existing commitments and IDs unchanged. Optional
[commitment diagnostics](DeveloperGuide.md#commitment-defensive-checks-and-diagnostics)
record processing stages separately without commitment names or schedules.
Normal launches keep the same console output.

### Adding a one-time commitment

```text
commitment add n/NAME on/YYYY-MM-DD start/HHmm d/MINUTES
```

Use `on/` instead of `day/` for an event that happens once:

```text
scheduleflow> commitment add n/Project meeting on/2026-10-30 start/1400 d/60
Added C2: Project meeting | One-time on 2026-10-30 (Friday) 14:00 - 15:00 (60 min)
```

This blocks 14:00–15:00 on 30 October 2026 only; other Fridays stay available.
The weekday is taken from the date, so do not also type `day/`, `from/` or `until/`.

- The date must be a real calendar date from `0001-01-01` to `9999-12-31`.
- The time and duration rules are the same as for recurring commitments.
- A one-time commitment must not overlap a recurring commitment that occurs on
  that date, or another one-time commitment on the same date. Adjacent times are
  allowed. A recurring occurrence that you skipped does not cause a conflict.
- Adding a recurring commitment is likewise rejected if it would overlap an
  existing one-time commitment.

One-time commitments receive ordinary IDs (`C1`, `C2`, ...) from the same sequence
as recurring commitments, and are listed, edited and deleted with the same commands.
Rejected additions do not consume an ID. A successful addition saves automatically
and clears the current plan. A recurring commitment entered with the same `from/`
and `until/` date has exactly one occurrence, so it is treated and displayed as a
one-time commitment.

### Editing a commitment

```text
commitment edit CNUMBER [n/NAME] [day/DAY|on/YYYY-MM-DD] [start/HHmm] [d/MINUTES] [from/YYYY-MM-DD until/YYYY-MM-DD]
```

Supply at least one field, in the order shown; do not type the square brackets.
Omitted fields retain their stored values. The existing ID is kept, and editing
does not consume a new ID or change other commitments. This edits the whole weekly
series, not just one occurrence. Supplying `from/` and `until/` together replaces
its date range. Omitting them preserves the existing range. Removing an existing
range is not supported yet.

```text
scheduleflow> commitment edit C1 start/1100 d/60
Edited C1: CS2113 lecture | Monday 11:00 - 12:00 (60 min)
```

You can rename with `commitment edit C1 n/New lecture` or move the series with
`commitment edit C1 day/TUE`. The full resulting interval must satisfy the same
rules as addition. Conflict checks exclude the edited series itself, but still
check every other series. Blank names, unknown IDs and empty edits are rejected.
When changing a late start and duration, supply both together so the complete
replacement ends by midnight.

For a one-time commitment, `n/`, `start/` and `d/` work as above. Move it to another
date with `on/`, which also sets the weekday:

```text
scheduleflow> commitment edit C2 on/2026-11-06
Edited C2: Project meeting | One-time on 2026-11-06 (Friday) 14:00 - 15:00 (60 min)
```

`day/` alone is rejected for a one-time commitment because its weekday comes from
its date. `on/` cannot be combined with `day/`, `from/` or `until/`. Using `on/` on a
recurring commitment turns it into a one-time commitment on that date, and its
skipped dates are discarded because it no longer repeats. Supplying `from/` and
`until/` for a one-time commitment turns it into a bounded weekly series.

Successful edits save automatically and clear the plan; run `plan` again. This
also applies when you explicitly supply an existing value. Invalid edits and failed
saves leave the saved data, IDs, current records and plan unchanged. Correct the
input or save problem and retry. Edited values survive restarting the application.
Editing a commitment also preserves configured study hours and date overrides.

### Skipping one occurrence

```text
commitment skip CNUMBER on/YYYY-MM-DD
```

Use this when one class or meeting is cancelled but the rest of its recurring
series remains unchanged:

```text
scheduleflow> commitment skip C1 on/2026-10-26
Skipped C1 on 2026-10-26: CS2113 lecture
```

The date must be an unskipped occurrence of that commitment: it must use the
same weekday and, for a bounded series, fall within its inclusive range. An
unknown ID, a date on another weekday, a date outside the range or an already
skipped date is rejected without changing saved data or the current plan.

A successful skip saves automatically and clears the current plan. Run `plan`
again; the released period is then available for study. Other commitments may
use the same time on that skipped date, while conflict checks still apply on
every remaining occurrence. Skips survive restarting the application and are
shown by `commitment list`. Delete the series if every occurrence should be
removed instead. One-time commitments cannot be skipped; delete them instead.

### Listing commitments

```text
commitment list
```

Lists commitments by weekday, Monday through Sunday, then by start time and numeric ID. Empty weekday groups are omitted. The displayed IDs remain the original commitment IDs. Bounded series show their inclusive date range after the name; unbounded series retain the shorter display.
Series with cancelled occurrences append those dates in chronological order,
for example `| skipped: 2026-10-26, 2026-11-02`.

```text
scheduleflow> commitment list
=== Recurring Weekly Commitments ===
[Monday]
C1. 10:00 - 12:00 | CS2113 lecture
C2. 12:00 - 13:00 | EE2026 lecture

[Friday]
C3. 23:30 - 24:00 | Weekly review

Total: 3 recurring commitments across the week.
```

One-time commitments appear in a separate section after the weekly series, ordered
by date, then start time and ID. The weekly section is omitted when there are only
one-time commitments.

```text
scheduleflow> commitment list
=== Recurring Weekly Commitments ===
[Friday]
C1. 10:00 - 11:00 | Lecture

Total: 1 recurring commitments across the week.

=== One-time Commitments ===
C2. 2026-10-30 (Friday) 14:00 - 15:00 | Project meeting
Total: 1 one-time commitments.
```

An empty list displays:

```text
No commitments found.
```

### Deleting a commitment

```text
commitment delete CNUMBER
```

Removes the entire weekly series, not just one occurrence, or removes a one-time
commitment.

Use the original `CNUMBER` shown by `commitment list`, not its position in the
display. An unknown ID is rejected without changing commitments or ID counters.
Deleting the final commitment leaves an empty list but does not reset its ID
sequence; a later addition receives a new ID.

```text
scheduleflow> commitment delete C1
Deleted commitment C1: CS2113 lecture
```

A successful deletion saves the change and clears the current plan. Run `plan` again to make the released time available for study sessions.

## Study hours

Set a recurring window independently for each weekday:

```text
hours tuesday /start 09:00 /end 22:00
hours wednesday /start 10:00 /end 18:00
```

Override a single calendar date without changing its recurring weekday setting:

```text
hours 2026-10-27 /start 14:00 /end 19:00
```

The date override takes priority over the weekday setting, which takes priority
over the default 08:00–22:00 window. Thus October 27 uses 14:00–19:00, other
Tuesdays use 09:00–22:00, Wednesdays use 10:00–18:00, and other days keep the default.

View the effective window or remove only the selected setting:

```text
hours tuesday
hours 2026-10-27
hours reset 2026-10-27
hours reset tuesday
```

Resetting a date restores its weekday hours (or the default). Resetting a
weekday restores its default but preserves date overrides. Resetting an unset
target is allowed. Viewing hours does not require or change a plan.

Reset confirmations explicitly show the restored hours. For example,
`hours reset tuesday` reports `Using default hours: 08:00 - 22:00.`
A date reset reports `Using recurring weekday hours` instead when that weekday
has a configured setting, followed by its actual start and end times.

Times must use `HH:mm` and fall on `:00` or `:30`, from `00:00` through `23:30`.
Start must be strictly before end on the same day. Overnight, zero-length and
`24:00` windows are unsupported. Each day has one continuous study window;
use commitments to block breaks within it. Dates may be any valid date in years
0001–9999; configuring hours does not extend the planning horizon.

Successful sets and resets save automatically and clear the current plan. Run
`plan` again to apply the change. Invalid commands and failed saves preserve
both settings and the current plan. Task and commitment changes preserve hours,
and settings survive restarting the application. Existing saves without hours
continue to use the default. A save containing custom hours requires this version
of ScheduleFlow; older versions cannot read the new format.

## Study plans

### Generating a plan

```text
plan
```

ScheduleFlow excludes completed tasks and allocates dated tasks by earliest
deadline first, with stored priority (HIGH, NORMAL, LOW) breaking deadline ties,
then numeric ID. All console-created tasks default to NORMAL, so `T2` still
comes before `T10` at an equal deadline. Loaded undated tasks follow all dated
tasks, by priority and ID, within the same horizon. Priority operations are not
yet available at the console.

Each task fills the earliest available 30-minute slots that end at or before its deadline. Work may split around commitments or across days. Adjacent slots for the same task on the same day are displayed as one session. Sessions never overlap commitments or other sessions.

When every task fits:

```text
scheduleflow> plan
Study plan generated successfully! Type 'schedule today' to view your sessions.
```

When there are no tasks:

```text
scheduleflow> plan
No tasks to schedule.
```

Even with no tasks, `plan` creates a current plan. You can then view commitments and free or unplanned periods using `schedule`.

Each `plan` command replaces the previous plan. It does not change stored task durations or save a plan to disk. This allocator follows deadline order; it does not guarantee the fewest sessions or the greatest number of fully completed tasks.

### Marking a study period unavailable

Use a one-time commitment to block time on a particular date, including time
that a previous plan assigned to a task:

```text
commitment add n/Unavailable on/2026-10-26 start/1000 d/60
plan
schedule 2026-10-26
```

Replace the example date with your chosen date within the current planning
range. This blocks 10:00-11:00 on that date only. You can choose any descriptive
name, such as `Appointment`; `Unavailable` has no special meaning. The period
appears as `[BUSY]` with its commitment ID. Replanning moves task work into other
eligible slots and reports unallocated minutes if it cannot all fit before the
deadlines. Stored task estimates do not change.

Adding the block saves automatically and clears the old plan; run `plan` again
to apply it. The block survives restarting, but the generated plan does not.
You can release it with `commitment delete CNUMBER`, using the ID shown by
`commitment list`, then run `plan` again. Successful deletion also saves and
clears the current plan.

The existing one-time commitment rules apply: half-hour-aligned starts,
positive durations divisible by 30, and an interval ending by midnight. A block
may overlap planned task sessions, but it cannot overlap another commitment
occurring on that date. Rejected additions preserve the existing plan and data.
There is no separate `unavailable` command or command to select a session by ID.

### Blocking multiple dates (planned)

Two extensions are specified for implementation but are not accepted by the
console yet:

```text
unavailable continuous n/Trip from/2026-10-26 1500 until/2026-10-28 1100
unavailable daily n/Appointment from/2026-10-26 until/2026-10-28 start/1500 end/1700
```

Continuous mode will block every moment from the first date/time up to, but
excluding, the last date/time. Daily mode will block only 15:00-17:00 on each
included date, leaving other hours available. Each generated period will have
its own commitment ID for independent editing/deletion. The full request will
be rejected if any period conflicts with an existing commitment.

The batch confirmation is implemented and unit-tested, but parsing and dispatch
are still pending. For now, use the existing single-date
`commitment add ... on/DATE` commands.
The [Developer Guide handoff](DeveloperGuide.md#multi-date-unavailable-periods-implementation-handoff-planned)
contains proposed outputs, owner responsibilities and acceptance tests.

### Session limits and breaks (planned console feature)

Issue #55's planning algorithm supports a continuous-work limit counting both
study tasks and commitments. Once the limit is reached, further study waits
for a fixed consecutive break. Commitments remain fixed even if they exceed
the limit; a longer commitment does not increase the required break duration.

This feature is currently available only through the Java planning API.
Break rows supplied by the planning API display as `HH:mm - HH:mm | [BREAK]`.
Settings commands and saving those settings are awaiting integration.
The console's existing `plan` command does not enforce these
limits yet; there is no supported command to configure them.

### Planning hours and date range

Study windows default to **08:00–22:00 every local calendar day**, including weekends.
The planner uses each date's effective [study hours](#study-hours).

For the day on which you generate a plan, scheduling starts at the later of the configured start and the current time rounded up to the next half hour. At or after the configured end, no study slots remain that day. Future days start at their configured start. The following examples use default hours.

| Time when `plan` runs | Earliest eligible start that day |
| --- | --- |
| 07:00 | 08:00 |
| 10:00:00 exactly | 10:00 |
| 10:00:01 | 10:30 |
| 10:07 | 10:30 |
| 21:45 | No slots remain; cutoff is 22:00 |

The planning limit is **midnight at the start of the same calendar date next year**, recalculated for each `plan` command. The plan includes the generation date through the day before that limit. Task additions use the same limit, calculated when the task is added.

For example, on 5 October 2026:

- A new task's deadline must be later than the current time and at or before **5 October 2027 at 00:00**.
- A new plan covers **5 October 2026 through 4 October 2027**, inclusive.
- With default hours, the latest possible study session ends at **22:00 on 4 October 2027**.

This is a calendar year, not a fixed 365 days. For a 29 February start date, the following year's boundary adjusts to 28 February. There is no first-startup limit and no need to delete saved data to move the range forward.

Dates and times use your computer's local wall time. Time zone offsets are not stored; changing the computer's time zone reinterprets saved times as local times. Durations are wall-clock minutes, including across daylight-saving changes.

### Unallocated work

If work does not fit, ScheduleFlow keeps a **partial plan** and reports the remaining minutes for each affected task. This is a valid planning result. Even a completely blocked task remains in the plan's unallocated-work report.

For example, if a 90-minute task is due at 08:30 and planning runs before 08:00 that day with no commitments:

```text
scheduleflow> plan
Study plan generated with unallocated work.
Scheduled: 30 min | Unallocated: 60 min
T1: Draft | Unallocated: 60 min | Reason: insufficient time before deadline
```

The task still has a stored duration of 90 minutes. Only 30 minutes were allocated in this plan.

| Reported reason | Meaning |
| --- | --- |
| `deadline passed` | The task's deadline is at or before the time the plan was generated. Its full duration remains unallocated. |
| `outside planning horizon` | The deadline is later than this plan's date limit, for example after restoring data or changing the clock. Its full duration remains unallocated. |
| `insufficient time before deadline` | Too few eligible slots remain before the deadline. Any slots that do fit are retained. |

The totals cover the whole plan. Unallocated tasks appear in deadline order, with priority then numeric ID breaking ties; undated tasks follow dated tasks. Slots must fit in full: a deadline of 08:59 permits an 08:00–08:30 session but not an 08:30–09:00 session.

Summary scheduled/unallocated totals count exact work, excluding padded capacity.
Schedule rows still show reserved interval durations. Undated remainder rows say
`No deadline` and `insufficient available study time`, never `deadline passed` or
`outside planning horizon`. They follow dated rows; priority breaks equal
deadline ties before numeric ID. Completed overdue tasks contribute no remainder.

## Viewing the schedule

Use either command:

```text
schedule today
schedule YYYY-MM-DD
```

`today` means the computer's current local date when the command runs. A dated command selects that calendar date explicitly.

The schedule displays the latest plan's full configured window in chronological order, along with the time the plan was generated. Commitments are clipped to the study window: with default hours, a 07:30–09:00 commitment appears as 08:00–09:00. Commitments entirely outside study hours remain visible in `commitment list` but do not appear in the schedule.

### Schedule labels

| Label | Meaning |
| --- | --- |
| `[TASK]` | A planned study session, showing its task ID, name, and session duration. |
| `[BUSY]` | A recurring commitment, showing its commitment ID and name. |
| `[FREE]` | Time eligible for planning that was not assigned to a task or blocked by a commitment. |
| `[UNPLANNED]` | Time before the planning cutoff on the day the plan was generated. It is not available study time in this plan. |
| `[BREAK]` | A supplied rest interval from the configured Java planning API; the console cannot configure this policy yet. |

A commitment remains `[BUSY]` even when it occurred before the planning cutoff. Adjacent entries are combined only when their label, ID, and name match, so separate commitments remain separate rows.

### Worked schedule example

Assume an empty store and that it is **Monday, 5 October 2026 at 07:00**. Run these commands in order:

```text
task add n/CS2113 draft due/2026-10-06 1800 d/180
commitment add n/CS2113 lecture day/MON start/1000 d/120
commitment add n/EE2026 lecture day/MON start/1200 d/60
plan
schedule today
```

The schedule is:

```text
=== Schedule for Monday (2026-10-05) ===
Generated at: 2026-10-05 07:00
08:00 - 10:00 | [TASK] T1: CS2113 draft (120 min)
10:00 - 12:00 | [BUSY] C1: CS2113 lecture
12:00 - 13:00 | [BUSY] C2: EE2026 lecture
13:00 - 14:00 | [TASK] T1: CS2113 draft (60 min)
14:00 - 22:00 | [FREE]
```

The task uses the two free morning hours first, then resumes after the commitments. All 180 minutes fit, so there is no unallocated-work footer.

### Planning partway through the day

If you generate a plan at **10:07** on 5 October 2026 with one 30-minute task named `Draft`, a future deadline within the horizon, and no commitments:

```text
=== Schedule for Monday (2026-10-05) ===
Generated at: 2026-10-05 10:07
08:00 - 10:30 | [UNPLANNED]
10:30 - 11:00 | [TASK] T1: Draft (30 min)
11:00 - 22:00 | [FREE]
UNPLANNED periods were before the planning cutoff.
```

When any work is unallocated, every schedule view also includes a footer for the **whole plan**, even if the affected task is due on another date. For the partial-plan example above, that footer is:

```text
Unallocated work for the whole plan:
T1: Draft | Unallocated: 60 min | Reason: insufficient time before deadline
```

If no plan exists, the app displays:

```text
No current plan. Run plan to generate one.
```

A selected date must fall within the current plan's date range. An out-of-range request reports the earliest and latest allowed dates; it does not produce an empty schedule. Run `plan` again when you need a fresh range.

**Plans remain fixed until regenerated.** Viewing a schedule later does not move its sessions, update its generation time, or account for completed work. Successful task or commitment additions, deletions and commitment edits, and study-hours sets and resets, clear the plan; failed commands preserve it. Restarting also clears the plan.

## Help and exit

### Viewing help

```text
help
```

Displays `=== ScheduleFlow Command Help ===`, followed by every supported command format and a short description. See the [command summary](#command-summary) for the full list.

### Exiting the program

```text
scheduleflow> exit
Goodbye for now!
```

`exit` closes the application without another save because successful changes have already been saved. End of input also prints the same goodbye message and exits normally. Extra arguments, such as `exit extra`, are rejected and the application keeps running.

## Saving and restoring data

The application loads saved data before showing its first prompt and saves
each successful task, commitment or study-hours change automatically, including
changed remaining-work estimates.

Tasks, commitments, study hours and the next ID counters are saved automatically after each successful change and restored at startup. No manual save or load command is needed.

New saves use version 2, 3 or 4 depending on commitment date bounds and skipped
occurrences, or version 5 when templates, occurrence provenance or an allocated
template ID counter require it. Ordinary tasks use `TASK2` rows, including in
version 5; generated tasks use `OCCURRENCE` rows with exact remaining minutes,
optional deadlines, priority and original template/deadline provenance. Templates,
generation cursors, custom study hours and all ID counters are preserved.
Versions 1 through 4 remain readable; loading does not rewrite them. New writes
use at least version 2. Older executables cannot read version 5, and older
applications without `TASK2` support cannot read those extended rows. See the
[Developer Guide](DeveloperGuide.md#basic-saving-and-loading) for the precise
layout. Plans remain temporary and are not saved.

- **Location:** `data/scheduleflow.txt`, relative to the folder from which you launch the application. Launch from the same folder to use the same data.
- **First launch:** If the file is absent, the app starts empty. The first successful change creates the saved data.
- **Temporary plans:** Plans are not saved. Run `plan` after restarting.
- **Failed save:** The change is rejected. Existing tasks, commitments, ID counters, saved data, and the current plan remain unchanged. Resolve the reported file or folder problem, then retry.
- **Invalid or unreadable file:** The app reports an error and stops before accepting commands. It preserves the file instead of silently starting empty or loading only part of it.
- **Overdue tasks:** Valid saved tasks remain available after their deadlines pass. Replanning reports their work as unallocated with reason `deadline passed`.

Use one running instance per data file. Saving requires a filesystem that supports atomic file replacement; if it does not, the app reports a save error and retains the previous data.

To back up your data, close the app and copy `data/scheduleflow.txt`. To transfer it, place the copy in the destination launch folder's `data` subfolder and use the Java 25 and JAR setup described in [Quick start](#quick-start). Back up any existing destination file before replacing it.

## Troubleshooting and FAQ

### What if my work does not fit before its deadline?

Run `plan` and review the unallocated minutes and reasons. The app retains every
session that fits. Revise an estimate with `task remaining`, change a deadline
with `task edit`, or remove a commitment if that time has become available.
Run `plan` again after changes.

### How do I mark work as complete or correct an entry?

Delete completed tasks using `task delete TNUMBER`. Correct commitments with
`commitment edit CNUMBER` and one or more fields; the ID is preserved. To correct
a task's name or deadline, use `task edit TNUMBER` with `n/` and/or `due/`;
the ID is preserved. Record completed work with `task progress`; crediting all
remaining minutes retains the task at zero. Explicit completion/reopening
commands are not supported yet. Use `task remaining` to correct an estimate
without recording completed work.

### Why did my schedule disappear?

A successful task or commitment addition, deletion, task edit, template change,
progress update, remaining-work revision, commitment edit or
occurrence skip, or a study-hours set or reset, clears the current plan. Plans
also disappear when the application restarts. Run `plan` to generate another one.

### Can I change the study hours or slot size?

Use `hours` to configure weekday hours and date overrides. The slot size remains fixed at 30 minutes.

### Can I schedule more than a year ahead?

New task deadlines must fall within the rolling calendar-year limit at the time of addition. Wait until the deadline enters that range before adding it. Each new plan recalculates its range; you do not need a reset or data deletion.

### Why was my command rejected?

Check the command's field order, case, prefixes, date, time, duration, and ID. Syntax errors display `Error: MESSAGE` followed by `Expected: USAGE`. Value errors, unknown IDs, and save errors display one `Error: MESSAGE` line. Rejected operations preserve saved data and the current plan.

For example, use `task delete T1`, not `task delete 1`; use commitment duration
`60`, not `45` or `060`; and start a commitment at `1030`, not `1015`.
Task duration `45` is valid, but task duration `060` is not.

### What should I do if the application cannot load my data?

Keep a backup of the reported file, resolve access problems, or restore a known-good backup before restarting. A malformed or unreadable file is a startup error; the app will not overwrite it with an empty store.

Optional storage diagnostics can help investigate save/load failures. Follow the
[storage logging instructions](DeveloperGuide.md#storage-defensive-checks-and-diagnostics)
to record processing stages in a separate log file. Normal launches remain quiet;
these storage logs do not include your task names or saved file contents.

## Command summary

| Action | Command |
| --- | --- |
| View help | `help` |
| Add a task | `task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES` |
| Edit a task | `task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]` |
| Add weekly task template | `task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES every/WEEKLY [until/YYYY-MM-DD]` |
| List task templates | `task recur list` |
| Stop future task generation | `task recur delete RNUMBER` |
| List tasks | `task list` |
| Delete a task | `task delete TNUMBER` |
| Revise a task's remaining time | `task remaining TNUMBER remaining/MINUTES` |
| Record completed minutes | `task progress TNUMBER done/MINUTES` |
| Record session progress | `task progress TNUMBER session/MINUTES percent/PERCENT` |
| Add a weekly commitment | `commitment add n/NAME day/DAY start/HHmm d/MINUTES [from/YYYY-MM-DD until/YYYY-MM-DD]` |
| Add a one-time commitment | `commitment add n/NAME on/YYYY-MM-DD start/HHmm d/MINUTES` |
| List commitments | `commitment list` |
| Delete a commitment | `commitment delete CNUMBER` |
| Skip one commitment occurrence | `commitment skip CNUMBER on/YYYY-MM-DD` |
| Set study hours | `hours DAY\|YYYY-MM-DD /start HH:mm /end HH:mm` |
| View effective study hours | `hours DAY\|YYYY-MM-DD` |
| Reset study hours | `hours reset DAY\|YYYY-MM-DD` |
| Edit a commitment | `commitment edit CNUMBER [n/NAME] [day/DAY\|on/YYYY-MM-DD] [start/HHmm] [d/MINUTES] [from/YYYY-MM-DD until/YYYY-MM-DD]` |
| Generate or replace the plan | `plan` |
| View today's schedule | `schedule today` |
| View a dated schedule | `schedule YYYY-MM-DD` |
| Exit | `exit` |

`TNUMBER`, `CNUMBER` and `RNUMBER` mean existing IDs such as `T1`, `C1` and `R1`.
`WEEKLY` is a literal keyword, not a placeholder. Replace the other uppercase
placeholders with your values; do not type the placeholders literally.

[Back to top](#scheduleflow-user-guide)
