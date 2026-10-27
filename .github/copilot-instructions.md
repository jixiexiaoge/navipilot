# Navipilot repository instructions

LeftGuidancePolicy filters Carrot fork-left codes at the 7714 boundary. The
Controls preference CarrotAmap.suppress_highway_left_guidance defaults true;
read it in the existing 200ms sender, with no new poller. Apply to current/next
guidance and lane_current action copies. Fresh route-bound ROAD_TYPE 0/6 and
unknown/expired categories block; known regular roads pass. Disabling restores
existing mapping. Withdraw immediately and retry failed enqueues; allowed regular
road forks use the oldest category/guide receipt. Clearly disclose that real left
exits are also blocked. Preserve local AMap display, manual commands and independent
streams. Width bands are not map-lane identity; never modify WSL or claim vehicle
behavior is verified from phone tests.

Overtake mode buttons distinguish off/tap-to-change from signal/auto preferences
saved but not enabled. Observe overtake_mode preference changes; delayed callbacks
must not restore stale mode state. Preserve the incomplete-authority suspension
and independent manual lane-change requests.

## Ramp-to-mainline recovery

SmartSpeedLimiter keeps a route-bound, 90-second recovery candidate from entry
text, a current ramp name with a low limit, or existing highway 40-to-60 policy.
Only an actual current road limit 70-120 stable for 3 seconds across advancing
source receipts supplies the target. Never default elevated roads to 80 or
highways to 120, or use camera limits as next-road limits. Recovery advances at
most 10 kph per 3 monotonic seconds, independent of old maneuver distance.
The existing compact callback supplies controlsState.curvature * carState.vEgo
as an observed yaw-rate proxy, not a safe-speed model. Require both receipts
advancing, <=500ms old and <=150ms apart; confirm <=0.01rad/s for 2 seconds and
at least 3 pairs, withdraw at >=0.02rad/s or missing/expired/nonfinite/low-speed
(<5m/s) input. These are initial engineering gates. Preserve the explicit curve
protection switch. Route, source-gap and selected-host changes clear old state.
7714 policy is not a SPEED command. Existing automatic cruise retains its raw
road-limit ceiling, activation gates and 15-second retry; during recovery also
cap by policy and reported cruise + 10, awaiting 7000 setpoint feedback.
Unexpected setpoint changes cancel that automatic recovery until disengagement
or route change. No new subscriptions, timers, authority, vehicle parameters or
live diagnostic commands. The settings page distinguishes waiting, curve and
timeout states; policy completion is not vehicle execution.
Missing limits withdraw the target but retain the bounded candidate for
reconfirmation. A source gap must not unlock the independent raw-limit SPEED
fallback; require new entry evidence, route change or manual settings.
Finish only after policy and fresh 7000 cruise setpoint both reach the real
target, preventing the completed state's timeout from blocking later limits.
Setpoint feedback is not a request-correlated ACK or actual vehicle speed.

## Compact Live layout

Short landscape pages (actual width > height and height < 480dp) hide inline
map/video and cancel road-video/frame-tap sessions. Center the height-bounded
DriveViewCard above scrollable details. The gear still opens the same stable
WebView fullscreen; Back restores driving-only mode. Hidden maps occupy no
layout/input/semantics. Taller unfolded screens keep their existing layout.

Below 600dp effective available width (divide by fontScale, minimum 1), Live
pins full-width video above a single scroller containing map, DriveViewCard,
then existing details. Cap video at 28% of page height and the map at 320dp,
preserving source aspect ratios. Wide layouts retain the existing equal-height
map/video row and fixed driving card with independently scrolling details.
Keep SurfaceView outside all scrollers. Preserve the single WebView under its
stable parent: use the un-clipped map anchor size and clip its overlay to the
lower scroll viewport. Fullscreen still pauses video and Back restores scroll.
Keep compact and wide-detail scroll states across width/font changes.
`RealTimeCompactLayoutTest` covers narrow order/scroll/fullscreen/wide transitions;
wide RealTimePage and fullscreen regressions explicitly set test density.

## Release and background snapshot boundaries

The launcher `app_name` is stable; About reads BuildConfig version name/code.
Only Gradle owns version numbers. `DashboardSnapshotPublisher` belongs to the
Core scope: Main captures immutable `DashboardInputs`, a conflated capacity-one
request channel serializes Default computation, and Main publishes only when no
newer refresh is pending. Cancellation blocks old results; never renew source TTLs.
Compact expiry/compatibility mapping uses Default even with a Main owner scope.
7714 retains its existing serialized session/enqueue/cache rules; profile with
`Navipilot.7714.update` and `Navipilot.dashboard.snapshot` trace sections before
changing its threading or locks. Membership uses a dedicated HTTPS-only client
without redirects or public HTTP fallback; LAN HTTP/WS remains separate.
Signing material stays local and ignored; retaining a key locally does not erase
historical exposure. Do not automatically rotate production keys or rewrite history.

## Task entry points

