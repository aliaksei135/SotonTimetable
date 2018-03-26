package com.aliakseipilko.sotontimetable;

import com.microsoft.identity.client.PublicClientApplication;

public class OfficeAuthEvent {

    private String accessToken;
    private PublicClientApplication pcApp;

    public OfficeAuthEvent(String accessToken,
            PublicClientApplication pcApp) {
        this.accessToken = accessToken;
        this.pcApp = pcApp;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public PublicClientApplication getPcApp() {
        return pcApp;
    }
}
