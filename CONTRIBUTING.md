# Contributing to PixelTune

First off — thank you. PixelTune is a community project, and every bug report,
translation, and pull request makes it better for everyone.

This document explains how to contribute effectively: what to include in
reports, how to set the project up locally, the conventions the codebase
follows, and the (small number of) legal rules that keep the project free
software.

## 💛 Ways to contribute

You don't need to write code to help:

-  **Bug reports** — reproducible reports are the single most valuable
  contribution during beta.
-  **Feature ideas** — well-argued proposals shape the roadmap.
-  **Translations** — bring PixelTune to more locales.
-  **Documentation** — improve the README, in-app help text, or this guide.
-  **Code** — bug fixes, refactors, and new features (see below).

## ⭕️ Reporting bugs

Open an [issue](https://github.com/Saineeee/PixelTune/issues) and include:

1. **Device & Android version** (e.g. Pixel 8, Android 15).
2. **App version** — from Settings → About, or the APK filename.
3. **Steps to reproduce** — numbered, minimal, deterministic if possible.
4. **Expected vs. actual behavior.**
5. **Logs** — a `adb logcat` capture around the failure, or a crash stack
   trace. In-app Settings → About also exposes debug options.

> [!TIP]
> Search existing issues (open *and* closed) before filing. If you can add
> information to an existing report — another affected device, a cleaner repro
> — that's more useful than a duplicate.

For streaming issues, note the source (YouTube / YouTube Music / SoundCloud /
NetEase / Telegram / Drive), whether it affects every track or specific ones,
and whether it happens on Wi-Fi and mobile data.

## 💡 Suggesting features

Open an issue and lead with the **problem**, not the solution: what were you
trying to do, what got in your way? Proposals that describe the use case get
better feedback and are easier to accept. Feature ideas that require new
third-party services or permissions will be discussed with extra care — see
the dependency policy below.

## 🛠️ Development setup

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Latest stable | Recommended IDE |
| JDK | 21 | Auto-provisioned by the Gradle toolchain |
| Gradle | 8.13 | Via the included wrapper |
| Android SDK | API 35 | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 29` |

### Getting started

```bash
git clone https://github.com/Saineeee/PixelTune.git
cd PixelTune
./gradlew :app:assembleDebug      # phone debug build
./gradlew :wear:assembleDebug     # Wear OS debug build
./gradlew :app:testDebugUnitTest  # JVM unit tests
```

Open the folder in Android Studio and let sync finish. An emulator or device
is only needed to run the app — all unit tests run on the JVM.

### Where things live

See the [Repository layout](./README.md#repository-layout) tree in the README.
The short version: `:app` is the phone application (data layer under
`data/`, Compose UI under `presentation/`), `:wear` is the Wear OS companion,
`:shared` holds phone ↔ Wear models, and `:baselineprofile` generates startup
profiles.

## 🎨 Coding conventions

- **Kotlin official style.** Match the surrounding code; don't reformat files
  you're not otherwise touching.
- **Compose first.** UI is Jetpack Compose (Material 3 Expressive). New
  screens live in `presentation/screens/`, reusable pieces in
  `presentation/components/`, and screen state belongs in a ViewModel or a
  dedicated state holder — not in composable singletons.
- **Layer boundaries.** UI → ViewModel → Repository → data sources. Don't
  reach into DAOs or services from composables, and don't put business logic
  in composables.
- **User-facing strings** go in `res/values/strings.xml` — never hardcoded.
- **Observe narrowly.** Prefer sliced, `distinctUntilChanged`-ed flows over
  exposing whole UI-state objects; it keeps recomposition cheap during
  playback.
- **Version catalog.** All dependencies and versions are declared in
  `gradle/libs.versions.toml` — no ad-hoc version strings in module build
  files.

## ✅ Testing & quality gates

Before opening a PR, make sure all of the following pass:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :wear:assembleDebug
```

- Add or update unit tests for bug fixes (a test that reproduces the bug is
  the best way to prove the fix).
- For behavior changes, verify manually on a device or emulator and describe
  what you tested in the PR.
- For playback-engine changes, test with **local files and at least one
  streaming source** — regressions there are easy to miss and hard for users
  to work around.

## 📦 Dependency policy

New dependencies are accepted deliberately, not by habit:

1. The dependency must be actively maintained and license-compatible with
   GPL-3.0 (Apache-2.0, MIT, BSD, MPL-2.0, LGPL are generally fine).
2. Justify it in the PR: what it does, what it replaces, APK size impact.
3. Add it to `gradle/libs.versions.toml` **and** to
   [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) with its license.

Dependencies with incompatible licenses (GPL-incompatible, proprietary,
source-available) will be declined.

## ⚖️ Legal notes (please actually read this part)

- **Your contributions are GPL-3.0.** By opening a PR you agree your work is
  licensed under the project's license — the GNU GPL v3.0 — so it can be
  distributed with PixelTune.
- **Only submit code you have the right to submit.** Do not copy code from
  other projects unless their license permits it, and **do not submit code
  copied from the upstream PixelPlayer repository** — it is proprietary
  software as of May 2026, and its code must never enter this project. If you
  want to propose something PixelPlayer does, describe the *idea* in an issue
  instead; implementations here must be written from scratch.
- Fixes ported from legitimately MIT-licensed sources must keep their
  attribution notices intact.

## 🔀 Pull request process

1. Fork the repo and create a focused branch
   (`fix/shortcuts-target-package`, `feat/queue-reorder`, …) from the default
   branch.
2. Keep PRs single-purpose. Large changes are easier to review as a series of
   small ones.
3. Use [Conventional Commits](https://www.conventionalcommits.org/) style
   (`feat:`, `fix:`, `perf:`, `refactor:`, `docs:`), one logical change per
   commit.
4. Fill in the PR description: what changed, why, how it was tested, and
   screenshots/screen recordings for UI changes.
5. If the change is user-visible, add an entry to the
   [CHANGELOG](./CHANGELOG.md) under an *Unreleased* heading.
6. A maintainer will review. Address feedback with new commits rather than
   force-pushing over the history, and rebase/squash only when asked.

> [!NOTE]
> During beta, PRs that reduce crash rates, fix broken features, or improve
> playback stability are prioritized over new features.

## 🌍 Translations

- String resources live in `app/src/main/res/values/strings.xml` (English
  source) with per-locale folders such as `values-de/`, `values-ru/`.
- Add a new locale by copying the source file and translating the values —
  keep string **names and placeholders unchanged**.
- Please don't submit machine-only translations blind; if you're fluent,
  you'll spot the context issues that machines can't.
- Widget, shortcut and notification strings matter most — they're what users
  see without opening the app.

## 🙌 Recognition

Contributors are credited in the app's About screen (fetched from the
repository's contributor list) and in release notes. If you contribute
regularly and would like a role in triage or review, say hello in the issue
tracker — the project grows with the people who care about it.
