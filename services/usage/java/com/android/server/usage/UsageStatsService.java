/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemConfig;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service that collects, aggregates, and persists application usage data.
 * This data can be queried by apps that have been granted permission by AppOps.
 */
public class UsageStatsService extends SystemService implements
        UserUsageStatsService.StatsUpdatedListener {
    static final String TAG = "UsageStatsService";

    static final boolean DEBUG = false;
    private static final long TEN_SECONDS = 10 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long FLUSH_INTERVAL = DEBUG ? TEN_SECONDS : TWENTY_MINUTES;
    private static final long TIME_CHANGE_THRESHOLD_MILLIS = 2 * 1000; // Two seconds.
    static final long DEFAULT_APP_IDLE_THRESHOLD_MILLIS = 2L * 24 * 60 * 60 * 1000; // 1 day
    static final long DEFAULT_CHECK_IDLE_INTERVAL = 8 * 3600 * 1000; // 8 hours

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_INFORM_LISTENERS = 3;
    static final int MSG_RESET_LAST_TIMESTAMP = 4;
    static final int MSG_CHECK_IDLE_STATES = 5;

    private final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;
    AppWidgetManager mAppWidgetManager;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private File mUsageStatsDir;
    long mRealTimeSnapshot;
    long mSystemTimeSnapshot;
    boolean mAppIdleParoled;

    long mAppIdleDurationMillis;

    long mCheckIdleIntervalMillis = DEFAULT_CHECK_IDLE_INTERVAL;

    private ArrayList<UsageStatsManagerInternal.AppIdleStateChangeListener>
            mPackageAccessListeners = new ArrayList<>();

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);

        mHandler = new H(BackgroundThread.get().getLooper());

        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        mUsageStatsDir = new File(systemDataDir, "usagestats");
        mUsageStatsDir.mkdirs();
        if (!mUsageStatsDir.exists()) {
            throw new IllegalStateException("Usage stats directory does not exist: "
                    + mUsageStatsDir.getAbsolutePath());
        }

        IntentFilter userActions = new IntentFilter(Intent.ACTION_USER_REMOVED);
        userActions.addAction(Intent.ACTION_USER_STARTED);
        getContext().registerReceiverAsUser(new UserActionsReceiver(), UserHandle.ALL, userActions,
                null, null);

        IntentFilter deviceStates = new IntentFilter(BatteryManager.ACTION_CHARGING);
        deviceStates.addAction(BatteryManager.ACTION_DISCHARGING);
        getContext().registerReceiver(new DeviceStateReceiver(), deviceStates);
        synchronized (mLock) {
            cleanUpRemovedUsersLocked();
        }

        mRealTimeSnapshot = SystemClock.elapsedRealtime();
        mSystemTimeSnapshot = System.currentTimeMillis();
        // Look at primary user's secure setting for this. TODO: Maybe apply different
        // thresholds for different users.
        mAppIdleDurationMillis = Settings.Secure.getLongForUser(getContext().getContentResolver(),
                Settings.Secure.APP_IDLE_DURATION, DEFAULT_APP_IDLE_THRESHOLD_MILLIS,
                UserHandle.USER_OWNER);

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // Observe changes to the threshold
            new SettingsObserver(mHandler).registerObserver();
            mAppWidgetManager = getContext().getSystemService(AppWidgetManager.class);
        } else if (phase == PHASE_BOOT_COMPLETED) {
            setAppIdleParoled(getContext().getSystemService(BatteryManager.class).isCharging());
        }
    }

    private class UserActionsReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                if (userId >= 0) {
                    mHandler.obtainMessage(MSG_REMOVE_USER, userId, 0).sendToTarget();
                }
            } else if (Intent.ACTION_USER_STARTED.equals(intent.getAction())) {
                if (userId >=0) {
                    postCheckIdleStates();
                }
            }
        }
    }

    private class DeviceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BatteryManager.ACTION_CHARGING.equals(action)
                    || BatteryManager.ACTION_DISCHARGING.equals(action)) {
                setAppIdleParoled(BatteryManager.ACTION_CHARGING.equals(action));
            }
        }
    }

    @Override
    public void onStatsUpdated() {
        mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
    }

    private void cleanUpRemovedUsersLocked() {
        final List<UserInfo> users = mUserManager.getUsers(true);
        if (users == null || users.size() == 0) {
            throw new IllegalStateException("There can't be no users");
        }

        ArraySet<String> toDelete = new ArraySet<>();
        String[] fileNames = mUsageStatsDir.list();
        if (fileNames == null) {
            // No users to delete.
            return;
        }

        toDelete.addAll(Arrays.asList(fileNames));

        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            final UserInfo userInfo = users.get(i);
            toDelete.remove(Integer.toString(userInfo.id));
        }

        final int deleteCount = toDelete.size();
        for (int i = 0; i < deleteCount; i++) {
            deleteRecursively(new File(mUsageStatsDir, toDelete.valueAt(i)));
        }
    }

    void setAppIdleParoled(boolean paroled) {
        synchronized (mLock) {
            if (mAppIdleParoled != paroled) {
                mAppIdleParoled = paroled;
                postCheckIdleStates();
            }
        }
    }

    void postCheckIdleStates() {
        mHandler.removeMessages(MSG_CHECK_IDLE_STATES);
        mHandler.sendEmptyMessage(MSG_CHECK_IDLE_STATES);
    }

    /** Check all running users' apps to see if they enter an idle state. */
    void checkIdleStates() {
        final int[] runningUsers;
        try {
            runningUsers = ActivityManagerNative.getDefault().getRunningUserIds();
        } catch (RemoteException re) {
            return;
        }

        for (int i = 0; i < runningUsers.length; i++) {
            final int userId = runningUsers[i];
            List<PackageInfo> packages =
                    getContext().getPackageManager().getInstalledPackages(
                            PackageManager.GET_DISABLED_COMPONENTS
                                | PackageManager.GET_UNINSTALLED_PACKAGES,
                            userId);
            synchronized (mLock) {
                final int packageCount = packages.size();
                for (int p = 0; p < packageCount; p++) {
                    final String packageName = packages.get(p).packageName;
                    final boolean isIdle = isAppIdle(packageName, userId);
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS,
                            userId, isIdle ? 1 : 0, packageName));
                }
            }
        }
        mHandler.sendEmptyMessageDelayed(MSG_CHECK_IDLE_STATES, mCheckIdleIntervalMillis);
    }

    private static void deleteRecursively(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File subFile : files) {
                deleteRecursively(subFile);
            }
        }

        if (!f.delete()) {
            Slog.e(TAG, "Failed to delete " + f);
        }
    }

    private UserUsageStatsService getUserDataAndInitializeIfNeededLocked(int userId,
            long currentTimeMillis) {
        UserUsageStatsService service = mUserState.get(userId);
        if (service == null) {
            service = new UserUsageStatsService(getContext(), userId,
                    new File(mUsageStatsDir, Integer.toString(userId)), this);
            service.init(currentTimeMillis);
            mUserState.put(userId, service);
        }
        return service;
    }

    /**
     * This should be the only way to get the time from the system.
     */
    private long checkAndGetTimeLocked() {
        final long actualSystemTime = System.currentTimeMillis();
        final long actualRealtime = SystemClock.elapsedRealtime();
        final long expectedSystemTime = (actualRealtime - mRealTimeSnapshot) + mSystemTimeSnapshot;
        if (Math.abs(actualSystemTime - expectedSystemTime) > TIME_CHANGE_THRESHOLD_MILLIS) {
            // The time has changed.
            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                final UserUsageStatsService service = mUserState.valueAt(i);
                service.onTimeChanged(expectedSystemTime, actualSystemTime);
            }
            mRealTimeSnapshot = actualRealtime;
            mSystemTimeSnapshot = actualSystemTime;
        }
        return actualSystemTime;
    }

    /**
     * Assuming the event's timestamp is measured in milliseconds since boot,
     * convert it to a system wall time.
     */
    private void convertToSystemTimeLocked(UsageEvents.Event event) {
        event.mTimeStamp = Math.max(0, event.mTimeStamp - mRealTimeSnapshot) + mSystemTimeSnapshot;
    }

    /**
     * Called by the Binder stub
     */
    void shutdown() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_REPORT_EVENT);
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void reportEvent(UsageEvents.Event event, int userId) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            convertToSystemTimeLocked(event);

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            final long lastUsed = service.getLastPackageAccessTime(event.mPackage);
            final boolean previouslyIdle = hasPassedIdleDuration(lastUsed);
            service.reportEvent(event);
            // Inform listeners if necessary
            if ((event.mEventType == Event.MOVE_TO_FOREGROUND
                    || event.mEventType == Event.MOVE_TO_BACKGROUND
                    || event.mEventType == Event.INTERACTION)) {
                if (previouslyIdle) {
                    // Slog.d(TAG, "Informing listeners of out-of-idle " + event.mPackage);
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, userId,
                            /* idle = */ 0, event.mPackage));
                }
            }
        }
    }

    /**
     * Forces the app's timestamp to reflect idle or active. If idle, then it rolls back the
     * last used timestamp to a point in time thats behind the threshold for idle.
     */
    void resetLastTimestamp(String packageName, int userId, boolean idle) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            final long lastTimestamp = timeNow - (idle ? mAppIdleDurationMillis : 0) - 5000;

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            final long lastUsed = service.getLastPackageAccessTime(packageName);
            final boolean previouslyIdle = hasPassedIdleDuration(lastUsed);
            service.setLastTimestamp(packageName, lastTimestamp);
            // Inform listeners if necessary
            if (previouslyIdle != idle) {
                // Slog.d(TAG, "Informing listeners of out-of-idle " + event.mPackage);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, userId,
                        /* idle = */ idle ? 1 : 0, packageName));
            }
        }
    }

    /**
     * Called by the Binder stub.
     */
    void flushToDisk() {
        synchronized (mLock) {
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void removeUser(int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            mUserState.remove(userId);
            cleanUpRemovedUsersLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryUsageStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<ConfigurationStats> queryConfigurationStats(int userId, int bucketType, long beginTime,
            long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryConfigurationStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEvents(int userId, long beginTime, long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEvents(beginTime, endTime);
        }
    }

    /**
     * Called by LocalService stub.
     */
    long getLastPackageAccessTime(String packageName, int userId) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            // android package is always considered non-idle.
            // TODO: Add a generic whitelisting mechanism
            if (packageName.equals("android")) {
                return timeNow;
            }
            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.getLastPackageAccessTime(packageName);
        }
    }

    void addListener(AppIdleStateChangeListener listener) {
        synchronized (mLock) {
            if (!mPackageAccessListeners.contains(listener)) {
                mPackageAccessListeners.add(listener);
            }
        }
    }

    void removeListener(AppIdleStateChangeListener listener) {
        synchronized (mLock) {
            mPackageAccessListeners.remove(listener);
        }
    }

    private boolean hasPassedIdleDuration(long lastUsed) {
        final long now = System.currentTimeMillis();
        return lastUsed <= now - mAppIdleDurationMillis;
    }

    boolean isAppIdle(String packageName, int userId) {
        if (packageName == null) return false;
        synchronized (mLock) {
            // Temporary exemption, probably due to device charging or occasional allowance to
            // be allowed to sync, etc.
            if (mAppIdleParoled) {
                return false;
            }
        }
        if (SystemConfig.getInstance().getAllowInPowerSave().contains(packageName)) {
            return false;
        }
        // TODO: Optimize this check
        if (isActiveDeviceAdmin(packageName, userId)) {
            return false;
        }

        if (mAppWidgetManager != null
                && mAppWidgetManager.isBoundWidgetPackage(packageName, userId)) {
            return false;
        }

        final long lastUsed = getLastPackageAccessTime(packageName, userId);
        return hasPassedIdleDuration(lastUsed);
    }

    void setAppIdle(String packageName, boolean idle, int userId) {
        if (packageName == null) return;

        mHandler.obtainMessage(MSG_RESET_LAST_TIMESTAMP, userId, idle ? 1 : 0, packageName)
                .sendToTarget();
    }

    private boolean isActiveDeviceAdmin(String packageName, int userId) {
        DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        if (dpm == null) return false;
        List<ComponentName> components = dpm.getActiveAdminsAsUser(userId);
        if (components == null) return false;
        final int size = components.size();
        for (int i = 0; i < size; i++) {
            if (components.get(i).getPackageName().equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    void informListeners(String packageName, int userId, boolean isIdle) {
        for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
            listener.onAppIdleStateChanged(packageName, userId, isIdle);
        }
    }

    private static boolean validRange(long currentTime, long beginTime, long endTime) {
        return beginTime <= currentTime && beginTime < endTime;
    }

    private void flushToDiskLocked() {
        final int userCount = mUserState.size();
        for (int i = 0; i < userCount; i++) {
            UserUsageStatsService service = mUserState.valueAt(i);
            service.persistActiveStats();
        }

        mHandler.removeMessages(MSG_FLUSH_TO_DISK);
    }

    /**
     * Called by the Binder stub.
     */
    void dump(String[] args, PrintWriter pw) {
        synchronized (mLock) {
            IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");
            ArraySet<String> argSet = new ArraySet<>();
            argSet.addAll(Arrays.asList(args));

            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                idpw.printPair("user", mUserState.keyAt(i));
                idpw.println();
                idpw.increaseIndent();
                if (argSet.contains("--checkin")) {
                    mUserState.valueAt(i).checkin(idpw);
                } else {
                    mUserState.valueAt(i).dump(idpw);
                }
                idpw.decreaseIndent();
            }
        }
    }

    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_EVENT:
                    reportEvent((UsageEvents.Event) msg.obj, msg.arg1);
                    break;

                case MSG_FLUSH_TO_DISK:
                    flushToDisk();
                    break;

                case MSG_REMOVE_USER:
                    removeUser(msg.arg1);
                    break;

                case MSG_INFORM_LISTENERS:
                    informListeners((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

                case MSG_RESET_LAST_TIMESTAMP:
                    resetLastTimestamp((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

                case MSG_CHECK_IDLE_STATES:
                    checkIdleStates();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    /**
     * Observe settings changes for Settings.Secure.APP_IDLE_DURATION.
     */
    private class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void registerObserver() {
            getContext().getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.APP_IDLE_DURATION), false, this, UserHandle.USER_OWNER);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            mAppIdleDurationMillis = Settings.Secure.getLongForUser(getContext().getContentResolver(),
                    Settings.Secure.APP_IDLE_DURATION, DEFAULT_APP_IDLE_THRESHOLD_MILLIS,
                    UserHandle.USER_OWNER);
            mCheckIdleIntervalMillis = Math.min(DEFAULT_CHECK_IDLE_INTERVAL,
                    mAppIdleDurationMillis / 4);
            postCheckIdleStates();
        }
    }

    private class BinderService extends IUsageStatsManager.Stub {

        private boolean hasPermission(String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID) {
                return true;
            }
            final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        @Override
        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime,
                long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<UsageStats> results = UsageStatsService.this.queryUsageStats(
                        userId, bucketType, beginTime, endTime);
                if (results != null) {
                    return new ParceledListSlice<>(results);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override
        public ParceledListSlice<ConfigurationStats> queryConfigurationStats(int bucketType,
                long beginTime, long endTime, String callingPackage) throws RemoteException {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<ConfigurationStats> results =
                        UsageStatsService.this.queryConfigurationStats(userId, bucketType,
                                beginTime, endTime);
                if (results != null) {
                    return new ParceledListSlice<>(results);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override
        public UsageEvents queryEvents(long beginTime, long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isAppIdle(String packageName, int userId) {
            try {
                userId = ActivityManagerNative.getDefault().handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), userId, false, true, "isAppIdle", null);
            } catch (RemoteException re) {
                return false;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.isAppIdle(packageName, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAppIdle(String packageName, boolean idle, int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManagerNative.getDefault().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, true,
                        "setAppIdle", null);
            } catch (RemoteException re) {
                return;
            }
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app idle state");
            final long token = Binder.clearCallingIdentity();
            try {
                PackageInfo pi = AppGlobals.getPackageManager()
                        .getPackageInfo(packageName, 0, userId);
                if (pi == null) return;
                UsageStatsService.this.setAppIdle(packageName, idle, userId);
            } catch (RemoteException re) {
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump UsageStats from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }
            UsageStatsService.this.dump(args, pw);
        }
    }

    /**
     * This local service implementation is primarily used by ActivityManagerService.
     * ActivityManagerService will call these methods holding the 'am' lock, which means we
     * shouldn't be doing any IO work or other long running tasks in these methods.
     */
    private class LocalService extends UsageStatsManagerInternal {

        @Override
        public void reportEvent(ComponentName component, int userId, int eventType) {
            if (component == null) {
                Slog.w(TAG, "Event reported without a component name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(TAG, "Event reported without a package name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportConfigurationChange(Configuration config, int userId) {
            if (config == null) {
                Slog.w(TAG, "Configuration event reported with a null config");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = "android";

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = UsageEvents.Event.CONFIGURATION_CHANGE;
            event.mConfiguration = new Configuration(config);
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public boolean isAppIdle(String packageName, int userId) {
            return UsageStatsService.this.isAppIdle(packageName, userId);
        }

        @Override
        public long getLastPackageAccessTime(String packageName, int userId) {
            return UsageStatsService.this.getLastPackageAccessTime(packageName, userId);
        }

        @Override
        public void prepareShutdown() {
            // This method *WILL* do IO work, but we must block until it is finished or else
            // we might not shutdown cleanly. This is ok to do with the 'am' lock held, because
            // we are shutting down.
            shutdown();
        }

        @Override
        public void addAppIdleStateChangeListener(AppIdleStateChangeListener listener) {
            UsageStatsService.this.addListener(listener);
        }

        @Override
        public void removeAppIdleStateChangeListener(
                AppIdleStateChangeListener listener) {
            UsageStatsService.this.removeListener(listener);
        }
    }
}
