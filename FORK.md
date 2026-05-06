# Accesa Fork — Notes

This is the **Accesa fork** of [`eu-digital-identity-wallet/eudi-app-android-wallet-ui`](https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui), extended with **Digital Euro** flows on top of the upstream EUDI identity surface.

The companion repository — backend services (`ncb-issuer`, `pid-issuer`, `bank-simulator`, `ecb`), specs, ADRs, and roadmap — lives at:

> **`mcserby-accesa/instant-payments-initiative`** (or the equivalent Accesa internal path).

The wallet's HTTP contracts (`/bank/onboard/*`, `/bank/de/top-up/*`, `/pid/par`, …) are owned by that repo's `specs/components/`. Treat the spec as the authority; this repo is the implementation.

---

## Forked at

| | |
|---|---|
| Upstream | `eu-digital-identity-wallet/eudi-app-android-wallet-ui` |
| Forked from commit | `0cb9433a65a0ff18586978aa852057711bb27bbd` |
| Latest upstream tag at fork time | `Wallet/Demo_Version=2026.02.35-Demo_Build=35` |
| Fork date | 2026-05-05 |

## Branch strategy

| Branch | Purpose |
|---|---|
| `main` | Tracks upstream `main` cleanly. **Do not commit Accesa changes here.** Used only as the merge target for `git fetch upstream && git merge upstream/main`. |
| `accesa-de` | Long-lived integration branch. **All Accesa-specific work goes here.** Periodically rebased on `main` to take in upstream changes. |
| `accesa-de/<feature>` | Topic branches off `accesa-de`. PRs target `accesa-de`. |

## Remotes

```
origin    https://github.com/mcserby-accesa/eudi-app-android-wallet-ui.git   (push + fetch)
upstream  https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui.git   (fetch only — push DISABLEd)
```

`upstream` push is disabled by URL trick (`git remote set-url --push upstream DISABLE`) so an accidental `git push upstream` cannot reach the EU repo.

## Syncing upstream

```
git checkout main
git fetch upstream
git merge --ff-only upstream/main      # main fast-forwards if no Accesa commits land here
git push origin main

git checkout accesa-de
git rebase main                        # bring upstream changes into our integration branch
# resolve conflicts if any, then:
git push --force-with-lease origin accesa-de
```

Always rebase `accesa-de` on `main`, never the other way around. Never merge `accesa-de` into `main`.

## What we add on top of upstream

