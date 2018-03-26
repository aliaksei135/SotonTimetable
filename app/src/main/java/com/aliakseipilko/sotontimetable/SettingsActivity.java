package com.aliakseipilko.sotontimetable;


import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.widget.Toast;

import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.AuthenticationResult;
import com.microsoft.identity.client.MsalClientException;
import com.microsoft.identity.client.MsalException;
import com.microsoft.identity.client.MsalServiceException;
import com.microsoft.identity.client.PublicClientApplication;

import java.util.Set;


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

        final static String CLIENT_ID = "";
        final static String SCOPES [] = {"https://graph.microsoft.com/Calendar.ReadWrite"};
        final static String MSGRAPH_URL = "https://graph.microsoft.com/v1.0/me";

        private PublicClientApplication pcApp;
        private AuthenticationResult authResult;

        // Brace yourself for a monolithic method
        @Override
        public void onCreate(Bundle savedInstanceState) {
            final SharedPreferences prefs = getActivity().getSharedPreferences(getActivity().getPackageName(), MODE_PRIVATE);
            super.onCreate(savedInstanceState);

            pcApp = new PublicClientApplication(getActivity(), CLIENT_ID);


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
        }

        private void startSotonService() {
            getActivity().startService(new Intent(getActivity(), SotonTimetableService.class));
        }

        private void stopSotonService(){
            getActivity().sendBroadcast(new Intent().setAction("com.aliakseipilko.sotontimetable.stopsync"));
        }

        private void authOffice(){
            pcApp.acquireToken(getActivity(), SCOPES, getAuthInteractiveCallback());
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
    }
}
