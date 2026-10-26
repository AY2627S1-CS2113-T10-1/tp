# ScheduleFlow tests

Root AGENTS.md applies to tests, including loading the Java coding standard
and Git standard and invoking the repository-local test-ui skill after each
coherent code update.

Add or update JUnit 5 tests with every new or changed Java behaviour, and add
regression tests with bug fixes. Cover valid, invalid and boundary cases that
matter to the contract. Keep assertions on observable behaviour; do not add
redundant tests for documentation-only changes.

Tests mirror source packages and follow the team's version 2 whole-file
allocation, not the package name alone. Nickhil owns `cli/CommandParserTest.java`,
`common/InputRulesTest.java`, `common/ValidationExceptionTest.java`,
`model/TaskTest.java`, `task/TaskEditTest.java`, `task/TaskRecurrenceTest.java`
and `task/TaskServiceTest.java`. In particular, TaskRecurrenceTest protects
already-generated Task mutations; Gabriel owns RecurringTaskServiceTest.

Darren owns CommandDispatcherTest and commitment tests. Ka How owns the other
cli tests, MainTest and the process UI plan/fixtures. Ernest owns SnapshotTest
and storage tests; Gabriel owns the remaining assigned model and planning tests.
Each owner covers the entire assigned test file, with no interior author-tag
overrides. Cross-feature assertions do not change that allocation. Coordinate
rollback and shared-contract coverage with the affected owners.

Mirror the SUT's path under `src/test/java`, use the same package declaration
and name the test class `SutNameTest`. Test methods may use the Java coding
standard's `method_scenario_expectedOutcome` naming convention. Matching
packages permit package-private access without changing production visibility.

Use JUnit 5, fixed local time inputs, Clock.fixed with an explicit zone where
a clock is required, temporary storage paths and purposeful fake Storage.
Never depend on the current date, the developer's saved data or timezone.
Restore modified shared JVM state in `finally` and isolate such tests from
concurrent execution.
Keep assertions focused on behavior and contract invariants. No disabled
placeholder tests or expected-stub-exception acceptance tests.

Foundation tests deliberately do not certify deferred services, planning,
persistence or CLI acceptance cases. Add those tests with the owning feature.
