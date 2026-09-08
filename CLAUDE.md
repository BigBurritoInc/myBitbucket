# myBitbucket - notes for AI assistants

Kotlin IntelliJ IDEA plugin. Source code comments are kept minimal by design - this file holds
the "why" that would otherwise bloat inline comments. Read the relevant section before touching
the area it covers.

**Comment length rule**: an inline code comment must fit in 1–3 lines. If it needs more than
that, trim it to a 1–3 line pointer and put the full explanation in the matching section of this
file instead - add a new section if none fits.

**Pointer format**: point to that section with its own tag line, not prose woven into the
explanation. Last line of the comment, `// docs: <section>` (`* docs: <section>` inside a KDoc
block); more than one section is `// docs: <section>, <section>`. Not "See CLAUDE.md "X"." inline
- that reads as part of the explanation to a human skimming the comment, competing for attention
with the actual reason, instead of as a skippable footnote the way a `TODO:` tag is. For example:

```kotlin
// Truncated so a long comment doesn't blow up an Inbox row.
// docs: Inbox
```

```kotlin
/** Caches each PR's merge status between polls.
 * docs: Fetching pull requests
 */
```

## Versioning

Bump `version` in `build.gradle` on every change intended for reinstall: IntelliJ's "Install
Plugin from Disk" treats an unchanged id+version as already installed and can skip replacing the
files. Convention: patch-increment per change (0.7 -> 0.7.1 -> ...), move to the next minor
version only for a genuinely new batch of work. Exception: a change confined to `build.gradle`
and/or `src/test/kotlin` that doesn't affect the shipped plugin's runtime behavior (a test
dependency fix, a dev-tool-only classpath tweak) doesn't need a version bump.

`changeNotes` in `build.gradle`'s `pluginConfiguration` block is user-facing changelog content
shown in the plugin marketplace/update dialog - write it for end users, not as a dev log. Keep it
short and selective, not a full version history:

- One entry per *notable* change, not per version bump. Most version bumps don't need a
  changeNotes entry at all - reinstall-triggering and changelog-worthy are different things.
- Only include what a user would actually notice or care about: a new feature, a fix for
  something visibly broken, a real UI change. Skip icon swaps, layout micro-adjustments, internal
  refactors, and anything that's dev-tooling-only (no "no user-visible change" entries - if
  there's nothing user-visible, there's no entry).
- No root-cause/implementation detail (class names, stack traces, "why it broke") - that belongs
  in this file's "Known incidents" section or in project memory, not in changeNotes.
- State the improvement, not the bug story. "X now works when Y" beats "Fixed X failing when Y,
  because Z" - the failure mode and its cause are project history, not something the user gets
  anything from reading. If a draft entry needs "because"/"which was caused by"/"it turned out"
  to make sense, rewrite it around the current behavior instead, or cut it. A changelog that
  reads like a string of confessions ("look how much was broken") is worse than no entry - this
  applies doubly to anything users never actually noticed (an internal fix, a race condition, a
  bug in code that shipped and got fixed in the same cycle) - see the next bullet.
- A quick follow-up fix to something not yet released doesn't get its own line - fold it into the
  entry it's fixing, or drop the superseded entry entirely, rather than stacking both.
- When adding a new entry, take the opportunity to re-scan the existing block for entries that no
  longer clear this bar and cut them - don't just append forever.

## Build

- IntelliJ Platform Gradle Plugin 2.x, pinned via `settings.gradle`'s
  `org.jetbrains.intellij.platform.settings` plugin - don't redeclare a version for
  `org.jetbrains.intellij.platform` in `build.gradle` itself, Gradle refuses to apply it twice.
- `create 'IC', '2025.2'`: a pinned, downloadable SDK (not a local IDE install) so every
  contributor builds against identical jars. Doesn't need to match the IDE version the plugin
  actually runs in - the code only uses stable public APIs, binary-compatible forward. Pinned to
  2025.2, not 2025.3: JetBrains folded Community/Ultimate into one distribution starting 2025.3,
  and the standalone IC download artifact `create('IC', ...)` needs no longer exists for it.
- This sandbox (and CI) cannot reach Maven Central or api.github.com, but
  `raw.githubusercontent.com` is reachable via direct `curl` - useful for checking actual
  IntelliJ Platform source when an API's current signature/behavior needs verifying (see
  "Verifying IntelliJ Platform APIs" below). `./gradlew build`/`compileKotlin` cannot be run here
  at all (no network route to Gradle's own plugin portal) - verify changes via full-file re-reads
  and a paren/brace balance check instead of compiling.

## Verifying IntelliJ Platform APIs

The platform's public API surface changes across versions, and a class this plugin compiles
against can be physically removed by the time a user's actual IDE runs it (see the Git.kt
GitFetcher incident below) - compiling clean is not proof a call will work at runtime. Before
using a platform API whose stability you're unsure of, check the real source rather than
guessing:

```
curl -s "https://raw.githubusercontent.com/JetBrains/intellij-community/<ref>/<path>"
```

`<ref>` can be `master` (current) or a version tag like `idea/251.23774.435` (2025.1) to check a
specific historical version. Not every path exists on every ref - some subsystems get
reorganized between versions (git4idea's source layout, for instance, is gone from current
`master` entirely as of this writing, though the runtime classes/package names are unchanged).
`@Deprecated` / `@Deprecated(forRemoval = true)` javadoc tags point at the real replacement API.

## PanelRunner

`src/test/kotlin/PanelRunner.kt` is a standalone `main()` that previews the real
`Panel`/`PRComponent` UI without launching a full IDE - a fast dev loop for adjusting layout.
Getting it to actually run standalone required several fixes, each layered on the last:

1. **Test dependencies.** `PRTest.kt`/`PRStateTest.kt` use JUnit4/`kotlin.test` with no
   `testImplementation` declared. Kotlin compiles a whole source set as one unit, so their
   unresolved references broke compilation for `PanelRunner.kt` too, despite it using neither.
   Fixed by declaring `kotlin-test-junit` and `junit:junit` in `build.gradle`.

