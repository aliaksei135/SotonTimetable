package com.aliakseipilko.sotontimetable;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.util.Log;
import android.widget.Toast;

import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.AuthenticationResult;
import com.microsoft.identity.client.MsalClientException;
import com.microsoft.identity.client.MsalException;
import com.microsoft.identity.client.MsalServiceException;
import com.microsoft.identity.client.PublicClientApplication;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;

import javax.net.ssl.HttpsURLConnection;


public class SotonTimetableService extends JobIntentService {

    private static final String AUTH_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/ldap/100";
    private static final String EVENTS_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/calendar/course_timetable/";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals("com.aliakseipilko.sotontimetable.synctimetable")) {
                SotonTimetableService.enqueueWork(
                        getApplicationContext(),
                        SotonTimetableService.class,
                        1001,
                        intent);
            }else if (action.equals("com.aliakseipilko.sotontimetable.stopsync")){
                onDestroy();
            }
        }
    };

    private static final String TAG = "SotonTimetableService";

    SharedPreferences prefs;
    PublicClientApplication pcApp;

    AlarmManager am;
    PendingIntent pi;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getPackageName(), MODE_PRIVATE);

        EventBus.getDefault().register(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.aliakseipilko.sotontimetable.synctimetable");
        filter.addAction("com.aliakseipilko.sotontimetable.stopsync");
        registerReceiver(receiver, filter);
        scheduleNextExec();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onOfficeAuthEvent(OfficeAuthEvent event){
        prefs.edit()
                .putString("office_access_token", event.getAccessToken())
                .apply();
        this.pcApp = event.getPcApp();
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        json = getJsonTimetable();
    }

    private void scheduleNextExec(){
        Intent i = new Intent(getApplicationContext(), SotonTimetableService.class);
        i.setAction("com.aliakseipilko.sotontimetable.synctimetable");

        am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        pi = PendingIntent.getBroadcast(getApplicationContext(), 2053, i, PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        trigger.set(Calendar.HOUR_OF_DAY, 8);
        trigger.set(Calendar.MINUTE, 0);
        Calendar interval = Calendar.getInstance();
        interval.setTimeInMillis(trigger.getTimeInMillis());
        interval.add(Calendar.DATE, 7);


        am.setInexactRepeating(AlarmManager.RTC, trigger.getTimeInMillis(), interval.getTimeInMillis(), pi);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        unregisterReceiver(receiver);
        am.cancel(pi);
    }

    public Json getJsonTimetable(String login, String pw) throws IOException {
        URL authURL = new URL(AUTH_ENDPOINT);
        Uri.Builder builder = new Uri.Builder()
                .appendQueryParameter("username", login)
                .appendQueryParameter("password", pw);
        String query = builder.build().getEncodedQuery();
        HttpsURLConnection connection = (HttpsURLConnection) authURL.openConnection();
        connection.setRequestMethod("POST");
        connection.setReadTimeout(15000);
        connection.setConnectTimeout(15000);
        connection.setDoInput(true);
        connection.setDoOutput(true);

        OutputStream os = connection.getOutputStream();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(os, "UTF-8"));
        writer.write(query);
        writer.flush();
        writer.close();
        os.close();

        connection.connect();

        if(connection.getResponseCode() == 200){
            //TODO Get Json
        }else{
            throw new IOException("Auth Failed, bad login?");
        }



    }
}
