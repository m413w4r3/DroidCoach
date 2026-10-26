package dev.atlas.coach.calendar.local

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.ZoneId

/**
 * Creates the one local calendar ATLAS is allowed to write to.
 *
 * CalendarProvider only accepts a calendar row from the account's registered
 * sync adapter (`CALLER_IS_SYNCADAPTER=true`). [AtlasCalendarSyncService] is
 * that adapter; it performs no network sync. No direct provider/SQL insertion
 * is used, and no existing account is touched.
 */
object AtlasLocalCalendar {
    const val ACCOUNT_TYPE = "dev.atlas.coach.calendar"
    const val ACCOUNT_NAME = "atlas-r3a-test"
    const val CALENDAR_DISPLAY_NAME = "ATLAS R3A TEST"
    const val CALENDAR_COLOR = 0xFF2E7D32.toInt()
    const val AUTHORITY = CalendarContract.AUTHORITY

    sealed interface Result {
        data class Ready(val calendarId: Long, val displayName: String) : Result
        data class Failed(val code: ErrorCode) : Result
    }

    enum class ErrorCode {
        READ_PERMISSION_DENIED,
        WRITE_PERMISSION_DENIED,
        ACCOUNT_CREATION_FAILED,
        SYNC_REGISTRATION_FAILED,
        PROVIDER_UNAVAILABLE,
        CALENDAR_CREATION_FAILED,
        CALENDAR_READ_BACK_FAILED,
    }

    fun ensure(context: Context): Result {
        val appContext = context.applicationContext
        if (!hasPermission(appContext, Manifest.permission.READ_CALENDAR)) {
            return Result.Failed(ErrorCode.READ_PERMISSION_DENIED)
        }
        if (!hasPermission(appContext, Manifest.permission.WRITE_CALENDAR)) {
            return Result.Failed(ErrorCode.WRITE_PERMISSION_DENIED)
        }

        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        val accountManager = AccountManager.get(appContext)
        val accountCreated = try {
            accountManager.addAccountExplicitly(account, null, null)
        } catch (_: SecurityException) {
            return Result.Failed(ErrorCode.ACCOUNT_CREATION_FAILED)
        } catch (_: Exception) {
            return Result.Failed(ErrorCode.ACCOUNT_CREATION_FAILED)
        }
        // false means the account already exists, which is the normal repeat case.
        if (!accountCreated) {
            val existing = try {
                accountManager.getAccountsByType(ACCOUNT_TYPE).any {
                    it.name == ACCOUNT_NAME && it.type == ACCOUNT_TYPE
                }
            } catch (_: SecurityException) {
                // The account may exist but be hidden without GET_ACCOUNTS; the
                // provider read-back below remains the authority.
                true
            } catch (_: Exception) {
                true
            }
            if (!existing) return Result.Failed(ErrorCode.ACCOUNT_CREATION_FAILED)
        }

        try {
            ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, AUTHORITY, false)
        } catch (_: SecurityException) {
            return Result.Failed(ErrorCode.SYNC_REGISTRATION_FAILED)
        } catch (_: Exception) {
            return Result.Failed(ErrorCode.SYNC_REGISTRATION_FAILED)
        }

        return try {
            findCalendar(appContext)?.let { return Result.Ready(it.first, it.second) }
            val inserted = insertCalendar(appContext)
                ?: return Result.Failed(ErrorCode.CALENDAR_CREATION_FAILED)
            val calendarId = ContentUris.parseId(inserted)
            val readBack = findCalendar(appContext)
                ?: return Result.Failed(ErrorCode.CALENDAR_READ_BACK_FAILED)
            if (readBack.first != calendarId) {
                return Result.Failed(ErrorCode.CALENDAR_READ_BACK_FAILED)
            }
            Result.Ready(readBack.first, readBack.second)
        } catch (_: SecurityException) {
            Result.Failed(ErrorCode.WRITE_PERMISSION_DENIED)
        } catch (_: Exception) {
            Result.Failed(ErrorCode.PROVIDER_UNAVAILABLE)
        }
    }

    /** Test/cleanup helper. Deletes only ATLAS events, then the ATLAS calendar row and account. */
    fun remove(context: Context): Boolean {
        val appContext = context.applicationContext
        val calendar = try {
            findCalendar(appContext)
        } catch (_: Exception) {
            null
        } ?: return true
        return try {
            appContext.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?",
                arrayOf(calendar.first.toString(), "dev.atlas.coach"),
            )
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .build()
            appContext.contentResolver.delete(
                uri,
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calendar.first.toString()),
            )
            AccountManager.get(appContext).removeAccountExplicitly(
                Account(ACCOUNT_NAME, ACCOUNT_TYPE),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findCalendar(context: Context): Pair<Long, String>? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        ),
        "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
        arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE),
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val id = cursor.getLong(0)
        val displayName = cursor.getString(1) ?: CALENDAR_DISPLAY_NAME
        if (cursor.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_OWNER) return null
        Pair(id, displayName)
    }

    private fun insertCalendar(context: Context): android.net.Uri? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Calendars.DIRTY, 0)
            put(CalendarContract.Calendars.DELETED, 0)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()
        return context.contentResolver.insert(uri, values)
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
