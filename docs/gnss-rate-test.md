# GNSS rate test

The first APK answers one question: how often does the tablet's direct Android
GPS provider produce a new fix containing speed?

It requests GPS-provider locations with zero minimum interval, zero minimum
distance, high accuracy, and no batching. Those values request the fastest
available updates; they do not force the GNSS hardware to produce a particular
rate.

## What the app distinguishes

- **Callback rate** uses app arrival times.
- **Unique fix rate** uses `Location.elapsedRealtimeNanos` and excludes repeated
  timestamps.
- **Fix age on arrival** compares the monotonic fix timestamp with the app's
  monotonic arrival time.
- **Raw GPS speed** is only `Location.getSpeed()`; it is not interpolated or
  estimated.
- **Speed accuracy** is shown only when Android supplies it.

The lightweight session CSV contains timing, speed, accuracy, and bearing. It
does not contain latitude or longitude.

## Safe test procedure

1. While parked outdoors with a clear view of the sky, open **GNSS Rate Probe**.
2. Grant location access and leave **Use precise location** enabled.
3. Press **Start test** and wait for a fix.
4. Leave the screen visible for at least two minutes while stationary.
5. Stop the test while parked.
6. Start a second test before a normal 10-15 minute drive. Do not operate or
   inspect the tablet while driving.
7. Stop the test after parking and reconnect the tablet by USB.

The stationary run establishes callback cadence and timestamp behavior. The
driving run establishes whether fixes contain usable speed and speed accuracy.

## Result interpretation

- A stable unique-fix rate near 1 Hz means the estimator would need to bridge
  approximately one second between GNSS corrections.
- A unique-fix rate of 5-10 Hz may eliminate the need for inertial speed fusion.
- A callback rate above the unique-fix rate indicates repeated or otherwise
  non-new locations and must not be interpreted as faster GNSS hardware.
- High or irregular fix age suggests buffering, scheduling delay, or batching.

Session files are stored in the application's external files directory under
`Documents/gnss-sessions`. They are ignored by the repository and are removed
if the application is uninstalled.
