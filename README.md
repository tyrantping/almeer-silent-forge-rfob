# Alma Connect (v0.1)
Control your own second Android phone over the same Wi-Fi or a hotspot.

## Use
1. Install the APK on both phones (Releases page, allow "Install unknown apps").
2. Second phone: **Make this device controllable** > enable Accessibility > **Start**. Note the 6-digit code.
3. Main phone: **Control a device** > pick the device (or type its IP) > enter the code > Connect.
4. Second phone taps **Allow**. Use the touchpad and buttons.
Hotspot: join the hosting phone's hotspot; its IP is usually 192.168.43.1.

## Updating from inside the app
Open the app; if a newer GitHub Release exists, tap **Update now**. Builds are signed with the key in app/alma-release.p12, so updates install over the old app. This needs a public repo; for a private repo use **Open releases page to update** and install the downloaded APK.

## GitHub
Push this folder to a new repo. Every push builds an APK and attaches it to a Release.
For a signed release APK add Actions secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
Without them a debug APK is built.

## Not yet built
Bluetooth, QR pairing, file transfer, clipboard sync, sound streaming, pinch-zoom, landscape.

All traffic is encrypted (ECDH + AES-256-GCM, key bound to the pairing code). Version 0.4 is not compatible with older versions: update both phones.

Taps not working? On the controlled phone: Settings > Apps > Alma Connect > top-right menu > **Allow restricted settings**, then Settings > Accessibility > Alma Connect > On.

Live screen view: on the second phone tap **Share screen** and accept the Android prompt.
