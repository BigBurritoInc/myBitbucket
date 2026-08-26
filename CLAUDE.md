# myBitbucket — notes for AI assistants

Kotlin IntelliJ IDEA plugin. Source code comments are kept minimal by design — this file holds
the "why" that would otherwise bloat inline comments. Read the relevant section before touching
the area it covers.

## Versioning

Bump `version` in `build.gradle` on every change intended for reinstall: IntelliJ's "Install
Plugin from Disk" treats an unchanged id+version as already installed and can skip replacing the
files. Convention: patch-increment per change (0.7 -> 0.7.1 -> ...), move to the next minor
version only for a genuinely new batch of work. Exception: a change confined to `build.gradle`
and/or `src/test/kotlin` that doesn't affect the shipped plugin's runtime behavior (a test
dependency fix, a dev-tool-only classpath tweak) doesn't need a version bump.

`changeNotes` in `build.gradle`'s `pluginConfiguration` block is user-facing changelog content
shown in the plugin marketplace/update dialog — write it for end users, not as a dev log. Keep it
short and selective, not a full version history:

- One entry per *notable* change, not per version bump. Most version bumps don't need a
  changeNotes entry at all — reinstall-triggering and changelog-worthy are different things.
- Only include what a user would actually notice or care about: a new feature, a fix for
  something visibly broken, a real UI change. Skip icon swaps, layout micro-adjustments, internal
  refactors, and anything that's dev-tooling-only (no "no user-visible change" entries — if
  there's nothing user-visible, there's no entry).
- No root-cause/implementation detail (class names, stack traces, "why it broke") — that belongs
  in this file's "Known incidents" section or in project memory, not in changeNotes.
- State the improvement, not the bug story. "X now works when Y" beats "Fixed X failing when Y,
  because Z" — the failure mode and its cause are project history, not something the user gets
  anything from reading. If a draft entry needs "because"/"which was caused by"/"it turned out"
  to make sense, rewrite it around the current behavior instead, or cut it. A changelog that
  reads like a string of confessions ("look how much was broken") is worse than no entry — this
  applies doubly to anything users never actually noticed (an internal fix, a race condition, a
  bug in code that shipped and got fixed in the same cycle) — see the next bullet.
- A quick follow-up fix to something not yet released doesn't get its own line — fold it into the
  entry it's fixing, or drop the superseded entry entirely, rather than stacking both.
- When adding a new entry, take the opportunity to re-scan the existing block for entries that no
  longer clear this bar and cut them — don't just append forever.

## Build

- IntelliJ Platform Gradle Plugin 2.x, pinned via `settings.gradle`'s
  `org.jetbrains.intellij.platform.settings` plugin — don't redeclare a version for
  `org.jetbrains.intellij.platform` in `build.gradle` itself, Gradle refuses to apply it twice.
- `create 'IC', '2025.2'`: a pinned, downloadable SDK (not a local IDE install) so every
  contributor builds against identical jars. Doesn't need to match the IDE version the plugin
  actually runs in — the code only uses stable public APIs, binary-compatible forward. Pinned to
  2025.2, not 2025.3: JetBrains folded Community/Ultimate into one distribution starting 2025.3,
  and the standalone IC download artifact `create('IC', ...)` needs no longer exists for it.
- This sandbox (and CI) cannot reach Maven Central or api.github.com, but
  `raw.githubusercontent.com` is reachable via direct `curl` — useful for checking actual
  IntelliJ Platform source when an API's current signature/behavior needs verifying (see
  "Verifying IntelliJ Platform APIs" below). `./gradlew build`/`compileKotlin` cannot be run here
  at all (no network route to Gradle's own plugin portal) — verify changes via full-file re-reads
  and a paren/brace balance check instead of compiling.

## Verifying IntelliJ Platform APIs

The platform's public API surface changes across versions, and a class this plugin compiles
against can be physically removed by the time a user's actual IDE runs it (see the Git.kt
GitFetcher incident below) — compiling clean is not proof a call will work at runtime. Before
using a platform API whose stability you're unsure of, check the real source rather than
guessing:

```
curl -s "https://raw.githubusercontent.com/JetBrains/intellij-community/<ref>/<path>"
```

`<ref>` can be `master` (current) or a version tag like `idea/251.23774.435` (2025.1) to check a
specific historical version. Not every path exists on every ref — some subsystems get
reorganized between versions (git4idea's source layout, for instance, is gone from current
`master` entirely as of this writing, though the runtime classes/package names are unchanged).
`@Deprecated` / `@Deprecated(forRemoval = true)` javadoc tags point at the real replacement API.

## PanelRunner

`src/test/kotlin/PanelRunner.kt` is a standalone `main()` that previews the real
`Panel`/`PRComponent` UI without launching a full IDE — a fast dev loop for adjusting layout.
Getting it to actually run standalone required several fixes, each layered on the last:

