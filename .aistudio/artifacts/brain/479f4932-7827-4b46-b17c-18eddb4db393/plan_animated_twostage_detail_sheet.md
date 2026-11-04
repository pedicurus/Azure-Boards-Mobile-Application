# Two-Stage Animated Detail & Create Sheet Plan

## Overview
- **Stage 1 (Half-Open Preview)**: Opening the Detail or Create sheet animates smoothly from the bottom of the screen to a partially expanded preview (`~62%` height) over a dimmed backdrop. Scrolling or dragging upward anywhere on the sheet smoothly expands it to full screen (`100%` height) and seamlessly continues scrolling the internal content.
- **Stage 2 (Locked Full-Screen with Upper-Screen Swipe-Down Complete Dismissal)**:
  - Once fully expanded, touches starting in the lower 2/3 of the phone screen (`downY > screenHeight * 0.35f`) are strictly locked to content scrolling and never move or collapse the sheet.
  - Touches starting in the upper 1/3 (encompassing the upper 1/4 and upper 1/3) of the phone screen (`downY <= screenHeight * 0.35f`) that swipe downward are intercepted on `PointerEventPass.Initial` on the stationary outer `Box` (avoiding `.offset` coordinate-space feedback loops and child click/scroll contention), smoothly dragging the sheet downward and closing the Detail sheet completely (`onDismiss()`) upon release.
- **Keyboard Auto-Expansion & Auto-Scroll**: Focusing or typing in Title, Description, or Discussion Comment automatically expands the sheet to full height, locks it open while the software keyboard is visible, and scrolls the active input (and `@mention` bar) above the keyboard.
