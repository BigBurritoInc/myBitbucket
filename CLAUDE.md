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

7. **No real Project.** `Model` became a per-project service (see "Per-project state" below) and
   now needs a real `Project` to construct — PanelRunner has none. Fixed by having `PRComponent`
   depend on a small `PRActions` interface (checkout/approve/merge) instead of `Model` directly;
   PanelRunner builds its `Panel` inline with a no-op `PRActions` stub instead of going through
   `PanelFactory.createReviewPanel()`. `Model` implements `PRActions` for the real plugin.

**Lesson for future platform-vs-standalone-JVM mismatches**: prefer a fix scoped to the actual
call site or to `PanelRunner.kt` itself over a broad JVM-wide property/flag — a broad lever
tends to touch unrelated platform code paths and trade one crash for another (see step 6).

## Per-project state

`Model`, `UpdateTaskHolder`, `DemoMode`, and the Settings page (`BitbucketHelperConfigurable`) are
all project-scoped — one instance per open IDE project (`@Service(Service.Level.PROJECT)` light
services for the first three; `<projectConfigurable>` in plugin.xml for the last), not shared
globals. Each gets its `Project` via constructor injection instead of guessing it. `Git.kt`'s VCS
operations (`checkoutBranch`/`currentBranch`) take `Project` as an explicit parameter for the same
reason. This replaced an earlier all-global design (`object Model`, `object UpdateTaskHolder`, a
shared `lateinit var currentProject`, `getStorerService()`'s `DataManager`/`ProjectManager`
guessing) that worked fine with one project open and broke with more than one: every open project
shared the same PR list, the same poll loop, and the same ambiently-guessed project for checkout,
so all windows ended up showing whichever project's data was guessed most recently. See "Known
incidents" below for that history.

## Fetching pull requests

The poll reads the configured repository directly:
`/rest/api/1.0/projects/{key}/repos/{slug}/pull-requests?state=OPEN&order=NEWEST&start&limit&avatarSize`,
paged, then split into "own" and "reviewing" client-side by `partitionPRs` (`bitbucket/PRQuery.kt`).
A pull request the user both authored and reviews lands in **both** lists — deliberately, so use two
`filter` passes, never `List.partition`.

This replaced `/rest/api/1.0/inbox/pull-requests`, which was called twice per cycle (once per role),
paged through every pull request on the **whole server**, and then discarded everything outside the
configured repo. Two reasons it had to go: the volume (an instance-wide list every 15s), and the fact
that Bitbucket Cloud has no inbox endpoint at all, so nothing built on it could ever be reused there.

`MergeStatusCache` is the other half of the volume story, and the larger half — the old code re-read
merge status for up to 20 pull requests *every* cycle, which was the bulk of all requests the plugin
made. It now re-fetches strictly when `PR.version` has increased — nothing else, no timer. Anything
the user does to a pull request bumps its version, so a steady repository costs zero merge-status
requests. The known gap: merge status also changes when the **target branch** moves, which doesn't
touch the pull request and so doesn't bump its version, leaving a stale answer until the pull request
changes for some other reason. That is an accepted trade for the request budget, not an oversight —
don't "fix" it by reintroducing a periodic refresh without weighing it against Bitbucket Cloud's
hourly ceiling.

Two invariants inside the cache. It must write the cached status back onto each cycle's freshly
parsed `PR` objects — `Diff.mergeStatusChanged` skips any status still marked `unknown`, so omitting
that silently kills the "your pull request can be merged" notification. And whatever
`MAX_FETCHES_PER_CYCLE` cuts off must be left *uncached*, so the next cycle picks it up; caching a
skipped pull request would leave it without a status until its version happened to change.

`BitbucketClient.openPRs()` returns `null` for "couldn't fetch" and an empty `RepositoryPRs` for
"genuinely no open pull requests", and `UpdateTask` skips the cycle on `null`. Collapsing the two is
what used to make one failed request look like every pull request being closed, and then reopened on
the next success — a burst of "New Pull Request is available" balloons for PRs the user already had.

**The poll paces itself** between `UpdateTaskHolder.MIN_DELAY_SECONDS` and `MAX_DELAY_SECONDS`: a
cycle that changes nothing doubles the wait, anything that changes drops it back to the floor. That
is why `Model.updateOwnPRs`/`updateReviewingPRs` return `Boolean` — the pacing needs to know. It also
means `UpdateTask` re-arms itself with a one-shot `schedule()` in a `finally` rather than running on
`scheduleWithFixedDelay`, so `cancel()` has to set its `cancelled` flag *before* cancelling the
future: `run()` may already be executing on another thread and would otherwise re-arm itself after
being cancelled. User actions don't wait out the backoff — `Model.approve`/`merge` call
`UpdateTaskHolder.reschedule()`, which starts a fresh task at the floor delay and polls immediately.

**The Reviewing list hides pull requests the user has already approved** behind a "Show approved
pull requests" link (`Panel.isHidden`, supplied by `createReviewPanel`; `PR.isApprovedBy`). The tab
title counts the same subset — a title saying 5 above a list showing 2 reads as a bug. `Panel` keeps
its own ordered `List<PR>` and rebuilds from it, rather than treating the Swing container as the
model: hidden pull requests have no component at all, and the footer link isn't a `PRComponent`, so
any code walking `getComponent(i) as PRComponent` would break. Rebuilds are cheap here because
`Model` only fires an update when the diff is non-empty.

## Current user

