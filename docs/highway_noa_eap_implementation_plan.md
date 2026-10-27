# Highway NOA / EAP implementation plan

## Current implementation: three advisory modes (2026-10-04)

### 7.9.1 presentation-only pairing and map expansion

Current and next segment maneuvers share an equal-width two-column area at
280dp effective card width, with a single-column fallback for narrow/large-font
cards. Each retains its own distance and remaining-time/duration label; the next
segment is not cumulative. Unknown fields remain visible, and Fresh/route-task
gates withdraw the complete pair. At 600dp effective dashboard width the native
map can toggle between its left pane and available full content width above both
columns. Navigation remains left, controls top-right and results below controls.
The safe area and 1040dp content cap remain; fold/font fallback clears expansion.
The same movable preview and state are reused with Fit and a 4:3 container, no
new decode/timer/network work, virtual-display resize or browser touch changes.
These local UI gestures do not alter navigation, source TTL or control authority.

### 7.7 review and presentation cleanup

The radar/following card and radar/cut-in candidate presentation are removed.
Tri-state blindspots remain in the vehicle/planning card; compact radar ingestion
and existing safety logic are unchanged. Route recovery now rejects recycled
pre-invalidation guide packets and reordered confirmation evidence. Wall-clock
rollback clears route and scene confirmation but preserves a previously latched
vehicle off-route state, requiring fresh guiding evidence before recovery.
These are App correctness fixes, not new driving control or road validation.

### 7.6 follow-up: explainable scene and route tasks

The App-only continuity follow-up binds tasks to route generation and actual
guide receipts, with a ten-second TTL. Off-route, alerts and stale data withdraw
tasks across the task card, map maneuver/lane panels and mode recommendations.
New route generations and expiry/alert recovery require two distinct guide
packets; after vehicle-reported off-route, fresh supported 7000 guiding state is
also required. Recovery without a generation change is not labeled a confirmed
reroute. No vehicle acknowledgement or execution is inferred.

Route changes invalidate old source clocks so existing 7714 streams withdraw
old navigation/lane/speed data rather than letting unrelated updates renew them.
Position remains available. The delivery panel reports only successful enqueue
and withdrawal caches, separately from 7000 feedback and lateral-plan phases.
There is no correlated adoption/rejection result in the current interface.
Consecutive-maneuver attention is based on actual next-segment time <=30 seconds,
not guessed distance, cumulative distance or lane-clearance calculations.
Synthetic replay regressions cover missed exits, recovery, replacement routes,
expiry, disorder and clock rollback; they do not constitute road validation.
No downloaded openpilot code, camera pipeline, protocol catalog or vehicle
control permission is changed. Voice and the top-down schematic remain removed.

Automatic scene evidence now exposes distinct guide-packet count and the receipt
span, with the existing three-packet/two-second threshold. Conflicting road types
at the same receipt timestamp clear evidence. UI polling is not road evidence.
Advice has explicit hold reasons (vehicle alert, off-route, pending scene,
unavailable map, unknown road, mismatched road) and distinguishes default
preference from a route recommendation overriding it. A fresh-only task panel
shows the current maneuver, next-segment distance and exit; next-segment distance
is not cumulative distance to the following maneuver. Mode colors are consistent
across both columns; metric borders and final-row placeholders are unified.
This improves supervised route preparation and transparency, not vehicle
perception, maneuver clearance or control authority. Strict Navi v2 streams,
7706 payloads and compact-state safety gates remain unchanged.

### 7.4 follow-up: surroundings and navigation observability

The top-down schematic has been removed. The following card retains tri-state
blindspots and second-target measurements; the mode geometry card retains the
independent TCP edge distances alongside experimental compact measurements.
No detection is not clearance. Invalid/missing targets remain unknown.
Metric grids use up to three columns at 420dp effective width, two at 280dp,
otherwise one, accounting for enlarged fonts. Map/vehicle comparison stays paired
in at most two columns. Compact spacing and shorter explanations keep fields and
alerts visible without truncation or changed control behavior.

The downloaded `xiaoge_data.py` sends `modelV2.meta.distanceToRoadEdgeLeft/Right`,
**not** left/right lane widths. TCP 7711 extracts only these independent display
values and Tesla lights/stop lines. Increasing packet sequence/timestamp and
one-second local receipt TTL reject old packets but cannot prove model freshness.
Missing, reordered, disconnected or device-switched data is withdrawn. Their
definitions differ from compact boundary-to-edge geometry: no subtraction or
localization fusion, and no control authority.

AMap actions/distances and received compact recommendations are shown side by
side; fresh ordered complete lane groups can be compared, not treated as sending
ACK, adoption or execution confirmation. New compact cards expose longitudinal
behavior phase, comfort style (not road mode), cruise target/jerk, navigation lamp
groups, radar candidates, lane/edge quality and lateral quantities with per-service
TTL. Candidate arrays are not deduplicated vehicle counts. Lamp groups require
explicit session/schema/guidance/visibility/validity and do not grant right of way.
AMap extras add actual total/remaining lights, raw ETA and current/next facilities;
raw distance text is not guessed into meters. Existing subscriptions, sampler,
manual commands and strict Navi v2 catalog remain unchanged. This is supervised
route observability, not an achieved EAP/FSD capability.

### 7.2 follow-up (supersedes the 7.1 selection and side-preference description below)

