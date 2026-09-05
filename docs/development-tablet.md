# Development tablet baseline

Captured through read-only ADB inspection on 2026-09-02. Device serial numbers,
accounts, and location coordinates are intentionally excluded.

## Device

| Property | Value |
| --- | --- |
| Manufacturer | Alldocube |
| Model | iPlay 60 mini |
| Android | 15 |
| API level | 35 |
| CPU ABIs | arm64-v8a, armeabi-v7a, armeabi |
| Physical display | 800 x 1340 at 190 dpi |

## Location

- Android declares both general location and GPS hardware features.
- The direct `gps` provider is enabled and advertises speed, bearing, and
  altitude support.
- The GNSS implementation identifies itself as Unisoc UMW2652.
- An outdoor stationary/walking characterization session recorded 90 unique
  fixes over 88.955 seconds: **1.0005 Hz** overall with a 1.0004-second median
  interval. The central 90% of intervals spanned 0.991-1.009 seconds.
- All 90 fixes supplied `Location.getSpeed()` and speed accuracy.
- Median fix age at callback arrival was 7.9 ms, with no evidence of material
  batching.
- Median reported speed accuracy was 0.547 m/s (1.22 mph), and median
  horizontal accuracy was 2.92 m during that short session.
- The direct Android GPS provider should therefore be treated as a stable
  approximately 1 Hz velocity source on this tablet.

## Motion sensors

The only general-purpose inertial sensor exposed to applications is:

| Sensor | Vendor | Android type | Advertised rate |
| --- | --- | --- | --- |
| SC7A20H accelerometer | Silan | `TYPE_ACCELEROMETER` | 3.12-200 Hz |

The tablet does **not** expose these sensors:

- gyroscope
- magnetometer
- linear acceleration
- gravity
- rotation vector or game rotation vector

The reported device-orientation sensor is a discrete screen-orientation sensor,
not a three-dimensional attitude estimate.

## Architectural consequence

The originally proposed orientation-assisted gravity removal is unavailable on
this tablet. Phase 0 should therefore characterize direct GNSS first and measure
the raw accelerometer at several requested rates. Any accelerometer-aided speed
estimate must be treated as an experimental, fixed-mount fallback whose errors
on grades and during sustained acceleration are corrected by GNSS. The app must
remain useful in a GNSS-only mode, and the estimator must not require virtual
linear-acceleration or orientation sensors to exist.