Tracked in [`specs/components/mobile-wallet.md`](https://github.com/mcserby-accesa/instant-payments-initiative/blob/main/specs/components/mobile-wallet.md) in the companion repo. Summary for M1:

- **First-launch QR-config screen** — sets `pidIssuerUrl` + `bankApiBaseUrl` from a workshop QR.
- **Bank-onboarding flow** — after PID issuance, immediately runs OID4VP against the chosen `bank-simulator` to create a customer and allocate an IBAN.
- **Wallet tab** — bank balance + DE balance + top-up sheet.
- **Top-up flow** — two-step initiate/confirm against `bank-simulator`.

Out of scope for M1: online payment, offline NFC, merchant mode, history with provenance.

## Local setup

| | |
|---|---|
| Android SDK | configured in `local.properties` (auto-generated, gitignored) |
| Min SDK | 29 (Android 10), per upstream |
| JVM | 17+ for Gradle (upstream `gradle.properties` sets `-Xmx8192m`) |
| Build tool | Gradle wrapper (`./gradlew`) |
| First sync | Open in Android Studio; let it sync; resolve any prompts |

## Disclaimer scope

The upstream repo's [README](README.md) carries an EUPL 1.2 licence and a "reference implementation, not production-ready" disclaimer. Both apply to this fork. The Accesa-specific demo banner (`DEMO — not real money`) is added in the wallet UI; the underlying disclaimers are unchanged.

## Conventions inherited from upstream

- Multi-module Gradle build. New Accesa-specific modules go under `de-feature/` or `de-logic/` to keep them visually separate from upstream `*-feature` / `*-logic` modules. The convention plugin's `LibraryModule` enum gets one entry per new module (e.g. `DeLogic(":de-logic")`) — small upstream touch, trivially resolvable on rebases.
- Signing: upstream's `signing.properties` is gitignored; debug builds work without it.
- Code style: upstream sets `kotlin.code.style=official` in `gradle.properties` but does **not** configure Spotless or ktlint as Gradle tasks. Format new code via Android Studio's built-in Kotlin formatter before committing so diffs against upstream stay clean.
- Topic-branch naming: from PR7 onwards we commit straight to `accesa-de` and push after each logical change. The earlier `prN-…` topic-branch convention added friction without buying review value; PR-numbered commits on `accesa-de` (clear commit messages, immediate push) are the working unit. Git refuses to create `accesa-de/<feature>` branches because `accesa-de` already exists as a leaf ref, so the slash convention from older drafts of this doc never worked anyway.

## End-to-end smoke test

Manual checklist for verifying wallet + bank-app + backends together: [`wiki/m1_smoke_test.md`](wiki/m1_smoke_test.md). Run it after any change to the intent surface (`AUTHORIZE_OPERATION` envelope, signing, callback wiring) and before every demo.

## Wallet ⇄ App API — `eudi-openid4vp://` (PRESENT_PID)

The **`PRESENT_PID`** primitive of the wallet ⇄ app contract (companion repo `specs/protocols/de-wallet-app-api.md`) reuses the upstream `eudi-openid4vp://` deep link **without any Accesa-specific code**. Verified by tracing the upstream pathway end-to-end:

| Stage | File | Behaviour |
|---|---|---|
| Manifest filter | `assembly-logic/src/main/AndroidManifest.xml:121–125` | Catches `eudi-openid4vp://` (placeholder-driven) |
| URI parsing | `ui-logic/.../navigation/helper/DeepLinkAction.kt` | `DeepLinkType.parse` returns `OPENID4VP` for the scheme |
| Activity entry | `ui-logic/.../container/EudiComponentActivity.kt::handleDeepLink` | Caches the intent; if the user already has a PID (`userIsLoggedInWithDocuments()`), pops to the dashboard |
| Dashboard pickup | `dashboard-feature/.../DashboardScreen.kt:172` (`LifecycleEffect ON_RESUME`) | Reads the cached intent and fires `Event.Init(intent)` |
| Dashboard routing | `dashboard-feature/.../DashboardViewModel.kt:271–289` | Builds a `RequestUriConfig(PresentationMode.OpenId4Vp(uri, …))` and emits `OpenDeepLinkAction` |
| Final navigation | `ui-logic/.../navigation/helper/DeepLinkHelper.kt::handleDeepLinkAction` | Routes `OPENID4VP` → `PresentationScreens.PresentationRequest` with the URI as the `requestUriConfig` argument |

**No internal-vs-external distinction.** The handler is symmetric — whether the wallet's own UI navigates to OID4VP or another package fires `Intent.ACTION_VIEW eudi-openid4vp://?…`, both land in the same code path. The only gate is `userIsLoggedInWithDocuments()`, which is exactly the right check (an unprovisioned wallet has nothing to present).

### Manual smoke test

Once you've installed `app-dev-debug.apk` on a device or emulator that already has a PID issued (run through QR-config + AddDocument first), fire an OID4VP intent from outside the wallet's package:

```sh
adb shell am start -W -a android.intent.action.VIEW \
  -d 'eudi-openid4vp://?request_uri=https://your-bank-backend/bank/onboard/request/abc123' \
  eu.europa.ec.euidi.dev
```

Expected: the wallet's `MainActivity` is brought to the foreground (singleTask), `EudiComponentActivity.onNewIntent` fires, the URI flows through the cached-intent mechanism, the dashboard picks it up on resume, and the `PresentationRequest` consent screen renders. After confirm + biometric, the wallet POSTs the VP token to the bank's `response_uri` (per OID4VP `direct_post` mode). The bank app, having registered an inbound filter for its own callback, observes the bank backend's "you are now logged in" response by polling `GET /bank/onboard/result/{onboardingId}`.

If the wallet has no PID issued yet, the intent is cached but no navigation happens. M1 design: bank apps assume the user has bootstrapped the wallet first (QR-config → PID issuance), and route through their own onboarding error UI if not.

---

## Next steps

1. Open the project in Android Studio (`File → Open` → this directory). Wait for the first Gradle sync to finish.
2. Verify the upstream demo build runs against the EU reference issuer (`https://issuer.eudiw.dev/`) — this confirms the fork is intact before we add Accesa-specific code.
3. Switch to `accesa-de` (`git checkout accesa-de`) and start implementing the M1 surfaces from `specs/components/mobile-wallet.md` in the companion repo.

When you start a Claude Code session in this directory for the wallet work, point it at `FORK.md` and the companion spec — those two together are the brief.
