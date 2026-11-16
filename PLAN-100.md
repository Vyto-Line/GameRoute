# Update 100 — the plan (released as build-100, 16 Nov 2026)

Agreed with the owner on 16 Nov 2026. Mockups: the "GameRoute — UI & experience mockups" canvas, rows 31–39
(board numbers below). Built in 4 stages, each with its own test build; release 100 goes out when all 4 pass.

## Stage 1 — Driving (test-drive before going on)
1. **Missed-turns fix** — keep usable city GPS fixes (only throw away impossible jumps, not merely
   inaccurate fixes); guess the position (tunnel mode) only when there are no usable fixes; the turn box
   moves to the next turn only once this one is done.
2. **Spoken directions** (39g) — round distances at fixed moments: town 200 m + at the turn (~5 s);
   motorway 1 km, 500 m + at the exit (~6 s); spoken a little early to cover GPS and voice delay.
   "How much it talks": Normal / Fewer / Turns only. The old timing stays available as a fallback.
3. **Stick to the road** even without a route.
4. **Pick the right road** when two run side by side or at bridges (heading, speed, the road you were on).
5. **Ignore jumps between tall buildings**; the arrow stays still when stopped.
6. **Car's speed and GPS** in Android Auto when the car shares them (tunnels: real speed, not a guess).
7. **Faster lock** at start (freshest last known fix, compass heading until moving).
8. **Weak GPS note** (39h) — small amber pill under the arrow with a soft circle; phone and car.

## Stage 2 — Speed and battery
9. **Settings › Performance** (36–36c) — Auto / Smooth / Battery (Auto follows Battery Saver and < 20 %);
   switches: map effects, animations (Full / Reduced / Off), map smoothness (High 60 / Normal 30 / Low 15),
   live friend updates, save GPS when stopped; "Last drive: battery %/hour · fps".
10. Behind the scenes — street-name layout reused between frames; finished map squares kept on the phone;
    map drawn ahead along the route; smaller caches on low-memory phones; less GPS when stopped; live
    friends connection pauses with the screen off; a speed check (frame time) on every build.
11. **Arctic snow** (37) — slows when stopped, turns itself off on struggling phones, off in Battery mode.
12. **Traffic savings** — no traffic refreshes in the background; coarser squares when zoomed far out.

## Stage 3 — Friends and convoys
13. **Your status** (38–38c) — Online / Do not disturb / Invisible, "Back to Online after".
    Invisible is silent: invites to you are not delivered (sender sees ✓ Sent, it expires), but you can
    still see friends and invite. Do not disturb: invites wait silently. Convoy members always see you.
14. **Convoy quick messages** — one tap ("Need fuel", "Stopping soon", "Go ahead"), read aloud in the car.
15. **Convoy sync** — gliding arrows; same distances and order on every phone (measured along the shared
    route); "Signal lost 20 s" with a faded arrow; race start on a shared clock; route/destination changes
    reach everyone within a second.
16. **Friends and convoys in the car** (32f–g, 35–35c) — Friends button where Report was (online count),
    invites first (Join), one-tap Invite, Start a convoy, lobby on the car map (START / READY UP), convoy
    status (joined / seen / sent, Again).

## Stage 4 — The rest
17. **Prestige overhaul** (31–31c) — Bronze → Diamond, a reward per rank, +XP, friends see your rank.
18. **Your recap** (31d–e) — month / year card to share; phone only; never shows Home or Work.
19. **Milestone animations** (33–33e) — every 5 levels, in the style's look, ~3 s, tap to skip; after parking;
    level 50 leads to prestige.
20. **Pace-note turn box in every style** (34–34n) — distance + trip km, junction sketch, "EXIT 2" /
    "TURN RIGHT" above the road you turn into, next turn underneath. No "!! TIGHT", no roundabout name.
21. **Current road** (34o) — small pill under the arrow, phone and car; hidden on unnamed roads.
22. **Directions on the car's dashboard** (32c) — navigation info to the cluster / HUD / turn pop-up.
23. **Car arrival card** (32d) — full name on two lines, arrival time, no XP.
24. **Home screen widget** — Home and Work with the drive time, plus the level ring.
25. **Backup & restore** (19).
26. **What's new** (39, 39b) — a row in Settings › Advanced with a NEW tag, no pop-up.
27. **Declutter** (32, 32b) — Report button removed; Show traffic moves to Settings › Map (phone and car).

## Decided against
XP / recap / prestige icon in the car, achievements, the 100 gift, "!! TIGHT", the roundabout name in the
turn box, the phone "driving on the car screen" page, My roads map, leave-time alerts, the pace-note voice,
the drive recorder. "Might City" keeps its name (not "Night City").
