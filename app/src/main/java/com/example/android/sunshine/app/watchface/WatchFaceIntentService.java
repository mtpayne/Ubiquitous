package com.example.android.sunshine.app.watchface;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.Date;

/**
 * IntentService which handles updating wear device with the latest data
 */
public class WatchFaceIntentService extends IntentService implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    public static final String ACTION_UPDATE_WATCHFACE = "ACTION_UPDATE_WATCHFACE";

    private GoogleApiClient mGoogleApiClient;

    public WatchFaceIntentService() {
        super("WatchFaceIntentService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceIntentService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null
                && intent.getAction() != null
                && intent.getAction().equals(ACTION_UPDATE_WATCHFACE)) {

//            mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceIntentService.this)
//                    .addConnectionCallbacks(this)
//                    .addOnConnectionFailedListener(this)
//                    .addApi(Wearable.API)
//                    .build();
//
//            mGoogleApiClient.connect();

            // Get today's data from the ContentProvider
            String location = Utility.getPreferredLocation(this);
            Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                    location, System.currentTimeMillis());
            Cursor data = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null,
                    null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");
            if (data == null) {
                return;
            }
            if (!data.moveToFirst()) {
                data.close();
                return;
            }

            // Extract the weather data from the Cursor
            int weatherId = data.getInt(INDEX_WEATHER_ID);
            double maxTemp = data.getDouble(INDEX_MAX_TEMP);
            double minTemp = data.getDouble(INDEX_MIN_TEMP);
            data.close();

            String formattedMaxTemperature = Utility.formatTemperature(this, maxTemp);
            String formattedMinTemperature = Utility.formatTemperature(this, minTemp);

            PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/weather-data");
            putDataMapReq.getDataMap().putString("date", (new Date()).toString());
            putDataMapReq.getDataMap().putInt("weatherId", weatherId);
            putDataMapReq.getDataMap().putString("maxTemp", formattedMaxTemperature);
            putDataMapReq.getDataMap().putString("minTemp", formattedMinTemperature);
            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq).await();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mGoogleApiClient.disconnect();
    }

    @Override
    public void onConnected(Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }
}
