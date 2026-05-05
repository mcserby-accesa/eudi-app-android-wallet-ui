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

- Multi-module Gradle build. New Accesa-specific modules go under `de-feature/` or `de-logic/` (TBD when first added) to keep them visually separate from upstream `*-feature` / `*-logic` modules.
- Signing: upstream's `signing.properties` is gitignored; debug builds work without it.
- Spotless / ktlint: upstream-configured. Run `./gradlew spotlessApply` before committing on `accesa-de` so PR diffs against upstream stay clean.

## Next steps

1. Open the project in Android Studio (`File → Open` → this directory). Wait for the first Gradle sync to finish.
2. Verify the upstream demo build runs against the EU reference issuer (`https://issuer.eudiw.dev/`) — this confirms the fork is intact before we add Accesa-specific code.
3. Switch to `accesa-de` (`git checkout accesa-de`) and start implementing the M1 surfaces from `specs/components/mobile-wallet.md` in the companion repo.

When you start a Claude Code session in this directory for the wallet work, point it at `FORK.md` and the companion spec — those two together are the brief.
