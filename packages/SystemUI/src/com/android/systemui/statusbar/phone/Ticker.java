package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageSwitcher;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;

import java.util.ArrayList;

public abstract class Ticker implements DarkReceiver {
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimeoutRunnable = this::advance;

    private TextSwitcher mTextSwitcher;
    private ImageSwitcher mIconSwitcher;
    private float mIconScale;
    private int mIconTint = 0xffffffff;
    private int mTextColor = 0xffffffff;
    private int mTickDuration = 3000;

    private Animation mAnimationIn;
    private Animation mAnimationOut;

    private ContrastColorUtil mNotificationColorUtil;

    private static class TickerEntry {
        final StatusBarNotification notification;
        final CharSequence text;
        final Drawable icon;

        TickerEntry(StatusBarNotification n, CharSequence t, Drawable i) {
            notification = n;
            text = t;
            icon = i;
        }
    }

    private final ArrayList<TickerEntry> mEntries = new ArrayList<>();
    private TickerEntry mCurrentEntry;

    public Ticker(Context context, View tickerLayout, int animationMode, int tickDuration) {
        mContext = context;
        mNotificationColorUtil = ContrastColorUtil.getInstance(mContext);
        updateAnimation(animationMode);
        updateTickDuration(tickDuration);

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
    }

    public void setViews(TextSwitcher ts, ImageSwitcher is) {
        final int outerBounds = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_size);
        final int imageBounds = mContext.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size);
        
        mIconScale = (float) imageBounds / (float) outerBounds;
        mIconSwitcher = is;
        mTextSwitcher = ts;

        mIconSwitcher.setScaleX(mIconScale);
        mIconSwitcher.setScaleY(mIconScale);

        // Select text for marquee scroll animation       
        for (int i = 0; i < mTextSwitcher.getChildCount(); i++) {
            View child = mTextSwitcher.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setSelected(true);
            }
        }

        setViewAnimations();
    }

    public void updateTickDuration(int duration) {
        mTickDuration = duration;
    }

    public void addEntry(StatusBarNotification n) {
        CharSequence text = n.getNotification().tickerText;
        Drawable icon = n.getNotification().getSmallIcon().loadDrawable(mContext);

        if (text == null || icon == null) return;

        // If what's being displayed has the same text and icon, just drop it
        // (which will let the current one finish, this happens when apps do
        // a notification storm).
        if (mCurrentEntry != null) {
            StatusBarNotification current = mCurrentEntry.notification;
            if (n.getPackageName().equals(current.getPackageName())
                     && n.getNotification().icon == current.getNotification().icon
                     && TextUtils.equals(current.getNotification().tickerText, 
                         n.getNotification().tickerText)) {
                 return;
             }
        }

        // Remove duplicates with same package and ID
        for (int i = 0; i < mEntries.size(); i++) {
            TickerEntry entry = mEntries.get(i);
            if (n.getId() == entry.notification.getId()
                    && n.getPackageName().equals(entry.notification.getPackageName())) {
                mEntries.remove(i--);
            }
        }

        final TickerEntry newEntry = new TickerEntry(n, text, icon);
        mEntries.add(newEntry);

        if (mCurrentEntry == null && !mEntries.isEmpty()) {
            showEntry(mEntries.get(0));
        }
    }

    private void showEntry(TickerEntry entry) {
        mCurrentEntry = entry;
        mEntries.remove(entry);

        if (entry.icon != null && mIconSwitcher != null) {
            mIconSwitcher.setAnimateFirstView(false);
            mIconSwitcher.reset();
            setAppIconColor(entry.icon);
        }

        if (entry.text != null && mTextSwitcher != null) {
            mTextSwitcher.setAnimateFirstView(false);
            mTextSwitcher.reset();
            mTextSwitcher.setText(entry.text);
            mTextSwitcher.setTextColor(mTextColor);
        }

        tickerStarting();
        scheduleAdvance();
    }

    private void scheduleAdvance() {
        mHandler.postDelayed(mTimeoutRunnable, mTickDuration);
    }

    private void advance() {
        if (mCurrentEntry != null) {
            mCurrentEntry = null;
            tickerDone();
        }

        if (!mEntries.isEmpty()) {
            showEntry(mEntries.get(0));
        }
    }

    public void removeEntry(StatusBarNotification n) {
        for (int i = mEntries.size() - 1; i >= 0; i--) {
            TickerEntry entry = mEntries.get(i);
            StatusBarNotification e = entry.notification;
            if (n.getId() == e.getId() && n.getPackageName().equals(e.getPackageName())) {
                mEntries.remove(i);
            }
        }
    }

    public void halt() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        mEntries.clear();
        tickerHalting();
    }

    public void setTextColor(int color) {
        mTextColor = color;
    }

    private void setViewAnimations() {
        if (mTextSwitcher != null && mIconSwitcher != null) {
            mTextSwitcher.setInAnimation(mAnimationIn);
            mTextSwitcher.setOutAnimation(mAnimationOut);
            mIconSwitcher.setInAnimation(mAnimationIn);
            mIconSwitcher.setOutAnimation(mAnimationOut);
        }
    }

    public void updateAnimation(int animationMode) {
        if (animationMode == 1) {
            mAnimationIn = AnimationUtils.loadAnimation(mContext, com.android.internal.R.anim.push_up_in);
            mAnimationOut = AnimationUtils.loadAnimation(mContext, com.android.internal.R.anim.push_up_out);
        } else {
            mAnimationIn = new AlphaAnimation(0.0f, 1.0f);
            mAnimationIn.setInterpolator(AnimationUtils.loadInterpolator(mContext, android.R.interpolator.decelerate_quad));
            mAnimationIn.setDuration(350);

            mAnimationOut = new AlphaAnimation(1.0f, 0.0f);
            mAnimationOut.setInterpolator(AnimationUtils.loadInterpolator(mContext, android.R.interpolator.accelerate_quad));
            mAnimationOut.setDuration(350);
        }

        setViewAnimations();
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> area, float darkIntensity, int tint) {}

    public void applyDarkIntensity(ArrayList<Rect> area, View v, int tint) {
        mTextColor = DarkIconDispatcher.getTint(area, v, tint);
        mIconTint = mTextColor;
        if (mTextSwitcher != null) {
            setTickerIconColor();
            mTextSwitcher.setTextColor(mTextColor);
        }
    }

    private void setTickerIconColor() {
        View currentView = mIconSwitcher.getCurrentView();
        if (currentView instanceof ImageView) {
            ImageView imageView = (ImageView) currentView;
            Drawable currentDrawable = imageView.getDrawable();
            if (currentDrawable != null) {
                Drawable mutable = currentDrawable.mutate();
                mutable.setTintList(ColorStateList.valueOf(mIconTint));
                imageView.setImageDrawable(mutable);
            }
        }
    }

    private void setAppIconColor(Drawable icon) {
        if (mNotificationColorUtil != null && mIconSwitcher != null && icon != null) {
            boolean isGrayscale = mNotificationColorUtil.isGrayscaleIcon(icon);
            mIconSwitcher.setImageDrawableTint(icon, mIconTint, isGrayscale);
        }
    }

    public abstract void tickerStarting();
    public abstract void tickerDone();
    public abstract void tickerHalting();
}
