/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.exceptions.PreemptiveFastFailException;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.ExceptionUtil;
import org.apache.hadoop.ipc.RemoteException;

import com.google.protobuf.ServiceException;

/**
 * Runs an rpc'ing {@link RetryingCallable}. Sets into rpc client
 * threadlocal outstanding timeouts as so we don't persist too much.
 * Dynamic rather than static so can set the generic appropriately.
 *
 * This object has a state. It should not be used by in parallel by different threads.
 * Reusing it is possible however, even between multiple threads. However, the user will
 *  have to manage the synchronization on its side: there is no synchronization inside the class.
 */
@InterfaceAudience.Private
public class RpcRetryingCallerImpl<T> implements RpcRetryingCaller<T> {
  // LOG is being used in TestMultiRowRangeFilter, hence leaving it public
  public static final Log LOG = LogFactory.getLog(RpcRetryingCallerImpl.class);
  /**
   * When we started making calls.
   */
  private long globalStartTime;

  /** How many retries are allowed before we start to log */
  private final int startLogErrorsCnt;

  private final long pause;
  private final int maxAttempts;// how many times to try
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final RetryingCallerInterceptor interceptor;
  private final RetryingCallerInterceptorContext context;

  public RpcRetryingCallerImpl(long pause, int retries, int startLogErrorsCnt) {
    this(pause, retries, RetryingCallerInterceptorFactory.NO_OP_INTERCEPTOR, startLogErrorsCnt);
  }
  
  public RpcRetryingCallerImpl(long pause, int retries,
      RetryingCallerInterceptor interceptor, int startLogErrorsCnt) {
    this.pause = pause;
    this.maxAttempts = retries + 1;
    this.interceptor = interceptor;
    context = interceptor.createEmptyContext();
    this.startLogErrorsCnt = startLogErrorsCnt;
  }

  private int getRemainingTime(int callTimeout) {
    if (callTimeout <= 0) {
      return 0;
    } else {
      if (callTimeout == Integer.MAX_VALUE) return Integer.MAX_VALUE;
      int remainingTime = (int) (callTimeout -
          (EnvironmentEdgeManager.currentTime() - this.globalStartTime));
      if (remainingTime < 1) {
        // If there is no time left, we're trying anyway. It's too late.
        // 0 means no timeout, and it's not the intent here. So we secure both cases by
        // resetting to the minimum.
        remainingTime = 1;
      }
      return remainingTime;
    }
  }
  
  @Override
  public void cancel(){
    cancelled.set(true);
    synchronized (cancelled){
      cancelled.notifyAll();
    }
  }

  @Override
  public T callWithRetries(RetryingCallable<T> callable, int callTimeout)
  throws IOException, RuntimeException {
    List<RetriesExhaustedException.ThrowableWithExtraContext> exceptions =
      new ArrayList<RetriesExhaustedException.ThrowableWithExtraContext>();
    this.globalStartTime = EnvironmentEdgeManager.currentTime();
    context.clear();
    for (int tries = 0;; tries++) {
      long expectedSleep;
      try {
        callable.prepare(tries != 0); // if called with false, check table status on ZK
        interceptor.intercept(context.prepare(callable, tries));
        return callable.call(getRemainingTime(callTimeout));
      } catch (PreemptiveFastFailException e) {
        throw e;
      } catch (Throwable t) {
        ExceptionUtil.rethrowIfInterrupt(t);
        if (tries > startLogErrorsCnt) {
          LOG.info("Call exception, tries=" + tries + ", maxAttempts=" + maxAttempts + ", started="
              + (EnvironmentEdgeManager.currentTime() - this.globalStartTime) + " ms ago, "
              + "cancelled=" + cancelled.get() + ", msg="
              + callable.getExceptionMessageAdditionalDetail());
        }

        // translateException throws exception when should not retry: i.e. when request is bad.
        interceptor.handleFailure(context, t);
        t = translateException(t);
        callable.throwable(t, maxAttempts != 1);
        RetriesExhaustedException.ThrowableWithExtraContext qt =
            new RetriesExhaustedException.ThrowableWithExtraContext(t,
                EnvironmentEdgeManager.currentTime(), toString());
        exceptions.add(qt);
        if (tries >= maxAttempts - 1) {
          throw new RetriesExhaustedException(tries, exceptions);
        }
        // If the server is dead, we need to wait a little before retrying, to give
        //  a chance to the regions to be
        // tries hasn't been bumped up yet so we use "tries + 1" to get right pause time
        expectedSleep = callable.sleep(pause, tries + 1);

        // If, after the planned sleep, there won't be enough time left, we stop now.
        long duration = singleCallDuration(expectedSleep);
        if (duration > callTimeout) {
          String msg = "callTimeout=" + callTimeout + ", callDuration=" + duration +
              ": " + callable.getExceptionMessageAdditionalDetail();
          throw (SocketTimeoutException)(new SocketTimeoutException(msg).initCause(t));
        }
      } finally {
        interceptor.updateFailureInfo(context);
      }
      try {
        if (expectedSleep > 0) {
          synchronized (cancelled) {
            if (cancelled.get()) return null;
            cancelled.wait(expectedSleep);
          }
        }
        if (cancelled.get()) return null;
      } catch (InterruptedException e) {
        throw new InterruptedIOException("Interrupted after " + tries
            + " tries while maxAttempts=" + maxAttempts);
      }
    }
  }

  /**
   * @return Calculate how long a single call took
   */
  private long singleCallDuration(final long expectedSleep) {
    return (EnvironmentEdgeManager.currentTime() - this.globalStartTime) + expectedSleep;
  }

  @Override
  public T callWithoutRetries(RetryingCallable<T> callable, int callTimeout)
  throws IOException, RuntimeException {
    // The code of this method should be shared with withRetries.
    this.globalStartTime = EnvironmentEdgeManager.currentTime();
    try {
      callable.prepare(false);
      return callable.call(callTimeout);
    } catch (Throwable t) {
      Throwable t2 = translateException(t);
      ExceptionUtil.rethrowIfInterrupt(t2);
      // It would be nice to clear the location cache here.
      if (t2 instanceof IOException) {
        throw (IOException)t2;
      } else {
        throw new RuntimeException(t2);
      }
    }
  }
  
  /**
   * Get the good or the remote exception if any, throws the DoNotRetryIOException.
   * @param t the throwable to analyze
   * @return the translated exception, if it's not a DoNotRetryIOException
   * @throws DoNotRetryIOException - if we find it, we throw it instead of translating.
   */
  static Throwable translateException(Throwable t) throws DoNotRetryIOException {
    if (t instanceof UndeclaredThrowableException) {
      if (t.getCause() != null) {
        t = t.getCause();
      }
    }
    if (t instanceof RemoteException) {
      t = ((RemoteException)t).unwrapRemoteException();
    }
    if (t instanceof LinkageError) {
      throw new DoNotRetryIOException(t);
    }
    if (t instanceof ServiceException) {
      ServiceException se = (ServiceException)t;
      Throwable cause = se.getCause();
      if (cause != null && cause instanceof DoNotRetryIOException) {
        throw (DoNotRetryIOException)cause;
      }
      // Don't let ServiceException out; its rpc specific.
      t = cause;
      // t could be a RemoteException so go around again.
      translateException(t);
    } else if (t instanceof DoNotRetryIOException) {
      throw (DoNotRetryIOException)t;
    }
    return t;
  }

  @Override
  public String toString() {
    return "RpcRetryingCaller{" + "globalStartTime=" + globalStartTime +
        ", pause=" + pause + ", maxAttempts=" + maxAttempts + '}';
  }
}
