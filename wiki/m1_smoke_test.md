# M1 + M2 manual smoke-test checklist

End-to-end runbook for the Accesa Digital Euro stack on a workshop emulator or
physical device. Covers the wallet (this repo), the bank-app, and the backend
services (companion repo `instant-payments-initiative`).

This is a **manual** checklist — there is no scripted integration test for the
two-app intent flow. Run through it whenever the wallet's intent surface
changes (PR6, PR7, future AUTHORIZE_OPERATION work) and before any demo.

---

## 0. Prerequisites

Hardware / OS:

- Android Studio (latest stable). JDK 17+ for Gradle.
- An Android emulator (API 29+) or a physical device with USB debugging enabled. Emulator is recommended for the workshop because `10.0.2.2` resolves automatically to the host's loopback.

Two repositories checked out side-by-side:

| Repo | Path |
|---|---|
| Wallet (this repo) | `C:\Users\mihai.serban\myprojects\eudi-app-android-wallet-ui` |
| Backends + bank-app (companion) | `C:\Users\mihai.serban\IdeaProjects\instant-payments-initiative` |

Wallet must be on `accesa-de` at PR8 or later (the cleartext config is what unblocks first-launch against the local pid-issuer).

---

## 1. Bring up the backend stack

From the companion repo:

```sh
cd /c/Users/mihai.serban/IdeaProjects/instant-payments-initiative
docker compose up -d
```

Verify each service is alive (each should return 200 / a JSON status):

```sh
curl -fsS http://localhost:8092/actuator/health   # pid-issuer
curl -fsS http://localhost:8090/actuator/health   # ncb-issuer
curl -fsS http://localhost:8080/actuator/health   # bank-simulator (Bank A)
curl -fsS http://localhost:8082/actuator/health   # bank-simulator (Bank B)
curl -fsS http://localhost:8095/actuator/health   # ecb (passive in M1)
```

If any port is unreachable, fix that first — the wallet will fail at the first cleartext request and the rest of the checklist is moot.

> **From an emulator**, the same hosts are reachable via `10.0.2.2:<port>` (Android's alias for the host's loopback). On a physical device, use the host machine's LAN IP.

---

## 2. Build + install the wallet (devDebug)

```sh
cd /c/Users/mihai.serban/myprojects/eudi-app-android-wallet-ui
./gradlew :app:assembleDevDebug
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

`applicationId` is `eu.europa.ec.euidi.dev`.

---

## 3. Wallet first-launch → PID issuance

1. Launch the wallet (`adb shell monkey -p eu.europa.ec.euidi.dev -c android.intent.category.LAUNCHER 1`, or tap from the launcher).
2. **Splash → QR-config screen** (because `WalletState` is empty).
3. **Paste** `http://10.0.2.2:8092` into the URL field, tap **Continue**.
4. Splash re-evaluates → **PIN setup** (`CREATE_WITH_ACTIVATION`) → **Biometric** → **AddDocument** flow.
5. Pick PID. The wallet opens a Custom Tab to the issuer's self-attestation form. Fill in any persona (any well-formed name + birth-date works); submit.
6. Custom Tab redirects → wallet finishes OID4VCI, stores the SD-JWT-VC. Documents tab shows the held PID.

**Expected at the end of step 6:**

- `WalletState` carries `pidIssuerUrl` (set), and post-issuance carries `deviceKeyAlias` + `deviceKeyPublicJwk`.
- Documents tab lists one PID, issued by `http://10.0.2.2:8092`.
- The wallet has never made a cleartext request to anything other than the issuer (no bank backend yet).

**If issuance fails** with a `CLEARTEXT communication not permitted` error, you've installed the release APK by mistake — only `app-dev-debug.apk` carries the cleartext override (PR8).

---

## 4. Install + launch the bank-app (`banka` flavor)

From the companion repo:

```sh
cd /c/Users/mihai.serban/IdeaProjects/instant-payments-initiative/apps/bank-app
./gradlew :app:assembleBankaDebug
adb install -r app/build/outputs/apk/banka/debug/app-banka-debug.apk
```

