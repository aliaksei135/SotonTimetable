package com.aliakseipilko.sotontimetable;

import static android.support.v4.app.NotificationCompat.DEFAULT_ALL;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.util.Log;

import com.aliakseipilko.sotontimetable.models.soton.EventJsonModel;
import com.aliakseipilko.sotontimetable.models.soton.TimetableJsonModel;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.extensions.android.accounts.GoogleAccountManager;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpExecuteInterceptor;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Beta;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.concurrency.ICallback;
import com.microsoft.graph.core.ClientException;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import br.com.goncalves.pugnotification.notification.PugNotification;


public class SotonTimetableService extends JobIntentService {

    final static String OFFICE_CLIENT_ID = "ac6b7a81-b8e2-4a9b-9689-8a5b0be0ac2e";
    final static String OFFICE_SCOPES[] = {"https://graph.microsoft.com/Calendars.ReadWrite"};

    private static final String[] GOOGLE_SCOPES = {CalendarScopes.CALENDAR,};

    private static final String AUTH_ENDPOINT = "https://my.southampton.ac.uk/campusm/sso/ldap/100";
    private static final String EVENTS_ENDPOINT =
            "https://my.southampton.ac.uk/campusm/sso/calendar/course_timetable/";
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
            if (action.equals("com.aliakseipilko.sotontimetable.synctimetable")) {
                SotonTimetableService.enqueueWork(
                        getApplicationContext(),
                        SotonTimetableService.class,
                        1001,
                        intent);
            } else if (action.equals("com.aliakseipilko.sotontimetable.stopsync")) {
                onDestroy();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        // To keep sessions alive
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.aliakseipilko.sotontimetable.synctimetable");
        filter.addAction("com.aliakseipilko.sotontimetable.stopsync");
        registerReceiver(receiver, filter);
        scheduleNextExec();
    }

    private void scheduleNextExec() {
        Intent i = new Intent(getApplicationContext(), SotonTimetableService.class);
        i.setAction("com.aliakseipilko.sotontimetable.synctimetable");

        am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        pi = PendingIntent.getBroadcast(getApplicationContext(), 2053, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        trigger.set(Calendar.HOUR_OF_DAY, 8);
        trigger.set(Calendar.MINUTE, 0);
        Calendar interval = Calendar.getInstance();
        interval.setTimeInMillis(trigger.getTimeInMillis());
        interval.add(Calendar.DATE, 7);


        am.setInexactRepeating(AlarmManager.RTC, trigger.getTimeInMillis(),
                interval.getTimeInMillis(), pi);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
        } catch (ConnectException e) {
            //TODO Impl retries
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            PugNotification.with(getApplicationContext())
                    .load()
                    .title("Timetable Sync Failed")
                    .message("Your SUSSED login didn't work, make sure it's right!")
                    .flags(DEFAULT_ALL)
                    .click(SettingsActivity.class)
                    .smallIcon(R.drawable.pugnotification_ic_launcher)
                    .simple()
                    .build();
        } catch (GoogleAuthException e) {
            e.printStackTrace();
            PugNotification.with(getApplicationContext())
                    .load()
                    .title("Google Sync Failed")
                    .message(e.getMessage())
                    .flags(DEFAULT_ALL)
                    .click(SettingsActivity.class)
                    .smallIcon(R.drawable.pugnotification_ic_launcher)
                    .simple()
                    .build();
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
                    .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                    .setPrettyPrinting()
                    .setLenient()
                    .create();

            TimetableJsonModel timetable = gson.fromJson(isr, TimetableJsonModel.class);

            isr.close();
            eventsConn.disconnect();
            connection.disconnect();
            return timetable;

        } else {
            connection.disconnect();
            throw new IOException("Auth Failed, bad login?");
        }

    }

    public List<Event> parseJsonToOffice(TimetableJsonModel json) {
        List<Event> parsedEvents = new ArrayList<>();

        for (EventJsonModel event : json.events) {
            Event newEvent = new Event();
            newEvent.iCalUId = event.getId();
            newEvent.subject = event.getDesc2();

            ItemBody body = new ItemBody();
            body.content = event.getDesc1() + "\nTeacher: " + event.getTeacherName();
            body.contentType = BodyType.html;
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
            end.setTimeZone("Europe/London");
            newEvent.setEnd(end);

            newEvent.setReminders(
                    new com.google.api.services.calendar.model.Event.Reminders()
                            .setUseDefault(false)
                            .setOverrides(
                                    Arrays.asList(
                                            new EventReminder().setMinutes(20).setMethod("popup")
                                    )
                            )
            );

            newEvent.setAttendeesOmitted(true);

            parsedEvents.add(newEvent);
        }

        return parsedEvents;
    }

    private void addEventsToOffice(List<Event> parsedEvents) {
        try {
            pcApp = new PublicClientApplication(getApplicationContext(), OFFICE_CLIENT_ID);
            pcApp.acquireTokenSilentAsync(OFFICE_SCOPES, pcApp.getUsers().get(0),
                    getAuthSilentCallback(parsedEvents));
        } catch (MsalClientException e) {
            e.printStackTrace();
        } catch (IndexOutOfBoundsException e) {
            PugNotification.with(getApplicationContext())
                    .load()
                    .title("Office Sync Failed")
                    .message("You forgot to sign in!")
                    .flags(DEFAULT_ALL)
                    .click(SettingsActivity.class)
                    .smallIcon(R.drawable.pugnotification_ic_launcher)
                    .simple()
                    .build();
        }
    }

