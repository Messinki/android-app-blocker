# Telling how a blocked app was launched

Idea: treat an app differently depending on how it was opened. For example, a YouTube
link sent in WhatsApp gets a shorter hold (or none), but opening YouTube from the home
screen gets the normal hold.

The accessibility service can't see this on its own. It only learns *which* package is on
screen, never the intent that opened it (no URL, no sender). Android gives the
intent only to the app being opened. `UsageStatsManager` doesn't have it either.

There are two ways around this.

## Method 1: Link trampoline (reliable, needs setup)

The blocker catches the link first, notes it, then passes it on to the real app.

1. Add a small invisible "trampoline" activity to the blocker, with intent filters
   for the app's web links (for example `https://www.youtube.com/*` and `youtu.be`).
2. **Manual setup, once per app:** turn off **Open supported links** for YouTube.
   Then go to the blocker's **Open by default → Add link** and tick those domains.
   (Android 12+ lets you approve links for an unverified app yourself. The blocker
   can't verify youtube.com, because that needs a file on YouTube's website. The app can open
   the settings screen for you, but you have to make the change.)
3. When a link is tapped, the trampoline gets the URL, and `getReferrer()` usually
   says which app sent it.
4. The trampoline saves "link launch for YouTube at time T". Then it passes the intent
   on with `setPackage("com.google.android.youtube")`.
5. When the accessibility service sees YouTube appear within about a second of that,
   `BlockEngine` uses the link rules instead of the normal ones.

**Limits**
- Only covers real web links that Android hands out. These skip the blocker and count as a
  normal launch:
  - links opened in an app's own browser (Instagram, Gmail and others)
  - the app's own notifications
  - apps that open YouTube by package name
- Custom link types (`vnd.youtube:` and similar) can be caught too, but Android may
  ask which app to use.
- An update to the target app may turn its "Open supported links" back on.
- Needs the setup above for every app/domain.

## Method 2: Look at what was on screen before (simple, a guess)

The accessibility service remembers which app was visible just before the blocked one:

- previous = home screen → probably a normal launch
- previous = another app (WhatsApp, browser…) → probably a link or share

**Pros:** no setup, nothing extra on the phone, works for every app.
**Cons:** it's a guess. On One UI, Recents counts as the home screen, so switching
apps through Recents looks like a launch from the home screen. Notifications and in-app
browsers also make it unclear.

## Which to use

Method 1 if link launches must be told apart reliably. Method 2 if roughly right is good
enough, or as a fallback for launches that Method 1 doesn't catch.
