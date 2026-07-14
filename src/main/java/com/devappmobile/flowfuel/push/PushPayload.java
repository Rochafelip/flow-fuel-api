package com.devappmobile.flowfuel.push;

public record PushPayload(String title, String body, String deepLink, String type) {

    public PushPayload(String title, String body, String deepLink) {
        this(title, body, deepLink, "generic");
    }
}
