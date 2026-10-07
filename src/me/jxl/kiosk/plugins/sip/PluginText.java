// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.util.Locale;

/** English source strings with an optional German translation bundled in the plugin JAR. */
final class PluginText {
    private PluginText() { }

    static String get(String key, String english) {
        if ("de".equals(Locale.getDefault().getLanguage())) {
            String translated=GermanStrings.get(key);
            if(translated!=null) return translated;
        }
        return english;
    }

    static String format(String key, String english, String... values) {
        String result=get(key, english);
        for(int i=0;i<values.length;i++) result=result.replace("{"+i+"}",values[i]==null?"":values[i]);
        return result;
    }

}
