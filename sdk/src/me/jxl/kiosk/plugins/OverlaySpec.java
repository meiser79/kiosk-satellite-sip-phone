// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

/** Placement and behavior for a native overlay. Sizes and insets are in dp. */
public final class OverlaySpec {
    public static final int WRAP = -1;
    public static final int FILL = -2;
    public static final int TOP_LEFT = 0;
    public static final int TOP = 1;
    public static final int TOP_RIGHT = 2;
    public static final int LEFT = 3;
    public static final int CENTER = 4;
    public static final int RIGHT = 5;
    public static final int BOTTOM_LEFT = 6;
    public static final int BOTTOM = 7;
    public static final int BOTTOM_RIGHT = 8;

    public final int anchor;
    public final int width;
    public final int height;
    public final int inset;
    public final boolean closeOnBack;
    public final boolean onTop;
    public final boolean touchable;

    private OverlaySpec(int anchor, int width, int height, int inset, boolean closeOnBack, boolean onTop, boolean touchable) {
        this.anchor = anchor;
        this.width = width;
        this.height = height;
        this.inset = inset;
        this.closeOnBack = closeOnBack;
        this.onTop = onTop;
        this.touchable = touchable;
    }

    public static OverlaySpec at(int anchor) {
        if (anchor < TOP_LEFT || anchor > BOTTOM_RIGHT) throw new IllegalArgumentException("Unknown overlay anchor");
        return new OverlaySpec(anchor, WRAP, WRAP, 0, true, false, true);
    }

    public static OverlaySpec fullScreen() {
        return new OverlaySpec(CENTER, FILL, FILL, 0, true, false, true);
    }

    public OverlaySpec size(int width, int height) {
        validSize(width); validSize(height);
        return new OverlaySpec(anchor, width, height, inset, closeOnBack, onTop, touchable);
    }

    public OverlaySpec inset(int dp) {
        if (dp < 0 || dp > 200) throw new IllegalArgumentException("Overlay inset must be between 0 and 200 dp");
        return new OverlaySpec(anchor, width, height, dp, closeOnBack, onTop, touchable);
    }

    public OverlaySpec closeOnBack(boolean value) { return new OverlaySpec(anchor, width, height, inset, value, onTop, touchable); }
    public OverlaySpec onTop(boolean value) { return new OverlaySpec(anchor, width, height, inset, closeOnBack, value, touchable); }
    public OverlaySpec touchable(boolean value) { return new OverlaySpec(anchor, width, height, inset, closeOnBack, onTop, value); }

    private static void validSize(int value) {
        if (value != WRAP && value != FILL && (value < 1 || value > 4096)) throw new IllegalArgumentException("Overlay size must be WRAP, FILL or 1 to 4096 dp");
    }
}
