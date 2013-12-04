package org.litecoinpool.miner;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.AccessControlException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.threadly.concurrent.SubmitterSchedulerInterface;
import org.threadly.concurrent.collections.ConcurrentArrayList;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.util.ExceptionUtils;

public class Worker implements Runnable {
  private static final long WORK_TIMEOUT = 60 * 1000; // ms
  
  public static enum Notification {
    SYSTEM_ERROR, PERMISSION_ERROR, CONNECTION_ERROR, AUTHENTICATION_ERROR,
    COMMUNICATION_ERROR, LONG_POLLING_FAILED, LONG_POLLING_ENABLED,
    NEW_BLOCK_DETECTED, NEW_WORK, POW_TRUE, POW_FALSE, TERMINATED
  };
  
  private final SubmitterSchedulerInterface scheduler;
  private final int threadCount;
  private final URL url;
  private final String auth;
  private final long scanTime; // ms
  private final long retryPause; // ms
  
  private volatile Work curWork = null;
  private volatile boolean running = false; // only changed when synchronized to this
  private URL lpUrl = null;
  private HttpURLConnection lpConn = null;
  private AtomicLong hashes = new AtomicLong(0L);
  
  public Worker(SubmitterSchedulerInterface scheduler, int threadCount, 
                URL url, String auth, 
                long scanMillis, long pauseMillis) {
    this.scheduler = scheduler;
    this.threadCount = threadCount;
    this.url = url;
    this.auth = auth;
    this.scanTime = scanMillis;
    this.retryPause = pauseMillis;
  }
  
  public long getRetryPause() {
    return retryPause;
  }
  
  public long getHashes() {
    return hashes.get();
  }
  
  public void stop() {
    synchronized (this) {
      running = false;
      this.notifyAll();
    }
  }
  
  @Override
  public void run() {
    List<Future<?>> futures = new ArrayList<Future<?>>(threadCount + 1);
    running = true;
    for (int i = 0; i < threadCount; ++i) {
      Future<?> f = scheduler.submit(new WorkChecker(i));
      futures.add(f);
    }

    synchronized (this) {
      do {
        try {
          if (curWork == null || lpUrl == null || 
              curWork.getAge() >= WORK_TIMEOUT) {
            curWork = getWork();
            if (lpUrl == null) {
              try {
                if ((lpUrl = curWork.getLongPollingURL()) != null) {
                  Future<?> f = scheduler.submit(new LongPoller());
                  futures.add(f);
                  notifyObservers(Notification.LONG_POLLING_ENABLED);
                }
              } catch (Exception e) {
                ExceptionUtils.handleException(e);
              }
            }
            notifyObservers(Notification.NEW_WORK);
          }
          if (running) {
            this.wait(Math.min(scanTime,
                               Math.max(1L, WORK_TIMEOUT - curWork.getAge())));
          } else {
            break;
          }
        } catch (InterruptedException e) {
          return; // let thread exit
        } catch (NullPointerException e) {
          // TODO - handle?
        }
      } while (running);
      running = false;
    }
    if (lpConn != null) {
      lpConn.disconnect();
    }
    try {
      FutureUtils.blockTillAllCompleteOrFirstError(futures);
    } catch (InterruptedException e) {
      return; // let thread exit
    } catch (ExecutionException e) {
      ExceptionUtils.handleException(e.getCause());
    }
    curWork = null;
    notifyObservers(Notification.TERMINATED);
  }
  
  private final ConcurrentArrayList<WorkerListener> listeners = new ConcurrentArrayList<WorkerListener>(0, 1);

  public void addObserver(WorkerListener o) {
    listeners.addLast(o);
  }
  
  private void notifyObservers(Notification longPollingEnabled) {
    Iterator<WorkerListener> it = listeners.iterator();
    while (it.hasNext()) {
      try {
        it.next().update(longPollingEnabled);
      } catch (Throwable t) {
        ExceptionUtils.handleException(t);
      }
    }
  }

  // should have this locked before calling
  private Work getWork() throws InterruptedException {
    while (running) {
      try {
        return new Work(url, auth);
      } catch (Exception e) {
        if (! running) {
          break;
        }
        if (e instanceof IllegalArgumentException) {
          notifyObservers(Notification.AUTHENTICATION_ERROR);
          stop();
          break;
        } else if (e instanceof AccessControlException) {
          notifyObservers(Notification.PERMISSION_ERROR);
          stop();
          break;
        } else if (e instanceof IOException) {
          notifyObservers(Notification.CONNECTION_ERROR);
        } else {
          notifyObservers(Notification.COMMUNICATION_ERROR);
        }
        curWork = null;
        
        if (running) {
          this.wait(retryPause);
        } else {
          break;
        }
      }
    }
    
    return null;
  }
  
  private class LongPoller implements Runnable {
    private static final int READ_TIMEOUT = 30 * 60 * 1000; // ms
    
    @Override
    public void run() {
      while (running) {
        try {
          lpConn = (HttpURLConnection) lpUrl.openConnection();
          lpConn.setReadTimeout(READ_TIMEOUT);
          curWork = new Work(lpConn, url, auth);
          if (! running) {
            break;
          }
          
          notifyObservers(Notification.NEW_BLOCK_DETECTED);
          notifyObservers(Notification.NEW_WORK);
        } catch (SocketTimeoutException e) {
          // TODO - handle?
        } catch (Exception e) {
          if (!running) {
            break;
          }
          notifyObservers(Notification.LONG_POLLING_FAILED);
          try {
            Thread.sleep(retryPause);
          } catch (InterruptedException ie) {
            return; // let thread exit
          }
        }
      }
      lpUrl = null;
      lpConn = null;
    }
  }
  
  private class WorkChecker implements Runnable {
    private int index;
    private int step;
    
    public WorkChecker(int index) {
      this.index = index;
      for (step = 1; step < threadCount;) {
        step <<= 1;
      }
    }
    
    @Override
    public void run() {
      try {
        Hasher hasher = new Hasher();
        int nonce = index;
        while (running) {
          try {
            if (curWork.meetsTarget(nonce, hasher)) {
              scheduler.execute(new WorkSubmitter(curWork, nonce));
              if (lpUrl == null) {
                synchronized (Worker.this) {
                  curWork = null;
                  Worker.this.notify();
                }
              }
            }
            nonce += step;
            hashes.incrementAndGet();
          } catch (NullPointerException e) {
            try {
              Thread.sleep(1L);
            } catch (InterruptedException ie) {
              return; // let thread exit
            }
          }
        }
      } catch (GeneralSecurityException e) {
        notifyObservers(Notification.SYSTEM_ERROR);
        stop();
      }
    }
  }
  
  private class WorkSubmitter implements Runnable {
    private Work work;
    private int nonce;
    
    public WorkSubmitter(Work w, int nonce) {
      this.work = w;
      this.nonce = nonce;
    }
    
    @Override
    public void run() {
      try {
        boolean result = work.submit(nonce);
        notifyObservers(result ? Notification.POW_TRUE : Notification.POW_FALSE);
      } catch (IOException e) {
        // TODO - handle?
      }
    }
  }
  
  public interface WorkerListener {
    public void update(Worker.Notification n);
  }
}
