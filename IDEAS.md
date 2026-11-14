# Ideas for later

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
