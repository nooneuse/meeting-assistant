package io.github.meetingassistant.core;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Fixed local error labels, without potentially sensitive remote exception messages. */
public final class NetworkErrors {
  public static String describe(String service, Throwable failure) {
    Throwable cause = failure;
    for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
      if (cause instanceof UnknownHostException) return service + "地址解析失败，请检查服务地址和手机网络";
      if (cause instanceof SSLException) return service + "安全连接失败，请检查系统时间和网络";
      if (cause instanceof SocketTimeoutException) return service + "连接超时，请检查网络后重试";
      if (cause instanceof ConnectException || cause instanceof NoRouteToHostException)
        return service + "无法建立连接，请检查网络和服务地址";
    }
    return service + "连接中断，请检查网络后重试";
  }

  private NetworkErrors() {}
}
