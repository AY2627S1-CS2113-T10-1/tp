---
name: seedu-java-coding-standard
description: Apply the SE Education Java basic and intermediate standard to all ScheduleFlow Java production code and tests.
---

# Java standard

Source: [SE Education](https://se-education.org/guides/conventions/java/intermediate.html), read 2026-09-27.

Required rules: use PascalCase noun type names, camelCase variables and verb
methods, and SCREAMING_SNAKE_CASE constants. Keep import ordering consistent.
Attach array brackets to types. Brace loops and conditionals; put conditional
bodies on separate lines. Document classes and public methods with Javadoc;
obvious accessors, exact overrides and tests have the source's exceptions.
Start summaries with descriptive verbs; use all parameter tags or none when
names explain them. Keep comment indentation aligned.

Source recommendations: lowercase packages; English names and American
comments; mixed-case acronyms; boolean-sounding names; plural collections;
scope-appropriate names; related constant prefixes. Use four-space indentation,
eight-space continuations, K&R braces, explicit imports, local initialization
and minimal variable scope. Keep fields nonpublic except constants/data-only
types. Use whitespace around operators and after commas/keywords; separate
logical blocks. Aim below 110 columns; never exceed 120. Mark intentional
switch fallthrough. Test names may use feature_scenario_outcome.

For uncovered topics, follow [Google Java Style](https://google.github.io/styleguide/javaguide.html).
SE rules take precedence where covered. Review the diff and run Checkstyle;
passing automation alone does not establish full compliance.

## Local decisions

- Use UTF-8, one public top-level type per matching file, explicit imports
  sorted lexically (static first), `@Override` where applicable, and no silent
  exception catches. These use the Google fallback.
- Existing record accessors and named APIs (including `snapshot`,
  `usage`, `minutes`, `forDate`, and renderer methods) override verb naming.
  Command type names also remain exact. Do not rename shared APIs for style.
- The requested `TODO(Owner): implement Type.method` markers override Google's
  newer linked-TODO format. Remove each marker when its method is implemented.
- Review [code quality](references/code-quality.md) after every code update.
- Run `gradlew.bat check` on Windows or `./gradlew check` on Unix using Java 25,
  then invoke the repository-local `test-ui` skill.
