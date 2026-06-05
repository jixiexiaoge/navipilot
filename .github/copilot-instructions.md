# Copilot instructions for navipilot/CPlink

## Build, test, and lint commands

Use the Gradle wrapper from repo root:

```bash
# Build app
./gradlew assembleDebug
./gradlew assembleRelease

# Lint/static analysis
./gradlew detekt

# Tests
./gradlew test
./gradlew test --tests "com.example.navipilot.GeoUtilsTest"
./gradlew test --tests "com.example.navipilot.GeoUtilsTest.distanceTo_samePoint_returnsZero"
./gradlew connectedAndroidTest
```

ABI defaults to `arm64-v8a`. To include `armeabi-v7a` too:

```bash
./gradlew assembleDebug -Pnavipilot.abis="arm64-v8a,armeabi-v7a"
```

## High-level architecture

- The app is an Android navigation companion that bridges map/navigation SDK signals into comma3/openpilot data channels.
- `MainActivity` is a coordinator split across `MainActivity.kt`, `MainActivityCore.kt`, `MainActivityUI.kt`, `MainActivityUIComponents.kt`, and `MainActivityLifecycle.kt`. Keep logic in these layers instead of re-centralizing into one file.
- `carrotManFields: MutableState<CarrotManFields>` is the central SSOT for nav/vehicle data. Navigation bridges (`AmapNavDataBridge`, `GoogleNavDataBridge`, `TencentNavDataBridge`) map SDK events into this model.
- Data flow:
  1. Navigation source events (broadcasts/SDK callbacks) update `carrotManFields`.
  2. `NetworkManager`/`CarrotManNetworkClient` push data to comma3 (UDP 7706 + TCP 7709).
  3. Device state comes back via `XiaogeDataReceiver` (TCP 7711), then feeds app state and overtake logic.

## Key conventions in this repository

- **Main-thread state mutation:** Bridges use `postFieldsMutate(...)` patterns to ensure `carrotManFields` writes occur on the main looper.
- **Navigation-mode exclusivity:** Respect `activeNavMode` (`AMAP`/`GOOGLE`/`TENCENT`/`AMAP_MOBILE`/`OSM`) and avoid cross-mode data contamination (e.g., AMAP broadcast ingestion is skipped while other embedded nav modes are active).
- **Broadcast backpressure:** High-frequency AMAP broadcasts are queued through bounded `Channel.BUFFERED` + `trySend` with single-coroutine consumption; do not switch this path to unbounded buffering.
- **CarrotManFields sizing constraint:** Keep large/tencent-specific tails in `CarrotManTencentSlice` and update via `withTencentSlice(...)`; keep transient runtime-only flags as `@Transient var` to avoid ART `VerifyError` from oversized data-class constructors/copy masks.
- **Error handling primitive:** Prefer the shared `core/Result.kt` sealed result + `runSafely(ErrorCode, ...)` for fallible flows instead of ad-hoc result wrappers.
- **Build-specific safeguards:** Keep `patchRClass` task wiring and related Tencent `R` handling intact; release uses `isShrinkResources = false` due to SDK compatibility.
