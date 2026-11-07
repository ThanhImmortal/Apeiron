# Apeiron

Dynamic overlay and animated connection pop-up widget for Android.

[![Release](https://img.shields.io/github/v/release/ThanhImmortal/Apeiron?style=flat-square)](https://github.com/ThanhImmortal/Apeiron/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square)](https://developer.android.com)

Apeiron is an open-source Android application that displays smooth animated overlay pop-ups when connecting headphones, chargers, or peripheral devices, delivering an intuitive visual experience built around Material 3 design principles.

---

## Features

### Audio Connection Pop-up
Automatically detects and displays animated pop-up widgets when plugging in headphones or connecting audio accessories.

### Charging Overlay
Dynamic overlay feedback when connecting or disconnecting power sources.

### Performance-Oriented
Built with Jetpack Compose, optimized for the render thread, low battery consumption, and clean service lifecycle handling to prevent memory leaks.

---

## Installation

Download the latest prebuilt APK directly:

1. Go to the [Releases](https://github.com/ThanhImmortal/Apeiron/releases) section.
2. Download `Apeiron.apk` from the latest release asset list.
3. Install the APK and grant the required system permission:
Display over other apps (Overlay permission) to allow pop-up widgets to appear over other applications.

---

## Tech Stack

Language: Kotlin

UI Toolkit: Jetpack Compose (Material 3)

Platform & Build: Android SDK (API 35), Gradle Kotlin DSL (`build.gradle.kts`)

Core Components: Foreground Service, BroadcastReceiver, WindowManager Overlay

---

## Local Build

To build the APK locally from source:

1. Clone the repository:
```bash
git clone [https://github.com/ThanhImmortal/Apeiron.git](https://github.com/ThanhImmortal/Apeiron.git)
