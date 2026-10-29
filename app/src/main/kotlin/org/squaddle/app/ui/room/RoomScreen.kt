@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.room

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.squaddle.core.encoding.Base32

@Composable
public fun RoomScreen(
    viewModel: RoomViewModel,
    roomName: String,
    onBack: () -> Unit,
    onOpenMembers: () -> Unit = {},
    onOpenInvite: () -> Unit = {},
    onOpenVoucherRedeem: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showCreateIdeaDialog by remember { mutableStateOf(false) }
    var showCreateEventDialog by remember { mutableStateOf(false) }
    var convertIdeaId by remember { mutableStateOf<ByteArray?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(roomName)
                        state.tier?.let { tier ->
                            Text(
                                tierLabel(tier),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("roomTierBadge"),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleMute) {
                        if (state.isMuted) {
                            Icon(Icons.Filled.NotificationsOff, contentDescription = stringResource(R.string.room_unmute))
                        } else {
                            Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.room_mute))
                        }
                    }
                    IconButton(onClick = onOpenVoucherRedeem, modifier = Modifier.testTag("openVoucherRedeemButton")) {
                        Icon(Icons.Filled.CardGiftcard, contentDescription = stringResource(R.string.room_redeem_voucher))
                    }
                    IconButton(onClick = onOpenMembers) { Icon(Icons.Filled.People, contentDescription = stringResource(R.string.members_title)) }
                    IconButton(onClick = onOpenInvite) { Icon(Icons.Filled.MailOutline, contentDescription = stringResource(R.string.invite_title)) }
                },
            )
        },
        floatingActionButton = {
            if (state.selectedIdeaId == null) {
                when (state.selectedTab) {
                    RoomTab.IDEAS -> FloatingActionButton(onClick = { showCreateIdeaDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.room_new_idea))
                    }
                    RoomTab.EVENTS -> if (state.canCreateEvent) {
                        FloatingActionButton(onClick = { showCreateEventDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.room_new_event))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.selectedIdeaId == null) {
                TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                    Tab(
                        selected = state.selectedTab == RoomTab.IDEAS,
                        onClick = { viewModel.selectTab(RoomTab.IDEAS) },
                        text = { Text(stringResource(R.string.room_tab_ideas)) },
                    )
                    Tab(
                        selected = state.selectedTab == RoomTab.EVENTS,
                        onClick = { viewModel.selectTab(RoomTab.EVENTS) },
                        text = { Text(stringResource(R.string.room_tab_events)) },
                    )
                }
            }
            when {
                state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.selectedIdeaId != null -> {
                    val idea = state.ideas.firstOrNull { it.id.contentEquals(state.selectedIdeaId) }
                    if (idea != null) {
                        IdeaDetail(
                            idea = idea,
                            comments = state.comments,
                            canCreateEvent = state.canCreateEvent,
                            onBack = { viewModel.selectIdea(null) },
                            onVote = { dir -> viewModel.toggleVote(idea.id, dir) },
                            onEdit = { title, desc -> viewModel.editIdea(idea.id, title, desc) },
                            onDelete = { viewModel.deleteIdea(idea.id) },
                            onAddComment = { text -> viewModel.addComment(idea.id, text) },
                            onDeleteComment = { commentId -> viewModel.deleteComment(commentId) },
                            onConvertToEvent = { convertIdeaId = idea.id },
                        )
                    }
                }
                state.selectedTab == RoomTab.EVENTS -> if (state.events.isEmpty()) {
                    EmptyEventsState()
                } else {
                    val shareEventLabel = stringResource(R.string.room_share_event)
                    EventsList(
                        events = state.events,
                        onRsvp = { eventId, value -> viewModel.rsvp(eventId, value) },
                        onCancel = { eventId -> viewModel.cancelEvent(eventId) },
                        onAddToCalendar = { event -> context.startActivity(calendarInsertIntent(event)) },
                        onShareIcs = { event -> context.startActivity(Intent.createChooser(shareIcsIntent(context, event), shareEventLabel)) },
                    )
                }
                state.ideas.isEmpty() -> EmptyIdeasState()
                else -> IdeasList(
                    ideas = state.ideas,
                    onSelect = { viewModel.selectIdea(it) },
                    onVote = { ideaId, dir -> viewModel.toggleVote(ideaId, dir) },
                )
            }
        }
    }

    if (showCreateIdeaDialog) {
        IdeaEditDialog(
            title = stringResource(R.string.room_new_idea_title),
            initialTitle = "",
            initialDesc = "",
            onDismiss = { showCreateIdeaDialog = false },
            onSave = { title, desc ->
                viewModel.createIdea(title, desc)
                showCreateIdeaDialog = false
            },
        )
    }

    if (showCreateEventDialog) {
        EventEditDialog(
            onDismiss = { showCreateEventDialog = false },
            onSave = { title, start, end, tz, location, allDay ->
                viewModel.createEvent(ideaId = null, title = title, start = start, end = end, tz = tz, location = location, allDay = allDay)
                showCreateEventDialog = false
            },
        )
    }

    val ideaToConvert = convertIdeaId
    if (ideaToConvert != null) {
        EventEditDialog(
            initialTitle = state.ideas.firstOrNull { it.id.contentEquals(ideaToConvert) }?.title.orEmpty(),
            onDismiss = { convertIdeaId = null },
            onSave = { title, start, end, tz, location, allDay ->
                viewModel.createEvent(ideaId = ideaToConvert, title = title, start = start, end = end, tz = tz, location = location, allDay = allDay)
                convertIdeaId = null
            },
        )
    }
}