Automatic advisory scene detection is now the default. Actual AMap guide packets
select highway (0), expressway/elevated-unconfirmed (6) or regular (1-5/7-9).
Require at least three distinct receipts spanning two seconds; UI polling does
not add evidence. Unknown/stale/route changes clear detected state, and a changed
road withholds old scene candidates while stabilizing. Manual selection overrides
automatic detection and persists; reselecting Automatic restores it. Automatic
results are not persisted as fresh road facts. No vehicle parameters or commands
are written. Sampling reuses the existing foreground AMap loop.

Highway and expressway now prefer **the second legal travel lane from the left**,
not the leftmost lane. Rank only actual ordered AMap recommendations by distance
to index 2 (ties prefer the smaller index); a single lane or exit recommendation
overrides that preference. Regular-road preference remains right-side legal
travel lanes. This does not identify mainline lanes or trigger maneuvers.

`data/LaneGeometry.kt` reads the already-decoded compact model lane lines and
road edges. At common x=5m and x=15m sections it interpolates x/y without
extrapolation, checks right-positive ordering and ego-straddling boundaries,
finite monotonic arrays, inner line probability >=0.7, inner line/edge std<=0.3m,
frameAge<=2, width 2.5-4.5m and explicitly fresh lateral state off. These are
experimental engineering filters, not validated probabilities or legal geometry.
Boundary-to-edge distance divided by width yields only approximate **width bands**.
Require residual <=0.2, both sections agreeing in band counts and width difference
<=0.4m; nonzero side bands require a probability/std/spacing-qualified outer line.
The bounded tracker requires three increasing model frame IDs spanning >=300ms
with <=250ms receipt gaps. Duplicate frames cannot add confirmation; invalidity,
expiry, disconnect, maneuvering, reversed frames or gaps clear the provisional
index. All processing uses the existing compact collector, not another loop.

The right-side UI separates width, edge space, provisional band index and status.
It explicitly does not treat bands as confirmed travel lanes: full-width shoulders,
restricted lanes and imperfect road-edge identification remain ambiguous. There
is no inferred left/right maneuver command or confirmed lane localization. Native
geometry metrics are not claimed to be the vehicle's exact current cross-section.
Tests cover scene debounce, manual persistence/no vehicle side effects, geometry
quality, shoulders/residuals, malformed arrays, expiry and distinct-frame history.

The long-term research goal is to improve supervised route assistance beyond EAP
toward FSD-like coverage. This is not a statement of achieved driving capability.
The implemented Android modes are **read-only route advisories**, not autonomous
vehicle-control modes. The older staged proposal below is historical planning;
its baseline paths, protocol-gap descriptions and proposed new streams must not
be treated as today's implementation or permission to extend the strict v2 catalog.

| Mode | Map category | Preference | Override |
|---|---|---|---|
| Highway EAP | Actual AMap `ROAD_TYPE=0` | Left side of route-recommended travel lanes | Exit/fork route guidance |
| Elevated NOA | `ROAD_TYPE=6` proves expressway only; elevation remains unconfirmed | Left side of route-recommended travel lanes | Fork/exit guidance; driver verifies road geometry |
| Regular NOO | Actual types 1-5, 7-9 | Right side of route-recommended travel lanes | Intersection/turn guidance and legal restrictions |

Selection is persisted under `CarrotAmap.driving_assistance_mode` with stable keys.
The default is highway **advisory**, not engagement. There is no automatic scene
switching, parameter profile write, new subscription, new inference, or command
emission. Changes cancel old pending App auto-lane changes. Existing manual
controls remain separate. No files in the downloaded openpilot tree are modified.

### Evidence from the complete local openpilot tree

Reviewed under `artifacts\openpilot\openpilot`:

- `selfdrive\controls\lib\desire_helper.py`: `LANECHANGE` feeds the existing
  blinker/intent state machine, not a route planner. Side geometry, road edges,
  blindspot/side objects, lateral activation, speed, driver inputs and trailer
  restrictions are handled in the vehicle-side state machine. A phone command is
  not proof of safe or legal adjacent-lane availability.
- `selfdrive\carrot\carrot_functions.py` and
  `selfdrive\carrot\radar_motion\lane_change_gap.py`: lane-change gap tracking is
  bounded following-distance credit after measured movement, **not** a route lane
  selector or permission to accelerate into another lane. Radar selections do not
  establish lane occupancy.
- `selfdrive\carrot\carrot_navi_control.py`: normalized navigation provides road
  category, speed, guidance, route and signal context. This does not establish a
  current-lane localizer or a complete Chinese-road three-mode behavior planner.
- `selfdrive\carrot\realtime\compact_state.py`: `NAVI_LANE_SCHEMA` contains
  present/count/distance/visible/available but no ego lane index, shoulder
  classification, legal-use restrictions or complete control authorization.
  App 7000 input remains a display subset; 7711 supplies only Tesla signals.

AMap `ROAD_TYPE=6` cannot distinguish elevated from ground-level expressways.
Typical highway 2/3/4-lane and elevated 2/3-lane layouts are contextual priors,
not measurements. Never remove the rightmost lane as a presumed shoulder:
AMap intersection recommendations are not a verified inventory of mainline
travel lanes or shoulders. Ground-road right preference must not override turn
guidance, bus/cycle/parking restrictions or actual traffic rules.

### Android module and UI

