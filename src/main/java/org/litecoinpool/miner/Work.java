package org.litecoinpool.miner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.threadly.util.Clock;

public class Work {
  private static final int DEFAULT_TIMEOUT = 10000; // ms
  private static final Pattern dataPattern = Pattern.compile("\"data\"\\s*:\\s*\"([0-9a-f]+)\"");
  private static final Pattern targetPattern = Pattern.compile("\"target\"\\s*:\\s*\"([0-9a-f]+)\"");
  private static final Pattern resultPattern = Pattern.compile("\"result\"\\s*:\\s*([0-9A-Za-z]+)");
  
  private URL url;
  private String auth;
  private long responseTime;
  private String xLongPolling = null;
  
  private byte[] data; // little-endian
  private byte[] target; // little-endian
  private byte[] header; // big-endian
  
  public Work(URL url, String auth) throws IOException {
    this(url, url, auth);
  }
  
  public Work(URL url, URL mainUrl, String auth) throws IOException {
    this((HttpURLConnection) url.openConnection(), url, auth);
  }
  
  public Work(HttpURLConnection conn, 
              URL mainUrl, String auth) throws IOException {
    String request = "{\"method\": \"getwork\", \"params\": [], \"id\":0}";
    
    conn = getJsonRpcConnection(conn, request, auth);
    int response = conn.getResponseCode();
    if (response == 401 || response == 403) {
      throw new IllegalArgumentException("Access denied");
    }
    String content = getConnectionContent(conn);
    
    responseTime = Clock.lastKnownTimeMillis();
    Matcher m = dataPattern.matcher(content);
    if (! m.find()) {
      throw new RuntimeException(content);
    }
    String sData = m.group(1);
    data = hexStringToByteArray(sData);
    m = targetPattern.matcher(content);
    if (! m.find()) {
      throw new RuntimeException(content);
    }
    String sTarget = m.group(1);
    target = hexStringToByteArray(sTarget);
    header = headerByData(data);
    xLongPolling = conn.getHeaderField("X-Long-Polling");
    this.url = mainUrl;
    this.auth = auth;
  }
  
  public boolean submit(int nonce) throws IOException {
    byte[] d = data.clone();
    d[79] = (byte) (nonce >> 0);
    d[78] = (byte) (nonce >> 8);
    d[77] = (byte) (nonce >> 16);
    d[76] = (byte) (nonce >> 24);
    String sData = byteArrayToHexString(d);
    String request = "{\"method\": \"getwork\", \"params\": [ \"" + sData + "\" ], \"id\":1}";
    
    HttpURLConnection conn = getJsonRpcConnection(url, request, auth);
    String content = getConnectionContent(conn);
    
    Matcher m = resultPattern.matcher(content);
    if (m.find() && m.group(1).equals("true")) {
      return true;
    } else {
      return false;
    }
  }
  
  protected boolean meetsTarget(int nonce, Hasher hasher) throws GeneralSecurityException {
    byte[] hash = hasher.hash(header, nonce);
    for (int i = hash.length - 1; i >= 0; i--) {
      int hashValue = (hash[i] & 0xff);
      int targetValue = (target[i] & 0xff);
      if (hashValue > targetValue) {
        return false;
      } else if (hashValue < targetValue) {
        return true;
      }
    }
    return true;
  }
  
  public byte[] getData() {
    return data;
  }
  
  public byte[] getTarget() {
    return target;
  }
  
  public byte[] getHeader() {
    return header;
  }
  
  public long getResponseTime() {
    return responseTime;
  }
  
  public long getAge() {
    return Clock.lastKnownTimeMillis() - responseTime;
  }
  
  public URL getLongPollingURL() throws URISyntaxException,
                                        MalformedURLException {
    if (xLongPolling == null) {
      return null;
    }
    return url.toURI().resolve(xLongPolling).toURL();
  }
  
  private static byte[] headerByData(byte[] data) {
    byte[] h = new byte[80];
    for (int i = 0; i < 80; i += 4) {
      h[i] = data[i + 3];
      h[i + 1] = data[i + 2];
      h[i + 2] = data[i + 1];
      h[i + 3] = data[i];
    }
    return h;
  }
  
  protected static String byteArrayToHexString(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    
    for (int i = 0; i < b.length; i++) {
      sb.append(Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1));
    }
    
    return sb.toString();
  }
  
  protected static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte)((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1),
                                                                                     16));
    }
    
    return data;
  }
  
  public static HttpURLConnection getJsonRpcConnection(URL url, String request,
                                                       String auth) throws IOException {
    return getJsonRpcConnection((HttpURLConnection) url.openConnection(),
                                request, auth);
  }
  
  public static HttpURLConnection getJsonRpcConnection(HttpURLConnection conn,
                                                       String request,
                                                       String auth) throws IOException {
    if (conn.getConnectTimeout() == 0) {
      conn.setConnectTimeout(DEFAULT_TIMEOUT);
    }
    if (conn.getReadTimeout() == 0) {
      conn.setReadTimeout(DEFAULT_TIMEOUT);
    }
    conn.setRequestMethod("POST");
    if (auth != null) {
      conn.setRequestProperty("Authorization", "Basic " + Base64.stringToBase64(auth));
    }
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Content-Length",
                            Integer.toString(request.getBytes().length));
    conn.setRequestProperty("X-Mining-Extensions", "midstate");
    conn.setAllowUserInteraction(false);
    conn.setUseCaches(false);
    conn.setDoOutput(true);
    
    DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
    wr.writeBytes(request);
    wr.close();
    return conn;
  }
  
  public static String getConnectionContent(HttpURLConnection conn) throws IOException {
    InputStream is = conn.getInputStream();
    ByteArrayOutputStream bos = new ByteArrayOutputStream(0);
    int len;
    byte[] buffer = new byte[4096];
    while ((len = is.read(buffer)) != -1) {
      bos.write(buffer, 0, len);
    }
    String content = bos.toString();
    is.close();
    return content;
  }
}
