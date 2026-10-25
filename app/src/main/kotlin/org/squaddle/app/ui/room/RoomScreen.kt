@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.room

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.squaddle.core.encoding.Base32

@Composable
public fun RoomScreen(viewModel: RoomViewModel, roomName: String, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(roomName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New idea")
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.selectedIdeaId != null -> {
                val idea = state.ideas.firstOrNull { it.id.contentEquals(state.selectedIdeaId) }
                if (idea != null) {
                    IdeaDetail(
                        idea = idea,
                        comments = state.comments,
                        modifier = Modifier.padding(padding),
                        onBack = { viewModel.selectIdea(null) },
                        onVote = { dir -> viewModel.toggleVote(idea.id, dir) },
                        onEdit = { title, desc -> viewModel.editIdea(idea.id, title, desc) },
                        onDelete = { viewModel.deleteIdea(idea.id) },
                        onAddComment = { text -> viewModel.addComment(idea.id, text) },
                        onDeleteComment = { commentId -> viewModel.deleteComment(commentId) },
                    )
                }
            }
            state.ideas.isEmpty() -> EmptyIdeasState(modifier = Modifier.padding(padding))
            else -> IdeasList(
                ideas = state.ideas,
                modifier = Modifier.padding(padding),
                onSelect = { viewModel.selectIdea(it) },
                onVote = { ideaId, dir -> viewModel.toggleVote(ideaId, dir) },
            )
        }
    }

    if (showCreateDialog) {
        IdeaEditDialog(
            title = "New idea",
            initialTitle = "",
            initialDesc = "",
            onDismiss = { showCreateDialog = false },
            onSave = { title, desc ->
                viewModel.createIdea(title, desc)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun EmptyIdeasState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No ideas yet", style = MaterialTheme.typography.titleLarge)
            Text("Tap + to suggest one.", modifier = Modifier.padding(top = 8.dp))
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
                contentDescription = "Upvote",
                tint = if (myVote == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("$score", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onVote(-1) }, modifier = Modifier.testTag("voteDown")) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Downvote",
                tint = if (myVote == -1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdeaDetail(
    idea: IdeaSummary,
    comments: List<CommentSummary>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onVote: (Int) -> Unit,
    onEdit: (String, String?) -> Unit,
    onDelete: () -> Unit,
    onAddComment: (String) -> Unit,
    onDeleteComment: (ByteArray) -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to ideas") }
            Text(idea.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (idea.canEditOrDelete) {
                IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit idea") }
                IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete idea") }
            }
        }
        idea.desc?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            VoteControl(score = idea.score, myVote = idea.myVote, onVote = onVote)
        }

        Text("Comments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(comments, key = { Base32.encode(it.id) }) { comment ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.text, modifier = Modifier.weight(1f))
                    if (comment.canDelete) {
                        IconButton(onClick = { onDeleteComment(comment.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete comment")
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                label = { Text("Add a comment") },
                modifier = Modifier.weight(1f).testTag("commentField"),
            )
            TextButton(
                onClick = { onAddComment(commentText); commentText = "" },
                enabled = commentText.isNotBlank(),
            ) { Text("Post") }
        }
    }

    if (showEditDialog) {
        IdeaEditDialog(
            title = "Edit idea",
            initialTitle = idea.title,
            initialDesc = idea.desc.orEmpty(),
            onDismiss = { showEditDialog = false },
            onSave = { title, desc -> onEdit(title, desc); showEditDialog = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this idea?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
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
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().testTag("ideaTitleField"),
                )
                OutlinedTextField(
                    value = ideaDesc,
                    onValueChange = { ideaDesc = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ideaDescField"),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ideaTitle, ideaDesc) }, enabled = ideaTitle.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
