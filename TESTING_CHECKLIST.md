# Znet Android closed test checklist

Use a fresh activation token from the client area for every clean install test.

## Access

- Fresh install opens the token screen.
- A valid one-time token is accepted and the app opens the main screen.
- The client area shows the new device after token activation.
- Removing the device in the client area returns the app to the token screen after restart or access refresh.
- "Обновить доступ" keeps the device session and refreshes server settings.

## Main screen

- Server card shows the flag and node name, not the IP address.
- Connection card shows protocol/transport, subscription name, days remaining, and expiry date.
- First connection shows the standard Android VPN permission dialog.
- Connect turns the main state green and 3x-ui shows traffic for the device user.
- Disconnect returns the app to the inactive state.

## Servers

- Server list opens without delay or shows a loading indicator.
- Selected node is marked clearly.
- Node cards show one line with the node name and do not repeat the country line.

## App rules

- Routing list contains installed apps and keeps user changes locally.
- Auto ON list can start VPN when a selected app is opened.
- Auto OFF list pauses VPN when a selected app is opened.
- Auto OFF has priority over Auto ON when rules overlap.
- "За границей" switches to the away behavior and can be turned off again.

## System behavior

- Znet notification appears while VPN or Auto ON/OFF service is active.
- Closing the app with a swipe does not break active VPN or automation.
- Notification text does not expose node IP addresses.
- VPN diagnostics can open Android VPN settings.

## Release sanity

- Release build is named "Znet", not "Znet Dev".
- Release APK installs, opens, accepts a token, and connects.
- The signed release APK passes `apksigner verify`.
