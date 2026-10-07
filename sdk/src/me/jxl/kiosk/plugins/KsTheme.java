// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins;

import android.graphics.drawable.Drawable;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Map;

/** Kiosk Satellite theme helpers for native plugin overlays. */
public class KsTheme {
    public int color(String role) { throw new UnsupportedOperationException(); }
    public Map<String, Integer> colors() { throw new UnsupportedOperationException(); }
    public boolean isDark() { throw new UnsupportedOperationException(); }
    public int px(float dp) { throw new UnsupportedOperationException(); }
    public float radiusCard() { throw new UnsupportedOperationException(); }
    public float radiusRow() { throw new UnsupportedOperationException(); }
    public float radiusControl() { throw new UnsupportedOperationException(); }
    public int inset() { throw new UnsupportedOperationException(); }
    public int cardGap() { throw new UnsupportedOperationException(); }
    public android.graphics.Typeface typeface(int weight) { throw new UnsupportedOperationException(); }
    public Drawable card() { throw new UnsupportedOperationException(); }
    public void styleText(TextView view, float sp, int weight, String role) { throw new UnsupportedOperationException(); }
    public void stylePill(TextView view, boolean filled) { throw new UnsupportedOperationException(); }
    public void styleSlider(SeekBar seekBar) { throw new UnsupportedOperationException(); }
}
