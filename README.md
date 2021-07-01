# android-ssl-pinning-demo

> _Part of [HTTP Toolkit](https://httptoolkit.tech): powerful tools for building, testing & debugging HTTP(S)_

A tiny demo app using SSL pinning to block HTTPS MitM interception.

## Try it out

You can either clone this repo and build it yourself in Android Studio, or download the APK from the [releases page](https://github.com/httptoolkit/android-ssl-pinning-demo/releases) and install it with `adb install android-ssl-pinning-demo.apk`.

Pressing each button will send an HTTP request with the corresponding configuration. The buttons are purple initially or while a request is in flight, and then turn green or red (with corresponding icons, and an error message popped up for failures) when the request succeeds/fails.

On a normal unintercepted device, every button should always immediately go green. On a device whose HTTPS is being intercepted (e.g. by [HTTP Toolkit](https://httptoolkit.tech/android)) all except the first button will go red, unless you've used Frida or similar to disable certificate pinning.

<img width=200 src="https://raw.githubusercontent.com/httptoolkit/android-ssl-pinning-demo/main/screenshot.png" alt="A screenshot of the app in action" />