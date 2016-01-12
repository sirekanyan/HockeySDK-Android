package net.hockeyapp.android.telemetry;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import net.hockeyapp.android.utils.AsyncTaskUtils;
import net.hockeyapp.android.utils.Util;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h3>Description</h3>
 * <p>
 * The TelemetryManager provides functionality to gather telemetry information about your users,
 * sessions, and – eventually – events and pageviews.
 * </p>
 * <h3>License</h3>
 *
 * <pre>
 * Copyright (c) 2011-2015 Bit Stadium GmbH
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * </pre>
 *
 * @author Benjamin Reimold
 **/
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TelemetryManager implements Application.ActivityLifecycleCallbacks {

  /**
   * The activity counter
   */
  protected static final AtomicInteger activityCount = new AtomicInteger(0);
  /**
   * The timestamp of the last activity
   */
  protected static final AtomicLong lastBackground = new AtomicLong(getTime());
  private static final String TAG = "TelemetryManager";
  /**
   * Background time of the app after which a session gets renewed (in milliseconds).
   */
  private static final Integer SESSION_RENEWAL_INTERVAL = 20 * 1000;

  /**
   * Synchronization LOCK for setting static context
   */
  private static final Object LOCK = new Object();

  /**
   * The only TelemetryManager instance.
   */
  private static volatile TelemetryManager instance;

  /**
   * The application needed for auto collecting session data
   */
  private static WeakReference<Application> weakApplication;

  /**
   * A sender who's responsible to send telemetry to the server
   * TelemetryManager holds a reference to it because we want the user to easily set the server
   * url.
   */
  private static Sender sender;
  /**
   * A channel for collecting new events before storing and sending them.
   */
  private static Channel channel;
  /**
   * A telemetry context which is used to add meta info to events, before they're sent out.
   */
  private static TelemetryContext telemetryContext;
  /**
   * Flag that indicates disabled session tracking.
   * Default is false.
   */
  private volatile boolean sessionTrackingDisabled;

  /**
   * Restrict access to the default constructor
   * Create a new INSTANCE of the TelemetryManager class
   * Contains params for unit testing/mocking
   *
   * @param context          the context that will be used for the SDK
   * @param telemetryContext telemetry context, contains meta-information necessary for telemetry
   *                         feature of the SDK
   * @param sender           usually null, included for unit testing/dependency injection
   * @param persistence,     included for unit testing/dependency injection
   * @param channel,         included for unit testing/dependency injection
   */
  protected TelemetryManager(Context context, TelemetryContext telemetryContext, Sender sender,
                             Persistence persistence, Channel channel) {
    this.telemetryContext = telemetryContext;

    //Important: create sender and persistence first, wire them up and then create the channel!
    if (sender == null) {
      sender = new Sender();
    }
    this.sender = sender;

    if (persistence == null) {
      persistence = new Persistence(context, sender);
    }

    //Link sender
    this.sender.setPersistence(persistence);

    //create the channel and wire the persistence to it.
    if (channel == null) {
      this.channel = new Channel(this.telemetryContext, persistence);
    }
    else {
      this.channel = channel;
    }

  }

  /**
   * Register a new TelemetryManager and collect telemetry information about user and session.
   * HockeyApp App Identifier is read from configuration values in AndroidManifest.xml
   * @param context     The context to use. Usually your Activity object.
   * @param application the Application object which is required to get application lifecycle
   *                    callbacks
   */
  public static void register(Context context, Application application) {
    String appIdentifier = Util.getAppIdentifier(context);
    register(context, application, appIdentifier);
  }

  /**
   * Register a new TelemetryManager and collect telemetry information about user and session.
   *
   * @param application   the Application object which is required to get application lifecycle
   *                      callbacks
   * @param context       The context to use. Usually your Activity object.
   * @param appIdentifier your HockeyApp App Identifier.
   */
  public static void register(Context context, Application application, String appIdentifier) {
    register(context, application, appIdentifier, null, null, null);
  }

  /**
   * Register a new TelemtryManager and collect telemetry information about user and session
   * Intended to be used for unit testing only, shouldn't be visible outside the SDK   *
   *
   * @param context The context to use. Usually your Activity object.
   * @param application the Application object which is required to get application lifecycle
   *                      callbacks
   * @param appIdentifier your HockeyApp App Identifier.
   * @param sender sender for dependency injection
   * @param persistence persistence for dependency injection
   * @param channel channel for dependency injection
   */
  protected static void register(Context context, Application application, String appIdentifier,
                                 Sender sender, Persistence persistence, Channel channel) {
    TelemetryManager result = instance;
    if (result == null) {
      synchronized (LOCK) {
        result = instance;        // thread may have instantiated the objectx
        if (result == null) {
          result = new TelemetryManager(context, new TelemetryContext(context, appIdentifier),
            sender, persistence, channel);
          weakApplication = new WeakReference<>(application);
        }
        if (Util.sessionTrackingSupported()) {
          result.sessionTrackingDisabled = false;
        }
        else {
          result.sessionTrackingDisabled = true;
        }
        instance = result;
        if (!result.sessionTrackingDisabled) {
          setSessionTrackingDisabled(false);
        }

      }
    }
  }

  /**
   * Determines if session tracking was enabled.
   *
   * @return YES if session tracking is enabled
   */
  public static boolean sessionTrackingEnabled() {
    return !instance.sessionTrackingDisabled;
  }

  /**
   * Enable and disable tracking of sessions
   *
   * @param disabled flag to indicate
   */
  public static void setSessionTrackingDisabled(Boolean disabled) {
    if (instance == null) {
      Log.d(TAG, "TelemetryManager hasn't been registered");
    }
    else {
      synchronized (LOCK) {
        if (Util.sessionTrackingSupported()) {
          instance.sessionTrackingDisabled = disabled;
          //TODO persist this setting so the dev doesn't have to take care of this
          //between launches
          if (!disabled) {
            getApplication().registerActivityLifecycleCallbacks(instance);
          }
        }
        else {
          instance.sessionTrackingDisabled = true;
          getApplication().unregisterActivityLifecycleCallbacks(instance);
        }
      }
    }
  }

  /**
   * Set the server url if you want telemetry to be sent to your own server
   *
   * @param serverURL the URL of your custom telemetry server as a String
   */
  public static void setCustomServerURL(String serverURL) {
    if(sender != null) {
      sender.setCustomServerURL(serverURL);
    }
    else {
      Log.w(TAG, "HockeyApp couldn't set the custom server url. Please register(...) the TelemetryManager before setting the server URL.");
    }
  }

  /**
   * Get the reference to the Application (used for life-cycle tracking)
   *
   * @return the reference to the application that was used during initialization of the SDK
   */
  private static Application getApplication() {
    Application application = null;
    if (weakApplication != null) {
      application = weakApplication.get();
    }

    return application;
  }

  /**
   * Get the current time
   *
   * @return the current time in milliseconds
   */
  private static long getTime() {
    return new Date().getTime();
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    // unused but required to implement ActivityLifecycleCallbacks
    //NOTE:
    //This callback doesn't work for the starting
    //activity of the app because the SDK will be setup and initialized in the onCreate, so
    //we don't get the very first call to an app activity's onCreate.
  }

  @Override
  public void onActivityStarted(Activity activity) {
    // unused but required to implement ActivityLifecycleCallbacks
  }

  @Override
  public void onActivityResumed(Activity activity) {
    updateSession();
  }

  @Override
  public void onActivityPaused(Activity activity) {
    //set the timestamp when the app was last send to the background. This will be continuously
    //updated when the user navigates through the app.
    this.lastBackground.set(this.getTime());
  }

  @Override
  public void onActivityStopped(Activity activity) {
    // unused but required to implement ActivityLifecycleCallbacks
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    // unused but required to implement ActivityLifecycleCallbacks
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    // unused but required to implement ActivityLifecycleCallbacks
  }

  /**
   * Updates the session. If session tracking is enabled, a new session will be started for the
   * first activity.
   * In case we have already started a session, we determine if we should renew a session.
   * This is done by comparing NOW with the last time, onPause has been called.
   */
  private void updateSession() {
    int count = this.activityCount.getAndIncrement();
    if (count == 0) {
      if (sessionTrackingEnabled()) {
        Log.d(TAG, "Starting & tracking session");
        renewSession();
      }
      else {
        Log.d(TAG, "Session management disabled by the developer");
      }
    }
    else {
      //we should already have a session now
      //check if the session should be renewed
      long now = this.getTime();
      long then = this.lastBackground.getAndSet(getTime());
      //TODO save session intervall in configuration file?
      boolean shouldRenew = ((now - then) >= SESSION_RENEWAL_INTERVAL);
      Log.d(TAG, "Checking if we have to renew a session, time difference is: " + (now - then));

      if (shouldRenew && sessionTrackingEnabled()) {
        Log.d(TAG, "Renewing session");
        renewSession();
      }
    }
  }

  protected void renewSession() {
    String sessionId = UUID.randomUUID().toString();
    telemetryContext.updateSessionContext(sessionId);
    trackSessionState(SessionState.START);
  }

  /**
   * Creates and enqueues a session event for the given state.
   *
   * @param sessionState value that determines whether the session started or ended
   */
  private void trackSessionState(final SessionState sessionState) {
    AsyncTaskUtils.execute(new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        SessionStateData sessionItem = new SessionStateData();
        sessionItem.setState(sessionState);
        Data<Domain> data = createData(sessionItem);
        channel.log(data);
        return null;
      }
    });
  }

  /**
   * Pack and forward the telemetry item to the queue.
   *
   * @param telemetryData The telemetry event to be persisted and sent
   * @return a base data object containing the telemetry data
   */
  protected Data<Domain> createData(TelemetryData telemetryData) {
    Data<Domain> data = new Data<Domain>();
    data.setBaseData(telemetryData);
    data.setBaseType(telemetryData.getBaseType());
    data.QualifiedName = telemetryData.getEnvelopeName();

    return data;
  }

  protected static Channel getChannel() {
    return TelemetryManager.channel;
  }

  protected void setChannel(Channel channel) {
    TelemetryManager.channel = channel;
  }

  protected static Sender getSender() {
    return TelemetryManager.sender;
  }

  protected static void setSender(Sender sender) {
    TelemetryManager.sender = sender;
  }

  protected static TelemetryManager getInstance() {
    return instance;
  }
}
