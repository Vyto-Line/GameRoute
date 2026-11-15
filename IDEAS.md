# Plans and ideas

Mockups: the GameRoute mockups canvas, rows 18 (Friends) and 19 (Backup & restore).

## Plan for 16 Nov 2026

### Release 1: Friends

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

## Later

- **Play Store**: add "Automatic: Android backup" back to Backup & restore (restore on a new
  phone only works reliably for Play Store installs).
- Maybe: UnifiedPush (ntfy) for instant invites with the app closed.