- `navigation\DrivingMode.kt`: pure road matching and map-candidate ranking.
- `AmapBroadcastHandlers` -> `AmapGuideExtras.reportedRoadType`: actual latest
  broadcast type, separate from retained legacy `roadType`/simplified `roadcate`.
  Missing/invalid values clear it; route generation and 10-second source TTL apply.
- `ui\components\DrivingModeCards.kt`: left-side selector after the brand card;
  match/unknown/mismatch states, route lane group and optional numbered candidate.
  Right-side status card after the existing controls/parameters and before HUD;
  vehicle reported lateral/longitudinal states, alerts and explicit unmet authority.
- Reuses the existing foreground-only 500ms AMap sampler and compact service TTLs.
  It does not add a broadcast coroutine, camera feed, encoder, or network loop.

Candidates require fresh selected navigation, matching (or explicitly unconfirmed
expressway) category, non-cached ordered junction lanes and identified directions
for every recommended lane. Rank only the route-recommended set. A right-only exit
recommendation wins over highway left preference, and a left-only turn wins over
regular-road right preference. Unknown/mismatched roads, stale data, source/route
changes, missing order/direction, off-route or takeover/prompt alerts withdraw the
candidate. The candidate never denotes ego lane, immediate maneuver direction,
number of required lane changes, legal availability or permission to execute.

### Remaining prerequisites before vehicle-side mode execution

1. A versioned vehicle capability/intent/acknowledgment contract negotiated with
   the installed Carrot version; do not silently add a fourteenth v2 JSON stream.
2. Verified ego-lane localization, travel/shoulder/restricted-lane classification,
   complete fresh control authority/override/fault inputs and adjacent rear gaps.
3. Vehicle-owned behavior planning with shadow logs/replay, driver-confirmed
   one-lane maneuvers and missed-exit rerouting instead of late multi-lane cuts.
4. Closed-course and supervised vehicle validation before any execution rollout.
   Unit tests and replay cannot establish closed-loop vehicle safety.

Regular NOO does not implement intersection right-of-way, pedestrian/cycle
negotiation, traffic-light control or autonomous turns. No Panda, DM, stock safety,
driver-override or actuator limits are altered.

Focused tests: `navigation.DrivingModeTest`, `DrivingModeSelectionTest`,
`AmapSpeedLimitBroadcastTest`; Compose instrumentation:
`ui.components.DrivingModeCardsTest` (requires an Android device).

## Historical staged proposal

This document describes a staged plan for building a supervised highway/high-grade-road NOA feature for Tesla HW3 vehicles using comma3/CarrotPilot plus the Android Navipilot app.

The target is **not full urban FSD**. The practical first target is closer to Tesla EAP/early NOA on controlled-access roads: lane keeping, route-aware lane preparation, driver-confirmed lane changes, exit-lane commitment, fork guidance, and conservative fallback when confidence is low.

## 1. Product boundary

### In scope for the first production-oriented track

- Highways, elevated roads, and urban expressways.
- Mainline lane keeping using the existing openpilot model and lateral planner.
- Route-aware lane preparation before exits and forks.
- One-lane-at-a-time lane changes through the existing lane-change state machine.
- Driver-confirmed lane changes first; limited automatic requests only after shadow validation.
- Exit-lane commitment when navigation and vision agree.
- Missed-exit fallback: continue safely and let the navigation app reroute.
- Logging, UI, replay, and explicit reject reasons.

### Out of scope for the first production-oriented track

- Unsupervised city-street FSD.
- Automatic unprotected left/right turns, U-turns, and ordinary intersections.
- Automatic cross-traffic negotiation.
- Traffic-light right-of-way decisions based only on phone navigation.
- Forcing late multi-lane cuts near an exit.
- Direct steering trajectories from the phone app.
- Any bypass of Panda safety, driver override, steering fault handling, or blindspot vetoes.

## 2. Current baseline

### Android app: `/mnt/c/AIcode/CPdazi`

The app already has the important navigation bridge:

- Amap Auto broadcast ingestion through `AmapBroadcastManager` and `AmapBroadcastHandlers`.
- Shared state in `CarrotManFields`.
- Legacy UDP 7706 and TCP 7709 transport through `CarrotManNetworkClient`.
- Carrot Navi v2 WebSocket transport through `NaviWebSocketV2Client` and `NaviStreamManager`.
- Amap lane broadcast parsing from `EXTRA_DRIVE_WAY`.
- Road type, TBT distance, exit/fork text, speed-limit, SDI, and route state extraction.
- comma3 state feedback through WebSocket/TCP, including model lane probabilities and blindspot state.
- Existing phone-side `AutoOvertakeManager` for advisory/command experiments.

Relevant app files:

- `app/src/main/java/com/jixiexiaoge/drivingassist/CarrotManDataModels.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/CarrotManNetworkClient.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/AmapBroadcastHandlers.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/AutoOvertakeManager.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/NetworkManager.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/navigation/NaviDataModels.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/navigation/NaviStreamManager.kt`
- `app/src/main/java/com/jixiexiaoge/drivingassist/navigation/NaviWebSocketV2Client.kt`

### CarrotPilot: `/home/jeff/op/cp`

CarrotPilot already has:

