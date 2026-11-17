# Black Bar 2.0

Android app that displays a plain black bottom overlay only in apps selected by the user.

## Features
- Lists installed launcher apps with icons and checkboxes.
- Shows the overlay only while a selected app is in the foreground.
- Hides it when switching away from a selected app.
- Tap the bar to hide it for 2 seconds.
- Height control from 10 to 150 dp (default 40 dp).

## Permissions
The app needs:
1. Display over other apps (`SYSTEM_ALERT_WINDOW`).
2. Usage access (`PACKAGE_USAGE_STATS`) so it can detect which app is currently foregrounded.

Grant both from the app's "Настроить разрешения Android" button. Usage access is a special Android setting, not a normal runtime permission.

## Build
Use Android Studio or the included GitHub Actions workflow to build `app-debug.apk`.
