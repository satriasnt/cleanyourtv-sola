package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The adzan alarm, and setting it again after a reboot or an update of the launcher. */
public final class AdzanReceiver extends BroadcastReceiver {

    static final String ACTION = "com.gathin.tvhome.action.ADZAN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION.equals(intent.getAction())) {
            Adzan.fired(context, intent);
        } else {
            Adzan.schedule(context);
        }
    }
}
