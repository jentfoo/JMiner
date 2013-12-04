package org.litecoinpool.miner;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Observable;
import java.util.Observer;

import org.threadly.concurrent.PriorityScheduledExecutor;
import org.threadly.concurrent.SubmitterSchedulerInterface;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

public class Miner implements Observer, Runnable {
  private static final String DEFAULT_URL = "http://127.0.0.1:9332/";
  private static final String DEFAULT_AUTH = "rpcuser:rpcpass";
  private static final long DEFAULT_SCAN_TIME = 5000;
  private static final long DEFAULT_RETRY_PAUSE = 30000;
  
  private static final DateFormat logDateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss] ");
  
  private static void log(String str) {
    System.out.println(logDateFormat.format(new Date(Clock.lastKnownTimeMillis())) + str);
  }
  
  public static void main(String[] args) {
    String url = DEFAULT_URL;
    String auth = DEFAULT_AUTH;
    int cpuCount = Runtime.getRuntime().availableProcessors();
    int nThread = cpuCount;
    long scanTime = DEFAULT_SCAN_TIME;
    long retryPause = DEFAULT_RETRY_PAUSE;
    
    if (args.length > 0 && args[0].equals("--help")) {
      System.out.println("Usage:  java Miner [URL] [USERNAME:PASSWORD] [THREADS] [SCANTIME] [RETRYPAUSE]");
      return;
    }
    
    if (args.length > 0)
      url = args[0];
    if (args.length > 1)
      auth = args[1];
    if (args.length > 2)
      nThread = Integer.parseInt(args[2]);
    if (args.length > 3)
      scanTime = Integer.parseInt(args[4]) * 1000L;
    if (args.length > 4)
      retryPause = Integer.parseInt(args[5]) * 1000L;
    
    int minThreadCount = Math.max(cpuCount, nThread);
    int maxThreadCount = Math.max(minThreadCount, cpuCount * 2);
    PriorityScheduledExecutor scheduler = new PriorityScheduledExecutor(minThreadCount, maxThreadCount, 1000 * 10);
    
    try {
      Miner m = new Miner(scheduler, nThread, 
                          url, auth, scanTime, retryPause);
      m.run();
    } catch (Exception e) {
      ExceptionUtils.handleException(e);
    }
  }
  
  private Worker worker;
  private long lastWorkTime;
  private long lastWorkHashes;
  
  public Miner(SubmitterSchedulerInterface scheduler, int nThread, 
               String url, String auth, 
               long scanTime, long retryPause) {
    if (nThread < 1) {
      throw new IllegalArgumentException("Invalid number of threads: "
                                         + nThread);
    } else if (scanTime < 1L) {
      throw new IllegalArgumentException("Invalid scan time: " + scanTime);
    } else if (retryPause < 0L) {
      throw new IllegalArgumentException("Invalid retry pause: " + retryPause);
    }
    
    try {
      worker = new Worker(scheduler, nThread, 
                          new URL(url), auth, 
                          scanTime, retryPause);
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("Invalid URL: " + url);
    }
    worker.addObserver(this);
  }
  
  public void start() {
    Thread t = new Thread(worker);
    t.setPriority(Thread.MIN_PRIORITY);
    t.start();
  }
  
  @Override
  public void run() {
    worker.run();
  }
  
  @Override
  public void update(Observable o, Object arg) {
    Worker.Notification n = (Worker.Notification) arg;
    if (n == Worker.Notification.SYSTEM_ERROR) {
      log("System error");
      System.exit(1);
    } else if (n == Worker.Notification.PERMISSION_ERROR) {
      log("Permission error");
      System.exit(1);
    } else if (n == Worker.Notification.AUTHENTICATION_ERROR) {
      log("Invalid worker username or password");
      System.exit(1);
    } else if (n == Worker.Notification.CONNECTION_ERROR) {
      log("Connection error, retrying in " + worker.getRetryPause() / 1000L
          + " seconds");
    } else if (n == Worker.Notification.COMMUNICATION_ERROR) {
      log("Communication error");
    } else if (n == Worker.Notification.LONG_POLLING_FAILED) {
      log("Long polling failed");
    } else if (n == Worker.Notification.LONG_POLLING_ENABLED) {
      log("Long polling activated");
    } else if (n == Worker.Notification.NEW_BLOCK_DETECTED) {
      log("LONGPOLL detected new block");
    } else if (n == Worker.Notification.POW_TRUE) {
      log("PROOF OF WORK RESULT: true (yay!!!)");
    } else if (n == Worker.Notification.POW_FALSE) {
      log("PROOF OF WORK RESULT: false (booooo)");
    } else if (n == Worker.Notification.NEW_WORK) {
      if (lastWorkTime > 0L) {
        long hashes = worker.getHashes() - lastWorkHashes;
        double speed = (double) hashes / Math.max(1, Clock.lastKnownTimeMillis() - lastWorkTime);
        log(String.format("%d hashes, %.2f khash/s", hashes, speed));
      }
      lastWorkTime = Clock.lastKnownTimeMillis();
      lastWorkHashes = worker.getHashes();
    }
  }
}
