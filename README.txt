FOSSIL FRONTIER – Android 2.0
=================================

Original, offline, portrait-oriented fossil excavation game.

GAMEPLAY
• Tap or drag across a 9×9 excavation area.
• Break soil and rocks, find skeleton pieces, crystals and coin caches.
• Progress through increasingly difficult layers and dig sites.
• Complete five prehistoric fossil collections.
• Reconstruct dinosaur exhibits in the museum to generate passive income.
• Upgrade pickaxe, automatic drill, fossil radar and mining cart.
• Offline income for up to eight hours; local autosave.
• Interactive brushing of fossil deposits; resume cleaning after closing the app.
• 5 changing excavation biomes with new colors and environmental details.
• Digging streak bonuses, cracking soil, dust animation and gentle feedback.
• Animated museum visitors and richer pixel-art illustrations.
• Installs as Fossil Frontier Expedition, separately from the original test APK.

CONTROLS
Bottom tabs: SCAVA / UPGRADE / MUSEO.
Dig by tapping or dragging through ground tiles.
Select upgrade cards to purchase improvements.
Select museum exhibit cards to assemble completed skeletons.

TECHNICAL DETAILS
Java 17; native Android Canvas; Android Gradle Plugin 8.7.3.
Compile SDK 35, min SDK 24; portrait mode.
No external assets, network permissions, tracking, ads or in-app purchases.
Every graphic is rendered procedurally by the application.

BUILD
GitHub Actions workflow creates a debug APK.
To compile locally with Android SDK installed:
  gradle --no-daemon :app:assembleDebug

Result:
  app/build/outputs/apk/debug/app-debug.apk

The debug APK is Android-installable for testing. A commercial release
requires release signing, device testing and store preparation.

This is an original project inspired by the broad genre of fossil excavation
and museum progression games. No proprietary assets or code were copied.
