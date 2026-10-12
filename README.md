# Buddy on the go (Android, Kotlin + Jetpack Compose)

## Build the APK
1. Install the latest stable Android Studio (it bundles JDK 17+). In SDK Manager install **Android SDK Platform 35**.
2. File > Open > select this folder. Let Gradle sync (needs internet once; it creates the Gradle wrapper itself).
3. Plug in a phone (USB debugging on) and press Run, or Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.
4. Release APK: Build > Generate Signed Bundle / APK > APK. Create the keystore once and back it up in two places (plan DIST-1).
5. Before sharing: set your contact email in `app/build.gradle.kts` (`CONTACT`) for the OpenStreetMap User-Agent, and change the package name if you want (Q1). Do this before friends install.

Run unit tests: `Run > app/src/test` (geometry, triggers, route guard, streaks, parsers, network isolation).

## What is in this build
- Alarm clock: exact alarms (setAlarmClock), repeat days, skip next, snooze limits, gradual volume, hold/math/shake dismiss with easy-dismiss fallback, auto-stop with missed log, call delay, reboot/time-zone recovery.
- Shared ring pipeline: foreground RingService on the alarm stream, full-screen RingActivity, re-alert, separate journey-alert channel and patterns.
- GPS alarm: arrive / leave / pass, distance or minutes lead, accuracy-aware confirmation, adaptive sampling, backup exact alarm, heartbeat watchdog, location-off alert, tap-to-resume after reboot, lockscreen progress notification with privacy version.
- Route guard: record, GPX/KML import, corridor off-route with hysteresis, wrong direction, destination, GPS-lost quiet/ring, re-join.
- Habits (streaks, grace, heatmap, reminders with Done action), To-dos (quick add, due, repeat, reminders, search).
- Online Mode (off by default) through one NetworkGate: place search (Photon, then Nominatim) and short-link resolving; per-feature switches, monthly data counters.
- Settings, Reliability check with fixes, brand guides, Test my setup, JSON export/import (clipboard or share).

## Not in this build (be aware)
- Map screens: MapLibre pin picker, offline map regions and the offline Place Index (plan M4), Google Maps provider (M11), Follow roads routing, multi-leg journeys, active-time windows for GPS alarms, place reminders on tasks, Android 16 ProgressStyle (a standard progress notification is used), the silent auto-resume experiment (its switch is shown disabled).
- Destinations are set with pasted coordinates/links, the Share sheet, saved places, Use my location, or online search.
- Deviations from the plan, chosen to keep the code buildable: SharedPreferences instead of DataStore, manual service locator instead of Hilt, state-based tabs instead of Navigation Compose, targetSdk 35 (raise to 36 when your SDK has it), dynamic color off by default so the indigo/amber look shows.
- This code was written without being compiled in this environment. Expect to fix a few compile errors on first sync; paste them back and they are quick to resolve. Real-phone testing (Xiaomi/Redmi/Realme battery rules) is still required.
"# BuddyOnTheGo" 
