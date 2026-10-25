# ScheduleFlow

ScheduleFlow is a Java 25 command-line application for university students who
want to manage coursework tasks, recurring weekly commitments, and study plans.

It allocates available study time in 30-minute slots according to task deadlines,
reports work that cannot fit before its deadline, and saves successful task and
commitment changes automatically.

## Current implementation

The following features are implemented:

- Add, edit, list, and delete coursework tasks, preserving stable IDs.
- Add, list, and stop weekly task templates with saved occurrence-generation history.
- Add, list, and delete recurring weekly commitments.
- Generate an earliest-deadline-first study plan.
- View today's schedule or a schedule for a specific date.
- Report unallocated work and the reason it could not be scheduled.
- Persist tasks, commitments, and ID counters in `data/scheduleflow.txt`.
- Restore saved data when the application starts.
- Preserve state when validation or saving fails.
- Run with UTF-8 console input and output.
- Package the application as an executable Shadow JAR.

Plans are temporary: they are cleared after a successful task or commitment
mutation and are not saved between application runs.

## User documentation

- [User Guide](UserGuide.md) — installation, commands, examples, scheduling
  rules, persistence, and troubleshooting.
- [Task editing](UserGuide.md#editing-a-task) and
  [weekly recurring tasks](UserGuide.md#weekly-recurring-tasks) — supported Task command examples.
- [Developer Guide](DeveloperGuide.md) — architecture, implementation details,
  design decisions, testing, and release verification.
- [About Us](AboutUs.md) — project team information.

## Quick start

The project requires Java 25.

From the repository root, run:

```shell
java -version
./gradlew --version
./gradlew build
```

On Windows, use:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
```

The executable JAR is produced at:

```text
build/libs/scheduleflow.jar
```

Launch it from a dedicated folder:

```shell
java -ea -jar scheduleflow.jar
```

The application stores data relative to the launch folder at:

```text
data/scheduleflow.txt
```

## Supported commands

```text
help
task add n/NAME due/YYYY-MM-DD HHmm d/MINUTES
task list
task delete TNUMBER
task edit TNUMBER [n/NAME] [due/YYYY-MM-DD HHmm]
task recur add n/NAME due/YYYY-MM-DD HHmm d/MINUTES every/WEEKLY [until/YYYY-MM-DD]
task recur list
task recur delete RNUMBER
task remaining TNUMBER remaining/MINUTES
commitment add n/NAME day/DAY start/HHmm d/MINUTES
commitment list
commitment delete CNUMBER
plan
schedule today
schedule YYYY-MM-DD
exit
```

Tasks and commitments use positive durations divisible by 30 minutes.
Study hours are 08:00–22:00 every day, including weekends.

## Testing

Run the Java build, tests, and Checkstyle with:

```shell
./gradlew build
```

Run the maintained process-level UI scenarios with Node.js 22 or later:

```shell
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
node --test test/release.test.mjs
```

The UI scenarios run the packaged application in isolated temporary folders and
cover startup, persistence, restart behavior, validation errors, Unicode data,
ID handling, partial plans, and release packaging.

See the [UI test plan](../test/ui-test-plan.md) for the complete scenario list
and verification protocol.

## Project structure

```text
src/main/java/scheduleflow/
├── cli/          Command parsing, dispatch, application lifecycle, and rendering
├── common/       Shared input validation and validation exceptions
├── commitment/   Recurring commitment operations
├── model/       Immutable tasks, commitments, and snapshots
├── planning/    Study-plan generation and daily schedule projection
├── storage/     UTF-8 persistence and atomic file replacement
└── task/        Coursework task operations

src/test/java/   JUnit 5 tests mirroring the production packages
test/             Node.js process/UI tests and scenario fixtures
docs/             User, developer, and team documentation
```

The application starts at `scheduleflow.Main`. The command-line layer parses and
dispatches commands to the task, commitment, planning, and storage components.
Mutating operations create candidate snapshots, save them first, and publish
them only after persistence succeeds.

For repository conventions and contribution instructions, see
[AGENTS.md](../AGENTS.md).
