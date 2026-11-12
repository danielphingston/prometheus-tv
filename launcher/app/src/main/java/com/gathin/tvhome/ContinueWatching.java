package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the "Continue watching" items other apps publish to the TV provider (config.xml continue_watching):
 * <ul>
 *   <li>preview programs in a channel whose name reads as Continue watching. Stremio publishes its list this way,
 *       not as Watch Next, and its rows carry no date, so the channel's own order is kept;</li>
 *   <li>Watch Next rows (Prime Video and others), newest engagement first.</li>
 * </ul>
 * Needs READ_TV_LISTINGS, granted with pm grant. Without ACCESS_ALL_EPG_DATA the provider returns only rows marked
 * searchable and refuses a selection, so every table is read whole with a named projection and filtered here
 * (docs/watch-next.md). Call off the main thread.
 */
final class ContinueWatching {
    static final String TAG = "TvHomeCW";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** At most this many cards. */
    static final int MAX_ITEMS = 20;
    /** At most this many items from one app, so one app's history cannot fill the row. */
    static final int MAX_PER_APP = 5;
    /** Watch Next rows untouched for longer than this are left out: apps leave old rows behind for months. */
    static final long MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000;
    /** Played this far through, an item counts as finished. */
    static final float FINISHED = 0.95f;

    static final class Item {
        String pkg;
        String title;
        String subtitle;
        String posterUri;
        String intentUri;
        long position;
        long duration;
        /** When it was last watched, for the sort: Watch Next's engagement time, or for a preview channel (which has
         *  none) when Home last opened its app, minus a minute per place so the channel's own order holds. */
        long when;

        /** 0..1 progress, or -1 when the app published none. */
        float progress() {
            return duration > 0 && position > 0 ? Math.min(1f, position / (float) duration) : -1f;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item)) return false;
            Item i = (Item) o;
            return position == i.position && duration == i.duration && Objects.equals(pkg, i.pkg)
                    && Objects.equals(title, i.title) && Objects.equals(subtitle, i.subtitle)
                    && Objects.equals(posterUri, i.posterUri) && Objects.equals(intentUri, i.intentUri);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pkg, title, subtitle, intentUri);
        }
    }

    private ContinueWatching() {}

    static boolean allowed(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Every app's items together, most recently watched first, at most MAX_PER_APP from each app. */
    static List<Item> read(Context context) {
        List<Item> out = new ArrayList<>();
        if (!allowed(context)) return out;
        ContentResolver resolver = context.getContentResolver();
        List<Item> all = new ArrayList<>(readPreview(context, resolver, continueChannels(resolver)));
        all.addAll(readWatchNext(resolver, System.currentTimeMillis()));
        all.sort((a, b) -> Long.compare(b.when, a.when));   // stable: equal times keep the order read
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perApp = new HashMap<>();
        for (Item i : all) {
            if (out.size() >= MAX_ITEMS) break;
            if (i.title == null || i.title.isEmpty() || i.intentUri == null) continue;
            if (i.progress() >= FINISHED) continue;
            if (perApp.getOrDefault(i.pkg, 0) >= MAX_PER_APP) continue;
            if (!seen.add(i.pkg + "\n" + i.title.toLowerCase(Locale.ROOT))) continue;
            perApp.merge(i.pkg, 1, Integer::sum);
            out.add(i);
        }
        return out;
    }

    /** Channels whose display name reads as Continue watching, by id, mapped to their package. */
    private static Map<Long, String> continueChannels(ContentResolver resolver) {
        Map<Long, String> out = new HashMap<>();
        String[] projection = {TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME};
        try (Cursor c = resolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (isContinueName(c.getString(2))) out.put(c.getLong(0), c.getString(1));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channels: " + e);
        }
        return out;
    }

    static boolean isContinueName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        return n.contains("continue watching") || n.contains("keep watching");
    }

    private static List<Item> readPreview(Context context, ContentResolver resolver, Map<Long, String> channels) {
        List<Item> items = new ArrayList<>();
        if (channels.isEmpty()) return items;
        String[] projection = {TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, "package_name",
                TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS};
        Map<String, Integer> place = new HashMap<>();
        try (Cursor c = resolver.query(TvContract.PreviewPrograms.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long channel = c.getLong(0);
                if (!channels.containsKey(channel)) continue;
                Item i = new Item();
                i.pkg = c.getString(1) != null ? c.getString(1) : channels.get(channel);
                int n = place.merge(i.pkg, 1, Integer::sum) - 1;
                long opened = LastOpened.get(context, i.pkg);
                i.when = opened > 0 ? opened - n * 60_000L : -n;
                i.title = c.getString(2);
                i.subtitle = episode(c.getString(4), c.getString(5), c.getString(3));
                i.posterUri = c.getString(6);
                i.intentUri = c.getString(7);
                i.position = c.getLong(8);
                i.duration = c.getLong(9);
                items.add(i);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview programs: " + e);
        }
        return items;
    }

    private static List<Item> readWatchNext(ContentResolver resolver, long now) {
        List<Item> items = new ArrayList<>();
        List<Long> engaged = new ArrayList<>();
        String[] projection = {"package_name", TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE};
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long when = c.getLong(9);
                if (when > 0 && now - when > MAX_AGE_MS) continue;
                // A watchlist entry was never started: it is not something to continue.
                if (!c.isNull(10) && c.getInt(10) == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                Item i = new Item();
                i.pkg = c.getString(0);
                i.title = c.getString(1);
                i.subtitle = episode(c.getString(3), c.getString(4), c.getString(2));
                i.posterUri = c.getString(5);
                i.intentUri = c.getString(6);
                i.position = c.getLong(7);
                i.duration = c.getLong(8);
                i.when = when;
                // Newest first: insert before the first older row.
                int at = 0;
                while (at < engaged.size() && engaged.get(at) >= when) at++;
                engaged.add(at, when);
                items.add(at, i);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e);
        }
        return items;
    }

    /** "S2 E1 · Title", "S2 E1", the episode title alone, or null. */
    static String episode(String season, String number, String episodeTitle) {
        StringBuilder s = new StringBuilder();
        if (season != null && !season.isEmpty()) s.append('S').append(season);
        if (number != null && !number.isEmpty()) s.append(s.length() > 0 ? " " : "").append('E').append(number);
        if (episodeTitle != null && !episodeTitle.isEmpty()) {
            s.append(s.length() > 0 ? " · " : "").append(episodeTitle);
        }
        return s.length() == 0 ? null : s.toString();
    }
}
