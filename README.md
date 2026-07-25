# Repo Master

A full Git client for Android — clone, stage, commit, push, resolve conflicts,
and edit files, all from your phone. Built with Jetpack Compose, JGit, and a
dark "command center" design system.

<p>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3ddc84?logo=android&logoColor=white">
  <img alt="Min SDK" src="https://img.shields.io/badge/minSdk-24-blue">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff?logo=kotlin&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-lightgrey">
</p>

## Features

- **Repo management** — clone over HTTPS, browse your repos, see uncommitted-change counts at a glance
- **Stage & commit** — swipe to stage/discard, commit with a message, push in one tap
- **Sync** — fetch, pull (merge or rebase), push, and combined sync, including force variants when you need them
- **Conflict resolution** — pick "ours"/"theirs" per file, or jump into the built-in editor to resolve by hand
- **History** — commit log, cherry-pick, amend, squash
- **Branches, tags & remotes** — full CRUD all three
- **File explorer & editor** — browse the working tree and edit text files directly
- **Blame view** — see who changed each line and when
- **.gitignore editor** — with quick-insert templates for common stacks
- **Discover** — search public GitHub repos and clone straight from search results
- **Credentials** — store personal access tokens per-remote, encrypted at rest via Android Keystore
- **Background sync** — optional scheduled fetch/pull so repos stay current without opening the app
- **Adaptive light & dark themes** — a dark "cockpit" look by default, with a full light theme for accessibility/system preference

## Screenshots

*(Add screenshots or a screen recording here — drop images in a `/docs` or
`/screenshots` folder and reference them, e.g. `![Home](docs/home.png)`.)*

## Tech stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Git engine | [JGit](https://www.eclipse.org/jgit/) |
| Persistence | Room |
| Background work | WorkManager |
| Async | Kotlin Coroutines + Flow |
| Navigation | Jetpack Navigation Compose |

## Getting started

### Requirements
- Android Studio (Koala or newer recommended)
- JDK 17
- Android SDK 34

### Build locally
```bash
git clone https://github.com/<your-username>/<your-repo>.git
cd <your-repo>
```
Open the project in Android Studio and let it sync — Android Studio
regenerates the Gradle wrapper jar automatically. Then **Run ▶** on a device
or emulator (min SDK 24 / Android 7.0+).

### Build from the command line
```bash
gradle assembleDebug
```
> This project intentionally doesn't check in the binary
> `gradle-wrapper.jar`. Android Studio regenerates it on sync; from a plain
> shell, install Gradle 8.9 yourself and call `gradle` directly (see
> `.github/workflows/build.yml` for the exact setup used in CI).

## CI/CD

One workflow (`build.yml`), two jobs, so a push shows up as a single run
with both jobs under it in the Actions tab:
- **`build-debug`** — runs on every push/PR: a fast debug build to catch
  compile errors early.
- **`build-release`** — only does real work on a `v*` tag push (or manually
  via **Run workflow**); on an ordinary branch push/PR it still appears in
  the run, just marked Skipped. Builds a signed, shrunk release APK and
  attaches it to a GitHub Release. Each run signs with a freshly generated
  throwaway keystore — great for installing on your own device(s), but
  **not** suitable for Play Store updates (which require a stable signing
  key across releases). To use a persistent key instead, generate one
  yourself, base64-encode it into a repo secret, and swap the "Configure
  release keystore" step in `build.yml` for one that decodes the secret.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Clone, fetch, push, pull over the network |
| All-files access (Android 11+) / `WRITE_EXTERNAL_STORAGE` (Android 10-) | Repos are stored in a **public** folder (`/storage/emulated/0/RepoMaster/repos`) instead of the app's private sandbox, so any file manager, other app, or PC-over-USB can reach your files directly |

## Project structure

```
app/src/main/java/com/willykez/repomaster/
├── data/            # Room entities/DAOs, repositories, encrypted credential storage
├── git/             # JGit wrapper — clone/fetch/pull/push/commit/etc.
├── sync/            # WorkManager background sync
├── navigation/      # Nav graph (AppNav.kt)
└── ui/
    ├── components/  # Shared design-system pieces (GlassCard, etc.)
    ├── theme/       # Color/Theme/Type — light & dark schemes
    └── screens/     # One package per screen (repolist, changes, explorer, editor, …)
```

## Design system

The app uses a shared `GlassCard` component (a translucent card with a
hairline border and an optional status-accent bar) across repo, file, and
credential rows, and a two-color accent system: **command blue** for
navigation/primary actions, **gold** reserved specifically for commit/push —
so gold always signals "this touches the remote." Everything is driven off
`MaterialTheme.colorScheme`, so it adapts correctly between dark ("cockpit")
and light mode.

## Contributing

Issues and PRs welcome. If you're proposing a UI change, a before/after
screenshot or short screen recording speeds up review a lot.

## License

MIT — see [LICENSE](LICENSE) (add one if it isn't already in the repo).
