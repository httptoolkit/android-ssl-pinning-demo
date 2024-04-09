# android-ssl-pinning-demo

> _Part of [HTTP Toolkit](https://httptoolkit.com/): powerful tools for building, testing & debugging HTTP(S)_

A tiny demo app using SSL pinning to block HTTPS MitM interception.

## Try it out

You can either clone this repo and build it yourself in Android Studio, or download the APK from the [releases page](https://github.com/httptoolkit/android-ssl-pinning-demo/releases) and install it with `adb install android-ssl-pinning-demo.apk`.

Pressing each button will send an HTTP request with the corresponding configuration. The buttons are purple initially or while a request is in flight, and then turn green or red (with corresponding icons and an error message popped up for failures) when the request succeeds/fails.

On a normal unintercepted device, every button should always immediately pass.

On a device whose HTTPS is being intercepted (e.g. with [HTTP Toolkit](https://httptoolkit.com/android/)), the unpinned buttons will pass, and then all other buttons the first 'unpinned' buttons will fail.

On an intercepted device using a standard Frida script (or similar) to automatically disable certificate pinning (e.g. https://github.com/httptoolkit/frida-interception-and-unpinning/) all buttons should pass _except_ the final "custom-pinned" button.

That final button uses low-level manual checks against the TLS connection, with no external libraries or config involved. It is still possible to make this pass too, but you'll need to do a little reverse engineering to disable that code specifically. See [this Android reverse engineering blog post](https://httptoolkit.com/blog/android-reverse-engineering/) for more details.

<img width=200 src="https://raw.githubusercontent.com/httptoolkit/android-ssl-pinning-demo/main/screenshot.png" alt="A screenshot of the app in action" />