# ScheduleFlow ownership

Root AGENTS.md and its three repository-local skills apply here. Read the
Developer Guide and relevant source and tests before modifying shared APIs.
Preserve exact record components, packages, constructors, checked exceptions
and methods.

Paths below are relative to this directory:

- Task owns `common/InputRules.java`, `common/ValidationException.java`,
  `model/Task.java` and `task/TaskService.java`.
- Commitment owns `model/Commitment.java` and
  `commitment/CommitmentService.java`.
- Save owns `model/Snapshot.java` and every Java file in `storage/`.
- Optimizer owns every Java file in `planning/`.
- Printing owns every Java file in `cli/` and `Main.java`.

Shared foundation files are implemented. Work within the assigned role;
coordinate interface changes with affected owners. Do not replace working
foundation behavior with placeholders or invent parallel DTOs.

Keep new helpers private. Services return candidate snapshots; Printing
saves a candidate before AppState.commit and success output. Only Printing
reads the injected clock or owns console text. Constructors do no I/O.
Only the planner enforces allocation cross-record invariants; Snapshot
uses Commitment.overlaps for weekly validation.

After every coherent code update, review/update the UI plan, invoke the local
test-ui skill, and run the applicable Java 25 checks before a local commit.
The starter BLOCKED allowance applies only to missing components needed by
the scenario; it never excuses a failure in a ready active scenario.
