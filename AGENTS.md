# Project context

This repository is a starter template for a greenfield Java project used in an introductory software engineering course in an undergraduate computer science program. Students use it as the starting point for their own projects.

# Default user context

Unless the user says otherwise, assume that you are assisting a student working on a project in this repository. If the user identifies themselves as an instructor or another project stakeholder, adapt your response to that role.

# Student profile

* Prior knowledge: Basic Java and OOP concepts.
* Level of programming experience: [to be filled]
* IDE and level of expertise: [to be filled]

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
Preserve public signatures and the ownership recorded in
`src/main/java/scheduleflow/AGENTS.md`; coordinate shared API changes with
affected owners.

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
