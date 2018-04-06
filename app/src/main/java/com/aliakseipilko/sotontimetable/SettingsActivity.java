package com.aliakseipilko.sotontimetable;


import android.Manifest;
import android.accounts.AccountManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.support.annotation.NonNull;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.calendar.CalendarScopes;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.AuthenticationResult;
import com.microsoft.identity.client.MsalClientException;
import com.microsoft.identity.client.MsalException;
import com.microsoft.identity.client.MsalServiceException;
import com.microsoft.identity.client.PublicClientApplication;

import org.greenrobot.eventbus.EventBus;

import java.util.Arrays;
import java.util.Set;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;


public class SettingsActivity extends AppCompatPreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    protected boolean isValidFragment(String fragmentName) {
        return PreferenceFragment.class.getName().equals(fragmentName)
                || GeneralPreferenceFragment.class.getName().equals(fragmentName);
    }

    public static class GeneralPreferenceFragment extends PreferenceFragment {

        final static String OFFICE_CLIENT_ID = "ac6b7a81-b8e2-4a9b-9689-8a5b0be0ac2e";
        final static String OFFICE_SCOPES[] = {"https://graph.microsoft.com/Calendar.ReadWrite"};
        final static String MSGRAPH_URL = "https://graph.microsoft.com/v1.0/me";
        private PublicClientApplication pcApp;
        private AuthenticationResult authResult;
        static final int REQUEST_ACCOUNT_PICKER = 1000;
        static final int REQUEST_AUTHORIZATION = 1001;
        static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
        static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1003;
        private static final String PREF_ACCOUNT_NAME = "accountName";
        private static final String[] GOOGLE_SCOPES = {CalendarScopes.CALENDAR,};
        private GoogleAccountCredential mCredential;

        // Brace yourself for a monolithic method
        @Override
        public void onCreate(Bundle savedInstanceState) {
            final SharedPreferences prefs = getActivity().getSharedPreferences(getActivity().getPackageName(), MODE_PRIVATE);
            super.onCreate(savedInstanceState);

            pcApp = new PublicClientApplication(getActivity(), OFFICE_CLIENT_ID);


            addPreferencesFromResource(R.xml.pref_general);

            final Preference masterSwitch = findPreference("enable_switch");
            final Preference sotonSignin = findPreference("soton_signin");
            final Preference enabledCals = findPreference("enabled_calendars");

            final Preference localSection = findPreference("local_cal_section");
            final Preference localCal = findPreference("local_cal_id");

            final Preference officeSection = findPreference("office_cal_section");
            final Preference officeSignin = findPreference("office_signin");
            final Preference officeCal = findPreference("office_cal_id");

            final Preference googleSection = findPreference("google_cal_section");
            final Preference googleSignin = findPreference("google_signin");
            final Preference googleCal = findPreference("google_cal_id");


            masterSwitch.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object o) {
                    if(o.equals(true)){
                        startSotonService();
                        enabledCals.setEnabled(true);
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
                @Override
                public boolean onPreferenceChange(Preference preference, Object o) {
                    Set<String> cals = preference.getSharedPreferences().getStringSet("enabled_calendars", null);

                    if (cals != null) {
                        if (cals.contains("local_cal")) {
                            localSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("local_cal_enabled", true)
                                    .apply();
                        } else {
                            localSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("local_cal_enabled", false)
                                    .apply();
                        }

                        if (cals.contains("office_cal")) {
                            officeSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("office_cal_enabled", true)
                                    .apply();
                        } else {
                            officeSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("office_cal_enabled", false)
                                    .apply();
                        }
                        if (cals.contains("google_cal")) {
                            googleSection.setEnabled(true);
                            prefs.edit()
                                    .putBoolean("google_cal_enabled", true)
                                    .apply();
                        } else {
                            googleSection.setEnabled(false);
                            prefs.edit()
                                    .putBoolean("google_cal_enabled", false)
                                    .apply();
                        }
                    }else{
                        localSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("local_cal_enabled", false)
                                .apply();
                        officeSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("office_cal_enabled", false)
                                .apply();
                        googleSection.setEnabled(false);
                        prefs.edit()
                                .putBoolean("google_cal_enabled", false)
                                .apply();
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

        private void authGoogle() {

            if (isDeviceOnline()) {
                // Initialize credentials and service object.
                mCredential = GoogleAccountCredential.usingOAuth2(
                        getActivity().getApplicationContext(), Arrays.asList(GOOGLE_SCOPES))
                        .setBackOff(new ExponentialBackOff());
            } else {
                Toast.makeText(getActivity(), "No Internet Access!", Toast.LENGTH_SHORT).show();
            }

        }


        private void startSotonService() {
            getActivity().startService(new Intent(getActivity(), SotonTimetableService.class));
        }

        private void stopSotonService(){
            getActivity().sendBroadcast(new Intent().setAction("com.aliakseipilko.sotontimetable.stopsync"));
        }

        private void authOffice(){
            pcApp.acquireToken(getActivity(), OFFICE_SCOPES, getAuthInteractiveCallback());
        }

        private AuthenticationCallback getAuthInteractiveCallback() {
            return new AuthenticationCallback() {
                @Override
                public void onSuccess(AuthenticationResult authenticationResult) {
                    /* Successfully got a token, call graph now */
                    Toast.makeText(getActivity(), "Successfully Signed In!", Toast.LENGTH_SHORT).show();


                    /* Store the auth result */
                    authResult = authenticationResult;
                    setToken(authenticationResult.getAccessToken());
                }

                @Override
                public void onError(MsalException exception) {
                    /* Failed to acquireToken */

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

        private void setToken(String accessToken) {
            getActivity().getSharedPreferences(getActivity().getPackageName(), MODE_PRIVATE)
                    .edit()
                    .putString("office_access_token", accessToken)
                    .apply();
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
                                            getActivity().getPackageName(), MODE_PRIVATE);
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
                EventBus.getDefault().post(mCredential);
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
                        getActivity().getPackageName(), MODE_PRIVATE)
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
