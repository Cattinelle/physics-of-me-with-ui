# Physics of Me

**Physics of Me** is an interactive, hands-on physics lab that turns a phone into a
measurement instrument. Users predict a result, use the phone's sensors or demo
data to measure it, compare the result with the formula, and unlock formula cards
as they complete activities.

The project is designed for mobile learning and experimentation. It is especially
suited to classrooms and self-guided lessons where a traditional physics
laboratory is not available.

## What you can explore

The activities are grouped into physics topics:

- **Mechanics:** slope acceleration, jumps, drops, lift motion, pendulums,
  circular motion, reaction time, and satellite-based running speed
- **Magnetism:** magnetic field measurements and the inverse-cube model of a
  small magnet
- **Waves and sound:** frequency and wavelength, beat frequency, and the speed
  of sound from an echo
- **Light and electromagnetic waves:** inverse-square light intensity and Wi-Fi
  signal strength
- **Electricity:** voltage, current, and battery power
- **Heat:** temperature change and cooling over time
- **Fluids and pressure:** estimating height change from barometric pressure

Each activity follows the same learning flow:

1. Set up a small real-world experiment.
2. Make a prediction.
3. Record measurements from the phone.
4. Compare the prediction, formula, and measured value.
5. Complete a short practice question and earn a formula card.

## Running the web app

Because the application is a standalone static site, it can be opened directly
or served with any local HTTP server.

From the repository root:

```powershell
python -m http.server 8000
```

Then open `http://localhost:8000` in a browser.

The web app also supports **Use demo data**, which provides realistic simulated
sensor readings for every activity. Demo data is useful for testing on a desktop
or when a phone does not expose a particular sensor.

On mobile browsers, the app can request supported motion and microphone sensors.
Sensor support varies by browser, operating system, and device. Magnetometer,
barometer, proximity, battery, Wi-Fi, and GPS experiments require the Android
application or demo data.

## Android app

The Android wrapper loads `index.html` in a `WebView` and exposes available phone
hardware through a JavaScript bridge. The app can provide:

- Accelerometer
- Gyroscope
- Magnetometer
- Light sensor
- Barometer
- Proximity sensor
- Microphone
- Battery information
- Wi-Fi signal
- GPS location and speed
- Speaker tone generation
- Screen touch input

A prebuilt APK is included as `physics-of-me.apk`.

The app asks for relevant permissions at runtime when an activity needs location
or microphone access. Optional sensors are detected at runtime, so unavailable
hardware does not prevent the app from starting.

## Building the Android APK

The APK is built without Gradle using the Android SDK command-line tools and
PowerShell. The build script expects:

- Windows
- Android SDK at `%LOCALAPPDATA%\Android\Sdk`
- Android build-tools `34.0.0`
- Android platform `android-34`
- JDK `keytool` and `javac`
- Python

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

The script:

1. Copies `index.html` into the Android assets.
2. Compiles the resources and Java bridge.
3. Builds `classes.dex`.
4. Aligns and signs the APK.
5. Writes `physics-of-me.apk` to the repository root.
6. Updates `version.json` with the app version and APK URL.

The first build creates a stable signing key at
`%USERPROFILE%\.android\physicsofme.jks`, allowing later builds to install over
the previous version. Keep this key private and back it up.

## Project structure

```text
index.html                         Web application, activities, demo data, and UI
android/src/com/visionaries/...    Android WebView wrapper and sensor bridge
android/res/                       Android icon and theme resources
android/AndroidManifest.xml        Permissions, sensor features, and app metadata
android/build.ps1                  Signed APK build script
physics-of-me.apk                  Prebuilt Android release
version.json                       In-app update metadata
```

## Measurement expectations

Phone sensors are convenient educational instruments, but they are not laboratory
grade. Results commonly vary by roughly 5–10 percent depending on calibration,
placement, sampling, and the experiment itself. The app intentionally highlights
the difference between a prediction, a formula, and a real measurement.

Use appropriate safeguards during physical experiments: add a cushion for drops
and slides, keep the phone secured, and do not attempt unsafe or distracting
activities while walking or moving through traffic.

## License

No license has been specified for this repository. Check with the project owners
before using or redistributing it outside this project.