- `carrotNavi` and `carrotNaviMedia` cereal services.
- Carrot Navi v2 receiver and payload builder.
- Lane, guidance, speed, traffic signal, route, and navigation-status schema.
- Existing model lane lines, road edges, lane-change state, and lane-change direction.
- Tesla blindspot, driver override, steering-fault, and Panda safety gates.
- Side-object and blindspot-aware lane-change feasibility logic.
- Existing `LANECHANGE LEFT/RIGHT` command path through `carrotMan` into `DesireHelper`.

Relevant CP files:

- `openpilot/cereal/custom.capnp`
- `openpilot/selfdrive/carrot/carrot_navi.py`
- `openpilot/selfdrive/carrot/carrot_navi_cereal.py`
- `openpilot/selfdrive/carrot/carrot_navi_control.py`
- `openpilot/selfdrive/carrot/carrot_serv.py`
- `openpilot/selfdrive/controls/lib/desire_helper.py`
- `openpilot/selfdrive/controls/lib/desire_lib/side_state.py`
- `openpilot/selfdrive/modeld/modeld.py`
- `openpilot/selfdrive/modeld/fill_model_msg.py`
- `openpilot/selfdrive/controls/lib/lateral_planner.py`
- `openpilot/selfdrive/controls/controlsd.py`
- `openpilot/selfdrive/carrot/xiaoge_data.py`

## 3. Key technical gap

The app already receives useful Amap lane data, but the current CP-side control path does not yet have a stable NOA contract.

Current mismatch:

- App v2 lane JSON currently sends fields like `lane_count`, `recommended_lane`, and nested `lanes[]`.
- CP `carrot_navi_cereal.py` currently expects lane fields like `count`, `current_lane`, `turn_info`, `available`, `road_category`, and `voice_code`.
- CP `carrot_navi_control.py` mainly consumes road category, speed, guidance, traffic, and route fields; it does not yet convert lane guidance into route-aware lane-change intent.

Therefore, the next step is not more direct `LANECHANGE` commands from the phone. The next step is a versioned **NOA intent** data contract plus CP-side shadow planning.

## 4. Architecture direction

The phone should provide navigation intent and map-derived priors. CP should own vehicle-state validation, perception checks, lane-change safety, and final request decisions.

```text
Amap / app route and lane data
        |
        v
Android NOA intent normalizer
        |
        v
Carrot Navi v2 json/noa_intent
        |
        v
CP RouteLaneIntent parser
        |
        v
CP LaneLocalizer
        |
        v
CP NOA Behavior Planner
        |
        v
Existing DesireHelper lane-change state machine
        |
        v
Existing model path, lateral MPC, Tesla control, Panda safety
```

The app must not output steering angles or final safety decisions. It should output:

- route generation;
- road class;
- maneuver type;
- distance to exit/fork;
- lane count;
- recommended lane set;
- raw Amap values for diagnostics;
- freshness and confidence metadata.

CP must decide:

- whether NOA is available;
- whether navigation data is fresh and stable;
- whether the current lane estimate is confident enough;
- whether an adjacent lane exists;
- whether blindspot/side objects block the maneuver;
- whether there is enough distance left;
- whether to request a left or right lane change;
- when to stop attempting and declare `MISSED_EXIT`.

## 5. Android app changes

### 5.1 Add a `noa_intent` v2 stream

Add a new JSON stream to the Carrot Navi v2 catalog:

```text
json/noa_intent
```

Suggested data model:

```json
{
  "schema_version": 1,
  "source": "amap_auto",
  "route_id": "optional-stable-route-id",
  "route_generation": 12,
  "road": {
    "name": "road name",
    "road_type": 6,
    "controlled_access": true,
    "is_highway": true,
    "is_ramp": false,
    "is_toll_gate": false,
    "is_service_area": false,
    "is_construction": false,
    "confidence": 0.8
  },
  "maneuver": {
    "type": "exit_right",
    "distance_m": 1450,
    "fork_distance_m": 1300,
    "exit_side": "right",
    "main_text": "keep right to exit",
    "near_road": "exit road",
    "raw_amap_icon": 1007,
    "raw_seg_assistant_action": 1
  },
  "lanes": {
    "lane_count": 4,
    "index_order": "left_to_right",
    "current_lane_hint": -1,
    "preferred_lanes": [3],
    "recommended_lane_mask": 8,
    "lanes": [
      {
        "index": 0,
        "directions": ["straight"],
        "recommended": false,
        "available": true,
        "raw_icon": "0",
        "raw_drive_way_number": 0,
        "raw_drive_way_lane_extended": "0",
        "raw_traffic_lane_extended_new": 0,
        "raw_traffic_lane_type": 0
      }
    ]
  },
  "position": {
    "lat": 31.0,
    "lon": 121.0,
    "heading_deg": 90.0,
    "speed_kph": 80.0,
    "accuracy_m": 4.0,
    "bearing_accuracy_deg": 10.0,
    "map_matched": true
  },
  "freshness": {
    "sequence": 88,
    "source_timestamp_ms": 123456789
  }
}
```

### 5.2 Normalize Amap lane data

Use `EXTRA_DRIVE_WAY` as the first source:

- `drive_way_size` -> `lane_count`
- `trafficLaneAdvised` -> `recommended`
- `drive_way_lane_Back_icon` -> raw icon and normalized lane directions
- `drive_way_number` -> raw lane number
- `drive_way_lane_Extended` -> raw extension
- `trafficLaneExtendedNew` -> raw extension/new style
- `trafficLaneType` -> raw lane type

The app should define a stable mapping from Amap lane icons/types to normalized lane directions:

