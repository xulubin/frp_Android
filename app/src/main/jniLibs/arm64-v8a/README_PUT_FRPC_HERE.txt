How to provide the frpc binary
==============================

Place the official frp client binary in THIS directory and rename it to:

    libfrpc.so

Final path:

    app/src/main/jniLibs/arm64-v8a/libfrpc.so

Why the "lib*.so" name is required:
  Android 10+ forbids executing a binary from any writable directory.
  By naming it lib*.so and putting it under jniLibs, the build extracts it to
  ApplicationInfo.nativeLibraryDir, which is the only location allowed for exec.

Source of the binary:
  frpc inside frp_0.71.0_android_arm64.tar.gz (~16.5 MB).

NOTE (cloud build):
  The GitHub Actions workflow downloads frpc automatically and writes it here
  as libfrpc.so, so you do NOT need to commit the binary. This file is
  git-ignored; only this README should be tracked.

NOTE (local build on Windows):
  Add a Defender exclusion for this project folder first, otherwise libfrpc.so
  may be deleted as a PUA false positive.
