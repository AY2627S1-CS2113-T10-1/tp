# ScheduleFlow tests

Root AGENTS.md applies to tests, including loading the Java coding standard
and Git standard and invoking the repository-local test-ui skill after each
coherent code update.

Add or update JUnit 5 tests with every new or changed Java behaviour, and add
regression tests with bug fixes. Cover valid, invalid and boundary cases that
matter to the contract. Keep assertions on observable behaviour; do not add
redundant tests for documentation-only changes.

Tests mirror source packages and belong to the role that owns the tested
type. Printing owns cli tests and the process UI plan/fixtures; Save and
Printing coordinate rollback tests. Optimizer owns PlanningRecordsTest.

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
