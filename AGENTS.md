# Project context

This repository is a starter template for a greenfield Java project used in an introductory software engineering course in an undergraduate computer science program. Students use it as the starting point for their own projects.

# Default user context

Unless the user says otherwise, assume that you are assisting a student working on a project in this repository. If the user identifies themselves as an instructor or another project stakeholder, adapt your response to that role.

# Student profile

* Prior knowledge: Basic Java and OOP concepts.
* Level of programming experience: Beginner
* IDE and level of expertise: Beginner

# Guidance for interacting with users

* Explain the rationale for significant actions: what you did and why.
* Keep explanations brief but instructive, supporting learning through responsible use of AI. For example:

  * When suggesting a Git command, briefly explain what it does.
  * Add explanatory Javadoc comments to all classes and to nontrivial methods and fields when their purpose or behavior is not obvious.
  * Make generated code as self-explanatory as possible, and include explanatory comments where they improve understanding.
  * When faced with a design choice, choose the simplest option that is sufficient for the requirements, while briefly explaining relevant more advanced alternatives.

# Project-specific requirements

## Java version:

Ensure that Java 25 is used when running the application or build tasks. On macOS, use `sdk use java 25.0.3.fx-zulu` to switch to Java 25 if needed.

Verify both `java -version` and the JVM reported by the Gradle wrapper; do
not silently downgrade the runtime. Import the project into IntelliJ as a
Gradle project with SDK 25, and keep Java source files encoded as UTF-8.

## Java comments and Javadoc

Use Javadoc (`/** ... */`) for comments that document a Java type, constructor,
method or field, including private declarations and test helpers. Place the
Javadoc directly before the declaration's annotations or modifiers; do not use
ordinary line/block comments for declaration documentation. Document all types
and nontrivial members as required by the Java coding standard. Obvious
accessors, exact overrides and test methods retain that standard's exceptions;
any documentation added to those declarations must still use Javadoc.

Describe purpose, contracts and relevant constraints. Add `@param`, `@return`
and `@throws` tags when they clarify inputs, results or failure conditions; if
using parameter tags, document every parameter. Use `{@code ...}` and
`{@link ...}` where appropriate. Keep documentation accurate when code changes.

Keep ordinary `//` or `/* ... */` comments for implementation explanations
inside method bodies, local variables, TODO ownership markers and tooling
directives. Javadoc must attach to a declaration, so do not convert these
comments mechanically. Review changed Java comments for this rule before
committing, in addition to running Checkstyle.

## Build and verification setup

Run checks from the repository root using the committed Gradle wrapper:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat build
node --test test/ui-runner.test.mjs
node .agents/skills/test-ui/scripts/run-ui-tests.mjs
```

Use `./gradlew` on Unix. The UI runner requires Node.js 22 or later and uses
only built-in modules, so it needs no npm install. If the default Gradle cache
is unwritable, set `GRADLE_USER_HOME` to an accessible writable cache instead
of changing project dependencies to work around the environment.

## Git

Use lightweight tags unless the user requests an annotated tag.
When proposing or creating a commit message, include enough detail to explain the rationale for the change.
Do not commit or push unless explicitly asked.

# ScheduleFlow persistent project workflow

The ScheduleFlow starter task explicitly authorizes local commits. For future
project code tasks, make local commits after significant verified changes as
part of this repository workflow; never push unless explicitly requested.

Before implementation, read `docs/DeveloperGuide.md`, `docs/UserGuide.md`,
the relevant source and tests, and any applicable nested `AGENTS.md` files.
Follow the scope and implementation requirements supplied by the programmer
for the current task; do not infer assignments or implementation plans from
historical handoff notes. Preserve existing public interfaces unless a change
is authorized. Coordinate incompatible changes with affected contributors and
update callers, tests and documentation together.

After every implementation, all AI agents working on this project must review
`docs/DeveloperGuide.md` and `docs/UserGuide.md` and update them as needed in
the same change. Document affected design, implementation and testing details
in the Developer Guide, and affected commands, examples and user-visible
behaviour in the User Guide. Keep descriptions consistent with verified
behaviour and distinguish planned features from implemented features.

Repository skills are discoverable under `.agents/skills/` in Codex. Load the
files explicitly if they are absent from the initial skill catalog:

- For every Java change, including tests, load and follow
  `.agents/skills/seedu-java-coding-standard/SKILL.md`.
- Before every commit, load and follow
  `.agents/skills/seedu-git-standard/SKILL.md`.
- Review every code change against
  `.agents/skills/seedu-java-coding-standard/references/code-quality.md` and its
  attributed CS2113 guidelines.
- After every coherent code update, review `test/ui-test-plan.md`, update
  affected cases/fixtures as needed, and invoke
  `.agents/skills/test-ui/SKILL.md`. Use this ScheduleFlow skill even if a global
  skill with the same name is listed for a different project.
- Run the applicable Java 25 tests/build/quality checks before committing.
  An intended scaffold may proceed with a recorded BLOCKED UI status; an
  actual active UI failure must be fixed and rerun in a new session first.

Preserve existing unrelated work. Keep separate quality improvements in
separate commits with tests and a problem/impact/change/rationale body.
Do not implement another owner's deferred features as a refactoring. No fake
success returns from stubs. Constructors must not perform I/O.

## Quality review and verification evidence

- Passing Checkstyle does not replace reviewing names, intent and design.
  Follow the repository skills' distinction between mandatory rules,
  recommendations and documented exceptions; do not rename public APIs solely
  to satisfy a style preference.
- Keep tests deterministic and isolate test data from the developer's saved
  data. Use controlled time and temporary storage where needed.
- A successful build or test-harness run does not establish that application
  features work. Verify the affected behaviour and acceptance cases before
  claiming completion; diagnostic stub failures are never feature passes.
- Review and activate ready planned UI cases. An unrelated unfinished
  component cannot excuse a failure in a ready active scenario. Report the
  runner's results accurately: exit 0 means active scenarios passed, exit 1
  means FAILED, and exit 2 means BLOCKED.
- Include the checks run, their results, unexecuted or blocked cases, and
  remaining limitations in the completion report. Use the exact transcript
  path printed by the UI runner when citing evidence. Transcripts are under
  ignored `build/ui-transcripts/`; preserve needed evidence before running
  Gradle `clean`, which removes build artifacts and transcripts.
- Keep generated output, saved data and temporary verification dependencies
  out of commits. Stage only intended changes and review the staged diff and
  whitespace before committing. Do not merge or rewrite history without an
  explicit request.
