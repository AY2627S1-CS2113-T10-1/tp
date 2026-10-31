# Ernest's storage release handoff

Owner: `ernestgoh-god`. Baseline: `8911912` (upstream/master fetched on
2026-10-31). This is a member handoff, not release certification.

## Completed independent work

`FileStorage.FileOperations` supplies package-private creation/write/atomic-move/
cleanup fault injection. Normal operations still use `Files`. Real temporary-file
tests preserve the previous target's bytes, snapshot and frozen plan after
partial writes, unsupported atomic moves and other replacement errors. Creation
failure skips cleanup; cleanup failure retains the original cause and adds a
suppressed error. No dependency, global provider, wire-format change, public API
change or non-atomic fallback is introduced.

These are controlled failures on Windows. Actual provider rejection of atomic
moves, permission denial, other operating systems and power-loss durability need
their own evidence. Existing directory-target tests provide a separate real
filesystem replacement failure; dispatcher fake-storage tests cover publication.

## Pending prerequisite and storage integration

Gabriel's tested `Planner.validate(Plan)` boundary is absent from the baseline.
Do not replace it with storage-owned interval/accounting rules. After that API
lands, Ernest must:

1. Validate decoded plans before returning them from `PlanCodec.decode`, retaining
   validation causes in `StorageException`. `StateCodec.decode` delegates plan
   files through this path and must reject the whole file too.
2. Validate before encoding/saving plans, before directories or temporary files
   are created. Preserve formats 1-5/7, 6/8 and the distinct legacy-5 plan layout.
3. Add impossible-interval/accounting regressions for codec, `load` and `loadPlan`,
   byte preservation and startup rejection. Correct only genuinely impossible
   synthetic fixtures, preserving their scenarios and original owners' tags.
4. Update the guides/PPP and both storage diagrams for the actual validation
   calls, then repeat the full Java, UI and release checks.

This P0 work is not complete, and existing structural/reference checks do not
establish full allocation feasibility. No merge/cherry-pick or substitute
validator is included in the independent work.

## Packaged corrupt-plan example for Ka How

Put this exact UTF-8 text in an isolated launch folder's
`data/scheduleflow.txt` (retain its final newline):

```text
SCHEDULEFLOW|6
NEXT|2|1|1
TASK2|1|2026-10-06T18:00|30|NORMAL|Draft
PLAN|2026-10-05T07:00|2027-10-05T00:00|1|0|0
SESSION|1|2026-10-05T08:00|2026-10-05T09:00|60
```

The task ID, row counts, horizon and interval are structurally valid, but the
session credits 60 minutes against a 30-minute source task. The baseline accepts
this file; it must **not** be labelled a passing rejection case yet.

After validation integration, the contract is exit status 1, one startup error
line, no `scheduleflow> ` prompt, empty stderr and byte-for-byte preserved input.
The proposed translation is `Error: Invalid saved plan: MESSAGE` followed by a
newline; `MESSAGE` must be taken from the verified validator implementation before
Ka How adds an exact packaged expectation. The exact message remains pending,
not an invented fixture. Keep a valid frozen restart case alongside it.

## Other-owner documentation handoff

The following owner updates remain pending; this note supplies wording/evidence
without editing their assigned blocks or sending messages on their behalf.

- **Ka How, UI plan / packaged acceptance:** replace the obsolete statement that
  injected temporary-write/unsupported-atomic-move checks are unexecuted with:
  “FileStorageTest injects creation, partial-write, unsupported-atomic-move,
  replacement and cleanup failures through a package-private collaborator while
  using real temporary files. Original target bytes and frozen plans are
  preserved; these checks do not certify actual provider failures or power-loss
  durability.” The plan was reviewed; its fixtures/output remain unchanged.
- **Ka How, final guide PDFs:** regenerate the guide PDFs after all owners finish
  their Markdown/diagram changes. This member change does not update release PDFs.
- **Nickhil, Appendix B:** no user-story status becomes complete from these
  independent failure tests; impossible-plan validation remains pending.
- **Gabriel, glossary/planning docs:** the save seam changes no domain term or
  allocation rule. Supply the tested validator API/exception contract for the
  pending integration.

## Documentation and attribution review

- Updated Ernest's DG persistence design, atomic-save implementation, storage
  verification, NFR-3 evidence and manual-recovery limits; updated the UG saving
  chapter and own PPP. Existing acknowledgements still cover all reused tools;
  no new runtime dependency or adapted third-party implementation was added.
- Reviewed the assigned UG Quick start, Recording duration, Listing tasks,
  Planning partway through the day, Why did my schedule disappear?, and
  Cannot load my data FAQ. Their commands, restoration and recovery behavior are
  unchanged by this save-only seam, so their text remains unchanged.
- Updated the storage class and atomic-save sequence sources and matching
  SVG/PNG assets to show the operations seam and cleanup order. No nonexistent
  validator dependency is drawn; that update is pending the API itself.
- Only FileStorage.java and FileStorageTest.java change Java. Both retain exactly
  one `ernestgoh-god` start before the package and one bare end after the class.
  No other contributor's code or historical authorship is reassigned.

## Local verification (2026-10-31)

- Java shell and Gradle launcher/daemon: Java 25.0.3; Gradle 9.6.1; Node 24.15.0.
- `gradlew.bat test --tests scheduleflow.storage.FileStorageTest`: 26 passed.
- `gradlew.bat build`: 449 JUnit tests, zero failures/errors/skips; Checkstyle
  and packaging passed. No application dependency/configuration was changed.
- `node --test test/ui-runner.test.mjs`: all 12 runner tests passed.
- Local UI skill: all 28 active cases passed, zero failed/blocked. Exact evidence:
  `C:\Users\ernes\Documents\GitHub\tp\build\ui-transcripts\ui-2026-10-31T06-44-24.341Z-72444.txt`.
- `node --test test/release.test.mjs`: both checks passed: all 28 maintained
  scenarios with assertions enabled, and exact-JAR normal ZIP/native launcher
  startup/exit. Exact evidence:
  `C:\Users\ernes\Documents\GitHub\tp\build\ui-transcripts\release-2026-10-31T06-52-06.995Z-68284.txt`.
  Earlier attempts exposed environment setup failures: no `jar` on PATH, then
  launcher command echo from a nonempty `DEBUG`. The final fresh run set
  `JAVA_HOME=C:\Program Files\Java\jdk-25.0.3` and removed `DEBUG` only in its
  process environment. No checks, launcher code or expected output were changed.
- Both diagram sources rendered with the repository's note-arrow exporter;
  SVG/PNG display assets were visually inspected. Guide links resolve locally.
- The impossible-plan diagnostic reached a prompt and exited normally after
  `exit`, with unchanged bytes; this confirms the missing rejection boundary,
  not a passing acceptance result.
- Six unavailable-range cases (UI-022 through UI-027) remain separately planned,
  unexecuted and owned by Darren/Ka How. They are not included in the 28 passes.
- Hosted CI, macOS/Linux runs, actual unsupported providers/permission denial
  and power-loss recovery were not tested. No whole-release sign-off is claimed.
