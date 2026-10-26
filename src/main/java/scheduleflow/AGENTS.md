# ScheduleFlow ownership

Root AGENTS.md and its three repository-local skills apply here. Read the
Developer Guide and relevant source and tests before modifying shared APIs.
Preserve exact record components, packages, constructors, checked exceptions
and methods.

Paths below are relative to this directory:

- Nickhil (`nickpatt`) owns `cli/CommandParser.java`, `cli/ParseException.java`,
  `common/InputRules.java`, `common/ValidationException.java`, `model/Task.java`,
  `model/TaskPriority.java`, `task/TaskEdit.java` and `task/TaskService.java`.
- Darren (`Darren50`) owns `cli/CommandDispatcher.java`, `model/Commitment.java`
  and `commitment/CommitmentService.java`.
- Ernest (`ernestgoh-god`) owns the whole `model/Snapshot.java` aggregate and
  every Java file in `storage/`.
- Gabriel (`GabrielYuSP`) owns every Java file in `planning/`,
  `model/StudyHours.java`, `model/StudyWindow.java`, `model/RecurringTask.java`,
  `model/TaskOccurrence.java` and `task/RecurringTaskService.java`.
- Ka How (`ka-how`) owns `Main.java` and `cli/App.java`, `cli/AppState.java`,
  `cli/Command.java`, `cli/CommandResponse.java`, `cli/ConsoleUi.java` and
  `cli/TextRenderer.java`.

This is the whole-file allocation from the team's version 2 authorship plan,
dated 2026-10-25. Each owner covers all members, helpers and comments in their
files, without method-level exceptions. Imports and cross-feature references do
not split ownership. Tags record the agreed allocation, not original authorship,
and do not reassign historical TODOs. See the Developer Guide's Code authorship
section for the applied source/test allocations.

Shared foundation files are implemented. Work within the assigned role;
coordinate interface changes with affected owners. Do not replace working
foundation behavior with placeholders or invent parallel DTOs.

Keep new helpers private. Services return candidate snapshots; the dispatcher
saves before AppState.commit and success output and captures command time from
the injected clock. The parser does not read the clock or storage. Only
ConsoleUi performs console I/O; TextRenderer owns response text. Constructors do no I/O.
Only the planner enforces allocation cross-record invariants; Snapshot
uses Commitment.overlaps for weekly validation.

After every coherent code update, review/update the UI plan, invoke the local
test-ui skill, and run the applicable Java 25 checks before a local commit.
The starter BLOCKED allowance applies only to missing components needed by
the scenario; it never excuses a failure in a ready active scenario.
