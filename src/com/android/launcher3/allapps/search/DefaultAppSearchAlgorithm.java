/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.allapps.search;

import static com.android.launcher3.allapps.AlphabeticalAppsList.PRIVATE_SPACE_PACKAGE;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_EMPTY_SEARCH;
import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_PRIVATE_SPACE_RESULT;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.AnyThread;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.R;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.pm.UserCache.CachedUserInfo;
import com.android.launcher3.search.SearchAlgorithm;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.search.StringMatcherUtility;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.LooperExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * The default search implementation.
 */
public class DefaultAppSearchAlgorithm implements SearchAlgorithm<AdapterItem> {

    private final Context mContext;
    private final LauncherAppState mAppState;
    private final UserCache mUserCache;
    private final Handler mResultHandler;
    private final boolean mAddNoResultsMessage;
    private long mSearchGeneration;

    public DefaultAppSearchAlgorithm(Context context, LooperExecutor uiExecutor) {
        this(context, uiExecutor, false);
    }

    public DefaultAppSearchAlgorithm(
            Context context, LooperExecutor uiExecutor, boolean addNoResultsMessage) {
        mContext = context.getApplicationContext();
        mAppState = LauncherAppState.getInstance(context);
        mUserCache = UserCache.INSTANCE.get(context);
        mResultHandler = new Handler(uiExecutor.getLooper());
        mAddNoResultsMessage = addNoResultsMessage;
    }

    @Override
    public void cancel(boolean interruptActiveRequests) {
        mSearchGeneration++;
        if (interruptActiveRequests) {
            mResultHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void doSearch(String query, SearchCallback<AdapterItem> callback) {
        mSearchGeneration++;
        final long searchGeneration = mSearchGeneration;
        mAppState.getModel().enqueueModelUpdateTask((taskController, dataModel, apps) ->  {
            ArrayList<AdapterItem> result = getTitleMatchResult(
                    apps.data.stream().filter(this::isSearchableApp).toList(), query);
            // Universal search: also surface matching Settings pages.
            result.addAll(getSettingsMatchResult(query, apps.data));
            if (isPrivateSpaceQuery(query) && isPrivateSpaceAvailable()) {
                result.add(0, new AdapterItem(VIEW_TYPE_PRIVATE_SPACE_RESULT));
            }
            if (mAddNoResultsMessage && result.isEmpty()) {
                result.add(getEmptyMessageAdapterItem(query));
            }
            mResultHandler.post(() -> {
                if (searchGeneration == mSearchGeneration) {
                    callback.onSearchResult(query, result);
                }
            });
        });
    }

    /** A Settings page that is searchable from the app drawer. */
    private static final class SettingsShortcut {
        final int mTitleRes;
        final String mAction;

        SettingsShortcut(int titleRes, String action) {
            mTitleRes = titleRes;
            mAction = action;
        }
    }

    private static final SettingsShortcut[] SETTINGS_SHORTCUTS = {
            new SettingsShortcut(R.string.search_setting_wifi, Settings.ACTION_WIFI_SETTINGS),
            new SettingsShortcut(R.string.search_setting_bluetooth,
                    Settings.ACTION_BLUETOOTH_SETTINGS),
            new SettingsShortcut(R.string.search_setting_display, Settings.ACTION_DISPLAY_SETTINGS),
            new SettingsShortcut(R.string.search_setting_sound, Settings.ACTION_SOUND_SETTINGS),
            new SettingsShortcut(R.string.search_setting_battery,
                    Settings.ACTION_BATTERY_SAVER_SETTINGS),
            new SettingsShortcut(R.string.search_setting_storage,
                    Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            new SettingsShortcut(R.string.search_setting_location,
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            new SettingsShortcut(R.string.search_setting_apps,
                    Settings.ACTION_APPLICATION_SETTINGS),
            new SettingsShortcut(R.string.search_setting_accessibility,
                    Settings.ACTION_ACCESSIBILITY_SETTINGS),
            new SettingsShortcut(R.string.search_setting_security,
                    Settings.ACTION_SECURITY_SETTINGS),
            new SettingsShortcut(R.string.search_setting_date_time, Settings.ACTION_DATE_SETTINGS),
            new SettingsShortcut(R.string.search_setting_about, Settings.ACTION_DEVICE_INFO_SETTINGS),
    };

    /**
     * Returns Settings pages matching the query. Pages whose target is already present as an
     * app result are skipped so the same entry is not listed twice.
     */
    private ArrayList<AdapterItem> getSettingsMatchResult(String query, List<AppInfo> apps) {
        ArrayList<AdapterItem> result = new ArrayList<>();
        if (query.isEmpty()) {
            return result;
        }
        PackageManager pm = mContext.getPackageManager();
        UserHandle user = Process.myUserHandle();
        ArrayList<AppInfo> candidates = new ArrayList<>();
        for (SettingsShortcut shortcut : SETTINGS_SHORTCUTS) {
            Intent intent = new Intent(shortcut.mAction);
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) {
                continue;
            }
            ComponentName cn = new ComponentName(
                    ri.activityInfo.packageName, ri.activityInfo.name);
            boolean alreadyListed = apps.stream()
                    .anyMatch(app -> cn.equals(app.componentName));
            if (alreadyListed) {
                continue;
            }
            candidates.add(new AppInfo(cn, mContext.getString(shortcut.mTitleRes), user, intent));
        }
        result.addAll(getTitleMatchResult(candidates, query));
        return result;
    }

    private static AdapterItem getEmptyMessageAdapterItem(String query) {
        AdapterItem item = new AdapterItem(VIEW_TYPE_EMPTY_SEARCH);
        // Add a place holder info to propagate the query
        AppInfo placeHolder = new AppInfo();
        placeHolder.title = query;
        item.itemInfo = placeHolder;
        return item;
    }

    /**
     * Filters {@link AppInfo}s matching specified query
     */
    @AnyThread
    public static ArrayList<AdapterItem> getTitleMatchResult(List<AppInfo> apps, String query) {
        // Do an intersection of the words in the query and each title, and filter out all the
        // apps that don't match all of the words in the query.
        final String queryTextLower = query.toLowerCase();
        final ArrayList<AdapterItem> result = new ArrayList<>();
        StringMatcherUtility.StringMatcher matcher =
                StringMatcherUtility.StringMatcher.getInstance();

        int total = apps.size();
        for (int i = 0; i < total; i++) {
            AppInfo info = apps.get(i);
            if (StringMatcherUtility.matches(queryTextLower, info.title.toString(), matcher)) {
                result.add(AdapterItem.asApp(info));
            }
        }
        return result;
    }

    private boolean isSearchableApp(AppInfo info) {
        CachedUserInfo userInfo =
                mUserCache.getUserManagerState().getCachedInfo(info.user);
        return !PRIVATE_SPACE_PACKAGE.equals(info.getTargetPackage())
                && !userInfo.isQuietModeEnabled()
                && (!userInfo.getIconInfo().isPrivate() || userInfo.isUnlocked());
    }

    private boolean isPrivateSpaceQuery(String query) {
        return query.equalsIgnoreCase(mContext.getString(R.string.private_space_label));
    }

    private boolean isPrivateSpaceAvailable() {
        // Cached user info is limited to the launcher context user's profile group.
        return mUserCache.getUserManagerState().getAllCachedInfos().stream()
                .anyMatch(userInfo -> userInfo.getIconInfo().isPrivate())
                || ApiWrapper.INSTANCE.get(mContext).getPrivateSpaceSettingsIntent() != null;
    }
}
