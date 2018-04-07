package com.aliakseipilko.sotontimetable;

import static android.support.v4.app.NotificationCompat.DEFAULT_ALL;

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

import com.aliakseipilko.sotontimetable.models.soton.EventJsonModel;
import com.aliakseipilko.sotontimetable.models.soton.TimetableJsonModel;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.core.DefaultClientConfig;
import com.microsoft.graph.core.IClientConfig;
import com.microsoft.graph.extensions.BodyType;
import com.microsoft.graph.extensions.DateTimeTimeZone;
import com.microsoft.graph.extensions.Event;
import com.microsoft.graph.extensions.GraphServiceClient;
import com.microsoft.graph.extensions.IEventCollectionRequestBuilder;
import com.microsoft.graph.extensions.IGraphServiceClient;
import com.microsoft.graph.extensions.ItemBody;
import com.microsoft.graph.extensions.Location;
import com.microsoft.graph.http.IHttpRequest;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import br.com.goncalves.pugnotification.notification.PugNotification;


public class SotonTimetableService extends JobIntentService {

    final static String CLIENT_ID = "ac6b7a81-b8e2-4a9b-9689-8a5b0be0ac2e";
    final static String SCOPES[] = {"https://graph.microsoft.com/Calendar.ReadWrite",
    };
    private static final String AUTH_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/ldap/100";
    private static final String EVENTS_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/calendar/course_timetable/";
    private static final String TAG = "SotonTimetableService";
    SharedPreferences prefs;
    PublicClientApplication pcApp;
    AlarmManager am;
    PendingIntent pi;

    IGraphServiceClient mGraphServiceClient;

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

