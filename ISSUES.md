# MeterSnap — Known Issues & Suggested Fixes

> Last updated: 2026-05-07

---

## Issue 1 — App Name Still Shows "Mete:Read"

**Observed:** The top bar and launcher show "Mete:Read" instead of the intended "METEREAD".

**Root Cause:**
`app/src/main/res/values/strings.xml` line 2:
```xml
<string name="app_name">Mete:Read</string>
```

**Fix:**
Change to:
```xml
<string name="app_name">METEREAD</string>
```

**Files affected:**
- `app/src/main/res/values/strings.xml`

---

## Issue 2 — Instruction Text Appears Dimmed

**Observed:** The text "Center the meter display and press Snap" on the camera screen looks dimmed/faded — hard to read clearly.

**Root Cause (confirmed from code):**
The `tv_instructions` TextView has `android:elevation="5dp"`, but `MeterGuideBoxView` (`meter_guide_box`) has `android:elevation="6dp"` and covers the entire screen. Its `onDraw()` paints a **semi-transparent black vignette** (`Color.argb(140, 0, 0, 0)` — about 55% opacity) over the area *above* the guide box. Since the instruction text sits in the upper 20% of the camera area (right where that top vignette is drawn), it gets visually suppressed by the overlay.

**Fix:**
Raise `tv_instructions` elevation above the guide box (6dp) so it renders on top of the vignette. Set it to `8dp` in `activity_main.xml`:
```xml
android:elevation="8dp"   <!-- was 5dp -->
```

**Also:** Change the instruction text to something clearer:
```xml
android:text="Make sure the meter numbers are visible, then press Snap"
```
*(or any variation along: "Align meter numbers inside the box and press Snap")*

**Files affected:**
- `app/src/main/res/layout/activity_main.xml` — elevation & text
- `app/src/main/java/com/kokeshi/metersnap/MainActivity.kt` — wherever `tv_instructions` text is reset in code (lines ~354, 575, 634, 693, etc.) — update those hardcoded strings too

---

## Issue 3 — Tap-to-Focus Is Offset / Biased Downward

**Observed:** When tapping the camera view to focus, the actual focus point lands noticeably below where the finger touched.

**Root Cause (likely):**
In `MainActivity.kt → performTapToFocus()`, the coordinate conversion uses `getLocationOnScreen()` for both `gestureOverlay` and `previewView`. The `previewView` lives inside `camera_container` which fills the entire screen including under the status bar and top bar. If the activity runs edge-to-edge (which it does — `camera_container` is unconstrained `match_parent`), `previewView.getLocationOnScreen()[1]` returns 0. But `gestureOverlay.getLocationOnScreen()[1]` correctly includes the status bar height.

The focus ring uses the *root view* offset which correctly places the ring where the finger was. But the metering point calculation sends coordinates offset by the status bar height into the PreviewView factory — pushing the real focus point downward.

```kotlin
// Current code (MainActivity.kt ~line 1527)
val previewX = (x + overlayLoc[0] - previewLoc[0])
val previewY = (y + overlayLoc[1] - previewLoc[1])
```

If `previewLoc[1] = 0` (edge-to-edge) and `overlayLoc[1] = statusBar + topBar (~80–100px)`, the metering Y is shifted down by that amount.

**Fix:**
Factor out the window insets so the translation is purely between the gesture area and the metering factory's expected coordinate space. The safest fix is to compute the tap position directly in PreviewView-local coordinates by converting through `ViewCompat.getRootWindowInsets()`:

```kotlin
private fun performTapToFocus(x: Float, y: Float) {
    val previewView = activePreviewView ?: return
    val cam = camera ?: return

    // Convert gesture_overlay-local coords to screen coords
    val overlayLoc = IntArray(2)
    gestureOverlay.getLocationOnScreen(overlayLoc)

    val screenX = x + overlayLoc[0]
    val screenY = y + overlayLoc[1]

    // Convert screen coords to previewView-local coords
    val previewLoc = IntArray(2)
    previewView.getLocationOnScreen(previewLoc)
    val previewX = (screenX - previewLoc[0]).coerceIn(0f, previewView.width.toFloat())
    val previewY = (screenY - previewLoc[1]).coerceIn(0f, previewView.height.toFloat())

    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(previewX, previewY)
    // ... rest unchanged
}
```

