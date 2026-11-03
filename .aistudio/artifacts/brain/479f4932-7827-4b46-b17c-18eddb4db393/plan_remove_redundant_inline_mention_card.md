# Plan: Removal of Obsolete Inline Mention Suggestion Card

## Overview
The application currently features a floating mention suggestion bar anchored directly above the virtual keyboard with horizontal chips, avatar initials, auto-scroll, and instant 1-character search. 
Prior to this implementation, an inline dropdown card (`People you can mention` / `Tap to mention`) was embedded directly within the comment input field container. Because the floating bar above the keyboard fully replaces this functionality and avoids keyboard occlusion, the inline card is obsolete and creates visual redundancy.

## Changes
1. **WorkItemComponents.kt**:
   - Remove the inline `@Mention Autocomplete Dropdown` card (`detail_mention_suggestions_list`, lines 1353-1460).
   - Retain the clean floating mention bar above the keyboard (`floating_mention_bar`), ensuring a single, seamless, non-occluded mention insertion experience.
2. **README.md**:
   - Update discussion mention documentation to highlight the dedicated floating keyboard mention bar.
3. **Verification**:
   - Run `compile_applet` and `:app:testDebugUnitTest` to verify clean compilation and test execution.
