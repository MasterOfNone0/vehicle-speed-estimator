# Accelerometer rate and noise test

The accelerometer probe measures the tablet's actual Android sensor-event rate,
timestamp timing, delivery delay, stationary gravity vector, and axis noise. It
does not estimate vehicle speed.

## Stationary test

Run three separate sessions with the tablet completely still in its intended
vehicle mount:

1. Select **50 Hz**, start the test, wait 60 seconds, then stop.
2. Select **100 Hz**, start the test, wait 60 seconds, then stop.
3. Select **200 Hz**, start the test, wait 60 seconds, then stop.

Do not touch the tablet while a session is running. Start and stop each test
while the vehicle is parked. The session standard deviation then represents
stationary sensor noise plus vehicle/environment vibration.

## Optional movement test

Use a separate 100 Hz session for a few slow, gentle forward/backward tilts or
translations. Mixing deliberate motion into a stationary session invalidates
its noise calculation.

## Measurements

- **Actual event rate** uses monotonic `SensorEvent.timestamp` values.
- **Sensor interval / jitter** reports the mean interval, interval standard
  deviation, and maximum interval.
- **Delivery delay** compares the sensor timestamp with
  `SystemClock.elapsedRealtimeNanos()` on callback arrival.
- **Session mean / gravity vector** shows the average X/Y/Z acceleration and its
  magnitude.
- **Session axis standard deviation** shows X/Y/Z variability over the entire
  session and is meaningful as noise only when the tablet remains still.

Each session writes a lightweight CSV with timestamps, requested rate, Android
accuracy status, raw X/Y/Z acceleration, and magnitude. It contains no location
or identifying device information.
