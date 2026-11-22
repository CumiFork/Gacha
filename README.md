# Floating Root Control

Kotlin Android app with a root shell status page and a draggable overlay control panel. Built with libsu 5.2.2, Gradle 8.7, AGP 8.6.1, and GitHub Actions. No Android Studio required.

## What is inside

- `MainActivity`: root shell check, kernel and `su` path readout, overlay permission card, start and stop controls, last command output.
- `FloatingMenuService`: foreground service with `specialUse` type, draggable bubble, draggable panel header, minimize and close controls, async root actions, custom command input.
- `RootApp`: sets the libsu default builder with an explicit application context before any shell is created.
- `.github/workflows/build.yml`: builds `app-debug.apk` on push to `main` and on manual dispatch.

## Push and build

From this directory:

```bash
git init -b main
git add .
git commit -m "Initial floating root control project"
git remote add origin <your-repository-url>
git push -u origin main
```

The workflow uses JDK 17 Temurin and Gradle 8.7 from `gradle/actions/setup-gradle@v4`. There is no `gradlew` or `gradle-wrapper.jar` in the repository. The APK is uploaded as artifact `app-debug-apk`.

## Run on device

1. Install the debug APK from the Actions artifact.
2. Grant root access to the app through your root manager.
3. Open the app and tap **Grant overlay permission**, then return to the app.
4. Tap **Start menu**, then tap the `ROOT` bubble to open the panel.
5. Use the built in actions or type a shell command and tap **Run command**.

The overlay window uses `TYPE_APPLICATION_OVERLAY`. Android 14 and 15 need the `FOREGROUND_SERVICE_SPECIAL_USE` permission, the `specialUse` service type, and the special use subtype property. All three are declared in the manifest and set when the foreground service starts.

## Adding a root action

Add one line to the `actions` list in `FloatingMenuService.kt`:

```kotlin
RootAction("Label", "command passed to the root shell")
```

The panel creates the button at runtime, so no layout edit is needed.

## Notes

- `Drop RAM cache` writes to `/proc/sys/vm/drop_caches`. Some SELinux policies block that write even with root, and the shell error appears in the output box.
- Overlay text input temporarily clears `FLAG_NOT_FOCUSABLE` so the soft keyboard can type into the overlay.
- The app is a root utility. Only install it on a device you control.
