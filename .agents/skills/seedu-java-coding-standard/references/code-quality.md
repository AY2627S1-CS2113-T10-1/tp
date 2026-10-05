# Code quality review

Source: [CS2113 Code Quality](https://nus-cs2113-ay2627-s1.github.io/website/se-book-adapted/chapters/codeQuality.html), read 2026-09-27.

Review new and touched code for:

- Clear names that explain purpose, consistent singular/plural usage and no
  misleading abbreviations or distinctions based only on case/numbers.
- Small, focused methods. Consider extraction beyond about 30 lines; this is
  guidance, not an automatic line-count failure. Avoid deep nesting and break
  complicated expressions into named steps when that improves comprehension.
- Named constants for unexplained literals, explicit grouping, logical order,
  and one abstraction level per method where practical.
- Simple solutions; justify complexity with actual requirements. Do not add
  speculative frameworks or optimize without a demonstrated need.
- Guard clauses where they clarify the happy path. Give switch defaults a real
  meaning or explicit error; do not disguise the last known option as default.
- Minimal variable scope, one purpose per variable, no silent exception
  handling, dead code or unnecessary duplication.
- Comments that explain intent and rationale to another developer, avoiding
  narration of obvious statements.

Preserve existing public APIs and required scaffold dependencies even while
unused. Compact constructor parameter assignments normalize/copy that same
value; they do not repurpose parameters. Keep shared validation in its assigned
owner; do not extract a competing public framework merely to reduce lines.

Make each separate quality improvement a coherent commit with its tests. Its
body explains the problem, impact, change and rationale. Keep unrelated
formatting separate from behavior changes.