    private GoogleAccountCredential googleCredential;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);

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
            if (prefs.getBoolean("office_cal_enabled", false)) {
                List<Event> events = parseJsonToOffice(json);
                addEventsToOffice(events);
            }
            if (prefs.getBoolean("google_cal_enabled", false)) {
                List<com.google.api.services.calendar.model.Event> events = parseJsonToGoogle(json);
                addEventsToGoogle(events);
            }
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
            Date cal = Calendar.getInstance().getTime();
            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat sdf = new SimpleDateFormat("YYYYDDD");
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

    public List<Event> parseJsonToOffice(TimetableJsonModel json) {
        List<Event> parsedEvents = new ArrayList<>();

        for (EventJsonModel event : json.events) {
            Event newEvent = new Event();
            newEvent.iCalUId = (event.getId());
            newEvent.subject = (event.getDesc2());

            ItemBody body = new ItemBody();
            body.content = event.getDesc1() + "\nTeacher: " + event.getTeacherName();
            body.contentType = BodyType.text;
            newEvent.body = (body);

            Location loc = new Location();
            loc.displayName = event.getLocCode();
            loc.address = null;
            newEvent.location = (loc);

            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

            DateTimeTimeZone start = new DateTimeTimeZone();
            start.dateTime = sdf.format(event.getStart());
            start.timeZone = "Europe/London";
            newEvent.start = (start);

            DateTimeTimeZone end = new DateTimeTimeZone();
            end.dateTime = sdf.format(event.getEnd());
            end.timeZone = "Europe/London";
            newEvent.end = (end);

            newEvent.reminderMinutesBeforeStart = 20;

            parsedEvents.add(newEvent);
        }

        return parsedEvents;
    }

    public List<com.google.api.services.calendar.model.Event> parseJsonToGoogle(
            TimetableJsonModel json) {
        List<com.google.api.services.calendar.model.Event> parsedEvents = new ArrayList<>();

        for (EventJsonModel event : json.events) {
            com.google.api.services.calendar.model.Event newEvent = new com.google.api.services
                    .calendar.model.Event();

            newEvent.setICalUID(event.getId());
            newEvent.setSummary(event.getDesc2());

            newEvent.setDescription(event.getDesc1() + "\nTeacher: " + event.getTeacherName());
            newEvent.setLocation(event.getLocCode());

            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

            EventDateTime start = new EventDateTime();
            start.setDateTime(new DateTime(sdf.format(event.getStart())));
            start.setTimeZone("Europe/London");
            newEvent.setStart(start);

            EventDateTime end = new EventDateTime();
            end.setDateTime(new DateTime(sdf.format(event.getEnd())));
            newEvent.setEnd(end);

            newEvent.setReminders(
                    new com.google.api.services.calendar.model.Event.Reminders().setOverrides(
                            Collections.singletonList(new EventReminder().setMinutes(20))));

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

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onGoogleCredential(GoogleAccountCredential googleCredential) {
        this.googleCredential = googleCredential;
    }

    private void addEventsToOffice(List<Event> parsedEvents) {
        try {
            pcApp.acquireTokenSilentAsync(SCOPES, pcApp.getUsers().get(0),
                    getAuthSilentCallback(parsedEvents));
        } catch (MsalClientException e) {
            e.printStackTrace();

        }
    }

    private void addEventsToGoogle(List<com.google.api.services.calendar.model.Event> parsedEvents)
            throws IOException {
        HttpTransport transport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        com.google.api.services.calendar.Calendar mService =
                new com.google.api.services.calendar.Calendar.Builder(
                        transport, jsonFactory, googleCredential)
                        .setApplicationName("SotonCal")
                        .build();

        BatchRequest batch = mService.batch();
        for (com.google.api.services.calendar.model.Event event : parsedEvents) {
            //TODO support custom calendar id
            mService.events().insert("University Timetable", event).queue(batch,
                    new JsonBatchCallback<com.google.api.services.calendar.model.Event>() {
                        @Override
                        public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                            PugNotification.with(getApplicationContext())
                                    .load()
                                    .title("Google Sync Failed")
                                    .message(e.getMessage())
                                    .flags(DEFAULT_ALL)
                                    .click(SettingsActivity.class)
                                    //.largeIcon(// TODO Add icon)
                                    .simple()
                                    .build();
                        }

                        @Override
                        public void onSuccess(com.google.api.services.calendar.model.Event event,
                                HttpHeaders responseHeaders) {
                            PugNotification.with(getApplicationContext())
                                    .load()
                                    .title("Google Sync Complete!")
                                    .message("Your Uni Timetable is now on your Google Calendar")
                                    .flags(DEFAULT_ALL)
                                    //.largeIcon(// TODO Add icon)
                                    .simple()
                                    .build();
                        }
                    });
        }
    }

    private AuthenticationCallback getAuthSilentCallback(final List<Event> parsedEvents) {
        return new AuthenticationCallback() {
            @Override
            public void onSuccess(final AuthenticationResult authenticationResult) {
                /* Successfully got a token, call Graph now */

                IClientConfig clientConfig = DefaultClientConfig.createWithAuthenticationProvider(
                        new IAuthenticationProvider() {
                            @Override
                            public void authenticateRequest(IHttpRequest request) {
                                request.addHeader("Authorization", "Bearer "
                                        + authenticationResult.getAccessToken());
                            }
                        });
                mGraphServiceClient = new GraphServiceClient.Builder().fromConfig(
                        clientConfig).buildClient();

                IEventCollectionRequestBuilder eventCollectionRequestBuilder =
                        mGraphServiceClient.getMe()
                                //TODO Support Custom Calendar ID
                                .getCalendars("University Timetable")
                                .getEvents();

                for (Event e : parsedEvents) {
                    eventCollectionRequestBuilder.buildRequest()
                            .post(e);
                }

                PugNotification.with(getApplicationContext())
                        .load()
                        .title("Office Sync Complete!")
                        .message("Your Uni Timetable is now on your Office Calendar")
                        .flags(DEFAULT_ALL)
                        //.largeIcon(// TODO Add icon)
                        .simple()
                        .build();

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
                    PugNotification.with(getApplicationContext())
                            .load()
                            .title("Office Sync Failed")
                            .message("You need to login in again :(")
                            .flags(DEFAULT_ALL)
                            .click(SettingsActivity.class)
                            //.largeIcon(// TODO Add icon)
                            .simple()
                            .build();
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
