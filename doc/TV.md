# Android TV receiver

The TV launcher opens a remote-friendly connection screen. The regular launcher
also selects this screen when Android reports a television/Leanback device.
The existing phone UI remains available on phones and tablets.

1. Keep the phone and TV on the same local network.
2. Select **Connect with QR code**. On the phone open Developer
   options → Wireless debugging → Pair device with QR code, then scan the TV.
   Leave the dialog open while pairing and automatic connection complete.
3. Open your video app on the phone. The TV preserves the source aspect ratio.

**Other ways to connect** contains two distinct flows:
- **Use a pairing code**: copy the complete `IP address:port` and six-digit code
  from the phone's pairing-code popup. The receiver pairs and discovers the
  separate connection endpoint automatically.
- **Connect by address**: for a phone already authorized with this receiver,
  enter `IP address:port` from the main Wireless debugging screen. This also
  supports a USB-authorized legacy `phone-IP:5555` endpoint.

The home screen remembers the last phone for reconnecting. No address fields
are shown for QR setup. A failed connection-port lookup opens the address form.

## Remote controls

- All setup dialogs use large buttons with explicit Up/Down navigation and a
  visible focus highlight. OK activates buttons or opens the keyboard. Keyboard
  Done moves to the next control. Back closes a dialog.
- Playback: arrows, OK/Enter, and media playback keys are forwarded to the phone.
  Whether these navigate the phone app depends on that app's keyboard support.
- Back or Menu: opens local receiver controls, including Resume, Send Back to
  phone, and Disconnect. Back is never silently trapped by the remote phone.
- TV volume keys remain local.

Certificate rejection opens the QR pairing dialog automatically. Other pairing
and connection failures are shown on screen and can be retried. The last
connection address, port, and audio selection are remembered. Picture-in-picture
and source-driven portrait rotation are disabled on TVs.

## Manual validation

On an Android TV, verify launcher discovery, visible focus and D-pad access to
all fields/buttons, pairing, failed-connection retry, portrait and landscape
streams, audio, Back/Menu access, disconnect and reconnect. Verify normal phone
launch still opens the upstream interface. Do not interpret a successful APK
build as proof that a source app handles D-pad input.

## Picture proportions

Back/Menu → Picture proportions chooses phone display dimensions (default) or
video stream dimensions. The phone setting reads the effective `wm size` and
uses the stream orientation; it falls back to stream dimensions if unavailable.
Both modes fit within the TV with black bars as needed, and preserve pointer
coordinate mapping. The choice is remembered. If the app itself stretches the
video inside the phone screen, changing the receiver ratio alone cannot identify
and correct that video rectangle independently.
