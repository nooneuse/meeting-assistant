package io.github.meetingassistant.core;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public final class NetworkClients {
  private NetworkClients() {}

  public static OkHttpClient create() {
    return new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();
  }
}
