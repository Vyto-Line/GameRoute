# Ideas for later

## Plan for 16 Nov 2026 (mockups: canvas rows 18 and 19)

1. Friends: your ID + friend code, add by code / link / QR, requests (Accept / Decline),
   list with Online / Offline, friend card (Invite, Remove, Block), "Make a new code"
   (friends stay).
2. Convoy invites to friends: one tap, Sent → Seen → Joined / Not now, ~15 min background
   check, expire after 2 h or when the convoy ends (never shown), grouped.
3. Add people you drive with: in the convoy list at any time (add button next to anyone who
   isn't a friend; no pop-ups while driving) and on the finish card ("Drove with you").
   The convoy menu's Invite button opens: "Invite friends" (who's online) or "Share the link".
4. Settings › Friends: "Show me as online", "Friends can invite me to convoys", code, blocked.
5. Settings › Backup & restore: Move to a new phone
   (QR + matching numbers; the old phone stops showing friends), backup file with password,
   restore from file. Includes friends + ID, saved places + labels, Home/Work, XP + trips,
   marked cameras, settings.
6. QR scanning with the camera (needed for "I'm the new phone" and adding friends by QR).

## Friends list (suggested 15 Nov 2026; mockups: canvas section 18)

Decided:
- Location is **never** shared with friends. Only inside a convoy you join, like today.
- The friends list shows only **Online / Offline** (no driving status, no last convoy).
  "Show me as online" can be turned off (then you always look offline).
- Add a friend by code (e.g. VYTO-4821), QR / link, or from people you just drove with
  (after a convoy).
- Friends can **invite you to a convoy** in one tap (notification: Join / Not now).
  Setting: "Friends can invite me to convoys" (off = invites wait in Friends, no notification).
- Friend card: Invite to a convoy, Remove friend, Block.

Invites (option 1, polished; mockups 18d, 18g, 18h):
- No push server. Invites arrive instantly while GameRoute is running (navigating, convoy,
  Android Auto); otherwise a WorkManager check about every 15 min posts the notification.
- The inviter sees each invite's status: Sent → Seen → Joined / Not now.
- Invites expire after 2 h or when the convoy ends, and expired ones are never shown.
- Several invites to the same convoy show as one ("3 invites").
- Later, maybe: UnifiedPush (ntfy) for instant invites with the app closed.

## When GameRoute goes on the Play Store
- Add "Automatic: Android backup" back to Backup & restore (restore on a new phone only
  works reliably for Play Store installs).