/** PROTOCOL.md §8.1's `"free"|"boosted"|"unlimited"|"custom"` - the first three get real
 * localized labels; `custom` (a relay-specific tier name, never defined by the protocol itself)
 * falls back to just capitalizing whatever the relay sent, same as before this pass. */
@Composable
private fun tierLabel(tier: String): String = when (tier) {
    "free" -> stringResource(R.string.tier_free)
    "boosted" -> stringResource(R.string.tier_boosted)
    "unlimited" -> stringResource(R.string.tier_unlimited)
    else -> tier.replaceFirstChar { it.uppercase() }
}

@Composable
private fun EmptyIdeasState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.room_no_ideas_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.room_no_ideas_hint), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun IdeasList(
    ideas: List<IdeaSummary>,
    modifier: Modifier = Modifier,
    onSelect: (ByteArray) -> Unit,
    onVote: (ByteArray, Int) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(ideas, key = { Base32.encode(it.id) }) { idea ->
            IdeaRow(idea, onClick = { onSelect(idea.id) }, onVote = { dir -> onVote(idea.id, dir) })
        }
    }
}

@Composable
private fun IdeaRow(idea: IdeaSummary, onClick: () -> Unit, onVote: (Int) -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("idea_${Base32.encode(idea.id)}"),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            VoteControl(score = idea.score, myVote = idea.myVote, onVote = onVote)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(idea.title, style = MaterialTheme.typography.titleMedium)
                idea.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun VoteControl(score: Int, myVote: Int, onVote: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { onVote(1) }, modifier = Modifier.testTag("voteUp")) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.room_upvote),
                tint = if (myVote == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("$score", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onVote(-1) }, modifier = Modifier.testTag("voteDown")) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.room_downvote),
                tint = if (myVote == -1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdeaDetail(
    idea: IdeaSummary,
    comments: List<CommentSummary>,
    canCreateEvent: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onVote: (Int) -> Unit,
    onEdit: (String, String?) -> Unit,
    onDelete: () -> Unit,
    onAddComment: (String) -> Unit,
    onDeleteComment: (ByteArray) -> Unit,
    onConvertToEvent: () -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.room_back_to_ideas)) }
            Text(idea.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (idea.canEditOrDelete) {
                IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.room_edit_idea)) }
                IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.room_delete_idea)) }
            }
        }
        idea.desc?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            VoteControl(score = idea.score, myVote = idea.myVote, onVote = onVote)
            if (canCreateEvent) {
                TextButton(onClick = onConvertToEvent) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Text(stringResource(R.string.room_make_this_an_event), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        Text(stringResource(R.string.room_comments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(comments, key = { Base32.encode(it.id) }) { comment ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.text, modifier = Modifier.weight(1f))
                    if (comment.canDelete) {
                        IconButton(onClick = { onDeleteComment(comment.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.room_delete_comment))
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                label = { Text(stringResource(R.string.room_add_comment_label)) },
                modifier = Modifier.weight(1f).testTag("commentField"),
            )
            TextButton(
                onClick = { onAddComment(commentText); commentText = "" },
                enabled = commentText.isNotBlank(),
            ) { Text(stringResource(R.string.room_post_comment)) }
        }
    }

    if (showEditDialog) {
        IdeaEditDialog(
            title = stringResource(R.string.room_edit_idea_title),
            initialTitle = idea.title,
            initialDesc = idea.desc.orEmpty(),
            onDismiss = { showEditDialog = false },
            onSave = { title, desc -> onEdit(title, desc); showEditDialog = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.room_delete_idea_confirm_title)) },
            text = { Text(stringResource(R.string.room_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun IdeaEditDialog(title: String, initialTitle: String, initialDesc: String, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var ideaTitle by remember { mutableStateOf(initialTitle) }
    var ideaDesc by remember { mutableStateOf(initialDesc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = ideaTitle,
                    onValueChange = { ideaTitle = it },
                    label = { Text(stringResource(R.string.room_title_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("ideaTitleField"),
                )
                OutlinedTextField(
                    value = ideaDesc,
                    onValueChange = { ideaDesc = it },
                    label = { Text(stringResource(R.string.room_description_optional_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ideaDescField"),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ideaTitle, ideaDesc) }, enabled = ideaTitle.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EmptyEventsState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.room_no_events_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.room_no_events_hint), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun EventsList(
    events: List<EventSummary>,
    modifier: Modifier = Modifier,
    onRsvp: (ByteArray, String) -> Unit,
    onCancel: (ByteArray) -> Unit,
    onAddToCalendar: (EventSummary) -> Unit,
    onShareIcs: (EventSummary) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(events, key = { Base32.encode(it.id) }) { event ->
            EventRow(
                event = event,
                onRsvp = { value -> onRsvp(event.id, value) },
                onCancel = { onCancel(event.id) },
                onAddToCalendar = { onAddToCalendar(event) },
                onShareIcs = { onShareIcs(event) },
            )
        }
    }
}

private fun formatEventTime(event: EventSummary): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(event.start).atZone(zone)
    return if (event.allDay) {
        start.toLocalDate().toString()
    } else {
        "${start.toLocalDate()} ${start.toLocalTime().withSecond(0).withNano(0)}"
    }
}

@Composable
private fun EventRow(
    event: EventSummary,
    onRsvp: (String) -> Unit,
    onCancel: () -> Unit,
    onAddToCalendar: () -> Unit,
    onShareIcs: () -> Unit,
) {
    var showCancelConfirm by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("event_${Base32.encode(event.id)}")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                    Text(formatEventTime(event), style = MaterialTheme.typography.bodyMedium)
                    event.location?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(onClick = onAddToCalendar) { Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.room_add_to_calendar)) }
                IconButton(onClick = onShareIcs) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.room_share_event)) }
                if (event.canCancel) {
                    IconButton(onClick = { showCancelConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.room_cancel_event)) }
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = event.myRsvp == "yes",
                    onClick = { onRsvp("yes") },
                    label = { Text(stringResource(R.string.room_rsvp_yes, event.yesCount)) },
                    modifier = Modifier.testTag("rsvpYes_${Base32.encode(event.id)}"),
                )
                FilterChip(
                    selected = event.myRsvp == "maybe",
                    onClick = { onRsvp("maybe") },
                    label = { Text(stringResource(R.string.room_rsvp_maybe, event.maybeCount)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
                FilterChip(
                    selected = event.myRsvp == "no",
                    onClick = { onRsvp("no") },
                    label = { Text(stringResource(R.string.room_rsvp_no, event.noCount)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.room_cancel_event_confirm_title)) },
            text = { Text(stringResource(R.string.room_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showCancelConfirm = false; onCancel() }) { Text(stringResource(R.string.room_cancel_event)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text(stringResource(R.string.room_keep_it)) }
            },
        )
    }
}

/**
 * Standalone "new event" dialog, or (with [initialTitle] pre-filled) the "convert idea to event"
 * flow - same dialog either way, [RoomScreen] just passes `ideaId = null` or the idea's id to
 * [RoomViewModel.createEvent] on save. Date/time are picked in the device's own zone
 * ([ZoneId.systemDefault]) and converted to an absolute Unix instant immediately - [tz] is
 * carried along only for other clients' own display purposes (`CalendarExport.kt`'s own doc
 * comment on why export doesn't need it back).
 */
@Composable
private fun EventEditDialog(
    initialTitle: String = "",
    onDismiss: () -> Unit,
    onSave: (title: String, start: Long, end: Long?, tz: String, location: String?, allDay: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var location by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.of(12, 0)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_new_event_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.room_title_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("eventTitleField"),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.room_location_optional_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.room_all_day), modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { showDatePicker = true }) { Text(date.toString()) }
                    if (!allDay) {
                        TextButton(onClick = { showTimePicker = true }) { Text(time.toString()) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val startInstant = if (allDay) date.atStartOfDay(zone).toInstant() else date.atTime(time).atZone(zone).toInstant()
                    onSave(title, startInstant.epochSecond, null, zone.id, location.ifBlank { null }, allDay)
                },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTimePicker = false }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