`Settings.login` is always blank in production: `Configurable.apply()` sets url/project/slug/token and
forces `useAccessTokenAuth = true`, and the Settings page has no login field at all (the Basic Auth UI
is unreachable — see "Basic Auth"). So the username can't come from settings.

It comes from the **`X-AUSERNAME` response header**, which Bitbucket puts on every authenticated
response, captured in `BitbucketClient.sendRequest` into the per-project `CurrentUser` service. That
costs nothing: the pull request request itself carries it, and the capture runs before the body is
parsed, so the very first response of a cycle populates it before that cycle partitions anything.
`BitbucketClient.probeCurrentUser()` (one `GET /rest/api/1.0/application-properties`, body discarded)
is the fallback for a caller that runs before any poll — `approve()` from the tool window — and is
guarded to at most once per client. `UpdateTaskHolder.createAndRun` clears `CurrentUser` so a newly
pasted token can't inherit the previous account's identity.

Two things to know before touching this:

- Comparison must be case-insensitive. `X-AUSERNAME` echoes the server's stored casing, which on
  LDAP-backed instances need not match the casing inside pull request payloads. Use `sameUserAs`.
- The approve endpoint's last path segment is a user **slug**, while the header carries a user
  **name**. They are the same string on most instances but not all (Bitbucket slugifies names with
  `@` or `.`). `User.slug` is present in Bitbucket's JSON but not mapped onto `User` — if approve
  starts 404ing for someone with an email-style username, that's the reason, and mapping `slug` as a
  trailing defaulted constructor param is the fix.

## Avatars

Reviewer/author pictures are always the bundled generic icon
(`ReviewerComponentFactory.defaultAvatarIcon`) — not fetched per-user, from Bitbucket or anywhere
else. Access tokens can't authenticate to the address Bitbucket serves avatar images from (out of
scope for what Atlassian documents tokens as valid for — REST API and Git only), and there's no
external fallback either, since these are internal company email addresses. Who approved or
requested changes on a PR is still shown, via `ReviewerComponentFactory.getStatusIcon()`'s overlay
mark on each reviewer's icon.

The icon is clipped to a circle by `ImageUtil.createCircleImage`, which uses `min(width, height)` as
the diameter — the source is square at `avatarSize`, so the circle is exactly as wide as the square
was and `ReviewerItem`'s bounds (and the status mark positioned against them) needed no adjustment.
Scaling goes through `ImageUtil.scaleImage`, **not** `Image.getScaledInstance`: the latter returns an
image whose dimensions may not be resolved yet, and `ImageUtil.toBufferedImage` quietly substitutes a
1x1 placeholder for those, which would leave the avatar blank.

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

- **The old `getStorerService()` ambient-project guessing** (`DataManager`/`ProjectManager`
  focused-window lookups, now deleted) went through three rounds of bugs — a startup NPE (0.7.2),
  an EDT-assertion crash from a background poll, and silently showing one project's PR list in
  every open window — each one patched around the symptom instead of the actual problem: guessing
  "the current project" at all rather than being told. Superseded by "Per-project state" above,
  which removes the guessing rather than making it more careful.
- **Approve did nothing, silently, for every access-token user.** It built the participant URL from
  `Settings.login`, which the Settings page never fills in, so the PUT went to a URL ending in an
  empty path segment with `{"user":{"name":""}}` as the body. Bitbucket rejected it; the client
  rethrew; `Model.approve` caught it and only wrote to the log — and the client it builds carries no
  `ClientListener`, so nothing reached the user either. Two lessons baked into the current code: the
  username comes from the server (see "Current user"), and `Model`'s approve/merge catch blocks now
  raise a notification, because a swallowed failure there is indistinguishable from success.
- **Returning an empty list on failure is not a neutral default.** `inbox()` used to map any
  exception to `emptyList()`, which `Diff` then read as "every pull request was removed", followed
  on the next successful poll by "every pull request is new" — one dropped request produced a burst
  of balloons. Anything feeding `Model` must distinguish "couldn't fetch" from "nothing there";
  `openPRs()` returns `null` for the former.
- **`service<T>()`** (the Kotlin inline extension from `com.intellij.openapi.components`, used
  in `Configurable.kt`/`MainWindow.kt` to fetch `Storer`) gets inlined into the *caller's* bytecode
  at compile time, internal-helper calls and all. Compiling against a newer platform SDK than a
  plugin's declared `since-build` covers is exactly how that goes wrong: this project compiles
  against IC 2025.2 (see "Build") but claimed `since-build="223.0"`, and the Marketplace verifier
  failed 0.5.1 against IDEA 2023.3.8 with `NoSuchMethodError` risk on `ServicesKt.serviceNotFoundError`
  — a helper that inlined call needed but that build's `ServicesKt` doesn't have. Fixed by calling
  the plain, non-inline `ComponentManager.getService(Class<T>)` everywhere instead — same effect,
  nothing of the platform's internals gets copied into our bytecode, so it's safe across the whole
  declared `since-build` range regardless of which SDK version compiled it.
- **`Git.kt`'s `AsyncFetchAndCheckout`** uses `GitFetchSupport`, not `GitFetcher` — the latter has
  been `@Deprecated`/`@Deprecated(forRemoval = true)` since at least 2023 and was physically
  removed from the platform by some 2026.x build, so a compile-clean call to
  `GitFetcher.fetchRootsAndNotify(...)` threw `NoSuchMethodError` at runtime on a user's real,
  newer IDE. See "Verifying IntelliJ Platform APIs" above — this is the incident that motivated
  checking real platform source before relying on an API's continued existence.