`applicationId` is `eu.accesa.de.bankapp.banka` (so it installs side-by-side with the wallet).

---

## 5. Bank-app onboarding via PRESENT_PID

1. Launch bank-app. Empty `WalletState` → splash with **"Continue with EUDI Wallet"** button.
2. Tap the button. Bank-app POSTs `/bank/onboard/start` to `bank-simulator:8080` and gets back a `requestUri`.
3. Bank-app fires `Intent.ACTION_VIEW eudi-openid4vp://?request_uri=…`.
4. **Wallet pops to foreground** on `PresentationScreens.PresentationRequest`. Consent UI shows "Bank A wants to verify your name and date of birth."
5. Tap **Share** → biometric → wallet POSTs the VP token to the bank's `response_uri` (OID4VP `direct_post`).
6. Bank-app polls `/bank/onboard/result/{onboardingId}` until `completed`. Receives `{ iban, holderName, bic, isReturning, customerCreatedAt }`.
7. Bank-app navigates to its account view, shows IBAN + balances (both €0 initially).

**Verify on the bank backend:**

```sh
curl -fsS http://localhost:8080/bank/accounts/<the-iban>
```

Should return the new customer with `bankBalance: 0`, `deBalance: 0`, an empty `txLog`.

---

## 6. Fund the account (workshop facilitator step)

Bank balance is €0 after onboarding. The facilitator simulates an inbound salary deposit:

```sh
curl -fsS -X POST http://localhost:8080/bank/admin/fund \
  -H 'Content-Type: application/json' \
  -d '{"iban": "<the-iban>", "amount": 10000}'
```

Bank-app's account view picks up the new balance on next refresh.

---

## 7. Top-up via AUTHORIZE_OPERATION (`type: "topUp"`)

1. From the bank-app's account view, tap **Top up**.
2. Enter an amount (e.g. €50) → **Continue**.
3. Bank-app POSTs `/bank/de/top-up/initiate { iban, amount, paymentRef }` → receives `{ pendingId, expiresAt }`.
4. Bank-app constructs an `OperationEnvelope { type: "topUp", … }` and fires `Intent.ACTION_VIEW eudi-de-authorize://?envelope=…&state=…&callback=banka-app://authorize-response`.
5. **Wallet pops to foreground** on the AuthorizeOperationScreen. Confirm UI shows:
   - Title: **"Authorise top-up"**
   - description verbatim (e.g. "Top up €50.00 from your account at Bank A")
   - Amount: €50.00
   - Bank: Bank A (or `DEMODEAA` if `bankDisplayName` not supplied)
   - Operation: Top up
   - **NOT** displayed: IBAN, holder name. (Privacy invariant — `de-wallet-app-api.md` §Privacy.)
6. Tap **Confirm** → biometric → wallet builds JWT, signs with the PID's StrongBox key, fires `Intent.ACTION_VIEW banka-app://authorize-response?state=…&authorization=<JWS>`. Wallet activity finishes.
7. Bank-app receives the callback, POSTs `/bank/de/top-up/confirm { pendingId, walletAuthorisation }`. Bank-simulator verifies the JWT against the customer's pinned `Customer.deviceKey`, then calls `ncb-issuer` to debit the bank's DCA + credit the user's holdings.
8. Bank-app refreshes the account view → bank balance is reduced by €50, DE balance is now €50.

**If the bank returns `AUTHORISATION_INVALID`**, the JWT verification failed. Common causes: clock skew (>±5 min between device and host), the wallet sent a stale credential's key (regression of the single-key override in PR6), or the envelope was mutated between sign + verify.

---

## 8. Payment via AUTHORIZE_OPERATION (`type: "payment"`) — M2

> **Status (2026-05-06):** the bank-app's Scan-to-pay surface is in flight on the companion-repo side. Once it lands, this section is the smoke check for PR7's payment render branch.

