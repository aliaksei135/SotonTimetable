package com.aliakseipilko.sotontimetable;


import android.Manifest;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.core.DefaultClientConfig;
import com.microsoft.graph.core.IClientConfig;
import com.microsoft.graph.extensions.Calendar;
import com.microsoft.graph.extensions.GraphServiceClient;
import com.microsoft.graph.extensions.ICalendarCollectionRequest;
import com.microsoft.graph.extensions.IGraphServiceClient;
import com.microsoft.graph.http.IHttpRequest;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.AuthenticationResult;
import com.microsoft.identity.client.MsalClientException;
import com.microsoft.identity.client.MsalException;
import com.microsoft.identity.client.MsalServiceException;
import com.microsoft.identity.client.PublicClientApplication;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;


public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        GeneralPreferenceFragment.handleOfficeRedirect(requestCode, resultCode, data);
    }

    public static class GeneralPreferenceFragment extends PreferenceFragment {

        final static String OFFICE_CLIENT_ID = "ac6b7a81-b8e2-4a9b-9689-8a5b0be0ac2e";
        final static String OFFICE_SCOPES[] = {"https://graph.microsoft.com/Calendars.ReadWrite"};
        final static String MSGRAPH_URL = "https://graph.microsoft.com/v1.0/me";
        //Only instantiated for auth
        @SuppressLint("StaticFieldLeak")
        static PublicClientApplication pcApp;
        static final int REQUEST_ACCOUNT_PICKER = 1000;
        static final int REQUEST_AUTHORIZATION = 1001;
        static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
        static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1003;
        private static final String PREF_ACCOUNT_NAME = "accountName";
        private static final String[] GOOGLE_SCOPES = {CalendarScopes.CALENDAR,};
        static ListPreference googleCal;
        static ListPreference officeCal;
        static ListPreference localCal;
        static SharedPreferences prefs;
        @SuppressLint("StaticFieldLeak")
        private static GoogleAccountCredential mCredential;


        private static void handleOfficeRedirect(int requestCode, int resultCode, Intent data) {
            pcApp.handleInteractiveRequestRedirect(requestCode, resultCode, data);
        }


        private void startSotonService() {
            getActivity().startService(new Intent(getActivity(), SotonTimetableService.class));
        }

        private void stopSotonService() {
            getActivity().sendBroadcast(
                    new Intent().setAction("com.aliakseipilko.sotontimetable.stopsync"));
        }

        @SuppressLint("ApplySharedPref")
        public static void setOfficeCalEntries(List<Calendar> cals) {
            String[] entries = new String[cals.size()];
            Set<String> entriesSet = new HashSet<>();
            for (int i = 0; i < cals.size(); i++) {
                String s = cals.get(i).name;
                entries[i] = s;
                entriesSet.add(s);
            }

            String[] values = new String[cals.size()];
            Set<String> valuesSet = new HashSet<>();
            for (int j = 0; j < cals.size(); j++) {
                String s = cals.get(j).id;
                values[j] = s;
                valuesSet.add(s);
            }

            officeCal.setEntries(entries);
            officeCal.setEntryValues(values);

            prefs.edit().putStringSet("office_cal_entries", entriesSet).commit();
            prefs.edit().putStringSet("office_cal_ids", valuesSet).commit();
        }

        @SuppressLint("ApplySharedPref")
        public static void setGoogleCalEntries(List<CalendarListEntry> cals) {
            String[] entries = new String[cals.size()];
            Set<String> entriesSet = new HashSet<>();
            for (int i = 0; i < cals.size(); i++) {
                String s = cals.get(i).getSummary();
                entries[i] = s;
                entriesSet.add(s);
            }

            String[] values = new String[cals.size()];
            Set<String> valuesSet = new HashSet<>();
            for (int j = 0; j < cals.size(); j++) {
                String s = cals.get(j).getId();
                values[j] = s;
                valuesSet.add(s);
            }

            googleCal.setEntries(entries);
            googleCal.setEntryValues(values);

            prefs.edit().putStringSet("google_cal_entries", entriesSet).commit();
            prefs.edit().putStringSet("google_cal_ids", valuesSet).commit();
        }

        // Brace yourself for a monolithic method
        @Override
        public void onCreate(Bundle savedInstanceState) {
            //Crude hack to get the same prefs
            prefs = getActivity().getSharedPreferences(
                    getActivity().getPackageName() + "_preferences", MODE_PRIVATE);
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.pref_general);

            final Preference masterSwitch = findPreference("enable_switch");
            final Preference sotonSignin = findPreference("soton_signin");
            final Preference enabledCals = findPreference("enabled_calendars");

            final Preference localSection = findPreference("local_cal_section");
            localCal = (ListPreference) findPreference("local_cal_id");

            final Preference officeSection = findPreference("office_cal_section");
            final Preference officeSignin = findPreference("office_signin");
            officeCal = (ListPreference) findPreference("office_cal_id");

            final Preference googleSection = findPreference("google_cal_section");
            final Preference googleSignin = findPreference("google_signin");
            googleCal = (ListPreference) findPreference("google_cal_id");

            if (prefs.getBoolean("local_cal_enabled", false)) {
                localSection.setEnabled(true);
            } else {
                localSection.setEnabled(false);
            }
            if (prefs.getBoolean("office_cal_enabled", false)) {
                officeSection.setEnabled(true);
            } else {
                officeSection.setEnabled(false);
            }
            if (prefs.getBoolean("google_cal_enabled", false)) {
                googleSection.setEnabled(true);
            } else {
                googleSection.setEnabled(false);
            }


            masterSwitch.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object o) {
                    if(o.equals(true)){
                        startSotonService();
                        enabledCals.setEnabled(true);
                        if (prefs.getBoolean("local_cal_enabled", false)) {
                            localSection.setEnabled(true);
                        } else {
                            localSection.setEnabled(false);
                        }
                        if (prefs.getBoolean("office_cal_enabled", false)) {
                            officeSection.setEnabled(true);
                        } else {
                            officeSection.setEnabled(false);
                        }
                        if (prefs.getBoolean("google_cal_enabled", false)) {
                            googleSection.setEnabled(true);
                        } else {
                            googleSection.setEnabled(false);
                        }
                    }else{
                        stopSotonService();
                        enabledCals.setEnabled(false);
                        localSection.setEnabled(false);
                        officeSection.setEnabled(false);
                        googleSection.setEnabled(false);
                    }
                    return true;
                }
            });

            enabledCals.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @SuppressLint("ApplySharedPref")
                @Override
                public boolean onPreferenceChange(Preference preference, Object o) {
                    Set<String> cals = (Set<String>) o;

                    if (cals != null) {
                        if (cals.contains("local_cal")) {
                            localSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("local_cal_enabled", true)
                                    .commit();
                        } else {
                            localSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("local_cal_enabled", false)
                                    .commit();
                        }

                        if (cals.contains("office_cal")) {
                            officeSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("office_cal_enabled", true)
                                    .commit();
                        } else {
                            officeSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("office_cal_enabled", false)
                                    .commit();
                        }
                        if (cals.contains("google_cal")) {
                            googleSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("google_cal_enabled", true)
                                    .commit();
                        } else {
                            googleSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("google_cal_enabled", false)
                                    .commit();
                        }
                    }else{
                        localSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("local_cal_enabled", false)
                                .commit();
                        officeSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("office_cal_enabled", false)
                                .commit();
                        googleSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("google_cal_enabled", false)
                                .commit();
                    }
                    return true;
                }
            });

            localCal.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object o) {
                    prefs.edit()
                            .putString("local_cal_id", (String) o)
                            .apply();
                    return true;
                }
            });

            officeSignin.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    authOffice();
                    return true;
                }
            });

            googleSignin.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    authGoogle();
                    return true;
                }
            });
        }

        private AuthenticationCallback getAuthInteractiveCallback() {
            return new AuthenticationCallback() {
                @Override
                public void onSuccess(AuthenticationResult authenticationResult) {
                    /* Successfully got a token, call graph now */
                    Toast.makeText(getActivity(), "Successfully Signed In!", Toast.LENGTH_SHORT)
                            .show();
                    prefs.edit().putBoolean("officeSignedIn", true);
                    new GetOfficeCalendarListTask().execute(authenticationResult.getAccessToken());
                }

                @Override
                public void onError(MsalException exception) {
                    /* Failed to acquireToken */
                    Toast.makeText(getActivity(), "That didn't work!", Toast.LENGTH_SHORT).show();
                    prefs.edit().putBoolean("officeSignedIn", false);

                    if (exception instanceof MsalClientException) {
                        /* Exception inside MSAL, more info inside MsalError.java */
                    } else if (exception instanceof MsalServiceException) {
                        /* Exception when communicating with the STS, likely config issue */
                    }
                }

                @Override
                public void onCancel() {
                    /* User cancelled the authentication */
                }
            };
        }

        /**
         * Attempt to call the API, after verifying that all the preconditions are
         * satisfied. The preconditions are: Google Play Services installed, an
         * account was selected and the device currently has online access. If any
         * of the preconditions are not satisfied, the app will prompt the user as
         * appropriate.
         */
        private void getResultsFromApi() {
            if (!isGooglePlayServicesAvailable()) {
                acquireGooglePlayServices();
            } else if (mCredential.getSelectedAccountName() == null) {
                chooseAccount();
            } else {
                new GetGoogleCalendarListTask().execute();
            }
        }

        private static class GetOfficeCalendarListTask extends AsyncTask<String, Void, Void> {


            @Override
            protected Void doInBackground(final String... strings) {
                /* Successfully got a token, call Graph now */
                IClientConfig clientConfig = DefaultClientConfig.createWithAuthenticationProvider(
                        new IAuthenticationProvider() {
                            @Override
                            public void authenticateRequest(IHttpRequest request) {
                                request.addHeader("Authorization", "Bearer "
                                        + strings[0]);
                                request.addHeader("Content-Type", "application/json");
                            }
                        });
                IGraphServiceClient mGraphServiceClient =
                        new GraphServiceClient.Builder().fromConfig(
                                clientConfig).buildClient();


                final ICalendarCollectionRequest calendarCollectionRequest =
                        mGraphServiceClient.getMe()
                                .getCalendars().buildRequest();

                List<Calendar> cals = calendarCollectionRequest.get().getCurrentPage();
                setOfficeCalEntries(cals);

                return null;
            }
        }

        private void authOffice() {
            pcApp = new PublicClientApplication(getActivity().getApplicationContext(),
                    OFFICE_CLIENT_ID);
            pcApp.acquireToken(getActivity(), OFFICE_SCOPES, getAuthInteractiveCallback());
        }


        private void authGoogle() {

            if (isDeviceOnline()) {
                // Initialize credentials and service object.
                mCredential = GoogleAccountCredential.usingOAuth2(
                        getActivity().getApplicationContext(), Arrays.asList(GOOGLE_SCOPES))
                        .setBackOff(new ExponentialBackOff());
                getResultsFromApi();
            } else {
                Toast.makeText(getActivity(), "No Internet Access!", Toast.LENGTH_SHORT).show();
            }

        }

        /**
         * Called when an activity launched here (specifically, AccountPicker
         * and authorization) exits, giving you the requestCode you started it with,
         * the resultCode it returned, and any additional data from it.
         *
         * @param requestCode code indicating which activity result is incoming.
         * @param resultCode  code indicating the result of the incoming
         *                    activity result.
         * @param data        Intent (containing result data) returned by incoming
         *                    activity result.
         */
        @Override
        public void onActivityResult(
                int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            switch (requestCode) {
                case REQUEST_GOOGLE_PLAY_SERVICES:
                    if (resultCode != RESULT_OK) {
                        //TODO Error notif
                    } else {
                        getResultsFromApi();
                    }
                    break;
                case REQUEST_ACCOUNT_PICKER:
                    if (resultCode == RESULT_OK && data != null &&
                            data.getExtras() != null) {
                        String accountName =
                                data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                        if (accountName != null) {
                            SharedPreferences settings =
                                    getActivity().getSharedPreferences(
                                            getActivity().getPackageName() + "_preferences",
                                            MODE_PRIVATE);
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putString(PREF_ACCOUNT_NAME, accountName);
                            editor.apply();
                            mCredential.setSelectedAccountName(accountName);
                            getResultsFromApi();
                        }
                    }
                    break;
                case REQUEST_AUTHORIZATION:
                    if (resultCode == RESULT_OK) {
                        getResultsFromApi();
                    }
                    break;
            }
        }

        private static class GetGoogleCalendarListTask extends AsyncTask<Void, Void, Void> {

            @Override
            protected Void doInBackground(Void... voids) {
                HttpTransport transport = AndroidHttp.newCompatibleTransport();
                JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
                final com.google.api.services.calendar.Calendar mService =
                        new com.google.api.services.calendar.Calendar.Builder(
                                transport, jsonFactory, mCredential)
                                .setApplicationName("SotonCal")
                                .build();

                try {
                    CalendarList req =
                            mService.calendarList().list().setMinAccessRole("writer").execute();
                    List<CalendarListEntry> cal = req.getItems();

                    setGoogleCalEntries(cal);
                } catch (IOException e) {
                    e.printStackTrace();
                }


                return null;
            }
        }

        /**
         * Attempts to set the account used with the API credentials. If an account
         * name was previously saved it will use that one; otherwise an account
         * picker dialog will be shown to the user. Note that the setting the
         * account to use with the credentials object requires the app to have the
         * GET_ACCOUNTS permission, which is requested here if it is not already
         * present. The AfterPermissionGranted annotation indicates that this
         * function will be rerun automatically whenever the GET_ACCOUNTS permission
         * is granted.
         */
        @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
        private void chooseAccount() {
            if (EasyPermissions.hasPermissions(
                    getActivity(), Manifest.permission.GET_ACCOUNTS)) {
                String accountName = getActivity().getSharedPreferences(
                        getActivity().getPackageName() + "_preferences", MODE_PRIVATE)
                        .getString(PREF_ACCOUNT_NAME, null);
                if (accountName != null) {
                    mCredential.setSelectedAccountName(accountName);
                    getResultsFromApi();
                } else {
                    // Start a dialog from which the user can choose an account
                    startActivityForResult(
                            mCredential.newChooseAccountIntent(),
                            REQUEST_ACCOUNT_PICKER);
                }
            } else {
                // Request the GET_ACCOUNTS permission via a user dialog
                EasyPermissions.requestPermissions(
                        this,
                        "This app needs to access your Google account (via Contacts).",
                        REQUEST_PERMISSION_GET_ACCOUNTS,
                        Manifest.permission.GET_ACCOUNTS);
            }
        }

        /**
         * Respond to requests for permissions at runtime for API 23 and above.
         *
         * @param requestCode  The request code passed in
         *                     requestPermissions(android.app.Activity, String, int, String[])
         * @param permissions  The requested permissions. Never null.
         * @param grantResults The grant results for the corresponding permissions
         *                     which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
         */
        @Override
        public void onRequestPermissionsResult(int requestCode,
                @NonNull String[] permissions,
                @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            EasyPermissions.onRequestPermissionsResult(
                    requestCode, permissions, grantResults, this);
        }


        /**
         * Checks whether the device currently has a network connection.
         *
         * @return true if the device has a network connection, false otherwise.
         */
        private boolean isDeviceOnline() {
            ConnectivityManager connMgr =
                    (ConnectivityManager) getActivity().getSystemService(
                            Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
            return (networkInfo != null && networkInfo.isConnected());
        }

        /**
         * Check that Google Play services APK is installed and up to date.
         *
         * @return true if Google Play Services is available and up to
         *         date on this device; false otherwise.
         */
        private boolean isGooglePlayServicesAvailable() {
            GoogleApiAvailability apiAvailability =
                    GoogleApiAvailability.getInstance();
            final int connectionStatusCode =
                    apiAvailability.isGooglePlayServicesAvailable(getActivity());
            return connectionStatusCode == ConnectionResult.SUCCESS;
        }

        /**
         * Attempt to resolve a missing, out-of-date, invalid or disabled Google
         * Play Services installation via a user dialog, if possible.
         */
        private void acquireGooglePlayServices() {
            GoogleApiAvailability apiAvailability =
                    GoogleApiAvailability.getInstance();
            final int connectionStatusCode =
                    apiAvailability.isGooglePlayServicesAvailable(getActivity());
            if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
                showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
            }
        }


        /**
         * Display an error dialog showing that Google Play Services is missing
         * or out of date.
         *
         * @param connectionStatusCode code describing the presence (or lack of)
         *                             Google Play Services on this device.
         */
        void showGooglePlayServicesAvailabilityErrorDialog(
                final int connectionStatusCode) {
            GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
            Dialog dialog = apiAvailability.getErrorDialog(
                    getActivity(),
                    connectionStatusCode,
                    REQUEST_GOOGLE_PLAY_SERVICES);
            dialog.show();
        }
    }
}
