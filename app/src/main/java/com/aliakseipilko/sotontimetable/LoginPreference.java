/*
 * Copyright 2018 Aliaksei Pilko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aliakseipilko.sotontimetable;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;


public class LoginPreference extends DialogPreference {

    private static String login_key;
    private static String password_key;

    private EditText login;
    private EditText password;

    public LoginPreference(Context context) {
        this(context, null);
    }

    public LoginPreference(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.dialogPreferenceStyle);
    }

    public LoginPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, defStyleAttr);
    }

    public LoginPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LoginPreference);
            login_key = a.getString(R.styleable.LoginPreference_login_key);
            password_key = a.getString(R.styleable.LoginPreference_password_key);
            a.recycle();
        }
        setPositiveButtonText("Login");
        setDialogLayoutResource(R.layout.login_preference);
    }

    @Override
    public int getDialogLayoutResource() {
        return R.layout.login_preference;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        login = view.findViewById(R.id.loginpref_login);
        password = view.findViewById(R.id.loginpref_password);
        login.setText(getSharedPreferences().getString(login_key, null));
        password.setText(getSharedPreferences().getString(password_key, null));
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult) {
            String l = login.getText().toString().toLowerCase().trim();
            String p = password.getText().toString();

            getSharedPreferences().edit()
                    .putString(login_key, l)
                    .putString(password_key, p)
                    .apply();
        }
    }
}
