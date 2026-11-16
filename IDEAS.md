# Plans and ideas

Mockups: the GameRoute mockups canvas, rows 18 (Friends) and 19 (Backup & restore).

## Plan for 16 Nov 2026

### Release 1: Friends · done in build-79

1. **Your ID and friend code** (e.g. VYTO-4821). The friendship is tied to a hidden permanent ID,
   not the code. "Make a new code": friends stay, old links/QR stop working.
2. **Add a friend** (18b): show your QR + "Send link", type their code, or pick someone you drove
   with. They get a request: Accept / Decline. Nothing is shared until they accept.
3. **Friends list** (18): your code + Share, requests on top, then Online / Offline only
   (no location, no driving status, no last convoy). Invite button on each friend.
4. **Friend card** (18c): name, Online, "Friends since…", Invite to a convoy, Remove friend, Block.
5. **Convoy invites** (18d, 18g, 18h):
   - one tap; the friend gets a notification + pop-up: Join the convoy / Not now;
   - instant while GameRoute is running (navigating, convoy, Android Auto), otherwise a background
     check about every 15 min (WorkManager), no push server;
   - the inviter sees Sent → Seen → Joined / Not now;
   - invites expire after 2 h or when the convoy ends, and expired ones are never shown;
   - several invites to the same convoy show as one ("3 invites").
6. **Add people you drive with:**
   - convoy menu (18j): add button next to anyone who isn't a friend; Invite opens
     "Invite friends" (shows who's online) or "Share the link";
   - during the trip (18i): the same add buttons in the convoy list, no pop-ups while driving
     (requests wait until the other person stops);
   - finish card (18e): "Drove with you" with Add buttons.
7. **Settings › Friends** (18f): "Your location is never shared with friends" note,
   "Show me as online", "Friends can invite me to convoys", my code, make a new code, blocked.
8. Greek text for all of it, tests, release.

### Release 2: Backup & restore

9. **Settings › Backup & restore** (19): Move to a new phone, I'm the new phone, Save a backup
   file (password), Restore from a file. Includes friends + ID, saved places + labels,
   Home / Work, XP + trip history, cameras you marked, settings.
10. **Move to a new phone** (19b): the old phone shows a QR + matching numbers; the new phone scans
    it; the old phone stops showing your friends afterwards.
11. **QR scanning with the camera**: for "I'm the new phone", and adding friends by QR.

## Rules decided

- Location is **never** shared with friends: only inside a convoy you join, as today.
- Positions stay end-to-end encrypted, like convoys.

## Next (planned 16 Nov 2026, mockups rows 25–27)

1. ~~**Violet Rain** map style (27)~~ · done in build-95.
2. **Declutter** (26): a round More button first (26j, option B), then Home, Work + the 2
   places you go to most at this hour; More opens a sheet (fuel, parking, chargers, start or join
   a convoy, saved places); while driving only turn, speed and arrival
   (buttons back for 5 s on a tap); non-urgent messages wait in a "for when you stop" counter
   (safety and convoy news still show; convoy invites still pop up in the car); short arrival card
   (XP as one line); settings search + Advanced group.
3. **XP and levels** (25): XP for new roads, clean drives, convoys, race places (only if you kept
   near the limits), daily streak, weekly challenges. Levels unlock cosmetics in a Garage
   (arrow, route colour, destination flag, arrival effect; no trails), a title ladder per style,
   a milestone every 10 levels, prestige at 50. Level ring on the home screen, Progress and Garage
   in the menu. Level-up only after you park.
4. **More map styles** (28), one at a time after Violet Rain: Rally Roadbook (pace-note turn
   cards), Blueprint, Nautical Chart, Arctic (bright day style), High Contrast. Not Comic Book
   (too busy, confusing while driving). Every style draws buildings in its own way.
5. **You screen** (26c, later): one button for progress, Garage, friends, trips and settings;
   friend request on top, friends' circles (online/offline only); tapping a friend (26h): Invite to
   a convoy (starts one if needed), Rename, Remove, Block, "Friends since · N drives together".

## Later

- **Play Store**: add "Automatic: Android backup" back to Backup & restore (restore on a new
  phone only works reliably for Play Store installs).
- Maybe: UnifiedPush (ntfy) for instant invites with the app closed.
- The illegal right turn from the test drive: check the spot once we know where it was.