2. **Eager top-level properties.** `PanelFactory.kt` declared `imagesSource`/`awtExecutor` as
   top-level properties with real default values (`= ImagesSource()`, etc). Kotlin compiles a
   file's top-level properties into one class with one shared `<clinit>` - touching *any* of
   them forces *all* their initializers to run first, in order. PanelRunner's first statement
   touched `awtExecutor`, which forced the real `ImagesSource()` to construct too, before
   PanelRunner's own next line could overwrite it with a lightweight stub - and merely
   loading/verifying `ImagesSource`'s class needs Apache HttpClient/`AppExecutorUtil` resolvable,
   both compile-only platform SDK deps, absent from a plain `JavaExec` runtime classpath. `by
   lazy` on `ImagesSource`'s own fields was tried first and was **insufficient** - the
   `ImagesSource()` constructor call itself was still unconditional. The real fix: both
   properties are `lateinit var` with no initializer at all, so the file's `<clinit>` contains
   zero bytecode referencing either type. `MainWindow.createToolWindowContent()` is now the one
   and only place the real plugin sets them, before any panel is built. (`imagesSource`,
   `awtExecutor`, and `ImagesSource` itself no longer exist as of the plugin no longer fetching
   avatars - see "Avatars" below - but the class-loading lesson generalizes to any future
   top-level property in this file, so it's kept.)

3. **Runtime classpath.** `create 'IC', ...` puts the platform SDK on the *compile* classpath
   only - a real plugin gets it from the IDE process it runs inside, so Gradle has no reason to
   also bundle it for a runtime classpath. This showed up incrementally, one missing class at a
   time (`VerticalLayout`, then others) until fixed at the category level: `build.gradle` extends
   `sourceSets.test.runtimeClasspath` with `sourceSets.main.compileClasspath`, plain core-Gradle
   `SourceSet` composition. A JetBrains-specific alternative,
   `testFramework(TestFrameworkType.Platform)`, was tried first and rejected at Gradle-sync time
   by this project's pinned plugin version (`Could not find method testFramework() ... on
   extension 'intellijPlatform'`) - reverted rather than chased further; the plain-Gradle
   approach avoids depending on that API surface at all.

4. **JPMS module opens.** Once the classpath was fixed, platform UI code
   (`GraphicsUtil`/`AntialiasingType`) hit `InaccessibleObjectException` reflecting into
   `java.desktop` internals - a real IDE process gets a long `--add-opens` list from its own
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
   JNA) whenever `SystemInfo.isMac` is true, with no opt-out flag - and that native library only
   ships inside a real IDE distribution, no Maven artifact exists for it, so it throws
   `UnsatisfiedLinkError`. A `systemProperty 'os.name', 'Generic'` spoof was tried first and
   **reverted**: `os.name` feeds far more platform code than scrollbar selection - specifically
   it broke `JBUIScale`'s JRE-managed-HiDPI fast path (step 5), which is only taken when
   `SystemInfo.isMac` is true, reintroducing the "Must be precomputed" crash. Too blunt a lever.
   Fixed instead in `PanelRunner.kt`: it wraps its panel in a plain `javax.swing.JScrollPane`
   instead of `PanelFactory.wrapIntoJBScroll()` - it doesn't need IntelliJ's themed scrollbar
   chrome, so it just doesn't use the component that pulls in the native dependency.
   `wrapIntoJBScroll` itself is untouched; the real plugin (`MainWindow.kt`) still uses it.

7. **No real Project.** `Model` became a per-project service (see "Per-project state" below) and
   now needs a real `Project` to construct - PanelRunner has none. Fixed by having `PRComponent`
   depend on a small `PRActions` interface (checkout/approve/merge) instead of `Model` directly;
   PanelRunner builds its `Panel` inline with a no-op `PRActions` stub instead of going through
   `PanelFactory.createReviewPanel()`. `Model` implements `PRActions` for the real plugin.

**Lesson for future platform-vs-standalone-JVM mismatches**: prefer a fix scoped to the actual
call site or to `PanelRunner.kt` itself over a broad JVM-wide property/flag - a broad lever
tends to touch unrelated platform code paths and trade one crash for another (see step 6).

## Per-project state

`Model`, `UpdateTaskHolder`, `DemoMode`, and `CurrentUser` are all project-scoped - one instance
per open IDE project (`@Service(Service.Level.PROJECT)` light services, no plugin.xml
registration needed), not shared globals. Each gets its `Project` via constructor injection
instead of guessing it. `Git.kt`'s VCS operations (`checkoutBranch`/`currentBranch`) take
`Project` as an explicit parameter for the same reason, and so does `ui/AddProjectDialog.kt` and
the `discovery`/`credentials` helpers it calls - there is no per-project `Configurable` any more
(see "Basic Auth"), but the same "always be told the project, never guess it" rule still applies
to whatever replaces it. This replaced an earlier all-global design (`object Model`, `object
UpdateTaskHolder`, a shared `lateinit var currentProject`, `getStorerService()`'s
`DataManager`/`ProjectManager` guessing) that worked fine with one project open and broke with
more than one: every open project shared the same PR list, the same poll loop, and the same
ambiently-guessed project for checkout, so all windows ended up showing whichever project's data
was guessed most recently. See "Known incidents" below for that history.

## Domain model vs wire format

`domain.PR` / `domain.Participant` / `domain.MergeStatus` are what the whole plugin above the client
works in. They carry no Jackson annotations and no Bitbucket flavour. Each flavour maps its own wire
format across at the client's edge: `bitbucket.server.dto` via `bitbucket.server.ServerDtoMapper` for
Server, `bitbucket.cloud.dto` via `bitbucket.cloud.CloudDtoMapper` for Cloud. `BitbucketServerClient`
and `BitbucketCloudClient` are the only two classes that see both a DTO package and the domain types
- each is the sole class for its own flavour's DTOs, never the other's. Both implement the
`BitbucketClient` interface (`bitbucket/BitbucketClient.kt`), which is what the rest of the plugin -
`Model`, `UpdateTaskHolder` - actually depends on; see "Adding Bitbucket Cloud support" below for how
`BitbucketClientFactory` picks between them.

This exists because Bitbucket Server and Bitbucket Cloud disagree on nearly every field, and on some
concepts entirely. The one that forced the split: **Cloud pull requests have no `version`.** Server
sends `id, title, description, state, createdDate, updatedDate, fromRef, toRef, author, participants,
reviewers, draft, links, properties, version`; Cloud sends `id, title, rendered, state, createdOn,
updatedOn, closeSourceBranch, destination, source, mergeCommit, author, participants, links,
commentCount, taskCount, draft`. Hence `PR.revision`, a monotonic "has this changed" token - Server's
`version` counter, Cloud's update timestamp. Everything that used to compare versions (`PRState`'s
diffing, `MergeStatusCache`) compares that instead. The Server client converts it back to an `Int`
for the merge endpoint's optimistic locking, which is the one place the number itself matters.

Other things the mapping absorbs, and which a Cloud mapper will have to absorb differently:

- Server has separate `reviewers` and `participants`; Cloud has only `participants`, with a `role`
  discriminator. The domain exposes `reviewers` and expects each backend to work out what that means.
- Server nests identity as `user.name` / `user.displayName`; the domain flattens it to
  `Participant.userName` / `displayName`, because Cloud users have no `name` at all (`uuid`,
  `account_id`, `nickname`).
- `PR.webUrl` replaces reaching into Server's `links.self[0].href`.
- `MergeStatus.known` replaces the old mutable `unknown` flag, so the domain type is immutable.
- Server omits `description` entirely when there isn't one; the domain always has a string.

**Rule: no DTO type may appear outside `bitbucket.**`.** If a UI class needs something new from a
pull request, add it to the domain and map it, rather than passing the DTO through - that's the
whole point of the split.

## Adding Bitbucket Cloud support

Implemented: `Settings.cloud` (`ui/Settings.kt`) - a new field, not a rename, default `false` so a
pre-Cloud `workspace.xml` still deserializes to Server - is the one thing `BitbucketClientFactory.
createClient` branches on, building a `bitbucket.cloud.CloudBitbucketClient` instead of a
`BitbucketServerClient`. `Settings.project`/`slug`/`login` are reused as-is rather than growing
Cloud-specific twins: `project` holds the workspace, `slug` the repo slug, `login` the account
email - same reasoning as keeping `project` named `project` at all (see below), and it means
`Settings`/`Storer`/`CredentialStore` needed no changes beyond the one new field.

`ui.parseAnyRepositoryUrl` (`ui/Settings.kt`) tries the Cloud shape (`bitbucket.org/{workspace}/
{repo_slug}`) first - the host is unambiguous - then falls back to the Server shape (`.../projects/
{X}/repos/{Y}`); `reconstructRepositoryUrl` does the inverse. `discovery.GitRemoteDiscovery.
parseCloneUrl` special-cases `host == "bitbucket.org"` before the Server-shaped `HTTP_PATH_PATTERN`/
`SSH_PATH_PATTERN` regexes - needed because Cloud's ssh clone URLs (`git@bitbucket.org:workspace/
repo.git`) have the same two-segment shape those regexes already matched, so without the special
case a Cloud ssh remote would have been silently misread as a Server one, producing a garbage
`.../projects/workspace/repos/repo` URL. `ui.AddProjectDialog` shows a different auth section per
flavour - Server's existing three-way group (detected Git credentials / Basic Auth / Access Token)
vs. Cloud's plain Email + API Token fields - decided live off `parseAnyRepositoryUrl(repoUrlField.
text)?.cloud`, not fixed at dialog-open, so pasting a URL of the other flavour mid-edit switches the
visible fields via a `DocumentListener` rather than requiring the dialog to be reopened. Cloud never
tries `credentials.GitCredentialHelper`: a saved git credential for `bitbucket.org` is a Bitbucket
username + token meant for git operations, not the email + API token Cloud's REST API wants, and
reusing one for the other would silently save the wrong credential.

`bitbucket.cloud.dto.PullRequestDto` and friends are Cloud's wire format - deliberately not unified
with `bitbucket.server.dto`, per "Domain model vs wire format" above. `bitbucket.cloud.
CloudDtoMapper` maps them into `domain` types, same role as `ServerDtoMapper`. Two mapping notes
worth knowing before touching this: Cloud has no `version` counter, so `PR.revision` is the parsed
`updated_on` timestamp instead (Server's mapper uses the real counter - see "Domain model vs wire
format"); and Cloud's author isn't itself a full participant record the way Server's is, so the
mapper looks the author up inside `participants` by `uuid` for a real approved/status answer instead
of hardcoding `UNAPPROVED`. Timestamps are parsed as `String` via `java.time.OffsetDateTime.parse`
rather than through Jackson's default date handling - Cloud's microsecond precision
(`"...000000+00:00"`) isn't safely covered by Jackson's `StdDateFormat`, and there is no compiler
here to catch a mismatch if it were.

`bitbucket.cloud.CloudBitbucketClient` fetches with `q=state="OPEN"` (no author/reviewer filter) and
reuses `bitbucket.partitionPRs` exactly like `BitbucketServerClient` does - deliberately, rather than
issuing two separately-filtered queries, since it gets the "a self-reviewed PR appears in both lists"
behavior for free from code that already exists. Pagination follows Cloud's own `page`/`pagelen`/
`next` shape (`bitbucket.cloud.dto.PageDto`, kept separate from Server's `start`/`limit`/
`isLastPage`/`nextPageStart` one) - `next` is already a complete URL for the following page, so
unlike Server's replay there's no query-param reconstruction between pages, just following the link
until it's absent. Approve and merge are bodyless/near-bodyless POSTs, simpler than Server's.

**Current user**: Cloud has no `X-AUSERNAME`-style header - `BitbucketCloudClient` resolves the
current user with one explicit `GET /2.0/user` call, caches the returned `uuid` for the client
instance's lifetime (same "one probe per client, recreated each reschedule" pattern as Server's
`probeCurrentUser`), and uses that `uuid` everywhere the rest of the plugin compares a PR's
author/reviewers against "me" - Cloud has no stable "username" any more, only opaque ids. This does
**not** go through the shared per-project `CurrentUser` service (see "Per-project state"); that
service's whole job is capturing a response header Cloud doesn't send, so Cloud keeps its own
resolution local instead of stretching that service to cover a concept it doesn't have. A Cloud
personal **API token** - the credential type this plugin uses - can call `GET /2.0/user` fine, given
the `read:user:bitbucket` scope; a Community report of that endpoint rejecting a token turned out to
be about Bitbucket's separate Repository/Project/Workspace *Access Tokens*, a bot-identity-bound
credential family this plugin doesn't use - worth knowing before treating that report as a blocker.

**Merge status is a deliberate gap, not a missing feature**: Bitbucket Cloud has no public,
documented endpoint for merge-conflict status (checked against several Community threads asking the
same question over multiple years, not assumed) - `CloudBitbucketClient.retrieveMergeStatus` always
returns an optimistic `canMerge = true, known = true`, so the Merge button stays enabled for Cloud
PRs and a real conflict surfaces as a failed merge attempt instead of being predicted up front. The
alternative - Bitbucket's undocumented internal API - was deliberately not used; see "DTOs are not
unified" and the "don't force a symmetric interface" reasoning above, which both argue for
Cloud-native gaps staying gaps rather than being paved over with something unstable.

Still Server-only, out of scope for this pass: diff, comments, tasks, attachments - nothing above
the client asks for any of these yet, so there was nothing to add. `Settings.project` stays named
`project` rather than becoming something flavour-neutral like `namespace`: Cloud's equivalent is a
workspace, not a project, but renaming the field would hit the exact silent-data-loss bug class
described in "Basic Auth" below ("Migrating a pre-0.6 install") - the platform's XML deserializer
drops a saved property that no longer matches the class instead of erroring, so a renamed field
would quietly lose every existing user's repository binding on upgrade. `Settings.cloud` is the
add-new-field/migrate-once pattern `accessToken` → `CredentialStore` used, applied here instead.

**Auth note**: Bitbucket Cloud's old "app passwords" were fully retired (final removal July 28,
2026), and their replacement - Cloud API tokens, which this plugin originally used over plain
Basic Auth - has itself since been discontinued: a personal, non-org-managed Bitbucket account has
no "create an API token" affordance left. What account settings calls "App authorizations" is not
a replacement token mechanism - it's just the list of OAuth apps already granted access, not a way
to mint one. The actual replacement is Bitbucket Cloud's OAuth 2.0 Authorization Code grant - see
"Bitbucket Cloud OAuth sign-in" below for the full design. `AddProjectDialog`'s old Email/API Token
fields and their `BasicAuthRequestFactory` wiring are gone for Cloud entirely; Server's Basic
Auth/Access Token paths are untouched.

## Bitbucket Cloud OAuth sign-in

`credentials.CloudOAuth` is the whole OAuth layer - the only place beyond one branch in
`BitbucketClientFactory.createRequestFactory` that Cloud auth touches. That branch resolves a
bearer token via `CloudOAuth.freshAccessToken()` and hands it to the existing
`AccessTokenRequestFactory` unchanged - the same class Server's own Access Token mode already
uses, since an OAuth access token is just another Bearer-header secret to that class.

**Shared built-in consumer, not per-user registration**: Bitbucket Cloud's OAuth has no PKCE
(Atlassian feature request open since Nov 2024, no ETA) and no device-authorization grant - the
Authorization Code exchange requires a client secret, full stop. Since this is a distributed
plugin, the alternative to embedding one shared consumer's client ID/secret would be asking every
user to register their own OAuth consumer and paste both values in - no better than the API token
field this replaces, arguably worse. A real published third-party Bitbucket CLI tool takes the
same shared-built-in-consumer approach for the same reason, confirmed by reading its own auth
setup docs. The trade-off accepted: the embedded secret is extractable from the plugin jar by
decompiling it - inherent to any native OAuth client against a provider with no PKCE, not specific
to this plugin's design.

Researched before accepting this trade-off (not assumed): RFC 8252 §8.5 says a secret statically
included in an app distributed to many users "should not be treated as confidential" and that
authorization servers requiring one from a public client serves "little value beyond client
identification" - i.e. the spec itself expects providers like Bitbucket to know this doesn't
protect anything. GitHub has publicly confirmed it ships the same way for VS Code, Visual Studio,
GitHub CLI and GitHub Mobile ("You cannot keep a secret 'secret' within a public client, but you
do have to embed it there anyhow" - github.com/orgs/community/discussions/15752). The
standards-recommended alternative - draft-ietf-oauth-v2-1 §2.1 names it explicitly -
is a backend-for-frontend broker: the plugin would talk only to a small server we'd run, which
holds the real secret and performs the code-for-token exchange, so the secret never ships in the
jar at all (this is how VS Code's own GitHub sign-in and GitKraken's Git integrations work).
Deliberately not built for this plugin: it would need standing up and maintaining a server, for a
plugin whose worst-case exposure is bounded by its own scopes (`account:read`, `pullrequest`,
`pullrequest:write` - no repo write, no account settings) and by Bitbucket enforcing the
consumer's one fixed redirect URI. Revisit if the scope list ever grows to include something more
sensitive (repo write/delete, account-level changes).

**Loopback callback, not a real redirect target**: the consumer's registered Callback URL is
`http://localhost:47829/callback` - Bitbucket does accept a localhost callback for a registered
consumer (confirmed against a real third-party tool's own setup instructions; Atlassian's own docs
don't say either way). `CloudOAuth.authorize()` opens a raw `java.net.ServerSocket` on that port
rather than `com.sun.net.httpserver.HttpServer` - sidesteps any question of whether that JDK
module is safely usable inside a real IDE's JPMS-launched process, at the cost of hand-parsing one
HTTP request line, which is all a one-shot local callback actually needs. If port 47829 is already
taken by something else on the machine, sign-in fails with a clear message instead of silently
hanging - not engineered around, since Bitbucket's consumer callback appears to be one fixed URL,
not a settable list to fall back through.

**Cancellation**: a single blocking `accept()` wouldn't notice a Cancel click on the wrapping
`ProgressManager` modal - `acceptWithCancellation` polls in ~1s bursts instead, calling
`ProgressManager.checkCanceled()` between attempts so Cancel actually stops the wait instead of
running out the full 5-minute timeout.

**Token persistence is deferred to `doOKAction()`, not immediate on sign-in**: `authorize()`
deliberately does not persist the bundle it returns - `AddProjectDialog` holds it in
`pendingOAuthTokens` and only calls `CloudOAuth.store()` if the dialog is actually confirmed with
OK, mirroring the existing "secret commits together with the rest of Settings, discarded on
Cancel" rule Server's fields already follow. Signing in and then clicking Cancel would otherwise
leave a token saved for a repository binding that was never actually applied.

**Refresh is proactive, not reactive-only**: `freshAccessToken()` runs once per client
construction (`BitbucketClientFactory.createRequestFactory`, i.e. roughly once per poll cycle) and
only makes a network call once the cached access token has under two minutes left - Bitbucket
rotates the refresh token on every use, so the newly returned one must be persisted immediately or
the next refresh would use a dead token. A failed refresh (the refresh token itself expired after
three months unused, or the user revoked the app's access from Bitbucket's "App authorizations"
page) clears the stored bundle and returns null; `BitbucketClientFactory` turns that into an empty
Bearer token, and the resulting 401 already routes through the existing
`UpdateTaskHolder.invalidCredentials()` → "Reconfigure Bitbucket repo…" flow, so no separate error
plumbing was needed for that path. `invalidCredentials()` itself branches on `settings.cloud` to
clear `CloudOAuth`'s stored bundle instead of `CredentialStore`'s Basic Auth entry.

**Not independently confirmed** - same "no compiler here" caveat as the rest of this file's Cloud
work: `com.intellij.ide.BrowserUtil.browse(url: String)` for opening the system browser, and
`com.intellij.openapi.progress.ProgressManager.checkCanceled()`'s exact behavior called from
inside a Runnable passed to `runProcessWithProgressSynchronously`. Both are extremely standard,
long-stable platform APIs, but verify against real source before trusting them if either misbehaves.

## Fetching pull requests

The poll reads the configured repository directly:
`/rest/api/1.0/projects/{key}/repos/{slug}/pull-requests?state=OPEN&order=NEWEST&start&limit&avatarSize`,
paged, then split into "own" and "reviewing" client-side by `partitionPRs` (`bitbucket/PRQuery.kt`).
A pull request the user both authored and reviews lands in **both** lists - deliberately, so use two
`filter` passes, never `List.partition`.

This replaced `/rest/api/1.0/inbox/pull-requests`, which was called twice per cycle (once per role),
paged through every pull request on the **whole server**, and then discarded everything outside the
configured repo. Two reasons it had to go: the volume (an instance-wide list every 15s), and the fact
that Bitbucket Cloud has no inbox endpoint at all, so nothing built on it could ever be reused there.

`MergeStatusCache` is the other half of the volume story, and the larger half - the old code re-read
merge status for up to 20 pull requests *every* cycle, which was the bulk of all requests the plugin
made. It now re-fetches strictly when `PR.version` has increased - nothing else, no timer. Anything
the user does to a pull request bumps its version, so a steady repository costs zero merge-status
requests. The known gap: merge status also changes when the **target branch** moves, which doesn't
touch the pull request and so doesn't bump its version, leaving a stale answer until the pull request
changes for some other reason. That is an accepted trade for the request budget, not an oversight -
don't "fix" it by reintroducing a periodic refresh without weighing it against Bitbucket Cloud's
hourly ceiling.

Two invariants inside the cache. It must write the cached status back onto each cycle's freshly
parsed `PR` objects - `Diff.mergeStatusChanged` skips any status still marked `unknown`, so omitting
that silently kills the "your pull request can be merged" notification. And whatever
`MAX_FETCHES_PER_CYCLE` cuts off must be left *uncached*, so the next cycle picks it up; caching a
skipped pull request would leave it without a status until its version happened to change.

`ServerBitbucketClient.openPRs()` returns `null` for "couldn't fetch" and an empty `RepositoryPRs` for
"genuinely no open pull requests", and `UpdateTask` skips the cycle on `null`. Collapsing the two is
what used to make one failed request look like every pull request being closed, and then reopened on
the next success - a burst of "New Pull Request is available" balloons for PRs the user already had.

**The poll paces itself** between `UpdateTaskHolder.MIN_DELAY_SECONDS` and `MAX_DELAY_SECONDS`: a
cycle that changes nothing doubles the wait, anything that changes drops it back to the floor. That
is why `Model.updateOwnPRs`/`updateReviewingPRs` return `Boolean` - the pacing needs to know. It also
means `UpdateTask` re-arms itself with a one-shot `schedule()` in a `finally` rather than running on
`scheduleWithFixedDelay`, so `cancel()` has to set its `cancelled` flag *before* cancelling the
future: `run()` may already be executing on another thread and would otherwise re-arm itself after
being cancelled. User actions don't wait out the backoff - `Model.approve`/`merge` call
`UpdateTaskHolder.reschedule()`, which starts a fresh task at the floor delay and polls immediately.

**The Reviewing list is ordered by `ReviewAttention`** - needs attention, then changes requested,
then approved - and collapses the approved ones behind a "Show already approved" button
(`Panel.attentionOf`, supplied by `createReviewPanel`; `PR.attentionFor`). One function drives both,
deliberately: sorting the *whole* list and then slicing off the approved tail is what keeps the
visible cards in place when the button reveals the rest. Sorting only the visible part, or hiding
without sorting, makes the revealed ones interleave and the list appear to reshuffle. `sortedBy` is
stable, so recency order survives inside each bucket. The tab
title counts the same subset - a title saying 5 above a list showing 2 reads as a bug. `Panel` keeps
its own ordered `List<PR>` and rebuilds from it, rather than treating the Swing container as the
model: hidden pull requests have no component at all, and the footer link isn't a `PRComponent`, so
any code walking `getComponent(i) as PRComponent` would break. Rebuilds are cheap here because
`Model` only fires an update when the diff is non-empty.

## Current user

This section is Server-specific - see "Adding Bitbucket Cloud support" above for how Cloud resolves
the current user instead (`GET /2.0/user` → `uuid`, no header involved).

`Settings.login` is populated now - Basic Auth mode fills it from a detected or manually typed
git credential (see "Basic Auth") - but the plugin still doesn't use it to identify the current
user: the approve endpoint needs a Bitbucket user **slug**, not a login, and Access Token mode has
no login at all. So the username still comes from the **`X-AUSERNAME` response header**, which
Bitbucket puts on every authenticated response, captured in `ServerBitbucketClient.sendRequest` into the
per-project `CurrentUser` service. That costs nothing: the pull request request itself carries it,
and the capture runs before the body is parsed, so the very first response of a cycle populates it
before that cycle partitions anything. `ServerBitbucketClient.probeCurrentUser()` (one `GET
/rest/api/1.0/application-properties`, body discarded) is the fallback for a caller that runs
before any poll - `approve()` from the tool window - and is guarded to at most once per client.
`UpdateTaskHolder.createAndRun` clears `CurrentUser` so a newly configured credential can't
inherit the previous account's identity.

Two things to know before touching this:

- Comparison must be case-insensitive. `X-AUSERNAME` echoes the server's stored casing, which on
  LDAP-backed instances need not match the casing inside pull request payloads. Use `sameUserAs`.
- The approve endpoint's last path segment is a user **slug**, while the header carries a user
  **name**. They are the same string on most instances but not all (Bitbucket slugifies names with
  `@` or `.`). `User.slug` is present in Bitbucket's JSON but not mapped onto `User` - if approve
  starts 404ing for someone with an email-style username, that's the reason, and mapping `slug` as a
  trailing defaulted constructor param is the fix.

## Inbox

**Currently disabled** - `UpdateTaskHolder.INBOX_FEATURE_ENABLED = false`. The feature below is
fully implemented and was working, but the UX (a whole extra tab, unread counts, a notification
toggle) needs a rework before it's worth shipping to users. Disabling it is one `const val`: the
tab isn't built (`MainWindow.showConfiguredContent`) and the poll loop never calls
`InboxTracker.detect()` (`UpdateTaskHolder.pollOnce`), so it makes zero extra requests while off.
Nothing below needs to change to re-enable it - flip the flag back once the UX is settled.

A third tool-window tab, next to Reviewing and Created: a feed of new comments on your own PRs,
replies to comments you made (even on a PR you're only reviewing), and new approvals on your own
PRs - see `bitbucket/InboxTracker.kt`, `ui/InboxStore.kt`, `ui/InboxPanel.kt`. Built because neither
Bitbucket flavour surfaces this inside the IDE at all; it only reaches you by email or the web UI's
own bell icon.

Neither Bitbucket Server nor Cloud has a cross-repository "what's new for me" endpoint, and neither
has a reliable "since a timestamp" filter on the per-PR comment source - only generic offset/cursor
paging. So `InboxTracker.detect()` fetches each tracked PR's comments in full every time it decides
to check (see "Revision-gated" below) and filters client-side against a persisted "last seen
comment id" (`InboxStore.inboxState.lastSeenCommentId`). Comment ordering isn't documented on
either platform, so this pages all the way to the end and filters by id afterwards rather than
assuming newest-first - correct either way, just not maximally efficient if the true order turns
out to be newest-first.

**Server and Cloud fetch comments completely differently - this is not a detail to genericize
away.** Cloud's `GET .../pullrequests/{id}/comments` is a real general-comment listing: flat
`values`, each with a `parent.id` back-reference on a reply. Server has no equivalent: its
`GET .../pull-requests/{id}/comments` *looks* like the same thing but is actually the file/line
comment search - it 400s ("The path parameter must be supplied") without a `path` query param, and
shipping that call was a real incident (see "Known incidents"). Server's real general-comment feed
is `GET .../pull-requests/{id}/activities`, filtered to `action == "COMMENTED"`; each such
activity's `comment` has no flat `parent` field either - a reply lives nested inside its parent's
own `comments` array, arbitrarily deep. `ActivityCommentDto.toDomainTree()` (`ServerDtoMapper.kt`)
walks that tree and derives each `domain.Comment.parentCommentId` from the nesting itself. This
also sidesteps a question the public docs don't answer - whether a reply gets its own `activities`
entry or only shows up nested under its thread's original entry - since every fetch walks the
*current* full tree of every `COMMENTED` activity's `comment`, on every page, rather than trying to
diff activity-level ids.

**Revision-gated, not polled on a timer.** A PR's comments are only re-fetched when
`PR.revision` has advanced past `InboxStore.inboxState.lastCheckedRevision[pr.id]` - the same
signal `ui/PRState.kt` already diffs on, reused independently here. A steady PR costs zero extra
requests. This matters more for Cloud, which caps authenticated REST use at 1,000 requests/hour/user
across `/2.0/repositories/**`; Server/DC is self-hosted and has no such ceiling, but the gate
applies to both uniformly rather than branching behavior on `Settings.cloud`.

**Own PRs get blanket coverage; PRs you're only reviewing get targeted coverage.** Any new comment
on your own PR is a `NEW_COMMENT` event - including a reply to a comment you left there, so there's
no separate REPLY check for your own PRs. On a PR you're only reviewing, only a reply to a comment
you specifically made becomes a `REPLY` event; a generic new top-level comment from someone else is
deliberately not surfaced - reviewing a PR isn't an invitation to every comment on it. Approvals
need no extra API call at all: `domain.PR.reviewers` (with `approved`/`status`) already arrives on
the pull-requests list call `UpdateTaskHolder` makes every poll, so `InboxTracker.checkApprovals`
just diffs it against a persisted snapshot (`InboxStore.inboxState.lastApprovals`).

**A PR seen for the first time seeds state and emits nothing.** Without this, starting to review a
PR with 40 existing comments, or the very first poll after installing the plugin, would flood the
Inbox with everything that PR has ever had. `firstSeen` (no stored `lastCheckedRevision` entry for
that PR id) is checked before emitting any event, though the comment/approval snapshot is still
recorded so the next poll has a correct baseline to diff against.

**Replies to an older, already-paged-past comment still resolve.** A reply's parent comment may
have been fetched (and excluded by the `sinceCommentId` filter) on an earlier poll, so "is this a
reply to me" can't rely only on the current poll's fetch batch. `InboxStore.inboxState.myCommentIds`
(PR id → ids of comments the current user authored) is built up incrementally across every poll,
including the first-seen seeding fetch, and is what `checkReviewingPR` actually checks
`parentCommentId` against.

**User comparison is case-insensitive**, via the same `String.sameUserAs` extension
`bitbucket/PRQuery.kt` already uses for own/reviewing partitioning - Server's `X-AUSERNAME` casing
need not match a comment or reviewer payload's casing on LDAP-backed instances. Comparing with `==`
here would silently misattribute your own comments/approvals as someone else's on such instances.
`BitbucketClient.currentUserIdentifier()` (Server: username; Cloud: uuid, see "Adding Bitbucket
Cloud support" above) is what every comparison here is against - resolved the same way `openPRs()`
already resolves it, so this costs no extra request.

**Deliberately public-API-only.** Every fetch here is the same documented `/rest/api/1.0/...`/
`/2.0/...` surface the rest of the plugin already uses - no undocumented internal API, and no
credential type (like a harvested browser-session cookie) beyond what the rest of the plugin
already asks the user for.

Persistence is `ui/InboxStore.kt`, the same `PersistentStateComponent` pattern as `Storer`/
`Settings` (`@State`, `WORKSPACE_FILE` storage, registered in plugin.xml as a `<projectService>`) -
read/unread state and every "last seen" watermark survive an IDE restart, and nothing gets replayed
as "new" just because the IDE reopened. The persisted property is named `inboxState`, not `state` -
see "Known incidents" for why that name matters, not just style. `recordEvents` dedupes by
`InboxEvent.id` (`"$prId:comment:$commentId"` / `"$prId:approval:$userName"` - stable across polls)
and caps stored history at 200 events; `pruneClosedPRs` keeps the watermark maps proportional to
currently-tracked PRs instead of growing forever as PRs close over a long session.

The balloon notification on a new event is off by default - `Settings.inboxNotificationsEnabled`,
toggled from the tab's own header checkbox, round-tripped by `Storer` like every other `Settings`
field. Off or on, the tab's badge count and row list always update; the setting only gates the extra
balloon via `Model.publishInboxEvents`.

## Avatars

Reviewer/author pictures are always the bundled generic icon
(`ReviewerComponentFactory.defaultAvatarIcon`) - not fetched per-user, from Bitbucket or anywhere
else. Access tokens can't authenticate to the address Bitbucket serves avatar images from (out of
scope for what Atlassian documents tokens as valid for - REST API and Git only), and there's no
external fallback either, since these are internal company email addresses. Who approved or
requested changes on a PR is still shown, via `ReviewerComponentFactory.getStatusIcon()`'s overlay
mark on each reviewer's icon.

The icon is clipped to a circle by `ImageUtil.createCircleImage`, which uses `min(width, height)` as
the diameter - the source is square at `avatarSize`, so the circle is exactly as wide as the square
was and `ReviewerItem`'s bounds (and the status mark positioned against them) needed no adjustment.
Scaling goes through `ImageUtil.scaleImage`, **not** `Image.getScaledInstance`: the latter returns an
image whose dimensions may not be resolved yet, and `ImageUtil.toBufferedImage` quietly substitutes a
1x1 placeholder for those, which would leave the avatar blank.

## Demo mode

`DemoModeAction` (registered in plugin.xml, not added to any menu/toolbar) fills the Reviewing tab
with 5 fake pull requests from `DemoData.kt` - for taking screenshots without a real server or any
real PR/user data. Invoke via Find Action (Cmd/Ctrl+Shift+A), search "myBitbucket" or "demo";
invoking it again switches back to real data (`DemoMode.kt`).

## Basic Auth

There is no Settings page any more (`ui/Configurable.kt` is deleted). A project starts
unconfigured and the tool window shows one "Add Bitbucket repo" button
(`MainWindow.showAddProjectPrompt`) - named to be clearly about the Bitbucket-side configuration,
not the local git repository - until a repository is bound to it. Clicking it - or the tool
window gear's "Reconfigure Bitbucket repo…" on an already-configured project - runs
`discovery.discoverRepositoryUrl` against the project's own git remotes to guess the repository
URL, and, for an http(s) remote, shells out to `git credential fill`
(`credentials.GitCredentialHelper`) to see whether the system's own git credential helper (OS
keychain, Git Credential Manager, ...) already has something saved for that host.
`ui/AddProjectDialog.kt` opens pre-filled with whatever was found; the user can accept it, edit
it, or switch to the other auth mode (Basic Auth vs. HTTP Access Token) and type it by hand -
picking a mode is never gated on detection having found something for it, only the initial
selection and pre-fill are (an earlier version disabled the Basic Auth radio button whenever
nothing was auto-detected, which left no way to sign in that way by hand - an ssh remote, no
`credential.helper` configured, or the helper process failing all hit this).

Whichever secret is obtained is written to **PasswordSafe** (`credentials.CredentialStore`), keyed
by server host[:port] rather than by repository - reconfiguring a different repository on a server
already signed into reuses what's saved instead of asking again. `Settings` itself (`ui/Settings.kt`,
still `PersistentStateComponent`/`workspace.xml`) never carries a secret any more - this fixes
Access Token's old plaintext-on-disk storage too, not just Basic Auth's.
`BitbucketClientFactory.createRequestFactory` reads the secret back from `CredentialStore` fresh on
every client build rather than caching it anywhere. A real 401
(`HttpResponseHandler.UnauthorizedException`) clears the stored secret
(`UpdateTaskHolder.NotifyingClientListener.invalidCredentials()`), so a later reconfigure doesn't
silently keep offering a credential already known to be dead.

Reconfiguring with the password/token field left blank deliberately **keeps whatever secret is
already saved** rather than clearing it, *as long as the auth mode being saved matches the one
already configured* - a brand new "Add Bitbucket repo" always requires one, and so does a
"Reconfigure" that switches auth mode (`AddProjectDialog.doValidate`). That second case matters
because `CredentialStore` is keyed by host, not by auth mode: the secret already saved under that
key when switching modes belongs to the *other* mode (e.g. an Access Token, while this save is
turning on Basic Auth). Blank used to be accepted there too, which meant switching modes with the
new field left empty silently kept authenticating with the old mode's secret - surfacing only as a
confusing "Invalid Bitbucket credentials" with no obvious cause, since URL/login all looked right.
Requiring the secret in that case is what closes it; leaving it optional the rest of the time is
what makes reconfigure re-running discovery + `git credential fill` actually useful - if nothing
new turns up, the user shouldn't have to retype a still-valid secret just to fix, say, a typo in the
repository URL.

`UpdateTaskHolder.scheduleNew()`/`reschedule()` dispatch onto a background executor rather than
running inline on the caller's thread now, because building a client means a `CredentialStore`
(PasswordSafe) read - blocking IO that must never run on the EDT. Tradeoff: `stop()` can now race a
`scheduleNew()`/`reschedule()` call made just before it that hasn't materialized into a task yet,
which would overwrite the `DummyTask` `stop()` just installed. Fine today - `DemoMode.enable()` is
the only `stop()` caller, and it's never invoked back-to-back with a schedule call - but worth
checking again before adding a new `stop()` caller.

**Migrating a pre-0.6 install.** Before 0.6, the only auth method reachable from the UI was Access
Token, and it was persisted in plaintext as `Settings.accessToken` in `workspace.xml`. 0.6 removed
that field from `Settings` - but the platform's default XML deserializer silently drops a saved
property that no longer exists on the class instead of erroring, so upgrading a configured project
with nothing else done here would leave it newly, silently unable to authenticate ("Invalid
Bitbucket credentials", no obvious cause since URL/project/slug all still load fine). Fixed by
keeping `Settings.accessToken` around as a legacy-only field - same name, so old XML still matches
it - that only `BitbucketClientFactory.migrateLegacySecretIfNeeded` ever reads: the first time a
request factory is built after upgrading, a non-blank value is copied into `CredentialStore` and
the field is cleared, so it's a one-time move rather than a second, parallel place secrets live.
Nothing else may read or write `Settings.accessToken` - in particular `copyFrom`/`AddProjectDialog`
don't touch it, so reconfiguring before the migration has run can't wipe it out from under it.

This only helps a project that jumps straight from a pre-0.6 build to one that already has this
migration. A project that was ever opened under the unreleased, un-fixed 0.6 build first has
already lost the plaintext token for good by the time a later build runs: IntelliJ's XML
deserializer silently drops the unknown `accessToken` property on load (that's the whole bug),
and the very next time that project's state is saved - which routinely happens just from having
the project open - `workspace.xml` is rewritten without it, for good. There is nothing in this
codebase that can recover a value that no longer exists anywhere on disk; the only fix for a
project already in that state is to re-enter the secret once via "Reconfigure Bitbucket repo…".

## Known incidents (for context on defensive-looking code)

- **`InboxStore`'s persisted property was named `state`.** Kotlin auto-generates a `getState()`
  accessor for a property named `state`, which collided at the JVM level with the explicit
  `override fun getState(): InboxState` `PersistentStateComponent` requires - `compileKotlin`
  failed with "Platform declaration clash". `Storer` next to it avoids the same trap by naming its
  property `settings`; `InboxStore` now names its `inboxState` for the same reason.
- **The Inbox feature's first Server implementation called `GET .../pull-requests/{id}/comments`,
  copying the shape of the working Cloud endpoint of the same name.** On Server that path is the
  file/line comment search, not a general-comment listing, and 400s ("The path parameter must be
  supplied") without a `path` query param - every poll on every PR under review threw. Server's
  real general-comment source is `GET .../pull-requests/{id}/activities`; see "Inbox" above for why
  its reply model (a nested `comments` tree, no flat `parent` field) is also different from Cloud's,
  and don't assume the two flavours' comment endpoints are shaped alike again.
- **The old `getStorerService()` ambient-project guessing** (`DataManager`/`ProjectManager`
  focused-window lookups, now deleted) went through three rounds of bugs - a startup NPE (0.7.2),
  an EDT-assertion crash from a background poll, and silently showing one project's PR list in
  every open window - each one patched around the symptom instead of the actual problem: guessing
  "the current project" at all rather than being told. Superseded by "Per-project state" above,
  which removes the guessing rather than making it more careful.
- **Approve did nothing, silently, for every access-token user.** It built the participant URL from
  `Settings.login`, which the Settings page never fills in, so the PUT went to a URL ending in an
  empty path segment with `{"user":{"name":""}}` as the body. Bitbucket rejected it; the client
  rethrew; `Model.approve` caught it and only wrote to the log - and the client it builds carries no
  `ClientListener`, so nothing reached the user either. Two lessons baked into the current code: the
  username comes from the server (see "Current user"), and `Model`'s approve/merge catch blocks now
  raise a notification, because a swallowed failure there is indistinguishable from success.
- **Returning an empty list on failure is not a neutral default.** `inbox()` used to map any
  exception to `emptyList()`, which `Diff` then read as "every pull request was removed", followed
  on the next successful poll by "every pull request is new" - one dropped request produced a burst
  of balloons. Anything feeding `Model` must distinguish "couldn't fetch" from "nothing there";
  `openPRs()` returns `null` for the former.
- **`git4idea.GitVcs cannot be cast to git4idea.GitVcs`.** `Git.gitVcs()` used to cast
  `VcsUtil.getVcsFor(project, baseDir)`'s result to `GitVcs` unconditionally. A runtime dependency
  declared directly on this module (instead of registered through `plugin.xml`) let git4idea's
  classes load twice, under two different classloaders, so the same class name failed its own `is`
  check. Fixed by declaring plugin dependencies through `plugin.xml` rather than the module's own
  runtime classpath; `gitVcs()` still checks `vcs is GitVcs` defensively rather than casting
  unconditionally, as a reminder not to reintroduce the direct dependency.
- **`service<T>()`** (the Kotlin inline extension from `com.intellij.openapi.components`, used
  in `Configurable.kt`/`MainWindow.kt` to fetch `Storer`) gets inlined into the *caller's* bytecode
  at compile time, internal-helper calls and all. Compiling against a newer platform SDK than a
  plugin's declared `since-build` covers is exactly how that goes wrong: this project compiles
  against IC 2025.2 (see "Build") but claimed `since-build="223.0"`, and the Marketplace verifier
  failed 0.5.1 against IDEA 2023.3.8 with `NoSuchMethodError` risk on `ServicesKt.serviceNotFoundError`
  - a helper that inlined call needed but that build's `ServicesKt` doesn't have. Fixed by calling
  the plain, non-inline `ComponentManager.getService(Class<T>)` everywhere instead - same effect,
  nothing of the platform's internals gets copied into our bytecode, so it's safe across the whole
  declared `since-build` range regardless of which SDK version compiled it.
- **`Git.kt`'s `AsyncFetchAndCheckout`** uses `GitFetchSupport`, not `GitFetcher` - the latter has
  been `@Deprecated`/`@Deprecated(forRemoval = true)` since at least 2023 and was physically
  removed from the platform by some 2026.x build, so a compile-clean call to
  `GitFetcher.fetchRootsAndNotify(...)` threw `NoSuchMethodError` at runtime on a user's real,
  newer IDE. See "Verifying IntelliJ Platform APIs" above - this is the incident that motivated
  checking real platform source before relying on an API's continued existence.
- **A wrong Basic Auth password crashed the poll instead of showing "Invalid Bitbucket
  credentials".** `RequestFactory` always adds its own preemptive `Authorization` header, so
  Apache HttpClient's own challenge/response auth handling was never meant to run - but
  `BitbucketClientFactory.createHttpClient()` still built the client via
  `HttpClients.createSystem()`, which leaves that handling on by default. A real 401 whose
  response carries a `WWW-Authenticate: Basic` challenge (routine for a Basic Auth failure,
  unlike most Access Token failures) made Apache try to resolve credentials itself via
  `SystemDefaultCredentialsProvider`; finding none registered (we only ever set the header, never
  Apache's own `CredentialsProvider`), it fell through to the JVM's `Authenticator` - landing in
  a long-standing IntelliJ platform bug there that throws a `NullPointerException`
  (`PlatformJdkProxyProvider$authenticator$1.getPromptedAuthentication`) instead of ever handing
  the 401 back to us. Fixed by disabling Apache's own auth handling
  (`RequestConfig.setAuthenticationEnabled(false)`) on the client, since it was always redundant
  with the header we already send - now a real 401 reaches `HttpResponseHandler` and produces the
  intended "Invalid Bitbucket credentials" notification instead of an uncaught crash, for either
  auth mode.

- **`CloudBitbucketClient.currentUserUuid()` latched a failed probe as permanent.** It set a
  `probedForCurrentUser` flag before the `GET /2.0/user` call and never cleared it, including on
  failure - so the first 403 (an OAuth token missing the `account` scope, in the field) or any
  other transient error disabled user resolution for that client's entire lifetime, which spans
  every poll until the next reconfigure/IDE restart. Every subsequent poll logged "Cannot tell
  which Bitbucket user the credentials belong to; skipping this update" forever, with no way to
  self-heal even after the underlying problem was fixed. Only success is cached now; failure is
  retried every call, backed off by `UpdateTaskHolder`'s normal poll interval like any other
  no-change cycle. `HttpResponseHandler` also now logs a 401/403 response's body (bounded, best
  effort), since Bitbucket's own error message is usually more specific than the status code -
  that's what actually confirmed the `account`-scope theory above. Separately, this probe now
  passes its own no-op `ClientListener` instead of the shared one: a background probe retried
  every poll must not fire `actionForbidden()`'s balloon every cycle the way a real forbidden
  user action should - `openPRs()`'s existing `currentUserUnknown()` (already deduped to one
  notification) is the right place for that.
- **The `account`-scope theory above was confirmed in the field, not just theorized.** A real
  sign-in hit exactly this: `GET /2.0/user` 403'd with
  `{"required":["account"],"granted":["pullrequest:write"]}` - the shared OAuth consumer (see
  "Bitbucket Cloud OAuth sign-in") simply never had "Account: Read" checked when it was
  registered on Bitbucket, despite this doc listing `account:read` among its scopes since that
  section was written. Bitbucket Cloud has no way to request additional scopes at
  authorize/token-exchange time beyond what a consumer was registered with - confirmed against
  Atlassian's own docs, which state the `scope` parameter can only *narrow*, never expand, a
  grant. There is no code fix for this: it's a one-time correction on Bitbucket's side (edit the
  consumer under Workspace settings → Apps and features → OAuth consumers, check "Account: Read",
  save), and every already-issued token keeps its old scope until the affected user logs out and
  signs back in via "Reconfigure Bitbucket repo…". Kept here so a future "Cannot tell which
  Bitbucket user" report isn't mistaken for a new code bug and re-investigated from scratch.
- **A cancelled Bitbucket Cloud sign-in left the "Reconfigure" dialog looking alive but inert.**
  `CloudOAuth.acceptWithCancellation()` and `AddProjectDialog.signIntoCloud()` each caught
  `Exception` around the code that polls `ProgressManager.checkCanceled()`, which also catches
  `ProcessCanceledException` and converts it into an ordinary `AuthorizationException` - exactly
  the platform's own progress/modal machinery is built to expect PCE to escape unmodified when a
  process is cancelled (e.g. by closing the OAuth browser tab, or Cancel on the modal progress).
  Swallowing it instead left that bookkeeping out of sync: the dialog's OK/Cancel buttons stayed
  visually enabled but stopped responding to clicks, and the only way out was a second sign-in
  attempt succeeding. Fixed by letting PCE propagate through both call sites unmodified.

- **`MainWindow` (the `ToolWindowFactory`) stored `project`/`window`/`reviewingContent`/
  `ownContent`/`inboxContent` as instance fields, set in `createToolWindowContent()`.** The
  platform shares one `ToolWindowFactory` instance across every open project - that's exactly why
  `createToolWindowContent(project, toolWindow)` takes both as parameters - so opening a second
  project overwrote the first project's fields on the same shared instance. `reconfigure()` and
  `addProject()` read `this.project` instead of a parameter, so with two projects open,
  "Reconfigure Bitbucket repo…" clicked from either project's gear menu could end up reading and
  overwriting *whichever* project's `Storer` happened to be cached most recently - one project's
  settings change silently applying to the other. The tab-count listener had the milder version of
  the same bug: `ownContent`/`reviewingContent`/`inboxContent` could point at the wrong project's
  `Content` by the time a count changed. Fixed by removing every instance field - `project` and
  `toolWindow` are now threaded through as explicit parameters on every private method, and
  `reviewingContent`/`ownContent`/`inboxContent` are locals closed over only by that one
  `showConfiguredContent()` call's own listeners, matching the "always be told the project, never
  guess (or cache) it" rule "Per-project state" above already called for.
- **`discoverRepositoryUrl` misread a GitHub/GitLab ssh remote as a self-hosted Bitbucket
  Server repo.** Bitbucket Server's ssh clone URLs have no `/scm/` segment to key off (see
  `SSH_PATH_PATTERN`'s own comment), so the parser accepts any `host:owner/repo(.git)` shape -
  which is exactly what `git@github.com:owner/repo.git` also looks like. Opening a project whose
  only remote was GitHub silently "discovered" a bogus repo (`owner` read as the Bitbucket project
  key, `repo` as the slug) and prefilled the Add Bitbucket repo dialog's URL field with it, even
  though nothing about that project was ever configured for Bitbucket. Server can be self-hosted
  at literally any hostname, so there's no positive signal in the URL shape alone to require
  instead. First fix tried was a denylist of well-known non-Bitbucket hosts - rejected: it only
  ever covers hosts someone thought to list (self-hosted GitLab, Gitea, Azure DevOps on a custom
  domain would all slip through). The actual fix, `discovery/ServerProbe.kt`'s
  `probeIsBitbucketServer`, confirms the candidate with one unauthenticated
  `GET {baseUrl}/rest/api/1.0/application-properties` before ever trusting it - the one endpoint
  every Bitbucket Server exposes without credentials, returning `{"version":..., "displayName":...}`.
  `parseCloneUrl` itself stays a pure, network-free pattern match (see its own doc comment) -
  `MainWindow.discoverRemoteAndCredentials` is what runs the probe, inside the same modal progress
  it already uses for credential lookups, and discards the candidate on any failure (wrong shape,
  non-200, DNS failure, timeout). Cloud is exempt: `bitbucket.org` is the one fixed host, nothing
  to disambiguate.
- **`requestFailed(e)` logged every connection failure via `LOG.error`.** A `LOG.error` call makes
  the IDE itself raise an "internal error" report - appropriate for a genuine plugin bug, not for
  an `UnknownHostException`/connect-timeout/SSL-handshake failure, which is an expected, user-
  actionable condition (wrong URL, host unreachable, VPN off) already handled by showing a
  notification and backing off the poll. On a host that stays unreachable this fired every poll
  cycle, so what looked like a plugin crash in the IDE's own error UI was often just one *other*
  open project's Bitbucket Server being unreachable - confusing when investigated from an unrelated
  project. Downgraded to `LOG.warn`, matching the `IOException` branch in `pollOnce()` right next
  to it, which already treated the same class of failure this way.
- **`bitbucket.cloud.dto.PullRequestDto` crashed the whole poll on a real PR with no
  participants.** Its `participants` field was declared `List<ParticipantDto>` with no default -
  fine as long as Bitbucket Cloud always sends the key, which it doesn't: a PR nobody has ever
  added as a reviewer/participant on can come back with `participants` missing entirely (or,
  seen in a different real response, present as literal JSON `null`) instead of `[]`. Jackson
  (via its Kotlin-aware non-null parameter check) refused to construct the DTO either way -
  `Cannot construct instance of PullRequestDto ... parameter participants` - which took down
  `openPRs()` for the *whole* poll, not just that one PR, since it's parsed as part of one page of
  results. A plain `= emptyList()` default on the constructor parameter would only have covered
  the missing-key case, not the explicit-null one, so the field is now a private, nullable
  `participantsOrNull` (matching `LinksDto`'s existing private-backing-field idiom in the Server
  DTOs) with a public `participants` getter that folds both `null` and missing into `emptyList()`.
  `bitbucket/cloud/dto/ParseTest.kt` pins both shapes so this can't silently regress.
- **`AddProjectDialog`'s OK button only ever validated on click, never proactively.** Its
  `doValidate()` override is the classic `DialogWrapper` API, which the platform calls
  automatically on every keystroke *only* when the dialog opts into continuous validation
  (`DialogWrapper.continuousValidation()`) - which defaults to `myDialogPanel == null`, and this
  dialog's `createCenterPanel()` returns a Kotlin UI DSL v2 `DialogPanel`, so `myDialogPanel` gets
  set and continuous validation is off (confirmed against real platform source, not assumed).
  Practically: OK stayed clickable through an incomplete Cloud sign-in or a since-cleared Server
  credential field, and only refused (with a balloon) once actually clicked. Fixed without opting
  into the DSL's own `validationOnInput` machinery - a plain `updateOkButtonState()` calling
  `isOKActionEnabled = doValidate() == null`, invoked from every listener that can change what
  `doValidate()` would return (all four text fields, both radio buttons, sign-in/log-out). Reuses
  `doValidate()` as the single source of truth rather than duplicating its logic. Also dropped the
  `secretRequired` mode-change gating that used to let a *cleared* (not just untouched) Server
  password/token field pass validation as "keep the old stored secret" - now always required
  when relevant, matching what the field being pre-filled with the real stored secret (see
  `init{}`) already made the normal case anyway.
- **Checkout and Approve shared one `GridBagLayout` cell, `Content()`-style - exactly one was
  ever visible.** A branch being checked out hid Approve entirely, so approving a PR you didn't
  want to check out required checking it out first just to get the button back. Gave them their
  own columns (`PRComponent.TOTAL_COLUMNS` 4 → 5) so both show together; Checkout now reflects
  "already done" by relabeling to "Checked out" and disabling itself instead of disappearing.
- **The PR card green (`PRComponent.greenColor`, `Color(86, 167, 106)`, same in both themes - see
  the light-theme-acidity fix above) was a component-instance field, unreachable from anywhere
  that isn't a `PRComponent`.** Needed again for the Cloud sign-in success label in
  `AddProjectDialog`, so it's now the top-level `ui/Colors.kt`'s `APPROVE_GREEN`, and `PRComponent`
  itself uses that instead of its own copy - one definition for every "this succeeded" green in
  the plugin.
- **Approve was one-way: a PR you'd already approved still showed a clickable "Approve", or, if
  you'd approved it in the same session, a permanently disabled "Approved" - either way, no way to
  withdraw an approval from the card.** `BitbucketClient.approve(pr)` had no counterpart. Added
  `unapprove(pr)` to the interface and both implementations, confirmed against real API references
  before writing either: Server reuses the exact same documented endpoint as approve, `PUT
  .../pull-requests/{id}/participants/{userSlug}`, just with `status`/`approved` flipped to
  `UNAPPROVED`/`false` (the older `DELETE .../participants/{userSlug}` also exists but actually
  removes the participant entirely, which isn't what "withdraw my approval" should do); Cloud is
  `DELETE .../pullrequests/{id}/approve`, the documented counterpart to `POST .../approve`.
  `PRComponent`'s Approve button now toggles between "Approve" and "Unapprove" based on its own
  `approved` state (seeded from `PR.attentionFor()` - the same check the "Show already approved"
  collapse already uses, so the two can't disagree) instead of only ever moving forward.
  `HttpResponseHandler`'s no-body `handle(response)` only accepted a bare 200; a Cloud DELETE
  returning 204 (no body, per the API's own docs) would have thrown as an "unexpected response" on
  every successful unapprove, so it now accepts 204 too, without a body read.
