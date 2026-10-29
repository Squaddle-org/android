package org.squaddle.app.ui.room

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import org.squaddle.core.encoding.Base32
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val icsDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
private val icsDateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** `ACTION_INSERT` into the device's own calendar app (android/CLAUDE.md M4b item 6) - this app
 * never writes calendar data itself, just hands the user's chosen calendar app the fields to
 * prefill; needs no calendar permission at all. [EventSummary.start]/[end] are Unix seconds. */
public fun calendarInsertIntent(event: EventSummary): Intent {
    val endSeconds = event.end ?: event.start
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start * 1000L)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endSeconds * 1000L)
    event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    return intent
}

/** PROTOCOL.md §4.3: `event.*` maps to a `VEVENT` (RFC 5545) at export time only - this is not a
 * protocol structure, just a text format built on demand. `UID = b32(eventId)@squaddle` per the
 * spec. [EventSummary.start]/[end] are already absolute Unix instants (not wall-clock-in-some-tz
 * values), so exporting them in UTC (`Z` suffix) is equally correct as a `TZID`/`VTIMEZONE` block
 * per RFC 5545 §3.3.5, and avoids needing a timezone database just to export one event. */
public fun buildIcs(event: EventSummary): String {
    val uid = Base32.encode(event.id) + "@squaddle"
    val endSeconds = event.end ?: if (event.allDay) event.start + 86_400 else event.start
    val dtStart: String
    val dtEnd: String
    if (event.allDay) {
        dtStart = "DTSTART;VALUE=DATE:" + icsDateFormat.format(Instant.ofEpochSecond(event.start))
        dtEnd = "DTEND;VALUE=DATE:" + icsDateFormat.format(Instant.ofEpochSecond(endSeconds))
    } else {
        dtStart = "DTSTART:" + icsDateTimeFormat.format(Instant.ofEpochSecond(event.start))
        dtEnd = "DTEND:" + icsDateTimeFormat.format(Instant.ofEpochSecond(endSeconds))
    }
    val lines = mutableListOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Squaddle//Squaddle//EN",
        "BEGIN:VEVENT", "UID:$uid", dtStart, dtEnd, "SUMMARY:" + icsEscape(event.title),
    )
    event.location?.let { lines += "LOCATION:" + icsEscape(it) }
    lines += "DTSTAMP:" + icsDateTimeFormat.format(Instant.now())
    lines += "END:VEVENT"
    lines += "END:VCALENDAR"
    return lines.joinToString("\r\n")
}

private fun icsEscape(text: String): String =
    text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

/** Writes a throwaway `.ics` file to the app's cache dir and shares it via a `FileProvider`
 * content URI (a plain `file://` URI can't cross the `ACTION_SEND` package boundary on API 24+) -
 * the receiving app (a calendar, email, chat...) reads the file once and this app never needs
 * to clean it up itself (`cacheDir` is OS-reclaimed). */
public fun shareIcsIntent(context: Context, event: EventSummary): Intent {
    val file = File(context.cacheDir, "event_${Base32.encode(event.id)}.ics")
    file.writeText(buildIcs(event))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/calendar"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
