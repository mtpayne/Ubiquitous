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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 *
 * In active mode displays date, time and weather information.
 * Weather from Sunshine app.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mTimePaintInteractive;
        Paint mTimePaintAmbient;
        Paint mDatePaint;
        Paint mTempMaxPaint;
        Paint mTempMinPaint;
        Paint mImagePaint;
        float mTimeSizeInteractive;
        float mTimeSizeAmbient;
        int mImageSize;
        float mTimeXOffsetInteractive;
        float mTimeXOffsetAmbient;
        float mDateXOffset;
        float mDateYOffset;
        float mTempMaxXOffset;
        float mTempMaxYOffset;
        float mTempMinXOffset;
        float mTempMinYOffset;
        float mImageXOffset;

        Bitmap mWeatherBitmap;
        Bitmap mWeatherBitmapResized;

        GoogleApiClient mGoogleApiClient;
        Resources mResources;
        String mTempMax;
        String mTempMin;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mResources = SunshineWatchFace.this.getResources();

            mTimePaintInteractive = createTextPaint(mResources.getColor(R.color.interactive_time_text));
            mTimePaintAmbient = createTextPaint(mResources.getColor(R.color.ambient_time_text));
            mDatePaint = createTextPaint(mResources.getColor(R.color.interactive_date_text));
            mTempMaxPaint = createTextPaint(mResources.getColor(R.color.interactive_max_temp_text));
            mTempMinPaint = createTextPaint(mResources.getColor(R.color.interactive_min_temp_text));
            mImagePaint = new Paint();

            mTime = new Time();
            // rest of setup needs round/square so moved to onApplyWindowInsets
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                if (null != mGoogleApiClient && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            // Load resources that have alternate values for round watches.
            // set size
            if(insets.isRound()) {
                mTimeSizeInteractive = mResources.getDimension(R.dimen.time_text_size_round_interactive);
                mTimeSizeAmbient = mResources.getDimension(R.dimen.time_text_size_round_ambient);
                mImageSize = (int)(mResources.getDimension(R.dimen.image_size_round_interactive));
                mDatePaint.setTextSize(mResources.getDimension(R.dimen.date_text_size_round_interactive));
                mTempMaxPaint.setTextSize(mResources.getDimension(R.dimen.temp_max_text_size_round_interactive));
                mTempMinPaint.setTextSize(mResources.getDimension(R.dimen.temp_min_text_size_round_interactive));
            } else {
                mTimeSizeInteractive = mResources.getDimension(R.dimen.time_text_size_square_interactive);
                mTimeSizeAmbient = mResources.getDimension(R.dimen.time_text_size_square_ambient);
                mImageSize = (int)(mResources.getDimension(R.dimen.image_size_square_interactive));
                mDatePaint.setTextSize(mResources.getDimension(R.dimen.date_text_size_square_interactive));
                mTempMaxPaint.setTextSize(mResources.getDimension(R.dimen.temp_max_text_size_square_interactive));
                mTempMinPaint.setTextSize(mResources.getDimension(R.dimen.temp_min_text_size_square_interactive));
            }

            // Set offsets. X to center align
            mTimePaintAmbient.setTextSize(mTimeSizeAmbient);
            mTimeXOffsetAmbient = mTimePaintAmbient.measureText("00:00") / 2;

            mTimePaintInteractive.setTextSize(mTimeSizeInteractive);
            mTimeXOffsetInteractive = mTimePaintInteractive.measureText("00:00:00") / 2;

            mDateXOffset = mDatePaint.measureText("000, 00 000") / 2;
            mDateYOffset = mTimeSizeInteractive;

            mTempMaxYOffset = mImageSize - mTempMaxPaint.getTextSize();
            mTempMinYOffset = mImageSize - mTempMinPaint.getTextSize();
        }

        /**
         * Setup weather image and dynamically center align weather information below time.
         */
        private void alignWeatherDisplay(int weatherId, String tempMax, String tempMin) {
            // Get weather image and resize
            int weatherArtResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
            mWeatherBitmap = BitmapFactory.decodeResource(getResources(), weatherArtResourceId);
            mWeatherBitmapResized = Bitmap.createScaledBitmap(mWeatherBitmap, mImageSize,
                    mImageSize, true);

            // Set temps. Add padding to max temp
            mTempMax = " " + tempMax + " ";
            mTempMin = tempMin;

            float tempMaxSize = mTempMaxPaint.measureText(mTempMax);
            float tempMinSize = mTempMinPaint.measureText(mTempMin);

            // Set offsets to center align
            mImageXOffset = (mImageSize + tempMaxSize + tempMinSize) / 2;
            mTempMaxXOffset = mImageXOffset - mImageSize;
            mTempMinXOffset = mImageXOffset - mImageSize - tempMaxSize;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            //Whether the display supports fewer bits for each color in ambient mode. When true, we
            //disable anti-aliasing in ambient mode.
            if (properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false)) {
                mTimePaintAmbient.setAntiAlias(true);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            if (isInAmbientMode()) {
                // Draw the background.
                canvas.drawColor(mResources.getColor(R.color.ambient_background));
                // Draw the time.
                canvas.drawText(String.format("%02d:%02d", mTime.hour, mTime.minute),
                        bounds.centerX() - mTimeXOffsetAmbient, bounds.centerY(), mTimePaintAmbient);
            } else {
                // Draw the background.
                canvas.drawColor(mResources.getColor(R.color.interactive_background));
                // Draw the time.
                canvas.drawText(String.format("%02d:%02d:%02d", mTime.hour, mTime.minute, mTime.second),
                        bounds.centerX() - mTimeXOffsetInteractive, bounds.centerY(),
                        mTimePaintInteractive);
                // Draw the date.
                canvas.drawText(mTime.format("%a, %d %b"), bounds.centerX() - mDateXOffset,
                        bounds.centerY() - mDateYOffset, mDatePaint);
                // Draw the weather image and temp if available.
                if(mWeatherBitmapResized != null) {
                    canvas.drawText(mTempMax, bounds.centerX() - mTempMaxXOffset,
                            bounds.centerY() + mTempMaxYOffset, mTempMaxPaint);
                    canvas.drawText(mTempMin, bounds.centerX() - mTempMinXOffset,
                            bounds.centerY() + mTempMinYOffset, mTempMinPaint);
                    canvas.drawBitmap(mWeatherBitmapResized, bounds.centerX() - mImageXOffset,
                            bounds.centerY(), mImagePaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {}

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather-data") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        int weatherId = dataMap.getInt("weatherId");
                        String maxTemp = dataMap.getString("maxTemp");
                        String minTemp = dataMap.getString("minTemp");
                        alignWeatherDisplay(weatherId, maxTemp, minTemp);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {}
    }
}
