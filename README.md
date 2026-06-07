# SageSMS

SageSMS is a personal, private fork of [QUIK](https://github.com/octoshrimpy/quik), which itself continues the [QKSMS](https://github.com/moezbhatti/qksms) lineage. This repository tracks ongoing development on top of that SMS/MMS client for Android.

Upstream project: [mehmetarslan/quik](https://github.com/mehmetarslan/quik) (forked from QKSMS/quik).

## SageSMS additions

Recent work in this fork includes:

- **Notification extract rules** — define search/extract patterns so notifications can surface dynamic text (for example delivery codes) from incoming messages.
- **Notification customization** — create and edit rules from a message in compose, with optional sender scope or global application.
- **Rule management** — list, edit, delete, import, and export notification rules.
- **Compose preview** — preview how a message would look in a notification under the current rules.

Inherited QUIK/QKSMS capabilities still apply: scheduled messages, backup, blocking, MMS, quick reply, swipe actions, and the rest of the upstream feature set.

## Clone

```bash
git clone https://github.com/mehmetarslan/sageSMS.git
cd sageSMS
```

## Build

Requires the Android SDK and a compatible JDK. From the repository root:

```bash
./gradlew :presentation:assembleDebug
```

Install the debug APK from `presentation/build/outputs/apk/debug/` on a device or emulator with SMS permissions.

## Reporting issues

This is a personal fork. Issues are best reported in your own notes or upstream projects when the bug is not specific to SageSMS changes.

When reporting a SageSMS-specific issue, include:

- Steps to reproduce
- App version / commit hash
- Device and Android version

## Thank you

SageSMS builds on work by many contributors across the QKSMS and QUIK projects.

A special thank you to Jake ([@klinker41](https://github.com/klinker41)) and Luke Klinker ([@klinker24](https://github.com/klinker24)) for [android-smsmms](https://github.com/klinker41/android-smsmms), which powers MMS support.

Thank you to Moez ([@moezbhatti](https://github.com/moezbhatti)) for creating and maintaining QKSMS, and to [Marcos Jones](https://github.com/octoshrimpy) and the QUIK community for continuing that work in QUIK.

## License

SageSMS is released under **The GNU General Public License v3.0 (GPLv3)**, as inherited from QKSMS/QUIK. See the [LICENSE](LICENSE) file in the repository root.
