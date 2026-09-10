# 58. Single Navigation Layout (No Tablet Rail)

## What changed

`MainAppScreen` previously switched its bottom navigation between a phone-style
`NavigationBar` (bottom) and a tablet-style `NavigationRail` (left side), based on window width
(`WindowWidthSizeClass` via `calculateWindowSizeClass`, or a raw `600.dp` breakpoint). This was a
standard Material 3 adaptive-navigation pattern, and had been deliberately built and verified live
on a tablet earlier in this project.

**The user explicitly rejected this pattern** after seeing it live on a Samsung tablet: "its not
responsive on tablet the bottom bar is moved from bottom to side... this is a mobile app which
will run on both all mobile and all tablet so correct its padding layout etc to run on both." The
product decision is: **one consistent bottom-navigation layout, on every screen size** - phone or
tablet - never a side rail. What looked like "good responsive design" from a platform-guidelines
perspective read as "the navigation moved somewhere unexpected" from the user's perspective.

## What was removed

- `MainAppScreen`'s `widthSizeClass` parameter, and the `BoxWithConstraints`/`isExpanded` logic
  that used it to pick `NavigationRail` vs `NavigationBar`.
- `MainActivity.kt`'s `calculateWindowSizeClass(this)` call - `MainAppScreen()` is now called with
  no arguments.
- `AdaptiveNavigationType` enum and `getAdaptiveNavigationType()` (`HashRouter.kt`) - existed only
  to feed this decision.
- `Breakpoints.kt` (`presentation/theme/`) - its only constant (`tablet = 600.dp`) existed only for
  this decision.
- The `androidx.compose.material3.windowsizeclass` Gradle dependency - commented out (not deleted,
  per this repo's existing convention for a dependency that might be wanted again later) in
  `app/build.gradle.kts`, since nothing else in the app uses it.

## What did NOT change

- The GST Dashboard's own separate bottom nav (Dashboard/Invoices/Returns/Reports/More) - unrelated
  to this rail/bar switch, still swaps in only while `AppRoute.GstDashboard` is active.
- The 5 main `NavigationBarItem`s (Home/Sales/Purchase/Money/Reports) and their test tags -
  untouched, same items, same tags, just always rendered via `NavigationBar` now.
- The center `FloatingActionButton` (Scan Document) - still `FabPosition.Center`, now shown on
  every screen size (previously hidden whenever the rail layout was active).

## If a future request asks for "better tablet support" again

Don't reach for `NavigationRail`/`WindowWidthSizeClass` again without checking this file and
confirming with the user first - that exact pattern was tried and explicitly rejected. If tablet
layout genuinely needs improvement, prefer scaling padding/content width within the *same*
bottom-bar structure (e.g. a centered max-width content column on very wide screens) over
introducing a second navigation paradigm.