```text
straight
left
right
uturn
merge_left
merge_right
exit_left
exit_right
unknown
```

Keep all raw fields in logs and in `noa_intent` so real-road mistakes can be diagnosed.

The car-machine normally emits this payload as `KEY_TYPE:13012`. Some builds
also attach it to `KEY_TYPE:10001`; both paths must invoke the same parser.
An empty or malformed update must not erase the most recent valid lane
guidance. The HUD distinguishes no received broadcast, fresh guidance, and
held-but-stale guidance.

### 5.3 Normalize AMap Auto maneuver icons

`KEY_TYPE:10001` `ICON` is a navigation-maneuver code, not a per-lane
`EXTRA_DRIVE_WAY` icon. Keep the protocols separate. `AmapManeuver` also
separates this low-numbered car-machine broadcast table (`8=U-turn`) from
the AMap mobile SDK high-numbered `iconType` table (`34=U-turn`). The App
retains the raw icon and mapping source in
`noa_intent.maneuver.raw_amap_icon` and
`noa_intent.maneuver.raw_amap_icon_source`, and shows both the code and its meaning
in the HUD. Examples: `2=left turn`, `3=right turn`, `8=U-turn`,
`55=exit ahead`, `65=elevated keep left`, and `66=elevated exit right`.
Unknown values must remain raw/unknown rather than being guessed as a route
lane direction.

`SEG_ASSISTANT_ACTION` is a separate vehicle-broadcast field and must not use
either ICON table. The verified real-road value `4` means **keep right**. The
HUD and debug panel show this semantic value; unverified action values remain
raw and unknown until matched against an AMap display capture.

### 5.4 Add route-generation and freshness

Every route recalculation must increment `route_generation`. CP should cancel pending lane-change plans when this value changes.

Recommended generation triggers:

- navigation starts;
- route recalculates;
- destination changes;
- off-route state enters/exits;
- selected route changes;
- route polyline resets.

### 5.5 Improve road-scope classification

NOA should be available only on controlled-access roads. The app should classify:

- highway;
- urban expressway/elevated road;
- ramp;
- service area;
- toll gate;
- tunnel;
- construction/traffic-control region if available;
- ordinary city road.

Existing `ROAD_TYPE` is useful but insufficient alone. Use it together with TBT text, exit fields, route segment assistant fields, road name, and Amap-specific NOA flags if available.

### 5.5 Keep App control mode conservative

Existing `AutoOvertakeManager` can remain for UI and controlled-access-road command experiments, but it should not become the final EAP controller. It may request one existing CP `LANECHANGE` only while CP is active and no driver take-over, fault, brake, gas, or blinker condition is present; CP `DesireHelper` and `side_state` retain final side-object and feasibility authority.

Preferred mode:

- App computes and displays NOA recommendation.
- App sends `noa_intent`.
- CP decides whether to request a lane change.
- Direct App `LANECHANGE` remains a manual/debug feature, not the main NOA path.

## 6. CarrotPilot changes

### 6.1 Compatibility fix for lane schema

Short term:

- Update `carrot_navi_cereal.py` to accept both current CP-style lane objects and current app-style lane objects.
- Preserve existing `CarrotNaviState.Lane` fields for UI and backward compatibility.

Medium term:

- Add explicit `noaIntent` or a separate `CarrotNoaIntent` schema rather than overloading `laneCurrent`.

### 6.2 Add `RouteLaneIntent`

Suggested file:

```text
openpilot/selfdrive/carrot/route_lane_intent.py
```

Responsibilities:

- Parse `noa_intent`.
- Validate schema version.
- Validate freshness and route generation.
- Convert raw lane data into preferred lane masks.
- Normalize maneuver type.
- Reject unsupported roads.
- Compute basic required lane-change direction/count when enough information exists.
- Produce a reject reason when invalid.

Suggested output:

```text
valid
route_generation
source
road_type
controlled_access
maneuver_type
distance_to_maneuver_m
fork_distance_m
exit_side
lane_count
preferred_lane_mask
preferred_lanes
current_lane_hint
required_lane_changes_hint
confidence
stale
off_route
reject_reason
```

### 6.3 Add lane localization

Suggested file:

```text
openpilot/selfdrive/controls/lib/lane_localizer.py
```

First version should be simple and conservative:

- Initial lane is unknown.
- App current-lane hint is weak evidence only.
- Model road-edge distances and lane-line probabilities estimate visible lane count.
- Successful left/right lane changes update lane index.
- Route-generation change, lane-count change, ramp merge, or weak lane lines lowers confidence.
- Output a probability distribution, not only one lane number.

Suggested output:

```text
estimated_lane
lane_probabilities
confidence
lane_count
reason
```

### 6.4 Add NOA behavior planner

Suggested file:

```text
openpilot/selfdrive/controls/lib/noa_behavior_planner.py
```

Planner state:

```text
OFF
AVAILABLE
ACTIVE_CRUISE
PREPARE_LANE_CHANGE
WAIT_DRIVER_CONFIRM
EXECUTE_ONE_LANE
VERIFY_AND_RELOCALIZE
PREPARE_NEXT
EXIT_LANE_COMMITTED
RAMP_FOLLOW
MISSED_EXIT
BLOCKED
LOW_CONFIDENCE
NAV_STALE
UNSUPPORTED_ROAD
```

Planner inputs:

