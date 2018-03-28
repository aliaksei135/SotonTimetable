package com.aliakseipilko.sotontimetable;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.util.Log;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import com.aliakseipilko.sotontimetable.models.office.OfficeEventJsonModel;
import com.aliakseipilko.sotontimetable.models.soton.EventJsonModel;
import com.aliakseipilko.sotontimetable.models.soton.TimetableJsonModel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.microsoft.graph.extensions.BodyType;
import com.microsoft.graph.extensions.DateTimeTimeZone;
import com.microsoft.graph.extensions.ItemBody;
import com.microsoft.graph.extensions.Location;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.AuthenticationResult;
import com.microsoft.identity.client.MsalClientException;
import com.microsoft.identity.client.MsalException;
import com.microsoft.identity.client.MsalServiceException;
import com.microsoft.identity.client.MsalUiRequiredException;
import com.microsoft.identity.client.PublicClientApplication;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;


public class SotonTimetableService extends JobIntentService {

    final static String CLIENT_ID = "";
    final static String SCOPES[] = {"https://graph.microsoft.com/Calendar.ReadWrite",
    };
    final static String MSGRAPH_URL = "https://graph.microsoft.com/v1.0/me";
    private static final String AUTH_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/ldap/100";
    private static final String EVENTS_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/calendar/course_timetable/";
    private static final String TAG = "SotonTimetableService";
    SharedPreferences prefs;
    PublicClientApplication pcApp;
    AlarmManager am;
    PendingIntent pi;
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
    private AuthenticationResult authResult;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getPackageName(), MODE_PRIVATE);

        // To keep sessions alive
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);

        EventBus.getDefault().register(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.aliakseipilko.sotontimetable.synctimetable");
        filter.addAction("com.aliakseipilko.sotontimetable.stopsync");
        registerReceiver(receiver, filter);
        scheduleNextExec();
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

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        try {
            TimetableJsonModel json = getJsonTimetable(prefs.getString("soton_login", null),
                    prefs.getString("soton_pw", null));
            List<OfficeEventJsonModel> events = parseJsonToOffice(json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public TimetableJsonModel getJsonTimetable(String login, String pw) throws IOException {
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

        if (connection.getResponseCode() == 200 || connection.getResponseCode() == 201) {
            Calendar cal = Calendar.getInstance();
            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat sdf = new SimpleDateFormat("YYYYwww");
            String stamp = sdf.format(cal);

            URL eventsURL = new URL(EVENTS_ENDPOINT + stamp);
            HttpsURLConnection eventsConn = (HttpsURLConnection) eventsURL.openConnection();
            eventsConn.setRequestMethod("GET");
            eventsConn.setDoInput(true);

            InputStreamReader isr = new InputStreamReader(eventsConn.getInputStream(), "UTF-8");
            Gson gson = new GsonBuilder()
                    .setDateFormat("YYYY-MM-dd'T'HH:mm:ss.SSSXXX")
                    .setPrettyPrinting()
                    .create();
            TimetableJsonModel timetable = gson.fromJson(isr, TimetableJsonModel.class);
            isr.close();
            eventsConn.disconnect();
            connection.disconnect();
            return timetable;

        }else{
            connection.disconnect();
            throw new IOException("Auth Failed, bad login?");
        }

    }

    public List<OfficeEventJsonModel> parseJsonToOffice(TimetableJsonModel json) {
        List<OfficeEventJsonModel> parsedEvents = new ArrayList<>();

        for (EventJsonModel event : json.events) {
            OfficeEventJsonModel newEvent = new OfficeEventJsonModel();
            newEvent.setiCalUId(Long.toString(event.getId()));
            newEvent.setSubject(event.getDesc2());

            ItemBody body = new ItemBody();
            body.content = event.getDesc1() + "\nTeacher: " + event.getTeacherName();
            body.contentType = BodyType.text;
            newEvent.setBody(body);

            Location loc = new Location();
            loc.displayName = event.getLocCode();
            loc.address = null;
            newEvent.setLocation(loc);

            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

            DateTimeTimeZone start = new DateTimeTimeZone();
            start.dateTime = sdf.format(event.getStart());
            start.timeZone = "Europe/London";
            newEvent.setStart(start);

            DateTimeTimeZone end = new DateTimeTimeZone();
            end.dateTime = sdf.format(event.getEnd());
            end.timeZone = "Europe/London";
            newEvent.setEnd(end);

            parsedEvents.add(newEvent);
        }

        return parsedEvents;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onOfficeAuthEvent(OfficeAuthEvent event) {
        prefs.edit()
                .putString("office_access_token", event.getAccessToken())
                .apply();
        this.pcApp = event.getPcApp();
    }

    private void addEventsToOffice(JsonArray parsedEvents) {
        try {
            pcApp.acquireTokenSilentAsync(SCOPES, pcApp.getUsers().get(0),
                    getAuthSilentCallback(parsedEvents));
        } catch (MsalClientException e) {
            e.printStackTrace();

        }
    }

    private AuthenticationCallback getAuthSilentCallback(final JsonArray parsedEvents) {
        return new AuthenticationCallback() {
            @Override
            public void onSuccess(final AuthenticationResult authenticationResult) {
                /* Successfully got a token, call Graph now */
                Log.d(TAG, "Successfully authenticated");

                /* Store the authResult */
                authResult = authenticationResult;

                RequestQueue queue = Volley.newRequestQueue(getApplicationContext());

                try {
                    JSONObject events = new JSONObject(parsedEvents.getAsString());
                    JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET,
                            MSGRAPH_URL,
                            events, new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            /* Successfully called graph, process data and send to UI */
                            Log.d(TAG, "Response: " + response.toString());
                            //TODO Success Notif
                        }
                    }, new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.d(TAG, "Error: " + error.toString());
                            //TODO Error Notif
                        }
                    }) {
                        @Override
                        public Map<String, String> getHeaders() {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Authorization",
                                    "Bearer " + authenticationResult.getAccessToken());
                            return headers;
                        }
                    };

                    request.setRetryPolicy(new DefaultRetryPolicy(
                            3000,
                            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
                    queue.add(request);

                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }

            @Override
            public void onError(MsalException exception) {
                /* Failed to acquireToken */
                Log.d(TAG, "Authentication failed: " + exception.toString());

                if (exception instanceof MsalClientException) {
                    /* Exception inside MSAL, more info inside MsalError.java */
                } else if (exception instanceof MsalServiceException) {
                    /* Exception when communicating with the STS, likely config issue */
                } else if (exception instanceof MsalUiRequiredException) {
                    /* Tokens expired or no session, retry with interactive */
                    //TODO Make notification to relogin with ui context
                }
            }

            @Override
            public void onCancel() {
                /* User cancelled the authentication */
                Log.d(TAG, "User cancelled login.");
            }
        };
    }
}