    private void addEventsToGoogle(
            final List<com.google.api.services.calendar.model.Event> parsedEvents)
            throws IOException, GoogleAuthException {
        Account acc = new Account(prefs.getString("accountName", null),
                GoogleAccountManager.ACCOUNT_TYPE);
        final String token = GoogleAuthUtil.getTokenWithNotification(getApplicationContext(), acc,
                "oauth2:" + GOOGLE_SCOPES[0], null);
        HttpRequestInitializer initializer = new HttpRequestInitializer() {
            @Override
            public void initialize(HttpRequest request) {
                RequestHandler handler = new RequestHandler(token);
                request.setInterceptor(handler);
                request.setUnsuccessfulResponseHandler(handler);

            }
        };
        HttpTransport transport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        final com.google.api.services.calendar.Calendar mService =
                new com.google.api.services.calendar.Calendar.Builder(
                        transport, jsonFactory, initializer)
                        .setApplicationName("SotonCal")
                        .build();

        final BatchRequest batch = mService.batch();
        final JsonBatchCallback<com.google.api.services.calendar.model.Event> callback =
                new JsonBatchCallback<com.google.api.services.calendar.model.Event>() {
                    @Override
                    public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                        Log.e(TAG, e.getMessage());
                        PugNotification.with(getApplicationContext())
                                .load()
                                .title("Google Sync Failed")
                                .message(e.getMessage())
                                .flags(DEFAULT_ALL)
                                .click(SettingsActivity.class)
                                .smallIcon(R.drawable.pugnotification_ic_launcher)
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
                                .smallIcon(R.drawable.pugnotification_ic_launcher)
                                .simple()
                                .build();
                    }
                };

        class AddToGoogleTask extends AsyncTask<Void, Void, Void> {

            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    for (com.google.api.services.calendar.model.Event event : parsedEvents) {
                        String calId = prefs.getString("google_cal_id", null);
                        mService.events().insert(calId, event).queue(batch,
                                callback);
                    }
                    batch.execute();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        }
        new AddToGoogleTask().execute();

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
                                request.addHeader("Content-Type", "application/json");
                            }
                        });
                mGraphServiceClient = new GraphServiceClient.Builder().fromConfig(
                        clientConfig).buildClient();


                String calId = prefs.getString("office_cal_id", null);
                final IEventCollectionRequestBuilder eventCollectionRequestBuilder =
                        mGraphServiceClient.getMe()
                                .getCalendars(calId)
                                .getEvents();

                @SuppressLint("StaticFieldLeak")
                class AddToOfficeTask extends AsyncTask<Void, Void, Void> {

                    @Override
                    protected Void doInBackground(Void... voids) {
                        for (Event e : parsedEvents) {
                            eventCollectionRequestBuilder.buildRequest()
                                    .post(e, new ICallback<Event>() {
                                        @Override
                                        public void success(Event event) {
                                            PugNotification.with(getApplicationContext())
                                                    .load()
                                                    .title("Office Sync Complete!")
                                                    .message(
                                                            "Your Uni Timetable is now on your "
                                                                    + "Office Calendar")
                                                    .flags(DEFAULT_ALL)
                                                    .smallIcon(
                                                            R.drawable.pugnotification_ic_launcher)
                                                    .simple()
                                                    .build();
                                        }

                                        @Override
                                        public void failure(ClientException ex) {
                                            Log.e(TAG, ex.getMessage());
                                            PugNotification.with(getApplicationContext())
                                                    .load()
                                                    .title("Office Sync Failed")
                                                    .message(ex.getMessage())
                                                    .flags(DEFAULT_ALL)
                                                    .click(SettingsActivity.class)
                                                    .smallIcon(
                                                            R.drawable.pugnotification_ic_launcher)
                                                    .simple()
                                                    .build();
                                        }
                                    });
                        }
                        return null;
                    }
                }

                new AddToOfficeTask().execute();

                PugNotification.with(getApplicationContext())
                        .load()
                        .title("Office Sync Complete!")
                        .message("Your Uni Timetable is now on your Office Calendar")
                        .flags(DEFAULT_ALL)
                        .smallIcon(R.drawable.pugnotification_ic_launcher)
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
                            .smallIcon(R.drawable.pugnotification_ic_launcher)
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

    @Beta
    class RequestHandler implements HttpExecuteInterceptor, HttpUnsuccessfulResponseHandler {

        /** Whether we've received a 401 error code indicating the token is invalid. */
        boolean received401;
        String token;

        public RequestHandler(String token) {
            this.token = token;
        }

        @Override
        public void intercept(HttpRequest request) {
            request.getHeaders().setAuthorization("Bearer " + token);
        }

        @Override
        public boolean handleResponse(
                HttpRequest request, HttpResponse response, boolean supportsRetry) {
            Log.i(TAG, response.getStatusCode() + response.getStatusMessage());
            if (response.getStatusCode() == 401 && !received401) {
                received401 = true;
                GoogleAuthUtil.invalidateToken(getApplicationContext(), token);
                return true;
            }
            return false;
        }
    }

}

