package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row below the dock (config.xml continue_watching): a heading and a sideways list of poster
 * cards with a progress bar, read by {@link ContinueWatching}. OK opens the item in its app; Up goes back to the
 * dock, Down on to the grid. MainActivity places the view in the gap {@link #gapDecoration} leaves under the dock and
 * keeps it there while the grid scrolls.
 */
final class ContinueRow {
    interface Host {
        void onRowUp();
        void onRowDown();
        void onRowFocused();
    }

    private static final int CARD_W_DP = 80, CARD_H_DP = 120, GAP_DP = 18;
    private static final int HEADER_DP = 22, PAD_TOP_DP = 8, TEXT_DP = 28, PAD_BOTTOM_DP = 4;
    private static final float FOCUS_SCALE = 1.1f;

    private final Activity activity;
    private final Host host;
    private final float density;
    private final PosterCache posters;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final LinearLayout root;
    private final TextView header;
    private final RecyclerView list;
    private final CardAdapter adapter = new CardAdapter();
    private List<ContinueWatching.Item> items = new ArrayList<>();
    private int focusedIndex;
    private boolean reading;

    ContinueRow(Activity activity, Host host, float density) {
        this.activity = activity;
        this.host = host;
        this.density = density;
        this.posters = new PosterCache(activity, dp(CARD_W_DP), dp(CARD_H_DP));

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setVisibility(View.GONE);
        // The row is moved into place with translationY, which focus search ignores: left open, Up from the dock
        // would find it where it was laid out, at the top. Only focus() lets focus in.
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        header = new TextView(activity);
        header.setText(R.string.continue_watching_title);
        header.setTextColor(0xCCFFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setSingleLine(true);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_DP)));

        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setPadding(0, dp(PAD_TOP_DP), 0, dp(PAD_BOTTOM_DP));
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(PAD_TOP_DP + CARD_H_DP + TEXT_DP + PAD_BOTTOM_DP)));
    }

    View view() { return root; }

    /** The row's full height, which the grid leaves free under the dock. */
    int heightPx() {
        return dp(HEADER_DP + PAD_TOP_DP + CARD_H_DP + TEXT_DP + PAD_BOTTOM_DP);
    }

    boolean hasItems() { return !items.isEmpty(); }

    boolean hasFocus() { return root.hasFocus(); }

    /** Lines the heading and the first card up with the dock's first tile. */
    void setStart(int px) {
        if (header.getPaddingLeft() == px) return;
        header.setPadding(px, 0, 0, 0);
        list.setPadding(px, list.getPaddingTop(), px, list.getPaddingBottom());
    }

    /** Reads the rows again off the main thread; changed(true) runs when the row appeared or went. */
    void refresh(java.util.function.Consumer<Boolean> changed) {
        if (reading) return;
        reading = true;
        Context app = activity.getApplicationContext();
        reader.execute(() -> {
            List<ContinueWatching.Item> fresh = ContinueWatching.read(app);
            activity.runOnUiThread(() -> {
                reading = false;
                if (activity.isDestroyed() || fresh.equals(items)) return;
                boolean was = !items.isEmpty();
                items = fresh;
                focusedIndex = Math.min(focusedIndex, Math.max(0, items.size() - 1));
                adapter.notifyDataSetChanged();
                root.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                changed.accept(was != !items.isEmpty());
            });
        });
    }

    /** Focuses the card focused last (the first one at first). */
    boolean focus() {
        if (items.isEmpty()) return false;
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        int index = Math.max(0, Math.min(focusedIndex, items.size() - 1));
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(index);
        if (vh != null) return ((CardHolder) vh).card.requestFocus();
        list.scrollToPosition(index);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(index);
            if (h != null) ((CardHolder) h).card.requestFocus();
        });
        return true;
    }

    void destroy() {
        reader.shutdownNow();
        posters.shutdown();
    }

    /** Leaves the row's height free under the dock row (the first {@code columns} grid positions). */
    RecyclerView.ItemDecoration gapDecoration(int columns) {
        return new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (hasItems() && position >= 0 && position < columns) outRect.bottom += heightPx();
            }
        };
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private void open(ContinueWatching.Item item) {
        PackageManager pm = activity.getPackageManager();
        Intent intent = null;
        try {
            if (item.intentUri.startsWith("intent:")) {
                intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                intent.setSelector(null);
                ComponentName target = intent.getComponent();
                // A row opens its own app only.
                if (target != null && !target.getPackageName().equals(item.pkg)) intent = null;
                else if (target == null && item.pkg != null) intent.setPackage(item.pkg);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
                if (item.pkg != null) intent.setPackage(item.pkg);
            }
        } catch (URISyntaxException e) {
            Log.w(ContinueWatching.TAG, "intent " + item.intentUri + ": " + e);
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(ContinueWatching.TAG, "open " + item.intentUri + ": " + e);
            }
        }
        // The deep link did not resolve: open the app itself.
        if (item.pkg == null) return;
        Intent launch = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(item.pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(ContinueWatching.TAG, "launch " + item.pkg + ": " + e);
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView poster;
        final TextView fallback, title, subtitle;
        final View track, fill;
        String posterUri;

        CardHolder(LinearLayout item) {
            super(item);
            float radius = 10 * density;
            card = new FrameLayout(activity);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x26FFFFFF);
            bg.setCornerRadius(radius);
            card.setBackground(bg);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setCornerRadius(radius);
            ring.setStroke(Math.max(2, dp(2)), Color.WHITE);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            fallback = new TextView(activity);
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            track = new View(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x66000000);
            trackBg.setCornerRadius(dp(2));
            track.setBackground(trackBg);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4),
                    Gravity.BOTTOM);
            tl.setMargins(dp(8), 0, dp(8), dp(8));
            card.addView(track, tl);

            fill = new View(activity);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(Color.WHITE);
            fillBg.setCornerRadius(dp(2));
            fill.setBackground(fillBg);
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START);
            fl.setMargins(dp(8), 0, 0, dp(8));
            card.addView(fill, fl);

            // The episode sits on the poster as a small badge, so the row needs only one line of text under it.
            subtitle = new TextView(activity);
            subtitle.setTextColor(Color.WHITE);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subtitle.setMaxWidth(dp(CARD_W_DP - 12));
            subtitle.setPadding(dp(5), dp(1), dp(5), dp(1));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xB3000000);
            badgeBg.setCornerRadius(dp(4));
            subtitle.setBackground(badgeBg);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            sl.setMargins(dp(6), dp(6), dp(6), 0);
            card.addView(subtitle, sl);

            item.addView(card, new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP)));

            title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(dp(CARD_W_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ttl.topMargin = dp(8);
            item.addView(title, ttl);

            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(150).start();
                v.setForeground(focused ? ring : null);
                title.setAlpha(focused ? 1f : 0.7f);
                if (focused) {
                    focusedIndex = getBindingAdapterPosition();
                    host.onRowFocused();
                } else {
                    root.post(() -> {
                        if (!root.hasFocus()) root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    });
                }
            });
            card.setOnClickListener(v -> {
                int p = getBindingAdapterPosition();
                if (p >= 0 && p < items.size()) open(items.get(p));
            });
            card.setOnKeyListener((v, keyCode, event) -> {
                int p = getBindingAdapterPosition();
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (down) host.onRowUp();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down) host.onRowDown();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return p == 0;                    // the row's start: stay
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return p == items.size() - 1;     // the row's end: stay
                    default:
                        return false;
                }
            });
        }

        void bind(ContinueWatching.Item item) {
            title.setText(item.title);
            title.setAlpha(card.isFocused() ? 1f : 0.7f);
            subtitle.setText(item.subtitle == null ? "" : item.subtitle);
            subtitle.setVisibility(item.subtitle == null ? View.GONE : View.VISIBLE);
            fallback.setText(item.title);
            float progress = item.progress();
            track.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
            fill.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = progress < 0 ? 0 : Math.max(dp(4), Math.round((dp(CARD_W_DP) - dp(16)) * progress));
            fill.setLayoutParams(lp);
            posterUri = item.posterUri;
            // setImageBitmap(null) still sets an empty drawable, so clear with setImageDrawable.
            Bitmap hit = posters.cached(posterUri);
            if (hit != null) poster.setImageBitmap(hit); else poster.setImageDrawable(null);
            if (poster.getDrawable() == null && posterUri != null) {
                String want = posterUri;
                posters.load(want, bitmap -> {
                    if (want.equals(posterUri) && bitmap != null) poster.setImageBitmap(bitmap);
                });
            }
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setClipChildren(false);
            item.setClipToPadding(false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(GAP_DP));
            item.setLayoutParams(lp);
            return new CardHolder(item);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