- `RouteLaneIntent`
- `carState`
- `modelV2`
- `radarState`
- current lane-change state/direction
- `selfdriveState`
- Tesla blindspot and steering-fault state

Planner output:

```text
request_active
direction
urgency
reason
distance_to_maneuver_m
remaining_lane_changes
confidence
reject_reason
```

### 6.5 Integrate with `DesireHelper`

Do not inject steering commands. Do not directly set MPC path. Do not fake model outputs.

The planner should request one adjacent lane change at a time through the existing lane-change state machine:

```text
NOA planner request left/right
  -> DesireHelper validates state
  -> side_state validates lane, blindspot, radar side objects
  -> model lane-change desire executes
  -> lane localizer verifies completion
  -> planner decides whether another lane change is needed
```

### 6.6 Add log and UI fields

Add enough state to diagnose real-road behavior:

```text
noaAvailable
noaActive
noaState
noaRoadConfidence
noaManeuverType
noaDistanceToManeuver
estimatedLane
estimatedLaneConfidence
preferredLaneMask
requiredLaneChanges
nextLaneChangeDirection
noaRejectReason
routeGeneration
```

These fields should appear in:

- cereal logs;
- Carrot Web live view;
- 7706/v2 debug overlay;
- route replay summaries;
- cluster/HUD only after the state is stable enough to avoid clutter.

## 7. Preparation-distance policy

Use distance budgeting instead of late decisions.

Initial conservative formula:

```text
required_distance =
  lane_changes * (ego_speed_mps * lane_change_time_s + settle_distance_m)
  + junction_freeze_distance_m
  + safety_margin_m
```

Suggested initial constants:

```text
lane_change_time_s = 7.0
settle_distance_m = 60.0
junction_freeze_distance_m = 200.0
safety_margin_m = 150.0
```

Example at 100 km/h with two right lane changes:

```text
required_distance =
  2 * (27.8 * 7 + 60) + 200 + 150
  ~= 859 m
```

If remaining distance is below the required distance, the planner should not force the maneuver. It should enter `MISSED_EXIT` or `LOW_CONFIDENCE` and allow rerouting.

## 8. Safety rules

Hard rules:

- Only controlled-access roads.
- One lane at a time.
- Re-localize after every lane change.
- Never loosen blindspot, side-object, steering, or Panda safety checks for navigation.
- Cancel on driver steering override.
- Cancel on Tesla temporary/permanent steering fault.
- Cancel on stale navigation data.
- Cancel on route-generation change.
- Cancel on missing/low-confidence lane lines.
- Cancel near toll gates, service areas, construction zones, ordinary intersections, or ambiguous merges.
- Freeze lane changes near the exit/fork.
- Prefer missed exit over late cut-in.

Tesla-specific rule:

- Tesla blindspot is a veto, not proof that the target lane is safe. It must be combined with radar side objects, model confidence, and conservative distance/time gaps.

## 9. Development phases

| Phase | Android app | CarrotPilot | Control authority |
|---|---|---|---|
| E0 | Log all Amap road/lane/exit raw fields | Store and replay raw fields | None |
| E1 | Add `json/noa_intent` | Parse and publish debug state | None |
| E2 | Add route generation and preferred lane masks | Add `RouteLaneIntent` and reject reasons | Advisory only |
| E3 | Display NOA plan in app | Add `LaneLocalizer` shadow mode | Advisory only |
| E4 | Keep app as intent source | Add `NOA Behavior Planner` shadow mode | Advisory only |
| E5 | Driver confirmation UX | Request one lane change after driver signal/confirm | Driver-confirmed |
| E6 | Stable route-generation and fork corridor | Limited automatic one-lane requests | Controlled-access only |
| E7 | Multi-lane preparation | Chain single-lane changes with re-localization | Strictly gated |
| E8 | Ramp/fork corridor validation | Shadow then limited ramp/fork assistance | Research/limited |

### Current implementation status

The following E1/E2 foundations are implemented as of the current app and CP worktrees:

- The app sends schema-v1 `json/noa_intent` with a route generation, source timestamp,
  source sequence, normalized lane-direction labels, the original AMap lane values, and
  a preferred-lane mask.
- Navigation start/stop and route-information fingerprint changes increment the app route
  generation, invalidating a previous advisory plan.
- CP publishes a typed `carrotNavi.noaIntent` diagnostic projection and uses a strict,
  advisory-only `RouteLaneIntent` parser. It rejects unsupported schemas and roads, future
  or stale timestamps, invalid lane ordering/counts/masks, invalid current-lane hints, and
  exit/fork intents without a preferred lane.
- CP now runs a shadow-only `LaneLocalizer`. It starts from a low-confidence map hint only
  when local lane-line quality is high, tracks confidence over time, updates after a completed
  existing lane change, and resets on route-generation or lane-count changes. The result is
  exported through 7711 for the App HUD, including lane probabilities and a diagnostic reason.
- CP now runs a shadow-only `NoaBehaviorPlanner` after localization. It applies the documented
  preparation-distance budget and reports `LOW_CONFIDENCE`, `BLOCKED`, `MISSED_EXIT`,
  `ACTIVE_CRUISE`, or `WAIT_DRIVER_CONFIRM` with a reason. Its `request_active` output is
  intentionally always false: it does not call `DesireHelper` or emit a lane-change command.