*(The key change: compute `screenX/Y` first, then subtract previewLoc — rather than adding overlayLoc and subtracting previewLoc in a single step, which is equivalent but makes the intent clearer and easier to debug.)*

If the bias persists after this, the next step is to log `overlayLoc`, `previewLoc`, and `previewView.width/height` at tap time to verify coordinates on a real device.

**Files affected:**
- `app/src/main/java/com/kokeshi/metersnap/MainActivity.kt` — `performTapToFocus()` (~line 1514)

---

## Issue 4 — App's "Previous Reading" Can Be Wrong With No Way to Edit It

**Observed:**
The app's "Previous Reading" displayed in the bill result is the **Month-End projection of the previous month** (stored by `ProjectedReadingManager`), not the actual value from the utility receipt. In real-life testing:
- App's Previous Reading: **1740** (projected)
- Actual receipt Previous Reading: **1741** (off by 1)
- Projected Month-End (both agree): **1770**

Even a 1-unit discrepancy causes the app's bill calculation to differ from the official receipt, and there is currently **no way for the user to correct** the App Previous Reading.

**Root Cause:**
`getAppPredictedPreviousReading()` in `ProjectedReadingManager` returns `previousProjection?.getEffectiveReading()` — which is the regression/prediction result from the previous month, not a verified actual reading. Predictions can be off even by a small amount.

The `BillResultActivity` receives this value via `PREVIOUS_READING` intent extra and displays it as a plain `TextView` (`text_previous_reading`) with no edit affordance for that specific field.

**Fix — Two parts:**

**Part A: Add an Edit button for App Previous Reading in BillResultActivity**

In `activity_bill_result.xml`, next to `text_previous_reading`, add a small pencil/edit icon button (similar to how `icon_edit_current` already exists for the current reading):

```xml
<ImageView
    android:id="@+id/icon_edit_previous"
    android:layout_width="18dp"
    android:layout_height="18dp"
    android:layout_marginStart="4dp"
    android:src="@android:drawable/ic_menu_edit"
    android:contentDescription="Edit previous reading"
    android:tint="@color/design_default_color_primary"
    android:clickable="true"
    android:focusable="true"
    android:background="?attr/selectableItemBackground" />
```

In `BillResultActivity.kt`, wire it to show a dialog (reuse the existing `dialog_edit_reading.xml` pattern) that lets the user enter the correct previous reading, then recalculates usage/bill on confirm.

**Part B: Offer to save a user-verified correction back to ProjectedReadingManager**

When the user edits the previous reading, call `ProjectedReadingManager.verifyProjection(year, month, verifiedReading)` so future sessions use the corrected value as the baseline. This prevents the same off-by-N error from carrying forward.

**Files affected:**
- `app/src/main/res/layout/activity_bill_result.xml` — add edit icon next to `text_previous_reading`
- `app/src/main/java/com/kokeshi/metersnap/BillResultActivity.kt` — wire edit dialog + recalculate
- `app/src/main/java/com/kokeshi/metersnap/utils/ProjectedReadingManager.kt` — use existing `verifyProjection()` to persist the correction

---

## Summary Table

| # | Issue | Severity | Files to Change |
|---|-------|----------|----------------|
| 1 | App name "Mete:Read" → "METEREAD" | Low | `strings.xml` |
| 2 | Instruction text dimmed by vignette overlay | Medium | `activity_main.xml`, `MainActivity.kt` (text resets) |
| 2b | Instruction text wording unclear | Low | `activity_main.xml`, `MainActivity.kt` |
| 3 | Tap-to-focus biased downward | Medium | `MainActivity.kt` |
| 4 | App Previous Reading can be wrong, no edit option | High | `activity_bill_result.xml`, `BillResultActivity.kt`, `ProjectedReadingManager.kt` |
