package org.enso.shttp.cloud_mock;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import org.enso.shttp.HttpMethod;

public interface CloudHandler {
  boolean canHandle(String subPath);

  void handleCloudAPI(CloudExchange exchange) throws IOException;

  interface CloudExchange {
    HttpExchange getHttpExchange();

    String subPath();

    void sendResponse(int code, String response) throws IOException;

    String decodeBodyAsText() throws IOException;

    HttpMethod getMethod();
  }
}