This is **not control authority**: the parser does not send a lane-change command and does
not alter `DesireHelper`, lateral planning, Tesla control, or Panda safety. Lane localization
and an NOA behavior planner must remain shadow-only until replay and road-test validation is
complete.

## 10. Validation plan

### Unit tests

- Amap lane-icon/type normalization.
- `noa_intent` schema serialization.
- CP parser compatibility for old and new lane JSON.
- Freshness and route-generation rejection.
- Preferred-lane-mask calculation.
- Lane-localizer confidence transitions.
- NOA planner states and reject reasons.

### Replay tests

Required scenarios:

- Right exit from left, middle, and right lanes.
- Left exit.
- Highway fork left/right.
- Ramp merge.
- Lane count changes before exit.
- Route recalculation during lane-change preparation.
- Phone disconnect.
- Amap lane data missing or contradictory.
- Model lane lines low confidence.
- Blindspot appears before and during request.
- Side radar object blocks target lane.
- Driver steering override.
- Tesla steering fault.
- Missed exit.

### Road tests

Road tests should start in shadow mode:

1. Record app NOA intent.
2. Record CP estimated lane and planned action.
3. Do not control.
4. Compare planned lane changes with what a human driver actually did.
5. Only after repeated correct shadow results enable driver-confirmed single-lane changes.

Primary metrics:

- Correct road-scope classification.
- Correct preferred lane set.
- Current-lane Top-1/Top-2 accuracy.
- Correct lane-change direction.
- False positive lane-change requests; target must be zero.
- Rejection when lane data is stale or contradictory.
- Rejection when blindspot/side object appears.
- Timely missed-exit fallback.

## 11. Highway ramp speed preconditioning

The App may derive a route-speed preconditioning suggestion from AMap's current
road limit, ramp/merge guidance, remaining maneuver distance, and model curvature.
It is a map prior, not a longitudinal controller: CP remains the only authority
that selects the final target speed, acceleration, braking, and safety fallback.

- **Mainline to exit ramp:** start at most 800m before a credible exit/ramp
  maneuver. Transition from the currently published route limit to the 55km/h
  ramp baseline in 5km/h steps. The step spacing is `800m / required steps`, so
  a 120→55 transition has 13 steps and reaches 55km/h at the maneuver point,
  rather than stopping at an unsafe intermediate value.
- **On-ramp to mainline:** start at most 800m before a credible merge/mainline
  maneuver and raise the route-speed suggestion in 5km/h steps toward the
  known mainline limit. If the upcoming higher limit is not yet available, use
  a conservative 80km/h provisional target and continue only when a fresh AMap
  mainline limit arrives.
- **Curves and degraded data:** a curve may pause an upward transition, but
  must never defer an exit-ramp reduction. Missing, stale, non-highway, or
  contradictory route data disables the transition and leaves CP's existing
  speed-source, SDI, section-speed, lead, and longitudinal-MPC protections in
  control.

## 12. Capability comparison

The realistic target is a supervised, conservative EAP/NOA subset. The table below compares Tesla EAP, the proposed Tesla HW3 + comma3 + CarrotPilot + Android app solution, and Tesla FSD.

Legend:

- `Yes`: can be implemented with the planned architecture.
- `Partial`: technically possible only under strict conditions or with driver confirmation.
- `No`: should not be implemented with this stack as an automated function.
- `Research`: possible research direction, but not a first production target.

