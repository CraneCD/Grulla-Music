# Reference screenshots

Rendered on the JVM by `app/src/test/kotlin/com/grulla/music/ui/ScreenshotTest.kt`
(Robolectric + Roborazzi) at 390×844 dp, in the Crane & Ink theme, day and night.
The score area shows real Verovio renders of the sample score (the WebView does not
run under Robolectric).

```sh
./gradlew :app:recordRoborazziDebug   # re-record after an intended UI change
./gradlew :app:verifyRoborazziDebug   # fail if the UI changed unexpectedly
```

The test JVM is Java 21 (Robolectric's Android 16 needs it); Gradle finds it as a toolchain.
