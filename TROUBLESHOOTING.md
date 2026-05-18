# Troubleshooting Guide

## ClassNotFoundException: CarrotApplication after package rename

### Symptom
```
java.lang.ClassNotFoundException: Didn't find class "com.example.navipilot.CarrotApplication"
```

### Root Cause
The application crashes on startup because:
1. The APK was built with stale build artifacts from before the package rename
2. Gradle's incremental build cached old DEX files that reference the wrong package
3. Test source directories contained duplicate/conflicting package structures

### Solution

#### Step 1: Clean all build artifacts
```bash
# Remove all build outputs
./gradlew clean

# Also manually delete build directories if needed
rm -rf app/build
rm -rf build
rm -rf .gradle
```

#### Step 2: Invalidate IDE caches (if using Android Studio)
1. File → Invalidate Caches / Restart
2. Choose "Invalidate and Restart"

#### Step 3: Rebuild from scratch
```bash
# For debug build
./gradlew assembleDebug

# For release build
./gradlew assembleRelease
```

#### Step 4: Uninstall old app from device
```bash
# Uninstall via ADB
adb uninstall com.example.navipilot

# Or uninstall manually from device Settings → Apps
```

#### Step 5: Install fresh build
```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install release APK
adb install app/build/outputs/apk/release/app-release.apk
```

### Verification
After rebuilding, verify the package name is correct:
```bash
# Extract and check AndroidManifest.xml from APK
unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | \
  grep -a "com.example.navipilot"
```

### Prevention
When renaming packages:
1. Always run `./gradlew clean` before building
2. Remove duplicate package directories in test sources
3. Update all package declarations to match directory structure
4. Verify AndroidManifest.xml references the correct package
5. Check ProGuard/R8 rules use the new package name

### Related Files Checked
- ✅ `app/src/main/AndroidManifest.xml` - Correctly references `com.example.navipilot.CarrotApplication`
- ✅ `app/src/main/java/com/example/navipilot/CarrotApplication.kt` - Correct package declaration
- ✅ `app/build.gradle.kts` - namespace = "com.example.navipilot"
- ✅ `app/proguard-rules.pro` - Keep rules updated for new package
- ✅ Test directories cleaned of old `carrotamap` package

### Technical Details
The Android ClassLoader searches for classes in the DEX files packaged in the APK. When build artifacts are cached during a package rename, the DEX files may still contain references to the old package structure, causing ClassNotFoundException at runtime even though the source code is correct.