| Capability | Tesla EAP / NOA | Proposed CarrotPilot + Android app solution | Tesla FSD | Practical implementation boundary |
|---|---|---|---|---|
| Adaptive cruise on highway | Yes | Yes | Yes | Existing openpilot longitudinal / Carrot speed logic can handle this, with Tesla safety limits preserved. |
| Lane keeping on highway | Yes | Yes | Yes | Existing model path, lateral planner, Tesla steering control, and Panda safety remain the foundation. |
| Route-aware highway navigation | Yes | Yes | Yes | App supplies Amap route/lane/exit intent; CP validates freshness, road scope, and maneuver distance. |
| Highway exit preparation | Yes | Yes | Yes | Planned `noa_intent` + CP behavior planner can prepare early and avoid late cut-ins. |
| Driver-confirmed lane change | Yes | Yes | Yes | First controllable milestone: CP requests one lane change through the existing `DesireHelper` path after driver confirmation. |
| Limited automatic highway lane change | Yes | Partial | Yes | Possible only after shadow validation, one lane at a time, with blindspot/side-object/model-confidence vetoes. |
| Multi-lane preparation before exit | Yes | Partial | Yes | Implement as chained single-lane changes with re-localization after each lane change; never execute a late multi-lane cut. |
| Highway fork guidance | Yes | Partial | Yes | Good target when Amap lane guidance, road edges, and model lane confidence agree; ambiguous forks should fall back to advisory. |
| Ramp merge assistance | Partial | Research | Yes | Requires better target-lane availability and side-object reasoning; start in shadow mode only. |
| Taking the correct exit lane | Yes | Partial / Yes after validation | Yes | Achievable on controlled-access roads when route generation is stable, lane count is reliable, and there is enough distance. |
| Overtake / slower-vehicle lane change | Yes | Research | Yes | Existing app `AutoOvertakeManager` ideas are useful, but production control should be CP-side and heavily gated. |
| Speed-limit adaptation | Yes | Yes / Partial | Yes | CP already has speed-source handling; App supplies AMap priors only, with source attribution and stale-data rejection. |
| Camera / SDI deceleration | Limited | Yes | Yes | CarrotPilot already has strong SDI/deceleration infrastructure; app data can improve attribution and UI. |
| Toll gate / service area handling | Partial | Advisory only | Yes | Should disable automated lane-change decisions near toll gates, service areas, and complex merges. |
| Urban traffic-light handling | No for EAP | Advisory only | Yes | Phone traffic-light data can inform UI or speed hints, but cannot decide right-of-way or automatic intersection crossing. |
| Stop-line stopping at city intersections | No for EAP | No / Research only | Yes | Requires reliable perception, prediction, and right-of-way planning; map/app data alone is insufficient. |
| Unprotected left/right turns | No | No | Yes | Not suitable for this stack as an automated feature. |
| U-turns | No | No | Yes | Not suitable for this stack as an automated feature. |
| Roundabouts and complex urban intersections | No | No | Yes | Requires city-driving planner and multi-agent prediction; out of scope. |
| Cross-traffic negotiation | No | No | Yes | The phone/app cannot provide sufficient real-time safety evidence. |
| Pedestrian / cyclist interaction planning | No | No | Yes | Existing openpilot perception is not an FSD city planner; keep driver responsible. |
| Parking-lot navigation / summon | No | No | Yes / Partial depending on Tesla package | Not feasible through comma3/openpilot control stack. |
| Automatic lane selection inside ordinary intersections | No | No / advisory only | Yes | Amap may know lane arrows, but CP cannot safely execute intersection path selection automatically. |
| Missed-exit reroute | Yes | Yes | Yes | Safe fallback: do not force unsafe maneuvers; let Amap reroute and re-enter planning. |
| Driver monitoring and override | Yes | Yes | Yes | Existing openpilot driver monitoring, steering override, cancel, and safety gates must remain authoritative. |
| Explainable reject reasons | Limited | Yes | Limited | CarrotPilot can expose explicit `noaRejectReason` in logs, web UI, app overlay, and replay. |
| Offline / weak-network continuation | Partial | Partial | Yes / Partial | CP can continue only with fresh cached intent for a short window; stale app/navigation data must cancel NOA. |
| Full urban FSD | No | No | Yes | The proposed solution is supervised L2 highway NOA/EAP-style assistance, not autonomous FSD. |

### What this stack can realistically deliver

The strongest achievable feature set is:

- highway and elevated-road route-aware cruising;
- early lane preparation for exits and forks;
- driver-confirmed single-lane changes;
- later, limited automatic one-lane highway lane changes;
- multi-lane preparation implemented as several safe single-lane changes;
- conservative missed-exit fallback;
- clear app/Carrot Web/debug explanations for why NOA is active, waiting, or rejected.

### What this stack should not promise

The project should not promise:

- automatic city intersection driving;
- automatic unprotected turns or U-turns;
- right-of-way decisions from Amap traffic-light/stop-line data;
- pedestrian/cyclist/cross-traffic negotiation;
- driverless or unsupervised operation;
- equivalence to Tesla FSD.

The correct comparison is:

```text
Tesla EAP/NOA:
  Mature proprietary highway navigation assistance.

CarrotPilot + Android app:
  Achievable supervised highway/high-grade-road NOA subset, with better local Amap integration and stronger transparency, but stricter safety fallback.

Tesla FSD:
  Full-stack proprietary urban/highway perception, prediction, planning, and control system. This project should not claim equivalence.
```

## 12. FSD and EAP positioning

This plan can get the project closer to **EAP/NOA behavior** on highways and elevated roads:

- navigate on highway;
- prepare for exits;
- change lanes one at a time;
- commit to exit lanes;
- assist simple forks;
- avoid unsafe late maneuvers.

It does not make the system equivalent to Tesla FSD:

- It does not use Tesla HW3 proprietary perception, occupancy, prediction, or planner outputs.
- It does not solve urban intersections, cross traffic, pedestrians, cyclists, or unprotected turns.
- It does not make right-of-way decisions from phone map data.
- It remains supervised L2 and must preserve driver responsibility.

It can reasonably aim to approximate a conservative subset of Tesla EAP/early NOA, not full FSD. The right product language is:

```text
Highway NOA / EAP-style assisted navigation for controlled-access roads.
```

Avoid language such as:

```text
Full Self-Driving
autonomous driving
driverless
city FSD
```

## 13. Recommended first implementation slice

The first code slice should be deliberately low-risk:

1. Add `noa_intent` data classes and stream in the Android app.
2. Populate it from existing Amap fields and `EXTRA_DRIVE_WAY`.
3. Add route-generation tracking in the app.
4. Update CP receiver/schema to accept `noa_intent`.
5. Add CP parser and debug output only.
6. Add replay fixtures.
7. Run several highway/elevated-road trips in shadow mode.

Only after shadow mode proves stable should the plan proceed to driver-confirmed lane changes.

## 14. Summary

The project should evolve as follows:

```text
Android app:
  navigation data, Amap lane semantics, route generation, NOA intent, UI/debug

CarrotPilot:
  validation, lane localization, NOA behavior planning, safety gates, logging/replay

Tesla/Panda:
  unchanged safety boundary and actuator enforcement
```

The safest implementation principle is:

> The app tells CarrotPilot where the route wants to go. CarrotPilot decides whether the vehicle can safely prepare for it. If confidence is insufficient, miss the exit and reroute rather than forcing a maneuver.
