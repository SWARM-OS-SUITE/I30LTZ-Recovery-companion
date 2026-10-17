# Working notes for AIs editing this app (read before you touch a file)

Updated 2026-10-12 by dev-claude. Two AIs were editing this repo at the same moment on 2026-10-12 and one whole-file
rewrite of `CoderDroidScreen.kt` was overwritten by the other. Nothing was lost, but please follow these rules.

## Rules
1. `bash /mnt/recoverybuild/70-scripts/ai-board.sh list`, then `claim` before you edit. The area for this app is
   `/mnt/boltzbuild`. If it reports OVERLAP, ask the user.
2. Shared files (`CoderDroidScreen.kt`, `CoderDroidViewModel.kt`, `MainActivity.kt`): use small exact-match edits,
   never a whole-file rewrite, and re-read the file right before editing.
3. Never put keys, tokens or model files in this repo (auto-sync pushes everything). Model weights live in the app's
   private storage on the phone, and downloads go to `/mnt/downloads/llm-models/`.
4. Lucille Assistant is a separate app. Inside this app the AI is **Dr. Boltz** (Dr. I30LTZ). User decision 2026-10-12:
   the IDE's AI button opens `CdrAiPanel` (Dr. Boltz). `CdrLucilleAssistantPanel.kt` is kept on disk but not shown.

## Who owns what right now (dev-claude claim, 2026-10-12)
| Area | Files |
|---|---|
| Offline AI engine, models, downloads | `data/ai/*`, `ui/OfflineAiViewModel.kt`, `ui/screens/OfflineModelsUi.kt` |
| File access (folder permission) | `data/storage/*`, `AndroidManifest.xml` storage permissions |
| IDE core (tabs, save, file ops, search) | `ui/coderdroid/CoderDroidViewModel.kt`, `CdrFileOps.kt`, `CdrFileTreePanel.kt`, `CdrMonacoEditorView.kt`, `CdrEditorController.kt`, `assets/monaco/editor.html` |
| Memory sync tests and hardening | `data/memory/*` |
| Tests | everything under `app/src/test`, `app/src/androidTest` |

Other AI (unclaimed, not on the board): `CdrMarketplaceDialog.kt`, `CdrLucilleAssistantPanel.kt`, the memory summary in
`AppRepository.kt`. Please claim `/mnt/boltzbuild` areas on the board so we do not collide.

## Status snapshot, 2026-10-12 02:25 (dev-claude): read this first if you continue
**Built and tested (emulator `Boltz_Test`, see `/mnt/boltzbuild/scripts/emu/README.md`):** folder button no longer crashes; All-files-access
prompt and "can't read this folder" banner; project restore; explorer; Monaco editor now sizes itself from `innerHeight` (viewport units
resolve to 0 in this WebView). 240 unit tests pass (`./gradlew :app:testDebugUnitTest`), one flaky: `PhoneNodeServerTest.listeningAddressesFollowTheRequestedSet`.
**Not done / next:** (1) run the AI on the emulator: Models dialog, download Qwen3 0.6B, Start, ask, Insert (never verified end to end);
(2) rebuild + `adb -s emulator-5554 install -r` 6.2.24 (the last VM fix, blocked tabs re-read, is not in `dist/`); (3) run-through of the other tabs
(Home, Tasks, Stats, Scan, I30LTZ, Repair, Deploy, Memory) and the stale "SWARM_ v6.2.0" / "DR. i3OLTZ" (letter O) / "MAD IT" strings;
(4) commit is the user's call; (5) installing 6.2.24 on the three phones needs the user's OK (they currently run another AI's 6.2.23).
**Decisions by the user:** IDE AI button = Dr. Boltz panel; storage access = All files access; model weights download OK (kept out of the repo).
**Open:** `TODO(human)` in `OfflineModelCatalog.recommendedFor`.

## Update 2026-10-13 01:45 (dev-claude)
**Fixed + verified on emulator:** 6.2.24 crashed the whole app the moment the offline model answered
(`NoSuchMethodError SendChannel.close$default` inside litertlm 0.16.1 `Conversation.sendMessageAsync(Flow)`; the library
was built against a newer kotlinx-coroutines than ours). `OfflineLlm.chat` now uses the callback overload
`sendMessageAsync(String, MessageCallback)` bridged to a `Channel`. 6.2.25 (vc85) in `dist/`: in-app download of Qwen3 0.6B (SHA ok),
auto-load on first question, answer streamed, app stayed alive. **Not on any phone** (needs the user's OK).
**Found, not fixed:** the AI button in the IDE does nothing visible until a file tab is open (panel only renders with an editor).
Stale strings and tab run-through (Home..Memory) still pending. Emulator note: boot needs `ANDROID_SDK_ROOT=/home/i30ltz/Android/Sdk`
(system image is there, not on /mnt/boltzbuild). Something else launched `com.lucille.app` on emulator-5554 mid-test: another session may share it.
**User direction 2026-10-13:** every IDE (phone, web, desktop) must be standalone with its own SDK/adb, own memory copy, Syncthing + git commit/pull.
Exists today: JGit commit/pull/push/clone (`CdrGitService`), peer memory sync (`data/memory/*`), "Build APK / ADB" button. No Syncthing integration yet.
