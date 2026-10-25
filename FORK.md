# BiliPai fork: bilipai-plus

This branch follows upstream [jay3-yy/BiliPai](https://github.com/jay3-yy/BiliPai) release tags and
keeps a small set of changes on top. The changes stay out of upstream files as far as possible, so
moving to a new release is a rebase with few conflicts.

## What the fork adds

| Commit | Where | What |
| --- | --- | --- |
| `fix(player): keep first video playback from silently stalling` | `MiniPlayerManager.kt`, `BackgroundAudioConnection.kt` | The first video after launch no longer stalls (media notification removed while buffering made the system media panel send `stop`; background-audio timeouts were swallowed as cancellations). |
| `feat(btr): …` | `btr-transport/` (new module), `feature/plugin/btr/` (new) | Port of [Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper) at `bbf4d3d`: range splitting over many CDN nodes, hedged copies, resume, deadline-ordered queue, automatic thread count, mainland/overseas/custom node lists. |

### Hooks in upstream files

Everything the host must call. On a conflict, re-add these lines; nothing else of the fork lives in
upstream files.

| File | Hook |
| --- | --- |
| `settings.gradle.kts` | `include(":btr-transport")` |
| `app/build.gradle.kts` | `implementation(project(":btr-transport"))` |
| `app/.../app/PureApplication.kt` | `PluginManager.register(BtrPlugin())` |
| `app/.../core/player/PlaybackMediaCache.kt` | wrap the host data source in `BtrDataSource(host)` |
| `app/.../feature/video/viewmodel/VideoPlaybackViewModel.kt` | `BtrTransport.registerTracks(...)` after the CDN rewrite; `BtrPlaybackMonitor.attach(player)` and `BtrTransport.addressRefresher` in `attachPlayer`; the refresher (`btrAddressRefresher`) and its reset in `onCleared` |
| `app/.../feature/video/ui/pager/PortraitVideoLoadPolicy.kt` | `BtrTransport.registerTracks(...)` |
| `app/.../feature/bangumi/BangumiPlayerViewModel.kt` | `BtrTransport.registerTracks(...)` |
| `app/.../feature/video/state/VideoPlayerState.kt` | deepen `resolvePlayerBufferPolicy(...)` with `BtrTransport.bufferFloor()` (anti-jitter buffer) |

## Following a new upstream release

```bash
scripts/fork-sync.sh v0.6.2
```

It fetches upstream tags, rebases `bilipai-plus` onto the tag, and runs the fork's tests. On a
conflict it stops in the rebase: resolve the hook lines above, `git rebase --continue`, and run it again.

## Building

Upstream resolves Miuix from GitHub Packages, which needs credentials (`gpr.user`/`gpr.key`). Without
them, point the build at a local Miuix checkout of the commit named in `gradle/libs.versions.toml`
(`miuix = "0.9.4-<commit>-SNAPSHOT"`):

```bash
git clone https://github.com/compose-miuix-ui/miuix.git ../miuix && git -C ../miuix checkout <commit>
./gradlew -Pbili.miuix.source=../miuix :btr-transport:testDebugUnitTest :app:assembleDev
```

At `v0.6.1` the Miuix commit uses AGP 9.4.1 and Gradle 9.8 previews while BiliPai uses AGP 9.3.2 and
Gradle 9.5. In the local Miuix checkout only, set `agp = "9.3.2"` in `gradle/libs.versions.toml` and
drop the `NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS` and `ENHANCED_GRAPH_ORDERING` previews from its
`settings.gradle.kts`.

Upstream's own unit-test source set does not compile at `v0.6.1` (four stale tests), so run the
fork's app tests with `--tests` filters only after setting those four aside, or run the module's tests.

A locally built APK is signed with your key: it cannot update an install from the official release.