1. From the bank-app's account view, tap the merchant Scan-to-pay surface (whatever name lands).
2. Scan / select a merchant; bank-app builds an `OperationEnvelope { type: "payment", payee: { merchantId, merchantName, description? }, … }` and fires `eudi-de-authorize://`.
3. **Wallet's confirm screen — payment branch (PR7):**
   - Title: **"Authorise payment"**
   - **Pay**  *MerchantName* (e.g. "MediaMarkt Saturn") — `payee.merchantName` rendered verbatim.
   - *(indented under)* `payee.description` if present (e.g. "Bluetooth headphones").
   - Amount: €X.XX
   - Bank: Bank A
   - **NO** Operation row (the title carries the type for payment).
   - **NOT** displayed: `payer.iban`, `payer.holderName`, `payee.merchantId`. (Privacy invariants — see `de-wallet-app-api.md` §Privacy.)
4. Tap **Confirm** → biometric → JWT fires back to bank-app via `banka-app://authorize-response?…`.
5. Bank-app POSTs the bank's payment-confirm endpoint; bank-simulator verifies the JWT (including `envelope.type == "payment"` and the merchant-cross-check) and orchestrates the NCB transfer.
6. Bank-app refreshes → balance reflects the payment; ECB dashboard sees a `holdingsTransfer` settlement event with the merchant's userId hash.

---

## 9. Workshop reset

Between demos, reset the whole stack so each participant starts clean:

```sh
# Wipe wallet state on-device.
adb shell pm clear eu.europa.ec.euidi.dev
adb shell pm clear eu.accesa.de.bankapp.banka

# Reset backend services from the companion repo.
curl -fsS -X POST http://localhost:8090/ncb/admin/reset
curl -fsS -X POST http://localhost:8080/bank/admin/reset
curl -fsS -X POST http://localhost:8082/bank/admin/reset
```

The `ncb` reset re-seeds the DCAs to €10M each. The bank resets clear customers + IBAN counter + idempotency cache.

---

## 10. Failure-mode quick reference

| Symptom | Likely cause | Fix |
|---|---|---|
| `CLEARTEXT communication not permitted` at first issuance | Installed release APK instead of debug | Reinstall `app-dev-debug.apk` (PR8 only ships in debug variants) |
| QR-config "Continue" does nothing | Bad URL format | Decoder requires `http://` or `https://` + a host. See `WalletConfigDecoder` |
| OID4VCI redirect lands on `eudi-openid4vp://callback` and crashes | Issuer returned an OID4VP error instead of a code | Check the Custom Tab — the issuer's self-attestation form may have rejected the submission |
| Bank-app's `PRESENT_PID` doesn't open the wallet | No PID held in the wallet | Repeat steps 3–6; ensure issuance completed |
| Wallet shows `Authorise…` then errors immediately | Envelope decode failed (`error=invalid_envelope`) | Inspect the bank-app's intent: required envelope fields per `de-wallet-app-api.md` |
| `error=expired` callback even though the user moved fast | Device clock differs from host by > 5 min | Sync time on the device / emulator |
| Bank returns `AUTHORISATION_INVALID` after Confirm | JWT verification failed bank-side | Check bank-simulator logs; look for type-mismatch, IBAN-mismatch, or kid-mismatch sub-reasons |
| Two wallet apps installed (e.g. dev + demo) → Android shows app-chooser on every intent | Multi-wallet selection (out of scope for v1) | Uninstall one of them |

---

## What's intentionally out of scope here

- **Offline NFC payment / merchant mode** — M4 (simulated SE counter); see `roadmap.md`.
- **Keycloak-mediated operator auth** — citizen flow stays wallet-mediated by design (ADR 0004 + 0006 split).
- **TLS for backends** — workshop ships HTTP-only; PR8 mirrors that for debug. Production deploys would terminate TLS at the bank backend.
- **Automated UI tests for the intent surface** — covered by manual checks here. The unit tests in `de-logic` cover the JWT/envelope/codec primitives that the integration depends on.