1. **Test dependencies.** `PRTest.kt`/`PRStateTest.kt` use JUnit4/`kotlin.test` with no
   `testImplementation` declared. Kotlin compiles a whole source set as one unit, so their
   unresolved references broke compilation for `PanelRunner.kt` too, despite it using neither.
   Fixed by declaring `kotlin-test-junit` and `junit:junit` in `build.gradle`.

2. **Eager top-level properties.** `PanelFactory.kt` declared `imagesSource`/`awtExecutor` as
   top-level properties with real default values (`= ImagesSource()`, etc). Kotlin compiles a
   file's top-level properties into one class with one shared `<clinit>` — touching *any* of
   them forces *all* their initializers to run first, in order. PanelRunner's first statement
   touched `awtExecutor`, which forced the real `ImagesSource()` to construct too, before
   PanelRunner's own next line could overwrite it with a lightweight stub — and merely
   loading/verifying `ImagesSource`'s class needs Apache HttpClient/`AppExecutorUtil` resolvable,
   both compile-only platform SDK deps, absent from a plain `JavaExec` runtime classpath. `by
   lazy` on `ImagesSource`'s own fields was tried first and was **insufficient** — the
   `ImagesSource()` constructor call itself was still unconditional. The real fix: both
   properties are `lateinit var` with no initializer at all, so the file's `<clinit>` contains
   zero bytecode referencing either type. `MainWindow.createToolWindowContent()` is now the one
   and only place the real plugin sets them, before any panel is built. (`imagesSource`,
   `awtExecutor`, and `ImagesSource` itself no longer exist as of the plugin no longer fetching
   avatars — see "Avatars" below — but the class-loading lesson generalizes to any future
   top-level property in this file, so it's kept.)

3. **Runtime classpath.** `create 'IC', ...` puts the platform SDK on the *compile* classpath
   only — a real plugin gets it from the IDE process it runs inside, so Gradle has no reason to
   also bundle it for a runtime classpath. This showed up incrementally, one missing class at a
   time (`VerticalLayout`, then others) until fixed at the category level: `build.gradle` extends
   `sourceSets.test.runtimeClasspath` with `sourceSets.main.compileClasspath`, plain core-Gradle
   `SourceSet` composition. A JetBrains-specific alternative,
   `testFramework(TestFrameworkType.Platform)`, was tried first and rejected at Gradle-sync time
   by this project's pinned plugin version (`Could not find method testFramework() ... on
   extension 'intellijPlatform'`) — reverted rather than chased further; the plain-Gradle
   approach avoids depending on that API surface at all.

4. **JPMS module opens.** Once the classpath was fixed, platform UI code
   (`GraphicsUtil`/`AntialiasingType`) hit `InaccessibleObjectException` reflecting into
   `java.desktop` internals — a real IDE process gets a long `--add-opens` list from its own
   launcher's `.vmoptions`; a plain `java -cp ...` process doesn't. `build.gradle` applies the
   same `--add-opens` set to every `JavaExec` task (the IDE-generated ad hoc
   `:PanelRunner.main()` task isn't one this file defines by name, so `tasks.withType(JavaExec)`
   is the only way to reach it).

5. **JBUIScale precompute.** `PRComponent`'s companion object calls `JBUI.scale(...)` at
   class-init time. A real IDE precomputes `JBUIScale`'s system scale factor during startup
   (`JBUIScale.preload()`); PanelRunner never runs that, so the first read threw
   `AssertionError("Must be precomputed")`. Fixed in `PanelRunner.kt` itself:
   `JBUIScale.setSystemScaleFactor(1f)` (an `@TestOnly`-marked method meant for exactly this) as
   the first line of `main()`, before any panel/`PRComponent` gets built.

6. **Mac-native scrollbar.** `JBScrollBar` unconditionally installs `MacScrollBarUI` (Cocoa via
   JNA) whenever `SystemInfo.isMac` is true, with no opt-out flag — and that native library only
   ships inside a real IDE distribution, no Maven artifact exists for it, so it throws
   `UnsatisfiedLinkError`. A `systemProperty 'os.name', 'Generic'` spoof was tried first and
   **reverted**: `os.name` feeds far more platform code than scrollbar selection — specifically
   it broke `JBUIScale`'s JRE-managed-HiDPI fast path (step 5), which is only taken when
   `SystemInfo.isMac` is true, reintroducing the "Must be precomputed" crash. Too blunt a lever.
   Fixed instead in `PanelRunner.kt`: it wraps its panel in a plain `javax.swing.JScrollPane`
   instead of `PanelFactory.wrapIntoJBScroll()` — it doesn't need IntelliJ's themed scrollbar
   chrome, so it just doesn't use the component that pulls in the native dependency.
   `wrapIntoJBScroll` itself is untouched; the real plugin (`MainWindow.kt`) still uses it.

**Lesson for future platform-vs-standalone-JVM mismatches**: prefer a fix scoped to the actual
call site or to `PanelRunner.kt` itself over a broad JVM-wide property/flag — a broad lever
tends to touch unrelated platform code paths and trade one crash for another (see step 6).

## Avatars

Reviewer/author pictures are always the bundled generic icon
(`ReviewerComponentFactory.defaultAvatarIcon`) — not fetched per-user, from Bitbucket or anywhere
else. Access tokens can't authenticate to the address Bitbucket serves avatar images from (out of
scope for what Atlassian documents tokens as valid for — REST API and Git only), and there's no
external fallback either, since these are internal company email addresses. Who approved or
requested changes on a PR is still shown, via `ReviewerComponentFactory.getStatusIcon()`'s overlay
mark on each reviewer's icon.

## Demo mode

`DemoModeAction` (registered in plugin.xml, not added to any menu/toolbar) fills the Reviewing tab
with 5 fake pull requests from `DemoData.kt` — for taking screenshots without a real server or any
real PR/user data. Invoke via Find Action (Cmd/Ctrl+Shift+A), search "myBitbucket" or "demo";
invoking it again switches back to real data (`DemoMode.kt`).

## Basic Auth

Access Token is the only auth method exposed in the Settings UI (`ui/Configurable.kt`) and the
tool window (`MainWindow.kt`'s Login tab is not added). Basic Auth itself
(`Settings.login`/`useAccessTokenAuth`, `http/BasicAuthRequestFactory.kt`,
`MainWindow.createLoginPanel()`) is left in place, fully functional, just unreachable from the
UI — don't delete it without being asked.

## Known incidents (for context on defensive-looking code)

- **`getStorerService()` (`ui/Configurable.kt`)** falls back to the first open project instead of
  a bare `!!` on the focused-component data context — that context can be null very early at
  startup (right as a project opens, before any component has focus), which is what crashed
  0.7.2 with an uncaught NPE from `MainWindow`'s post-construction `invokeLater` block. A second,
  later issue: `DataManager.getDataContext()` itself now asserts EDT and throws
  (`RuntimeExceptionWithAttachments`) when called off it — `UpdateTaskHolder`'s periodic poll
  builds its `BitbucketClient` on a background executor, so
  `BitbucketClientFactory.createRequestFactory()` -> `storer` -> `getStorerService()` crashed
  there. Fixed by only attempting the `DataManager` lookup when `Application.isDispatchThread` is
  true; off EDT it goes straight to the open-projects fallback. A third issue, same root cause as
  both prior ones (guessing "the current project" ambiently instead of being told): with more than
  one IDE project window open, `DataManager`'s focused-component context resolves to *whichever
  window currently has OS focus* — if that's a different project at the moment
  `UpdateTaskHolder`'s periodic poll builds its `BitbucketClient`, `storer.settings` comes back as
  that other (unconfigured) project's blank `Settings`, and `BitbucketClient.urlBuilder()` throws
  `MalformedURLException: no protocol:` on an empty `settings.url` — caught by `inbox()`'s
  catch-all, logged as "Request failed", PR list silently empty. Fixed properly this time instead
  of patching around the symptom again: `ui/PanelFactory.kt` now has a `lateinit var
  currentProject: Project`, set once by `MainWindow.createToolWindowContent()` — the one place
  that actually *knows* which project this toolwindow belongs to, no guessing involved.
  `getStorerService()` uses it whenever set, before ever touching `DataManager`/`ProjectManager`.
  Known remaining risk, not yet hit or fixed: `Git.kt`'s `currentProject()` has the identical
  ambient-`DataManager` pattern for checkout/branch operations — same class of bug could occur
  there with multiple windows open, just hasn't been reported.
- **`BitbucketClientFactory.storer`** is a computed `get()`, not an eager `val` — the platform
  forbids requesting a service (`project.service<T>()`) from a Kotlin `object`'s class
  initializer ("Class initialization must not depend on services").
- **`Git.kt`'s `AsyncFetchAndCheckout`** uses `GitFetchSupport`, not `GitFetcher` — the latter has
  been `@Deprecated`/`@Deprecated(forRemoval = true)` since at least 2023 and was physically
  removed from the platform by some 2026.x build, so a compile-clean call to
  `GitFetcher.fetchRootsAndNotify(...)` threw `NoSuchMethodError` at runtime on a user's real,
  newer IDE. See "Verifying IntelliJ Platform APIs" above — this is the incident that motivated
  checking real platform source before relying on an API's continued existence.
