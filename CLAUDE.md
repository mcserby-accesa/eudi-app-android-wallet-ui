# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Fork context — read this first

This is the **Accesa fork** of the EU `eudi-app-android-wallet-ui`. Per [ADR 0004](file:///C:/Users/mihai.serban/IdeaProjects/instant-payments-initiative/plan/decisions/0004-two-app-model-wallet-and-bank-app.md) (companion repo) the system is a **two-app model**: this wallet stays **bank-agnostic** and only owns identity primitives + an intent-based authorisation API; per-bank UI / balances / top-up live in a separate greenfield `bank-app` (not in this repo). The fork-level rules (branch strategy, upstream-sync workflow, conventions for keeping diffs clean against upstream) live in `FORK.md` — read it before doing any structural work.

Key implications:
- **All Accesa work happens on `accesa-de`**, never on `main`. `main` only tracks `upstream/main` cleanly.
- Topic branches go `accesa-de/<feature>` and PR back into `accesa-de`.
- **Specs are owned by the companion repo** at `C:\Users\mihai.serban\IdeaProjects\instant-payments-initiative` under `specs/`. Particularly authoritative for this repo: `specs/components/mobile-wallet.md` (M1 scope), `specs/protocols/de-wallet-app-api.md` (PRESENT_PID + AUTHORIZE_OPERATION intent contract), and the relevant ADRs in `plan/decisions/`. The wallet here is the *implementation*; the spec repo is the *authority*. Do not modify specs in this repo — flag any contract question to the user so it can be made spec-side first.
- The wallet does NOT call any bank backend directly. It receives `Intent.ACTION_VIEW` from bank apps and replies via callback URIs. The only HTTP backend the wallet contacts is the **PID issuer** during initial OID4VCI issuance.
- New Accesa-specific Gradle modules are named `de-feature/`, `de-logic/`, or (from M4a) `de-storage/` so they are visually distinct from upstream `*-feature` / `*-logic` modules. `de-logic` + `de-feature` already exist (M1); `de-storage` lands with M4a.
- Upstream does not configure Spotless or ktlint as Gradle tasks (verified — no `spotlessApply` task exists). Format new code via Android Studio's built-in Kotlin formatter before committing so diffs against upstream stay clean. See `FORK.md` §"Conventions inherited from upstream" for the full reasoning.

## Build & run

The build is the upstream Gradle wrapper, multi-module Android project. JDK 17+ is required for Gradle (set in `build-logic/convention/build.gradle.kts`); SDK path lives in the (gitignored) `local.properties`.

Two product flavors × two build types yield four variants:

| Flavor | Purpose |
|---|---|
| `dev` | Dev-environment services (`ec.dev.issuer.eudiw.dev`, etc.) |
| `demo` | Demo-environment services (latest main) |

Both flavors define `WalletCoreConfigImpl.kt` separately under `core-logic/src/{dev,demo}/java/eu/europa/ec/corelogic/config/`. That file is the single source of truth for issuer URLs, OpenID4VP config, trust stores. **Per-flavor config edits go in those two files** — there is no shared override.

Common Gradle tasks (run from the repo root):

```bash
./gradlew assembleDevDebug          # build the dev/debug APK (default during dev work)
./gradlew assembleDemoDebug         # build the demo/debug APK
./gradlew installDevDebug           # install to a connected device/emulator
./gradlew test                      # all unit tests across modules
./gradlew :dashboard-feature:test   # run tests for one module
./gradlew :dashboard-feature:testDevDebugUnitTest --tests "*DashboardViewModel*"   # single test class
./gradlew spotlessApply             # auto-format (run before every accesa-de commit)
./gradlew lint                      # Android lint
./gradlew koverHtmlReport           # coverage HTML (Kover is wired via convention plugin)
```

Emulator → host loopback uses `https://10.0.2.2` (Android emulator alias for the host's `localhost`). When pointing the wallet at locally-run backends (issuer, verifier, bank-simulator, ncb-issuer), edit the relevant URL in the flavor's `WalletCoreConfigImpl.kt`.

## High-level architecture

The app is a **multi-module Android Gradle project** with a strict layered dependency graph (full graph in `wiki/dependency-graph.md`, summary in `README.md`). Key layering rules:

- `app` is a thin shell — it only depends on `assembly-logic` plus the baseline-profile.
- `assembly-logic` is the composition root: it pulls in every `*-feature` module and wires Koin DI graph (`assembly-logic/src/main/java/eu/europa/ec/assemblylogic/di/AssemblyModule.kt`). The Android `Application` class lives here (`Application.kt`) — it sets up Koin, the RQES SDK, analytics, and periodic `WorkManager` jobs (revocation, re-issuance).
- **`*-feature` modules** (`startup-feature`, `dashboard-feature`, `presentation-feature`, `proximity-feature`, `issuance-feature`, `common-feature`, and the Accesa **`de-feature`**) own user-facing screens. They depend on `common-feature` plus the `*-logic` modules they need.
- **`*-logic` modules** (`business-logic`, `core-logic`, `network-logic`, `storage-logic`, `ui-logic`, `authentication-logic`, `analytics-logic`, `resources-logic`, and the Accesa **`de-logic`**) own cross-cutting concerns. `core-logic` wraps the EUDI Wallet core SDK; `ui-logic` holds the design system + MVI plumbing; `resources-logic` holds strings, drawables, and trust-store certificates; `de-logic` owns the DE envelope wire format + AUTHORIZE_OPERATION JWT builder.
- The Accesa **`de-storage`** module (added in M4a) encapsulates the simulated Secure Element for offline DE tokens — token storage, holderPub keypair generation, `txCounter`. Treated like a real SE: no callers outside `de-feature` reach in.
- **`build-logic/convention`** holds the Gradle convention plugins (`project.android.application`, `project.android.feature`, `project.android.koin`, `project.wallet.core`, etc.). New modules apply these plugins instead of duplicating `android { ... }` boilerplate. See `build-logic/convention/build.gradle.kts` for the plugin registry and `build-logic/convention/src/main/kotlin/project/convention/logic/AppFlavor.kt` for the `dev`/`demo` flavor definitions.

### Per-screen pattern

Every screen follows the same triplet:

- `XxxScreen.kt` — Jetpack Compose UI, observes state via `collectAsStateWithLifecycle()`.
- `XxxViewModel.kt` — extends `MviViewModel<Event, State, Effect>` from `ui-logic` (`uilogic/mvi/`). State / Event / Effect are sealed classes/interfaces declared at the top of the file.
- `XxxInteractor.kt` (per feature, in `interactor/`) — the use-case layer between the ViewModel and `core-logic` / repositories. Interactors are injected via Koin (annotation-based: `@Single`, `@Factory`, `@KoinViewModel`).

Navigation is centralized in `ui-logic` (`uilogic/navigation/Screen.kt` and `*Screens` enums). Cross-feature navigation goes through the route names defined there — features don't reference each other's route strings directly.

### Wallet core integration

`core-logic` is the single integration point for the upstream `eu.europa.ec.eudi.wallet:wallet-core` SDK. Anything that talks to OpenID4VCI (issuance), OpenID4VP (presentation), ISO18013-5 (proximity), or document storage goes through `core-logic`. Features should never import `eu.europa.ec.eudi.wallet.*` directly — go through `core-logic` interfaces.

### MVI conventions

`ViewState`, `ViewEvent`, `ViewSideEffect` are marker interfaces (`uilogic/mvi/MviContract.kt`); `MviViewModel` is the base class. State updates use `setState { copy(...) }`, side effects use `setEffect { ... }` (one-shot, e.g. navigation), events come in via `setEvent(...)`. When adding a screen, mirror the pattern in `dashboard-feature/.../dashboard/DashboardViewModel.kt`.

## Configuration knobs

For deeper changes (deeplink schemes, scoped issuance documents, self-signed certs, theming, PIN storage, analytics) the upstream wiki at `wiki/configuration.md` is the reference — start there before guessing.

`secrets.defaults.properties` holds non-secret defaults consumed via the Secrets Gradle plugin; real secrets go in `secrets.properties` (gitignored). Release signing pulls from `signing.properties` or `ANDROID_KEY_*` env vars.