| Task | Start here | Follow through |
|---|---|---|
| Build or validate | [Commands](#build-test-and-lint), `app\build.gradle.kts` | `gradle\libs.versions.toml`, daemon JVM criteria, focused test class |
| Home UI and map fullscreen | `MainActivityUI`, `AdaptiveLayouts.HomeDashboardTabs` | `RealTimePage`, `LocalMapBrowser`, `ProjectionState`; keep one WebView |
| Navigation input or 7714 output | `AmapBroadcastManager`, `AmapBroadcastHandlers` | `NaviSourceTracker` -> `NaviStreamManager` -> `NaviDataModels` -> `NaviWebSocketV2Client` -> actual receiver consumer |
| Vehicle observations | `data\CarrotWsClient`, `CompactStateDecoder`, `CompactTelemetryStore` | Typed display snapshots -> `DashboardStore` -> native UI or `/dashboard` |
| Vehicle commands | `NetworkManager.sendControlCommand` | `CarrotManNetworkClient.build7706Payload`; distinguish enqueue, delivery failure and execution |
| Projection | `projection\ProjectionService` under `com.navipilot.amap2tesla` | Privileged display -> JPEG/frame store -> local HTTP server -> bundled player |
| Lane estimates | `camera\WebRtcRoadCameraPlayer`, `vision\LaneInference` | `data\LaneGeometry`/`LaneFusion`, `ModelLaneCountSource` in `navigation\LaneCountSource.kt`; UI-only side estimates differ from wire fusion |
| Commerce or entitlements | `web\commerce\commerce_app.py`, its README and tests | Environment-backed SQLite, payment settlement, license/device binding and release manifest |

Paths without a prefix in this table are relative to
`app\src\main\java\com\jixiexiaoge\drivingassist`, except the explicitly named
projection package. Detailed architecture and invariants follow below.

## Automatic capability status

8.0.24 Controls modes are local configuration, always labelled requests paused.
`data/AutomationObservation` is display-only: existing compact connection/timeout
and required-service TTLs distinguish disconnected, waiting, expired and missing
authority. Fresh/active observations never authorize control. Reuse the existing
status card; no legacy overtake update call, new timer or default-allow send flag.
CEM saves local configuration without parameter writes; manual Exp/Chill actions
are labelled separately. Preserve compact display-only guards, automatic overtake
suspension, manual lane-change requests and enqueue-not-execution semantics.
AR/edge estimates cannot supply authority. See the current implementation record
at the top of `docs\fix-auto-overtake-wiring.md`; older proposals are historical.

## Reading this repository

This is an Android Kotlin/Compose application with a separate Python Flask
commerce service. Start with the commands and Architecture sections below;
consult `CLAUDE.md` and `README.md` for feature-specific detail. Both contain
release history: older transport, layout and data-source descriptions may have
been superseded. The current source files and the current contracts below take
precedence over historical examples.

For 7714 work, read `docs/cpdazi-7714-current-source-mapping-audit.md` for
the current field/source matrix and unresolved mapping/default-value issues;
`docs/cpdazi-7714-data-remediation-plan.md` records the earlier implementation
and blockers. Research findings are not implemented fixes or device validation.
Check the actual receiver version before relying on its defaults or consumers.
Keep this file and `CLAUDE.md` synchronized when commands, architecture
boundaries or repository conventions change.

For current home behavior, start with `AdaptiveLayouts.HomeDashboardTabs`,
`MainActivityUI` and `RealTimePage`, then follow `LocalMapBrowser` and
`ProjectionState` for the embedded map. The current source supersedes the
7.9.58 junction experiment: 7.9.64 removes the junction renderer/JSON IPC chain,
and the right pane always shows the 5001 road camera. Do not restore the old
schematic/vector takeover from release notes. Earlier AMap source investigations
remain in README; 10019 cross visibility and 30426 maneuver vectors are not
evidence of an official real-scene image or complete model-cross source.

## Current navigation and vehicle contracts

8.0.21 adds display-only independent edge width bands: prefer left-based numbering,
otherwise show right-based numbering without inferring left index or total lanes.
Validate each edge independently at 5m/15m, with same-side outer-line support for
additional bands; require three advancing model frames across >=600ms, gaps <=750ms.
AR corroborates each side; an opaque upper class is never an exact count.
Geometry display may survive missing AR results only within the active foreground
video session/host/route and fresh modelV2/lateralPlan receipts (skew <=300ms, explicit off).
Withdraw/reconfirm on expiry, maneuvering, rollback and identity/model changes.
Enable geometry and AR frame taps only for fresh AMap ROAD_TYPE=0/6; a ramp action
or typical 1-3 lane layout is not proof of current ramp identity. Road shoulders,
gore areas and legal lanes remain unclassified. Label width bands explicitly.
Keep the video overlay to a compact summary; tap opens scrollable read-only
width/edge-distance, corroboration and limitations details without hiding video.
Never send single-edge geometry as 7714 current_lane or relax existing wire gates.

8.0.15 lane fusion overrides the old raw-AR current-lane adapter. A single
LaneFusionTracker corroborates same-frame AR left/right counts with 5m/15m
modelV2 width bands and fresh explicit lateralPlan=off. Require local receipt
skew <=300ms, three advancing paired observations spanning >=600ms, gaps <=750ms.
These are initial engineering gates, not calibrated probability or capture sync.
Four output classes are verified, but upper class 3 semantics are not: withhold
its lane index, never invent 3+ or extend to six lanes. Preserve opaque scores.
Missing/conflicting/expired/maneuvering/rollback/host/route/session/model changes
withdraw and reconfirm. No single-source wire current-lane fallback or fabricated
shoulder classification. UI labels estimates explicitly; reuse the existing
500ms foreground sampler. ModelLaneCountSource supplies only fusion estimates;
7714 takes the oldest AR/modelV2/lateralPlan receipt and their original TTLs.
Fusion is enabled only when fresh AMap reportedRoadType is 0 (highway) or 6
(expressway/elevated); all other roads withdraw to not-enabled state.
When a map junction graph is present, keep current_lane=-1 even for equal counts:
map/current-road identity is unverified; model_* diagnostic keys remain separate.
Without a map graph, fusion count/current still need a fresh route road category.
No new subscriptions, control authority, CAN/7706 changes or WSL writes.

8.0.14 display override: match Carrot jetlink_status.badge. Only jetSON requires
specialized health telemetry; a current ready/active MAC/iOS/Android report is not
a warning solely because fresh=false or severity=unknown. Active shows the device
name alone in green; ready remains distinct, explicit warning/error and disconnect
still override. Keep local 3s receipt expiry and HTTP/lifecycle bounds.
AMap displayNavigationSignal no longer requires isNavigating or routeTasksReady.
Show a valid selected-AMAP, route-bound fresh lamp receipt immediately, retaining
phase countdown expiry and invalid/stop/route/source/rollback withdrawal. This is
display-only; preserve stricter 7714, independent CAN and cruise encoding.

2026-10-23 protocol repair: 7714 is the only navigation-data transport.
All 7706 builders, including periodic, command, destination and debug preview,
must exclude GPS, road limits/categories, SDI, TBT and route summaries. A destination
is an explicit compatible operation, never inferred from retained navigation state.
Do not restore full packets or turn-block dedup to unlock carrotCmd.
7714 route summaries require distance and time from the same actual guide packet;
partial/malformed packets withdraw the summary, never renew retained siblings.
Do not publish ETA differences as moved_time_sec. Position road names belong to
the position sample, not the shared fields cache. SDI/secondary SDI and section
control use immutable route/receipt-bound snapshots; a partial packet cannot borrow
old limits or block state. Section control needs real positive END_DISTANCE and
the same packet's actual LIMITED_SPEED, never corrected road policy or total length.
Ordinary camera packets and section-end events clear old section control facts.
Generic keep-left/right and roundabout U-turn lack an equivalent verified CP code:
withhold wire guidance while preserving independent AMap labels/graphics.
Sharp-left maps to CP16, not fork-left17. Receiver CAN countdown and unknown
road-category retention limitations remain blocked; no fabricated data or WSL writes.

Live fixed content is ordered map/5001 video row, then Dashpilot driving card;
8.0.11 keeps equal heights but sizes widths from actual projection width/height
and video frame aspect, rather than equal-width panes with map letterboxing.
Center the pair when height-capped. Reuse the existing foreground 500ms projection
state observer for dimensions; never resize the shared display or crop/stretch.
details scroll independently. Keep SurfaceView outside the scroller. Both embedded
and external players contain the complete image (letterboxing is allowed), using
visualViewport size/offset with innerWidth/innerHeight fallback. Match touch mapping
to actual image dimensions. External fullscreen is best-effort, retrying on the
first touch if a gesture is required; embedded clients never fullscreen or report
viewport sizes. Browser regression:
`node --test app\src\test\js\dashboard.test.cjs app\src\test\js\player.test.cjs`.
`ProjectionPlayerFitTest` checks the actual player/JPEG in a phone WebView using
a local fixture in wide/tall containers. This does not validate the live AMap
source, external browser fullscreen permission, or vehicle integration.

8.0.9 command delivery: heartbeat carries only carrotIndex/epochTime/timezone,
never carrotCmd (including "heartbeat"), which overwrites the receiver mailbox.
Command copies retain their command/direction/index and enqueue-time target;
target changes abort remaining copies and retirement packets. Surface asynchronous
manual delivery failures; keep last nonempty command diagnostics distinct from
heartbeats and explicitly not an ACK. Latest modeld passes carrotMan directly
to DesireHelper; do not invent navigation/desiredSpeed values to unlock commands.
Compact still lacks complete automation authority: automatic overtake remains
paused, saving a mode is not enabling automation, rejected enqueues are not actions.
Never send live vehicle-control commands as an automatic diagnostic.

The latest read-only Carrot tree is
`\\wsl.localhost\Ubuntu24\home\jeff\carrot`; receiver code is under
`openpilot\selfdrive\carrot`. Synthetic probes confirm CAN phase without countdown
survives cereal/control parsing, but carrot_serv clears TrafficLight when
remain_sec<=0. This consumer limitation is not fixed by App transmission;
never invent countdown or claim vehicle adoption. Keep WSL read-only.

8.0.6 requires Android 12/API31. CAN observations from 7711 take priority for
7714 traffic_signal; use AMap only as an independent fallback/reference.
Never join AMap direction/countdown to CAN phase or use stop-line distance as
lamp distance. A known CAN yellow withdraws the stream because this contract
has no yellow slot. Preserve actual local wall receipt in source_timestamp_ms,
not polling time. Require increasing 7711 sequence/source timestamp; duplicates
cannot renew freshness, malformed/rollback input clears on reconnect. Read timeout
and display TTL are both 1500ms; packet timestamp ordering is not CAN capture proof.
Embedded projection consumes ProjectionState.serverPort and bounded /health
identity (service=navipilot-projection, schema_version=1, running=true), never an
arbitrary open port. React to service restart/port changes across 8080-8099.
Keep Jetlink phase visible during warnings; tap shows read-only health/reason.
8.0.8 uses Carrot-style compact READY/ON/LOAD/LINK/RETRY/OFF and ERROR/WARN/DATA?
labels. Healthy ready/active are green but text distinguishes them; preparation
and warnings are amber, failures/disconnection red, unknown/absence gray.
Do not infer HOT from a generic warning. The driving card bottom-right IP/status
must wire onConnectionClick to the existing 7706 debug overlay even with live video.

The Live-page Jetlink badge reads only nested `jetlink` from the selected host's
GET `:7000/api/egpu/model`, every 2s while STARTED. Cancel on page leave/background,
reset on target changes and expire local observations at 3s monotonic receipt age.
HTTP is bounded to 1s/64KiB without redirects. Surface errors as unavailable,
never retain a successful badge on failure. Ready differs from model active;
fresh means health telemetry freshness, not physical USB presence. Outer eGPU
active/available are not Jetlink flags, remembered labels are not connection
proof, and iOS does not distinguish iPhone/iPad. No new control authority.

8.0 removes the obsolete MainActivityUI header/HUD/status/lane component chain
and its old DrivingHudVisibilityTest. Do not restore those test-only components
or the unused showAdvancedDialog callback. AdvancedFunctionsContent remains
directly rendered in Controls; preserve current RealTimePage/DriveViewCard,
Core connection state and the active DriveViewCard TrafficLamp enum.

AMap navigation lamps render from the independent route/receipt-bound
`displayNavigationSignal`, not shared legacy lamp fields or a generic clock.
Require selected AMAP and a valid independent signal receipt, not active navigation
or ready route tasks. Raw status 1/2
means red/green with a real positive phase countdown; subtract receipt age and
withdraw at countdown end or 10s TTL without predicting the next phase. Unknown
directions are neutral, never straight. Missing/malformed data, route/source
changes, stops and clock rollback withdraw old display facts. Do not guess
status 3/4 as yellow. Cruise `lightsData` is a separate domain: status 0/1 is
red/green and direction 1/2/3 is left/straight/right; show its reported countdown.
Keep display validation separate from the stricter 7714 `navigationSignal`
conversion and preserve CAN lamps, 7706 and vehicle-control behavior.

Read the current version from `app/build.gradle.kts`; release notes below
describe the feature that introduced a behavior, not the current build version.
7714 remediation uses independent route/receipt-bound road-category, navigation
signal and live-lane snapshots, never the retained read-only laneDisplay cache.
Only the 7714 boundary maps raw ROAD_TYPE 0/6 to CP 1 and 1-5/7-9 to CP 6;
keep shared legacy roadcate unchanged. Unknown/10 withdraws lane_current: the
receiver control ignores Valid and serv may retain an old category after
withdrawal, so that compatibility case is still blocked, not fully fixed.
Require explicitly enabled lanes with a complete unique 0..N-1 ordering.
Unknown availability is omitted. `LaneTurnCode` translates recognized Back_icon
directions separately from TBT ICON; an unrecognized lane omits the entire
turn_info array. This is not proof of receiver arrow rendering or complete
multi-direction semantics. Never infer ego lane from a row index/recommendation.
Navigation lights require actual positive source countdown and verified codes;
right/uturn JSON support does not prove a source mapping. Do not guess missing
directions, signal distances, off-route recovery or route geometry. Shrinking
composites withdraw old facts before sending a valid subset, retaining failed
enqueue retries. Never heartbeat unchanged lane_current to renew receiver
road-category age. See docs/cpdazi-7714-data-remediation-plan.md for evidence
and remaining blockers. Keep WSL read-only and all 7706 commands unchanged.
7.9.27 may raise the engaged cruise set speed through the existing UDP 7706
SPEED command only when fresh 7000 controlsState.enabled and carControl.longActive
are both true, a same-route raw AMap current road limit in 60-120 km/h has
remained unchanged for 3 seconds, and that limit exceeds fresh vCruiseCluster.
Never auto-lower speed or use upcoming camera, section, vehicle-adopted, or smart
policy limits. Route/limit changes restart confirmation; rejected overlapping
commands are not success, successful queueing shows a user toast, and unchanged
unapplied targets may retry only after 15 seconds. Keep 7714, manual SPEED,
vehicle parameters, and lane-change behavior unchanged.
7.9.26 replaces the AR 7000 camera WebSocket/MediaCodec path with the device
WebRTC service at port 5001. POST the completed host-candidate SDP offer to
`/stream` with cameras=[road], empty bridge lists, a stable app-install client
ID, takeover=false and carrot_state=false. Receive only the H.264 road track,
whose expected source size is 964x604, through SurfaceViewRenderer. Keep the
offer-side `data` channel required by teleoprtc but do not consume vehicle state.
Release the peer on page leave, background, target/renderer changes; preserve
brief freezes and reconnect only after a sustained 20s frame stall. Surface
`carrot_vision_busy` without automatic takeover. No vehicle parameters, commands,
STUN/TURN, background camera session or 7000 video fallback.
The current worktree wires the existing WebRTC road frame tap to
`vision/LaneInference` through `ui/components/RealTimePage`; the former
`ArCameraPage` has been merged into the default Live page. The 7.9.26
no-inference description in older documentation is historical.
Its JNI implementation is `app/src/main/cpp/lanenet_jni.cpp`,
reached through `LaneNative`. Keep the capacity-one frame queue and reusable
buffers: inference runs off the WebRTC decoder thread.
`ModelLaneCountSource` now exposes estimates to `NaviStreamManager`: a model
sample must be at most 1200ms old with count 1..8 and current lane 1..count.
Valid map lane graphics take count priority; otherwise model count is used.
Only a confirmed dual-source fusion sample can supply estimated current_lane,
and only without a valid map junction graph. With a map graph, current_lane
stays -1 even when counts match; model_* diagnostics remain separate.
Independent single-edge width bands remain UI-only. A known fresh route-bound
road category is still required.
Do not describe this as display-only, verified map-lane identity or control
authority. Model/map association remains unverified. `LaneResult.atMs` is
frame-tap receipt wall time, not camera capture time. Samples are bound to host,
route generation and foreground video session, also limited by monotonic age;
target/route/renderer changes and background/page leave clear old results.
Model estimates cannot supply availability, lane directions,
route geometry or signal facts. Keep these limitations visible in protocol work.
The JNI library is a thin runtime adapter: CMake does not link `libVAE.so`;
`lanenet_jni.cpp` loads it with `dlopen`/`dlsym`. Preserve that boundary and the
packaged runtime/model compatibility rather than substituting upstream MNN
based only on matching API names.
The historical 7.9.26 native Board replaces its fixed title/summary/read-only text with one
horizontally scrollable instrument row. Show only fresh gear, cruise, actual
speed, suggested speed, adopted limit, blinking yellow turn arrows and brake
lamp; missing/expired items occupy no space and never show "unknown". Put the
value/icon above its label. Keep alerts, control/lane status, guidance,
acceleration and brake hold in the scrolling summary below; Board detail keeps
only planning/control. Move full AMap, navigation comparison and 7000
navigation/limit panels to Navigation(2). Do not repeat vehicle-state cards.
Remove accessory, device-diagnostic and positioning display snapshots/code.
The native Board still hides model/boundary, controlsState lateral-feedback and
radar target-observation details; retain those remaining snapshot/web TTLs.
7.9.25 supersedes the 7.9.2 raw-only 7714 road-limit policy: retain the raw
AMap limit for display, but send an explicitly route/receipt-bound fresh smart
policy via existing speed.road_limit_kph. The enabled highway/expressway 40->60
rule and ramp steps must reach this path, including standalone 12110 updates.
Keep section-camera limits unchanged, source TTLs/oldest receipt and route gates.
Disabling takes effect on the next valid source update; no automatic SPEED
command or vehicle parameter changes. Recent policy diagnostics are not ACKs.
Historically, 7.9.25 moves the home sidebar to the right and reduces its width from 88dp to
59dp. Tabs have a 59dp minimum height and may grow for large fonts. Preserve
wrapped connection/IP text, independent scrolling, default Board, v2 scroll
state, stable composition and zero-width collapse. The dashboard data inventory
is artifacts/dashboard_inventory.xlsx, a source-based report, not live telemetry.

## Historical feature constraints

The dated notes below are archival context, not a complete or current
implementation contract. Follow the current contracts and Architecture
sections above, and verify the source when a historical note conflicts with
current code. Do not recreate superseded classes or transports from old notes.

7.9.24 recovers MediaCodec.CodecException locally on the existing camera WS;
log diagnostics, discard damaged codec, retain same-source parameter sets and
wait for a real keyframe. Ordinary frame gaps flush/reuse the synchronous codec
once, never feed P frames while waiting, prepend cached CSD on first keyframe.
Generation-bound rendered callbacks must not renew freshness after flush/reset.
Real config/size/source changes still rebuild. Keep bounds and existing stall
watchdogs; no resolution/FPS fabrication, WebRTC or vehicle parameter changes.
The queue stays at 8 frames without DROP_OLDEST: when full, the OkHttp reader
may wait at most 500ms for consumer capacity. Never block Main, spawn per-frame
jobs or enlarge the queue. Any timed-out drop still produces a local sequence
gap and requires keyframe resynchronization.
7.9.23 uses a valid JSON ip from UDP7705 as the selected device endpoint;
warn and fall back to the sender only for missing/invalid IP. This supersedes
7.9.22 sender priority. Keep automatic target notifications and online target
stability. Sidebar shows a connection icon, original service-aware status and
selected IP above section buttons; discovery alone never means 7000 online.
Keep 88dp scrollable sidebar, zero-width collapse and existing page state.
7.9.22 removes fixed discovery hosts and old-subnet priority. UDP 7705 uses
the actual sender address, not a possibly stale advertised interface IP.
New discoveries may replace expired targets, never hijack an active selection.
Active fallback scans only current wlan/eth/ap private IPv4 local /24 candidates,
excluding self, in batches of at most 16. Verify bounded (1024-byte) HTTP 200
/api/heartbeat_status JSON with ok=true and hb; open TCP ports are not identity.
Do not scan VPN/cellular interfaces or reuse a cached phone subnet. Keep target
notifications for AR/compact/7714 and foreground-only video; no system changes.
7.9.21 separates the 10s WS/hello deadline from post-handshake input/decode
watchdogs. Hello is validated separately, never evicted by video backlog.
The bounded 8-frame DROP_OLDEST queue uses a local ingress sequence to detect
every drop even without encodeId. Backlog gaps/stale frames resynchronize the
decoder on the same WS; discard stale input and wait for a real keyframe.
Retain same-source parameter sets, 1s frozen-output withdrawal and genuine
4s input/8s decode reconnects. Log bounded close reasons and session counts,
not video payloads. Real-P-frame 1928x1208 HEVC phone fixture tests are not
proof of live comma/LAN stability. No WebRTC or vehicle/network setting changes.
7.9.20 keeps the instrument overview first, followed by navigation/speed limits,
vehicle/following, planning/control, model, accessories and diagnostics.
Render native AMap details first in navigation and move the model plot to its
own later group. Share group frames with 8dp padding/5dp gaps; service titles,
actual sources and independent freshness wrap inline. Retain all metrics,
64dp minimum cells and 18sp values. The 200dp dial grows with fontScale>=1 and
the status rail uses effective-width 1-3 columns. Do not change data or actions.
7.9.19 resynchronizes encoder gaps on the existing WS: release codec references
but retain same-source parameter sets until codec/size changes. Wait for a real
keyframe, never feed broken P-frame references. Drain output while waiting up to
500ms for decoder input; a single 10ms buffer miss is not a disconnect. Reset
retry backoff after a session lasting 10s. Keep queue bounds and stale/stall gates.
7.9.18 keeps AR on selectedIP:7000/ws/camera/road and explicitly does not use
WebRTC or /stream. Prefer cereal frameType: livestreamH264/qcameraH264 are AVC
and fullHEVC is HEVC; only infer from codec/complete parameter sets when type is
absent. Never detect the same NAL with both bit layouts and call it mixed.
MediaCodec waits for SPS/PPS/IDR for AVC or VPS/SPS/PPS/IRAP for HEVC, and resets
on codec/config/source gaps. Preserve the bounded consumer, automatic foreground
target connection, source aspect ratio, stall recovery and lifecycle release.
7.9.17 appends Guide(5), an offline read-only UsageGuidePage covering setup,
each section, projection/membership/backup and troubleshooting. Preserve Board
as default and all existing indices/scroll keys. Guide uses the existing content
scroller, pinned title and zero-width sidebar collapse; no network or actions.
7.9.16 AR uses a 1928x1208 placeholder and actual decoded/cropped source ratio
when known, centered without stretching/cropping. Keep the last ratio through
same-target stalls/reconnects. Register camera target updates when NetworkManager
is created, independently of compact initialization; replay selected IP and clear
on empty target. AR connects automatically when visible and a target is found,
never auto-navigate away from Board or keep video running off-page/background.
7.9.15 appends AR(4); Board(0) remains initial and existing section keys stay.
Only the foreground AR page opens selectedIP:7000/ws/camera/road. Camera wire
is a 4-byte big-endian JSON length, JSON metadata, then Annex-B H.264, after
hello/direct-encode-relay. Validate 2MiB packets, 16KiB metadata, size, dimensions
and H.264 codec/frameType; roadEncodeData can be HEVC and must not be guessed AVC.
Use one bounded 8-frame consumer and native MediaCodec/SurfaceView, no JPEG polling,
transcoding, inference, overlays or camera forwarding. Wait for SPS/PPS/IDR after
loss/config changes; encodeId detects gaps, segmentId is a per-frame index.
Withdraw frozen output at 1 second; reconnect on 4-second input/8-second decode
stall. Leave/background/target/surface change releases WS/codec. No global
camera subscription, vehicle parameter changes or control authority. Preserve
existing telemetry, projection and the single foreground AMap sampler.
7.9.14 puts the sidebar toggle at the left of the pinned section heading,
outside the content scroller. Collapsed sidebar occupies zero width, no rail;
expanded width is 88dp. Preserve content composition, v2 scroll state, safe
insets and 1040dp content cap. The heading always exposes the reopen button.
7.9.13 home order is Board(0), Vehicle(1), Navigation(2), Controls(3).
Board is initial. Vehicle merges former Vehicle/Planning/Results completely:
geometry, diagnostics, overtake configuration/suspension and 7714 delivery.
Use dashboard-v2 section scroll keys; preserve the collapsible sidebar,
saveable selection/collapse, content composition and the one AMap sampler.
7.9.12 removes all 7711/Xiaoge TCP input, parsers, receipts and signal/edge UI.
Vehicle inputs come only from existing 7000 compact subscriptions. Do not
replace unavailable Tesla CAN lamps/stop-line or model-meta edge distances with
navigation lamps, planner trafficState or model curves. No new raw/CAN/camera
subscription or authority. Dashboard motion summary uses carState acceleration
and hold; groups follow vehicle/control, plan/targets, navigation, diagnostics.
Connection badges require a live 7000 handshake and fresh data, never TCP fallback.
7.9.11 introduced the 88dp left sidebar; 7.9.14 removes its collapsed
rail and moves reopening to the heading. Selected section/collapse are saveable,
Board remains initial; preserve v2 per-section scroll keys and the content's
composition across collapse. Sidebar scrolls independently on short/large-font
screens; do not alter the shared sampler, data gates or actions.
7.9.10 centralizes repeated telemetry on Board. Vehicle retains experimental
7000 experimental geometry and connection diagnostics; Planning retains overtake
configuration/request suspension; Results shows 7714 delivery (not ACK).
Navigation retains map, header lamps, mode actions and route tasks; Controls
retains actions/settings. Board owns 7000, full AMap detail and
navigation comparison; native omits JSON map-* summaries when full AMap cards
render from the same snapshot. Use 8dp panel padding/5dp gap, omit repeated
helper prose, retain freshness/unknown/source and essential read-only limits.
The first home section, Board/看板, shares DashboardSnapshot with the LAN
projection player. DashboardStore holds immutable display inputs only, never
Activity references. Reuse existing compact receipts and the
single foreground AMap sampler; do not add telemetry subscriptions or a timer.
GET /dashboard has explicit JSON keys and source-specific remainingMs; browsers
subtract request RTT and expire panels independently, including during request
stalls. Do not expose coordinates, identities or credentials. Preserve embedded
preview and all map endpoints/control shapes; dashboard layout must not resize
the virtual display. All dashboard metrics are read-only, never vehicle commands.
Board defaults to an integrated instrument display, not stacked metric cards:
central actual-speed dial, flanking cruise/gear and radar values, status rail,
navigation and actual vehicle motion. At the phone's ~691x763dp viewport the normal
font overview fits above the fixed tabs; large fonts/narrow widths may scroll.
All source panels follow continuously by scrolling, without a details toggle.
DashboardRoad plots only bounded valid fresh modelV2 position/laneLines/roadEdges
in model coordinates; expiry removes curves. No extrapolation, radar fusion,
fake lanes, ego localization or camera imagery to imitate the USB cluster.
Projection and membership pages share a pinned heading and dashboard return
button outside their scrollable content.
The phone home UI uses fixed Board/Vehicle/Navigation/Controls
sections with Board selected initially. Keep section navigation in the
collapsible left sidebar, preserve independent scroll state, and keep
the single foreground AMap sampler running across section changes; navigation
content reuses its sampled snapshot. Board observations use independent 7000
carState/longitudinalPlan/radarState leadOne/carControl values and service TTLs.
Never infer
stopping, permission, safety, lane identity, control authority, or execution.
Each home section has a compact heading, purpose summary and read-only/manual
boundary label; sidebar navigation uses icon+label with clear selected state.
Preserve consistent tab widths, Board as the initial section, independent scroll
state, and all existing content/actions when refining the presentation.
VehicleDetailsGroup merges behavior, vehicle/planning and perception into one
frame, preserving all section headers, availability badges, metrics and tags.
Telemetry card padding/gap are 8dp/5dp; results gap is 8dp. Keep 64dp minimum
metrics and 18sp values without truncation or collapse. Overtake side panels
share a row only when each metric area, after the 8dp gap and 16dp side padding,
has at least 280dp effective width (fontScale at least 1); otherwise stack.
Layout changes must not alter TTL, authority or preview work.
Telemetry headers identify their actual 7000 service. Lateral metrics report
controlsState freshness independently of modelV2; lane-line use reports
lateralPlan freshness independently of longitudinalPlan. Navigation lamps
report carrotNavi reception independently of behavior. Finishing a lane change
is not completion confirmation. Test visual alignment using layout bounds,
not Button's expanded minimum-touch semantics.
OvertakeStatusCard is read-only: show fresh service-specific side-forward
radar selections, raw carState lane markings, model boundary/outer probabilities
(left 1/0, right 2/3) and blindspot tri-states. Missing is unknown, no detection
is not clearance, selections are not verified adjacent-lane vehicles. Compact
still lacks automation authority; never resume requests from these observations.
AutoOvertakeManager cancels pending state on null/incomplete authority before
lane inference, and clears direction/debounce/result/lane memory when suspended.
The WSL carrotpilot tree is read-only. AMap's bounded single-consumer queue
preserves ingress receipt time. 7714 heartbeat envelopes retain actual source
receipts; composite speed/lane streams use the oldest included receipt.
Send optional independent segment time only from the same route and guide receipt.
7714 road limits use fresh route-bound raw roadSpeedLimit, not SmartSpeedLimiter
output; local policy and 7706 are unchanged. Compact services reject repeated/old
16-bit sequences with wrap handling; model frameId must also advance. Frozen
models expire, rollback withdraws and reconnects. Vehicle-native navigation
display is not an AMap ACK; execution seconds become milliseconds, not latency.
No new camera/model/calibration subscriptions, timers or control authority.
Sparse explicitly enabled 13012 graphics have a route-bound display-only
`NaviSourceTracker.laneDisplay` snapshot. `displayJunction` may retain it beyond
10 seconds only with fresh actual guide receipts on the same ongoing route;
label it “最近路口图示 · 非实时推荐”, with previous markers and no current-action
distance. `junction`/`lanes`, driving advice, comparisons, control and 7714 retain
the strict original lane TTL. Never refresh `lane_current` from guides/polling
or admit legacy preference-restored lanes. Explicit disable/empty/invalid,
route reset, stop/source switch, guide gaps and clock rollback clear the cache.
Reject equal route-boundary timestamps and duplicate/out-of-order receipts.
Keep the existing sampler and `routeTasksReady` display gate; no new timer.
Current/next maneuver-segment panels share an equal-width pair: at least 280dp
effective card width (fontScale >=1) uses two columns, otherwise stack without
truncation. Keep current remaining time and next-segment duration/distance
distinct; next is not cumulative. Fresh/route-task gates withdraw both.
At the existing 600dp effective dashboard threshold, tapping the native preview
toggles full available content width above both columns and the normal left-pane
map. Navigation remains left; controls stay atop right-hand results. Reparent
the same movable preview, preserve safeDrawing/1040dp max and Fit/4:3 container;
never add decoder, timer, HTTP, remote touch or virtual-display resizing.
Folding/font fallback clears expansion. Settings buttons keep their own action.

AMap cruise-road cards consume display-only `NaviSourceTracker` snapshots from
explicit ICON=0, with their own 10-second TTL; show only for selected AMAP and
`isNavigating=false` (an expired still-active route must not reveal cruise).
Missing fields replace old values; stop/end/source switch/clock rollback withdraw
them. Reuse the existing 500ms sampler; never refresh navigation clocks or send
cruise data through 7714/control. Navigation speed/bearing use independently fresh
map position, not phone-position fallback. Section phases require actual 10001
camera 8/9 or valid 12110 numeric-distance flags, not average speed or raw text.
Keep section raw distance text and numeric meters distinct. Current/next facility
cards retain raw/numeric distance and raw type codes without duplicated rows.
Draw maneuver graphics from verified navigation ICON mappings, never lane
Back_icon; unknown is neutral and route-task holds withdraw the graphics.

## Build, test, and lint

Before every ADB installation to a phone, increment both versionName and
versionCode, build the updated APK, then verify the installed device version.
Keep the Jetlink badge visually aligned with ModePill: light tinted capsule,
thin outline, matching typography and top inset, no dark rectangular panel.

Run commands from the repository root. On Windows use `gradlew.bat`; on
macOS/Linux use `./gradlew`. The wrapper can be launched with JDK 17, but
`gradle\gradle-daemon-jvm.properties` requests a Java 21 daemon; setting
JAVA_HOME alone does not override those criteria. Java/Kotlin bytecode targets
JVM 11. Resolve the Android SDK from machine-local `local.properties` (`sdk.dir`);
use that SDK's `platform-tools\adb.exe` rather than assuming an IDE or SDK path.
The Android app uses SDK 35 (minSdk 31), Compose, and the version
catalog in `gradle/libs.versions.toml`. Native builds declare NDK 26.1.10909125
and CMake 3.22.1 in `app/build.gradle.kts`.

Run Gradle tests and APK builds sequentially: concurrent invocations share
Kotlin/generated outputs and have caused incremental compilation races.

```powershell
# Debug build
.\gradlew.bat assembleDebug

# Release build; requires release values in local.properties
.\gradlew.bat assembleRelease

# Static analysis
.\gradlew.bat detekt
.\gradlew.bat :app:lintDebug

# All unit tests
.\gradlew.bat test

# One test class
.\gradlew.bat :app:testDebugUnitTest --tests "com.jixiexiaoge.drivingassist.data.DashboardSnapshotTest"

# One Kotlin backtick-named test method (Gradle wildcard selector)
.\gradlew.bat :app:testDebugUnitTest --tests "com.jixiexiaoge.drivingassist.data.VehicleObservationTest.*soft hold*"

# Local embedded-map URL and listener-probe behavior
.\gradlew.bat :app:testDebugUnitTest --tests "com.jixiexiaoge.drivingassist.ui.components.LocalMapBrowserProbeTest"

# Bundled browser dashboard/player behavior (Node built-in test runner)
node --test app\src\test\js\dashboard.test.cjs app\src\test\js\player.test.cjs

# Commerce service tests (run from the repository root)
python -m unittest discover -s web\commerce\tests -p "test_*.py"
python -m unittest discover -s web\commerce\tests -p "test_commerce_app.py" -k paid_callback_grants_projection_entitlement_once

# Instrumented tests
.\gradlew.bat connectedAndroidTest

# Build instrumented APK without running device tests
.\gradlew.bat :app:assembleDebugAndroidTest

# One instrumented test class (requires a connected Android device/emulator)
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jixiexiaoge.drivingassist.ui.components.LocalMapBrowserTest
```

The default native ABI is `arm64-v8a`. Build both supported ABIs with:

```powershell
.\gradlew.bat assembleDebug -Pnavipilot.abis="arm64-v8a,armeabi-v7a"
```

`local.properties` is machine-local and must not be committed. It supplies the
Android SDK path and optional OAuth/API and release-signing values. The app
targets Java/Kotlin JVM 11. Release signing is read from
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and
`RELEASE_KEY_PASSWORD`.

Debug uses application ID `com.jixiexiaoge.drivingassist.debug`; release uses
`com.jixiexiaoge.drivingassist`. APKs are under `app/build/outputs/apk/`.
Keep R8 and resource shrinking enabled for release, the existing JNI/reflection
keep rules, core-library desugaring, and the large CarrotManFields compiler
assertion flags. `gradle.properties` sets the Gradle heap to 12GiB and R8 to
16GiB; do not reduce these to work around a build failure.

Detekt configuration is `config/detekt/detekt-config.yml`; existing whole-repo
findings can fail the task. Its reports are under `app/build/reports/detekt/`.
An APK build or test-APK build does not prove instrumented/device tests ran.

## Commerce service

- `web/commerce/` is a separate Flask service for the product site, device/license APIs, Z-Pay orders and callbacks, and release management. Its SQLite database and release files are configured with environment variables; see `web/commerce/README.md` for 1Panel deployment and required settings.
- The service uses `web/commerce/tests/` with Python `unittest`. Run its tests from the repository root with the commands above.
- Payment callbacks—not browser return pages—grant entitlements. Keep payment secrets server-side and preserve callback signature, amount, order, and idempotency checks.
- Dependencies are in `web/commerce/requirements.txt`. The deployment entrypoint
  is `commerce_app:app`, run from `web/commerce`; environment variables configure
  SQLite, release storage and payment/admin credentials. Amounts are integer
  cents and existing orders retain the amount captured when created.

## Architecture

- `MainActivity` is a coordinator. Keep shared state and business operations in
  `MainActivityCore`, Compose rendering in `MainActivityUI` and
  `MainActivityUIComponents`, and initialization/permission/cleanup logic in
  `MainActivityLifecycle`.
- The projection feature lives under `com.navipilot.amap2tesla` and supports
  only Amap Auto. The flow is
  `ProjectionService` -> Shizuku/Stellar
  `PrivilegedDisplayService` -> virtual display -> `MjpegEncoder` and
  `LatestFrameStore` -> `LocalProjectionServer`. The server serves
  `amap2tesla_player.html` at `/`, MJPEG at `/stream`, health JSON at
  `/health`, and browser control over the WebSocket. It tries ports 8080-8099
  and supports LAN and Tesla connection modes.
  `ProjectionService` publishes the bound port in `ProjectionState.serverPort`.
  `LocalMapBrowserPanel` probes only that published port, at most three times,
  requiring bounded `/health` JSON with `service=navipilot-projection`,
  `schema_version=1` and `running=true`. It loads `/?embedded=1` in WebView;
  the player uses `/frame` JPEG polling instead of external `/stream` MJPEG.
  A running projection service independently enables the map connection even
  without a fresh AMap navigation broadcast; see `RealTimePage`.
  Keep embedded clients from taking viewport ownership or resizing the shared
  virtual display. Service restart/port changes must revalidate identity;
  TCP reachability is not a service identity check.
- The comma3 flow is
  AMAP Auto broadcasts -> `AmapBroadcastManager` /
  `AmapBroadcastHandlers` -> `MainActivityCore.carrotManFields` ->
  `NaviSourceTracker` / `NaviStreamManager` / `NaviWebSocketV2Client` (7714).
  Device discovery uses UDP 7705. UDP 7706 carries only heartbeat/time, commands,
  and compatible destinations; old 7709/7712/7713 senders are disabled.
- Inbound comma3 data uses `data/CarrotWsClient` on port 7000 for
  `/ws/compact_state` (CVS1/CVB1) for vehicle/HUD data. Legacy XiaogeVehicleData
  type names are compatibility models populated by the compact adapter.
  Separately, restored `data/XiaogeBridgeClient` receives TCP 7711 length-prefixed
  JSON stop-line distance and Tesla CAN lamp color into `DashboardStore`.
  It does not feed `carrotManFields` or automation authorization. Its CAN phase
  now participates in 7714 with the independent-source priority rules above.
  The store filters these observations at 1500ms local monotonic receipt age;
  the client's read timeout is 1500ms. It validates increasing packet sequence
  and source timestamp; neither is proof of source CAN capture freshness.
  CAN lamp 3 means yellow; navigation hint 3 means left-turn green.
  `DriveViewCard` renders CAN scene/lamp and AMap navigation pills separately.
  Never attach AMap direction/countdown to CAN color or use AMap distance in
  the CAN scene: there is no verified same-signal association. AMap hints use
  fresh display/source gates and the matching red/green countdown.
  `DashboardSnapshot.driving` is a typed `DrivingDisplay` projection of the
  existing 7000 service TTLs, not parsed localized panel strings. Use adopted
  road limit only for the sign, never suggested speed. P hides cruise/limit;
  wheel and cruise colors reflect lateral and longitudinal activity separately.
  The native rear/side/top vehicle views are independent vector approximations,
  not Bevy 3D. Model road curves are clipped to actual observed 0-50m support,
  never extended or replaced with a default straight road. Band fill requires
  overlapping noncrossing inner boundaries with probability >=0.7; line alpha
  follows reported probability. Missing seatbelt/door/LDW/battery/odometer fields
  are not fabricated. See docs/dashpilot-ui-port-report.md for the port inventory.
  Compact telemetry and the foreground-only 5001 WebRTC/frame-tap path are
  separate lifecycles; model estimates enter 7714 only through the explicit
  lane-count adapter above, not telemetry or control authorization.
  The cruise button opens the selected device's HTTP 7000
  web page; without a selected device, show a connection prompt.
- Draw AMap lane direction graphics independently; do not copy third-party PNGs.
  Raw graphic emphasis and special/complex lane codes are not recommendation,
  ego localization or maneuver authorization. Retain original direction text,
  explicit unknown states, source recommendation flags and route freshness.
  Raw broadcast diagnostics are opt-in while the dialog is open: 100 packets,
  48 fields per packet, 8192 value characters per packet and 2048 characters per field, visibly truncated and
  cleared on close. Keep the bounded broadcast channel and single consumer.
  Settings backup uses a strict schema and a 64KiB limit, previews restoration,
  and includes only route mode and two bounded overtake thresholds, never
  credentials, location, device data or automation switches. Saving settings
  does not send commands; restored values take effect after a manual restart.
- `CarrotManFields` is the shared navigation/vehicle state. Its UDP 7706
  serialization contract is `CarrotManNetworkClient.build7706Payload`;
  preserve field names, units, and serialized `carrotIndex` behavior. Never
  include `nRoadLimitSpeed` in minimal packets or command keys in heartbeats.
  Track real navigation-source freshness separately from inbound telemetry.
  Compact telemetry is a subset: missing authorization/override/fault fields
  must not be treated as safe defaults for automation.

## Repository conventions

- Tapping the Live driving-card gear expands the same embedded map to the
  available safe-area content. Android Back/gesture restores the original layout
  and detail scroll position. Keep the map under one stable composition parent
  and lay it out at the measured inline anchor or full content bounds; movable
  content across subcompositions has crashed on-device. Never create another
  WebView/projection session or resize the shared display.
  While expanded, hide the SurfaceView and suspend the foreground road-video
  session/frame tap; Back resumes the existing page's video lifecycle.
  Suppress hidden content's input/semantics, including during map loading.

- Current home sections are Live/实时(0) and Controls/控制(1), with Live selected
  initially. Navigation content is merged into Live; the old sidebar is gone.
  `AdaptiveLayouts.HomeDashboardTabs` owns section/saveable state and
  `MainActivityUI` selects content. The driving-card steering wheel enters
  Controls; Controls exposes section navigation at its top left.
  Preserve saveable selection, index clamping for old saved sections and
  `dashboard-v2-*` state keys. Keep the single foreground 500ms AMap sampler
  outside section selection. `RealTimePage` owns its scrolling: the driving
  card and map/video row stay outside the detail scroller to avoid SurfaceView
  layer/clipping problems. The row is embedded local projection on the left
  and the road camera on the right, using actual video dimensions when known.
  `DashboardStore` shares immutable display inputs with the LAN
  player, never Activity references. Browser panels expire independently using
  source TTL minus request RTT; changing dashboard layout must not resize the
  projection virtual display.

- Compact metric grids use up to three columns at 420dp
  effective width, two at 280dp, otherwise one (divide by fontScale >=1).
  Keep equal-width final-row cells and at most two columns for map/vehicle pairs.
  Shorten repeated explanations, never truncate values, alerts or unknown states.
  Preserve blindspot
  and lead-status unknown/false/true distinctions; no detection is not clearance.
  Vehicle/HUD telemetry uses 7000 compact; 7711 supplies only the separate
  CAN lamp/stop-line observations above. Missing CAN observations must not be
  reconstructed from planner/model/navigation lookalikes. Reuse
  the foreground AMap sampler.
  Compare only fresh ordered route lane recommendations, never claim v2 ACK or
  execution. New planning/model/radar fields use their own service TTL, not another
  service's receipt. Radar candidates are not a unique vehicle count. Navigation
  lamp groups require explicit linked/guiding/schema/visibility/validity gates.
  Keep raw facility-distance and ETA text distinct from normalized numeric units.

- Three driving modes (`navigation/DrivingMode.kt`) are local, read-only route
  advisories, not vehicle automation. Highway/elevated prefer the second lane
  from the left within route recommendations, and regular
  roads prefer right only within fresh, ordered, direction-known AMap junction
  recommendations. Route guidance overrides side preference. Use the actual
  `AmapGuideExtras.reportedRoadType`, never legacy/default road fields or telemetry
  timestamps. Type 6 proves an expressway, not elevation or shoulder absence.
  Do not infer ego localization, mainline lane count, shoulder or lane legality.
  Mode changes persist in `MainActivityCore`, cancel pending auto lane changes,
  and must not send control commands, change vehicle parameters or relax compact
  telemetry safety gates. Preserve the existing single foreground AMap sampler.
  Automatic scene detection is default in 7.2: three distinct guide receipts
  spanning >=2 seconds, reset on unknown/stale/route changes, with manual override.
  `data/LaneGeometry.kt` exposes experimental equal-width bands using fresh model
  lines/edges at 5m and 15m and distinct increasing frames. Bands are not verified
  ego travel-lane indices, shoulder classification or maneuver permission.

- Treat `activeNavMode` as an exclusive source selector. An inactive navigation
  source must not overwrite shared navigation fields.
- AMAP broadcasts can be frequent. Preserve the bounded
  `Channel.BUFFERED`, non-blocking `trySend`, and single consumer in
  `AmapBroadcastManager`; do not introduce an unbounded queue or one coroutine
  per broadcast.
- Update `carrotManFields` from its current value with `copy(...)`. Use
  `MainActivityCore.updateFieldsSafely` for multi-step asynchronous updates
  that need mutual exclusion, and keep Compose-facing state/UI work on the
  main dispatcher.
- `CarrotManFields` is already large. Keep runtime-only values outside its
  constructor, following existing `@Transient` properties, to avoid further
  growth of generated data-class methods.
- Long-lived work owns a `CoroutineScope` with a `SupervisorJob` and must be
  cancelled by its lifecycle owner. Preserve explicit cleanup for projection,
  network, and WebSocket services.
- Do not change projection endpoint names or control message shapes without
  updating the bundled player and preserving `/stream`, `/control` WebSocket
  behavior, and `/health` compatibility.
- Use `navigation/CoordinateConverter` at map-provider boundaries. Domestic
  map inputs are GCJ-02 while coordinates sent to comma3 are WGS-84.
- Preserve clock domains: compact telemetry freshness uses the client's
  monotonic `System.nanoTime()/1_000_000` clock, while AMap snapshots carry
  ingress receipt time. Never substitute UI sampling, queue-consumption or
  heartbeat time for a source receipt. Compact service sequences use 16-bit
  wrap-aware ordering and model frameId must advance; repeated frames cannot
  renew freshness. A 7714 delivery snapshot reports successful enqueue, not
  receipt, adoption or execution by the vehicle.
- For 7714 changes, trace wire JSON through `carrot_navi_cereal.py` and
  `carrot_navi_control.py` to the actual consumer. Cereal-derived validity fields
  are not necessarily wire keys, and accepted JSON does not prove a field is
  consumed. Missing optional values can become zero/false on the receiver.
  Composite timestamps must reflect each included source fact; a partial
  broadcast must not make retained sibling values fresh. Keep AMap maneuver
  ICON, lane Back_icon and CP turn codes as separate encoding domains.
- The Compose theme in `ui/theme/Theme.kt` is intentionally a fixed automotive
  dark scheme; do not enable dynamic colors for new UI.
- Tests use JUnit 4 annotations with Google Truth, MockK, and coroutine test
  utilities where needed. Place focused tests under `app/src/test/java`;
  use the package corresponding to the production code being tested. The
  current app package is `com.jixiexiaoge.drivingassist`.
