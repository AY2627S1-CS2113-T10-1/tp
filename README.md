# ScheduleFlow

ScheduleFlow is a Java 25 starter for a five-member scheduling project.
The shared foundation and public APIs are implemented and tested. The
application is **not functionally complete**: task/commitment operations,
persistence, planning and the CLI deliberately remain owned stubs.

Start with the [Developer Guide](docs/DeveloperGuide.md), the
[User Guide](docs/UserGuide.md), and [AGENTS.md](AGENTS.md).
Module ownership is recorded in
[the source instructions](src/main/java/scheduleflow/AGENTS.md).

Import this repository as a Gradle project in IntelliJ and select JDK 25.
Keep the standard `src/main/java` and `src/test/java` source roots.

```powershell
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
```

On Unix, use `./gradlew`. The existing wrapper, Shadow plugin and JUnit 5
framework are retained. Packaging produces `build/libs/scheduleflow.jar`,
whose entry point is `scheduleflow.Main`. Running it at this stage reaches
an intentional unsupported-method exception. UI preflight reports BLOCKED.

Repository-local standards and testing skills live in
[.agents/skills](.agents/skills). Nothing needs installing globally.
See [the maintained UI plan](test/ui-test-plan.md) for exact scenario
fixtures, isolation, comparisons, transcripts and readiness rules.
