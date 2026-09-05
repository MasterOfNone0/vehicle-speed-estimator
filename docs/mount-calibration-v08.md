# Mount calibration manager v0.8

The development tablet has no gyroscope, gravity sensor, linear-acceleration
sensor, magnetometer, or rotation-vector sensor. The manager therefore uses the
raw accelerometer conservatively and only changes calibration when GNSS has
established that the vehicle is stationary.

## State sequence

```text
READY
  |
  | orientation differs by at least 12 degrees
  | and a later low-speed GNSS fix confirms the car stayed stopped
  v
MOUNT_MOVED  -- output invalid; speed held at zero while stopped
  |
  | plausible orientation and low gravity noise for 2 seconds
  v
CALIBRATING  -- rotate transform if needed and settle effective bias
  |
  | plausible orientation and low gravity noise for 1 second
  v
READY
```

The candidate must exist for at least 0.75 seconds before a later trustworthy
low-speed GNSS fix can confirm it. A moving fix cancels only an unconfirmed
candidate. This distinction prevents sustained acceleration or a hard launch
between 1 Hz GNSS observations from creating a false zero-speed hold, while a
confirmed tablet movement remains invalid until it stabilizes.

Automatic transform rotation is limited to 45 degrees. Larger changes remain
in `MOUNT_MOVED` until the tablet returns to a plausible mounted position. A
brief pass through the old angle while the tablet is being handled does not
restore `READY`; the gravity signal must remain quiet.

## Hills and limitations

The manager captures its gravity anchor at the start of a confirmed stop. If the
vehicle arrives and stops on a hill, that grade is already present in the anchor
and does not trigger mount recalibration. Movement of the tablet after the stop
can trigger it once a subsequent low-speed fix confirms the vehicle stayed
stopped.

The accelerometer identifies pitch and roll changes relative to gravity. It
cannot identify tablet yaw around gravity. The adjustment applies the minimum
3D rotation between the old and new gravity vectors, preserving the established
vehicle-forward direction as far as the available sensor permits. Large yaw
changes still require straight-line driving evidence or a future explicit
calibration flow.

## Focused tablet check

1. Start **Live RealDash Output** outdoors while parked and wait for `READY`.
2. Tilt or remove the tablet while the vehicle remains stopped.
3. After the next low-speed GPS fix, confirm the app reports `MOUNT_MOVED`,
   output validity clears, and estimated speed remains zero.
4. Place the tablet in its normal mount and hold it still.
5. Confirm the state passes through `CALIBRATING` and returns to `READY`.
6. Perform one or two ordinary launches and stops. Neither launch should produce
   `MOUNT_MOVED` or a false zero-speed hold.
