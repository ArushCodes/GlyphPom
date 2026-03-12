# 🔴 GlyphPom

**A minimalist, hardware-integrated Pomodoro timer for the Nothing Phone ecosystem.**

GlyphPom isn't just a timer; it’s a tactile focus experience. Built using the Nothing Ketchum SDK, it utilizes the Glyph Interface to provide non-intrusive, ambient feedback on your focus sessions.

## 🛠 Features (Powered by Hardware)

* **Mathematical Pulse Sync:** During REST mode, the Glyph LEDs perform a "breathing" animation. The logic ensures that LED count updates only occur at the "trough" (the dimmest point) of the breath for a seamless visual experience.
* **Glitch Mode (Hardcore):** Integrated with the accelerometer. If you pick up your phone during a focus session, the Glyph lights will "glitch" and vibrate. Failure to place the phone back down within the grace period resets your session.
* **Wiggle to Peek:** While focused, the LEDs remain off to prevent distraction. Give the phone a small nudge to "peek" at your progress; the LEDs will fill and drain to show remaining time.
* **Flip to Pause:** Uses the Z-axis sensor to intuitively pause and resume sessions based on phone orientation.
* **Flow State:** A momentum-based mode where focus continues past 00:00 with a calm breathing animation for deep-work sessions.

## 📸 Interface
| Main Timer | Configuration |
| :--- | :--- |
| *[Add Screenshot 1]* | *[Add Screenshot 2]* |

## 🏗 Technical Stack
* **Language:** Kotlin
* **Architecture:** Foreground Service for precise timing and hardware lifecycle management.
* **Hardware SDK:** Nothing Glyph Developer Kit (`com.nothing.ketchum`).
* **Design:** Authentic N-Dot typography and 000000-black high-contrast UI.

## 🚀 Installation
1. Go to the [Releases](https://github.com/ArushCodes/GlyphPom/releases) section.
2. Download the latest `app-debug.apk`.
3. Sideload onto your Nothing Phone (Ensure "Install from Unknown Sources" is enabled).

## 🤝 Contributing
I built this as my first-ever Android app! If you're a fellow Nothing developer and want to improve the LED patterns or add support for more device models, feel free to open a Pull Request.

---
*Created by [Arush](https://github.com/ArushCodes)*
