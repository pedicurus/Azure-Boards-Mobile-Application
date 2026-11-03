# Full-Screen Detail Page & Keyboard Auto-Scroll Plan

Replace the draggable half-expanded bottom sheet container for the Work Item Detail and Create views with stable full-screen pages that close exclusively via the Back or Close (`X`) buttons, and make the scrollable detail viewport keyboard-aware so focusing or typing in **Description** or **Discussion Comment** automatically scrolls the active field above the keyboard without collapsing or jumping down.

## Confirmed Decisions
- **Full-Screen Detail Presentation (No Swipe-to-Close)**: Present the Work Item Detail and Create pages as full-screen overlay pages closed only via the top-left Back button, system Back gesture, or top-right `X` button.
- **Keyboard-Aware Auto-Scroll for Description & Comments**: Apply `.imePadding()` to the outer full-screen `Column` and `weight(1f)` to the elevated content `Surface`, place `.verticalScroll(...)` before padding modifiers, and attach `BringIntoViewRequester` + focus/IME observers to both the **Description** and **Discussion Comment** inputs so they automatically scroll above the keyboard when focused and while typing.
