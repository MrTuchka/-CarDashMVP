# Build without Android Studio

This project includes `.github/workflows/build-apk.yml`.

On GitHub:
1. Create a repository and upload the project contents.
2. Open **Actions** → **Build Android APK** → **Run workflow**.
3. After the job finishes, download artifact **CarDash-MVP-debug**.
4. Inside it is `app-debug.apk`.

The workflow installs JDK 17, Android SDK 36 / Build Tools 36.0.0 and Gradle 9.6.0, then runs `:app:assembleDebug`.
