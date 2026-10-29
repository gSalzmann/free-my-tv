package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;

import java.util.function.Consumer;

/**
 * The Live TV sheet's channels: finds one in Android's TV listings (TvContract) by the number the TV shows, so the
 * TV's own Live TV app opens on it. Reading the listings needs READ_TV_LISTINGS, the permission Continue Watching
 * uses: the first time it's granted through the TV's own ADB (Free My TV mode), and Original takes it back.
 * Without it, or where the TV keeps its channels to itself, nothing is found.
 */
final class TvChannels {
    private TvChannels() {}

    /** Looks the channel up off the UI thread; `done` gets the intent that tunes it, or null, on the main thread. */
    static void find(Context c, String number, Consumer<Intent> done) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Intent found = null;
            try {   // nothing may escape: :home's watchdog would count it as a crash
                found = lookUp(app, number);
            } catch (Exception e) {
                AppLog.w("channel " + number + ": " + e);
            }
            Intent result = found;
            main.post(() -> done.accept(result));
        }, "tv-channel").start();
    }

    private static Intent lookUp(Context app, String number) throws Exception {
        if (!WatchNext.allowed(app) && Capabilities.adb(app)) {
            try (Adb adb = Adb.connect(app, 3000, null)) {
                adb.shell("pm grant " + app.getPackageName() + " " + WatchNext.PERMISSION);
            }
            AppLog.w("channels: listings permission " + (WatchNext.allowed(app) ? "granted" : "not granted"));
        }
        if (!WatchNext.allowed(app)) return null;
        String tuner = HomeApps.tunerInput(app), want = normalize(number);
        long best = -1;
        boolean bestOnTuner = false;
        int rows = 0;
        StringBuilder seen = new StringBuilder();
        // No selection: TvProvider refuses one from apps without full EPG access, so the rows are filtered here.
        String[] columns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER};
        try (Cursor cur = app.getContentResolver().query(TvContract.Channels.CONTENT_URI, columns, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                rows++;
                String input = cur.getString(1), shown = cur.getString(2);
                if (rows <= 12) seen.append(' ').append(shown);
                if (shown == null || !want.equals(normalize(shown))) continue;
                boolean onTuner = input != null && input.equals(tuner);
                if (best < 0 || onTuner && !bestOnTuner) {
                    best = cur.getLong(0);
                    bestOnTuner = onTuner;
                }
            }
        }
        AppLog.w("channel " + number + ": " + (best < 0 ? "not found" : "found") + " in " + rows + " listed (" + seen.toString().trim() + ")");
        if (best < 0) return null;
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(best)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** "4.1", "4-1" and "04.1" are the same channel. */
    static String normalize(String number) {
        StringBuilder out = new StringBuilder();
        for (String part : number.trim().split("[.\\-, ]+")) {
            if (part.isEmpty()) continue;
            String digits = part.replaceFirst("^0+(?=\\d)", "");
            if (out.length() > 0) out.append('.');
            out.append(digits);
        }
        return out.toString();
    }
}
