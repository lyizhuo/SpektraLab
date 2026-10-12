# Screenshots

The S5 acceptance asks for phone and tablet screenshots of the working canvas.
They are produced by the instrumented tests, which write them into the app's
external files directory, and pulled with

```bash
adb pull /sdcard/Android/data/com.spektralab.app/files/screenshots android/screenshots
```

Names carry the width class the test ran at (`phone-*.png`, `tablet-*.png`) so a
regression at one breakpoint is visible without opening the images. The files
are the operator's record, not build inputs: nothing reads them.
