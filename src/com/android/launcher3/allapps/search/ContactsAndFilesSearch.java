/*
 * Copyright (C) 2026 The Android Open Source Project
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


import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;

import com.android.launcher3.R;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Contacts and files results for the app drawer search.
 *
 * <p>Runs on the model background thread. When the permission is not granted, a single row
 * is returned that opens this app's settings so the user can grant access.
 */
final class ContactsAndFilesSearch {

    private static final int MAX_CONTACTS = 5;
    private static final int MAX_FILES = 5;

    private ContactsAndFilesSearch() {
    }

    static List<AdapterItem> search(Context context, String query) {
        List<AdapterItem> items = new ArrayList<>();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return items;
        }
        if (hasContactsPermission(context)) {
            items.addAll(queryContacts(context, trimmed));
        } else {
            items.add(permissionRow(context, R.string.search_permission_contacts));
        }
        if (hasFilesPermission(context)) {
            items.addAll(queryFiles(context, trimmed));
        } else {
            items.add(permissionRow(context, R.string.search_permission_files));
        }
        return items;
    }

    static boolean hasContactsPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasFilesPermission(Context context) {
        if (Environment.isExternalStorageManager()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static List<AdapterItem> queryContacts(Context context, String query) {
        List<AdapterItem> items = new ArrayList<>();
        Uri uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query));
        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        };
        try (Cursor c = context.getContentResolver().query(uri, projection, null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC")) {
            if (c == null) {
                return items;
            }
            while (c.moveToNext() && items.size() < MAX_CONTACTS) {
                long id = c.getLong(0);
                String lookupKey = c.getString(1);
                String name = c.getString(2);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Uri contactUri = ContactsContract.Contacts.getLookupUri(id, lookupKey);
                Intent intent = new Intent(Intent.ACTION_VIEW, contactUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                items.add(DefaultAppSearchAlgorithm.searchActionItem(
                        name, () -> startActivity(context, intent)));
            }
        } catch (SecurityException e) {
            // Permission revoked between the check and the query; show nothing.
        }
        return items;
    }

    private static List<AdapterItem> queryFiles(Context context, String query) {
        List<AdapterItem> items = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\'";
        String[] args = {"%" + escaped + "%"};
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
        };
        try (Cursor c = resolver.query(collection, projection, selection, args,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) {
                return items;
            }
            while (c.moveToNext() && items.size() < MAX_FILES) {
                long id = c.getLong(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Uri fileUri = Uri.withAppendedPath(collection, String.valueOf(id));
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(fileUri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                items.add(DefaultAppSearchAlgorithm.searchActionItem(
                        name, () -> startActivity(context, intent)));
            }
        } catch (SecurityException e) {
            // Permission revoked between the check and the query; show nothing.
        }
        return items;
    }

    /** A row that opens this app's permission page, shown when a permission is missing. */
    private static AdapterItem permissionRow(Context context, int titleRes) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return DefaultAppSearchAlgorithm.searchActionItem(
                context.getString(titleRes), () -> startActivity(context, intent));
    }

    private static void startActivity(Context context, Intent intent) {
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // No handler installed for this item; nothing to open.
        }
    }
}
