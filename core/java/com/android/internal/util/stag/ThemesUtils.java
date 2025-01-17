/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.stag;

import static android.os.UserHandle.USER_SYSTEM;

import android.app.UiModeManager;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

public class ThemesUtils {

    public static final String TAG = "ThemesUtils";
    
     // Switch themes
    private static final String[] SWITCH_THEMES = {
        "com.android.system.switch.stock", // 0
        "com.android.system.switch.oneplus", // 1
	    "com.android.system.switch.narrow", // 2
        "com.android.system.switch.contained", // 3
        "com.android.system.switch.telegram", // 4
        "com.android.system.switch.md2", // 5
        "com.android.system.switch.retro", // 6
        "com.android.system.switch.stockish", //7
        "com.android.system.switch.gradient", //8
        "com.android.system.switch.oos", // 9
        "com.android.system.switch.fluid", // 10
        "com.android.system.switch.android_s", // 11
    };
    
    // Statusbar Signal icons
    private static final String[] SIGNAL_BAR = {
        "org.blissroms.systemui.signalbar_a",
        "org.blissroms.systemui.signalbar_b",
        "org.blissroms.systemui.signalbar_c",
        "org.blissroms.systemui.signalbar_d",
        "org.blissroms.systemui.signalbar_e",
        "org.blissroms.systemui.signalbar_f",
        "org.blissroms.systemui.signalbar_g",
        "org.blissroms.systemui.signalbar_h",
    };

    // Statusbar Wifi icons
    private static final String[] WIFI_BAR = {
        "org.blissroms.systemui.wifibar_a",
        "org.blissroms.systemui.wifibar_b",
        "org.blissroms.systemui.wifibar_c",
        "org.blissroms.systemui.wifibar_d",
        "org.blissroms.systemui.wifibar_e",
        "org.blissroms.systemui.wifibar_f",
        "org.blissroms.systemui.wifibar_g",
        "org.blissroms.systemui.wifibar_h",
    };

    public static void updateSwitchStyle(IOverlayManager om, int userId, int switchStyle) {
        if (switchStyle == 0) {
            stockSwitchStyle(om, userId);
        } else {
            try {
                om.setEnabled(SWITCH_THEMES[switchStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change switch theme", e);
            }
        }
    }

    public static void stockSwitchStyle(IOverlayManager om, int userId) {
        for (int i = 0; i < SWITCH_THEMES.length; i++) {
            String switchtheme = SWITCH_THEMES[i];
            try {
                om.setEnabled(switchtheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}    
