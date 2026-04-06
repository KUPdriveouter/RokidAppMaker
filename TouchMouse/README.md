# GazeMou - Head Tracking Mouse for Rokid Glasses

Head-controlled cursor for Rokid AR glasses (RV101). Uses the built-in gyroscope to translate head movement into a mouse cursor overlay, enabling hands-free interaction on a device with no touchscreen.

**Latest: v1.6.0** | [Download APK](https://github.com/KUPdriveouter/RokidAppMaker/releases/latest)

---

## What's New in v1.6.0

> v1.5.0 was internal only. This release includes all changes from v1.5.0 and v1.6.0.

### Gesture Overhaul (from v1.5.0)
- **Selection mode** replaces circle gesture — nod down-up-down-up opens a 4-direction menu (Left=scroll, Right=dwell, Up=cursor off, Down=exit)
- **Shake to activate** — doridori (head shake) turns cursor ON when it's off. Passive sensor always listening.
- Nod x2 exits scroll/dwell mode back to normal cursor

### Awake Mode (v1.6.0)
- **Shake to wake** — doridori while screen is off wakes the display and opens the app drawer
- **Auto Cursor** option — automatically enables cursor + dwell mode on awake
- Screen-off detection: only shake (yaw) is recognized; nod (pitch) is completely blocked

### App Drawer & Favorites (v1.6.0)
- **Favorites picker** — browse installed apps, toggle favorites with tap/click
- **Split-screen UI** — top half shows ordered favorites, bottom half shows all apps
- Launcher built-in apps (Camera, Translate, AI Chat, etc.) shown with `[B]` tag
- App icons displayed alongside names
- **Reorder favorites** — grab with tap, move with swipe, drop with tap
- Cursor click support — dwell-click works on app list rows

### Auto Focus (v1.6.0)
- Cursor drifts slowly to screen center when idle (600ms threshold)
- Only active in normal cursor mode (disabled in dwell/scroll/selection)

### Shake Detection Tuning (v1.6.0)
- Moderate threshold — comfortable range, not too large
- 1.5x shake pattern (3 crossings) — left-right-left is enough
- Min 250ms interval filter — blocks false triggers from walking/vehicle vibration
- Gesture state cleared on screen off — no stale triggers on wake

---

## Controls

| Action | Gesture |
|--------|---------|
| Toggle cursor | L-L-R-R on touchpad |
| Cursor ON (when off) | Shake (doridori) |
| Selection mode | Nod down-up-down-up |
| Dwell click mode | Selection > Right |
| Scroll mode | Selection > Left |
| Cursor OFF | Selection > Up |
| Exit mode | Selection > Down / Nod x2 |
| Back button | Shake (when cursor ON + Shake Back enabled) |
| Awake | Shake (when screen off + Awake enabled) |

## Settings

| Setting | Description |
|---------|-------------|
| Accessibility | Open system accessibility settings |
| Cursor | Toggle cursor on/off |
| Speed X / Speed Y | Head tracking sensitivity |
| Hold Time | Dwell click duration |
| Cooldown | Delay before next dwell |
| Range | Dwell activation radius |
| Shake Back | Shake to go back (on/off) |
| Awake | Shake to wake screen + open apps (on/off) |
| Auto Cursor | Auto-enable cursor + dwell on awake (on/off) |
| Auto Focus | Cursor drifts to center when idle (on/off) |
| Favorites | Open app picker to manage favorite apps |

## Install

1. Download APK from [Releases](https://github.com/KUPdriveouter/RokidAppMaker/releases)
2. Sideload to Rokid glasses (via ADB or [RokidApkUploader](https://github.com/nicekid1/RokidApkUploader))
3. Settings > Accessibility > GazeMou > Enable
4. L-L-R-R on touchpad to start

## Requirements

- Rokid Glasses RV101 (YodaOS-Sprite, Android 12 / API 32)
- No Google Play Services required

## Tech Stack

- Kotlin / Android AccessibilityService
- Gyroscope sensor for head tracking
- System overlay for cursor rendering
- No external dependencies beyond AndroidX
