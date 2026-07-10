package com.hermes.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.ChatMessage
import com.hermes.mobile.data.HermesSession
import com.hermes.mobile.data.ModelOption
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesApp(model: HermesViewModel) {
    val state = model.state.value
    val context = LocalContext.current

    LaunchedEffect(state.oauthUrl) {
        state.oauthUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }

    when {
        !state.connected -> ConnectScreen(state, model)
        state.selectedSessionId != null -> ChatScreen(state, model)
        state.selectedProject != null -> ProjectSessionsScreen(state, model)
        else -> HomeScreen(state, model)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: HermesUiState, model: HermesViewModel) {
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val projects = remember(state.sessions) {
        state.sessions.groupBy { it.cwd.trim().lowercase() }
    }
    val visibleSessions = remember(state.sessions, searchQuery) {
        val query = searchQuery.trim()
        state.sessions
            .filter {
                query.isBlank() || it.title.contains(query, true) ||
                    it.preview.contains(query, true) || it.projectName.contains(query, true)
            }
            .sortedByDescending { it.lastActive }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = model::startNewChat,
                icon = { Icon(Icons.Outlined.Edit, null) },
                text = { Text("Chat") },
                shape = RoundedCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = model::connect,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
            item {
                HomeHeader(
                    version = state.status?.version.orEmpty(),
                    searchVisible = searchVisible,
                    onSearch = { searchVisible = !searchVisible },
                    onRefresh = model::connect,
                    onSettings = model::disconnect,
                )
            }
            if (searchVisible) {
                item {
                    SearchField(searchQuery, { searchQuery = it }, { searchQuery = "" })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (projects.isNotEmpty() && searchQuery.isBlank()) {
                item {
                    SectionTitle("Projects", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(
                            projects.entries.sortedByDescending { entry -> entry.value.maxOfOrNull { it.lastActive } ?: 0.0 },
                            key = { it.key },
                        ) { entry ->
                            val representative = entry.value.maxByOrNull { it.lastActive } ?: entry.value.first()
                            ProjectCard(
                                name = representative.projectName,
                                cwd = representative.cwd,
                                sessions = entry.value,
                                onSelect = model::selectProject,
                            )
                        }
                    }
                }
            }
                item {
                    SectionTitle(if (searchQuery.isBlank()) "Sessions" else "Results", Modifier.padding(top = 18.dp, bottom = 4.dp))
                }
                if (visibleSessions.isEmpty()) {
                    item { EmptyState(if (searchQuery.isBlank()) "No sessions yet" else "No matching sessions") }
                } else {
                    itemsIndexed(visibleSessions, key = { _, s -> s.id }) { index, session ->
                        SessionRow(
                            session = session,
                            onClick = { model.selectSession(session.id) },
                            onDelete = { model.deleteSession(session) },
                        )
                        if (index < visibleSessions.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    version: String,
    searchVisible: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onRefresh,
            label = { Text("Connected${if (version.isNotBlank()) " · Hermes $version" else ""}") },
            leadingIcon = { Icon(Icons.Outlined.CheckCircle, "Refresh connection", Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
        )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onSearch) {
                    Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search, "Search")
                }
                Surface(
                    modifier = Modifier.size(42.dp).clickable(onClick = onSettings),
                    shape = CircleShape,
                    color = HermesYellow,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("H", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("Search sessions") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "Clear search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProjectCard(
    name: String,
    cwd: String,
    sessions: List<HermesSession>,
    onSelect: (String?, String) -> Unit,
) {
    ElevatedCard(
        onClick = { onSelect(name, cwd) },
        modifier = Modifier.width(196.dp).height(116.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "${sessions.size} ${if (sessions.size == 1) "session" else "sessions"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSessionsScreen(state: HermesUiState, model: HermesViewModel) {
    val sessions = state.sessions
        .filter { it.cwd.equals(state.selectedProjectCwd, ignoreCase = true) }
        .sortedByDescending { it.lastActive }
    BackHandler { model.selectProject(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.selectedProject.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${sessions.size} sessions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "All projects") { model.selectProject(null) } },
                actions = { RoundIconButton(Icons.Outlined.Add, "New chat", model::startNewChat) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { model.selectSession(session.id) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: HermesSession,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    if (onDelete == null) {
        SessionRowContent(session, onClick)
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(end = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete chat",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        SessionRowContent(session, onClick)
    }
}

@Composable
private fun SessionRowContent(session: HermesSession, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (session.isActive) {
                Box(Modifier.padding(top = 7.dp, end = 8.dp).size(8.dp).background(HermesYellow, CircleShape))
            }
            Text(
                session.title.ifBlank { "Untitled session" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            Text(relativeTime(session.lastActive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.preview.isNotBlank()) {
            Text(session.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${session.messageCount} messages · ${session.projectName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: HermesUiState, model: HermesViewModel) {
    val listState = rememberLazyListState()
    val title = state.sessions.firstOrNull { it.id == state.selectedSessionId }?.title ?: "New chat"
    val timelineItems = remember(state.messages) { groupTimelineMessages(state.messages) }
    val bottomAnchorIndex = timelineItems.size + if (state.error != null) 1 else 0

    BackHandler { model.leaveChat() }

    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text,
        state.messages.lastOrNull()?.streaming,
    ) {
        if (timelineItems.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(state.selectedProfile, state.selectedModel.ifBlank { "Default model" })
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Sessions", model::leaveChat) },
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { ChatComposer(state, model) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingChat) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!state.loadingChat && state.messages.isEmpty()) {
                    item { EmptyChatState() }
                }
                items(timelineItems, key = { it.key }) { item ->
                    if (item.messages.first().role == "tool") {
                        ToolActivityGroup(item.messages)
                    } else {
                        MessageRow(item.messages.first())
                    }
                }
                state.error?.let { error -> item { ErrorCard(error) } }
                item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
            }
        }
    }
}

private data class ChatTimelineItem(
    val key: String,
    val messages: List<ChatMessage>,
)

private fun groupTimelineMessages(messages: List<ChatMessage>): List<ChatTimelineItem> {
    val items = mutableListOf<ChatTimelineItem>()
    messages.forEach { message ->
        val previous = items.lastOrNull()
        if (message.role == "tool" && previous?.messages?.firstOrNull()?.role == "tool") {
            items[items.lastIndex] = previous.copy(messages = previous.messages + message)
        } else {
            items += ChatTimelineItem(key = message.id, messages = listOf(message))
        }
    }
    return items
}

@Composable
private fun ToolActivityGroup(messages: List<ChatMessage>) {
    val expandable = messages.size > 1
    var expanded by remember(messages.first().id) { mutableStateOf(false) }
    val summary = if (expandable) {
        "${messages.size} tool actions · ${messages.last().text}"
    } else {
        messages.first().text
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable) { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    tint = HermesYellow,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expandable) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Hide tool details" else "Show tool details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 33.dp, end = 10.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                messages.forEach { message ->
                    Text(
                        "• ${message.text}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = ColorTokens.userBubble,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        "tool" -> ToolActivityGroup(listOf(message))
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = HermesYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Hermes is thinking", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val displayText = message.text.ifBlank { "Thinking\u2026" }
            if (message.streaming) {
                Text(displayText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            } else {
                MarkdownText(
                    raw = displayText,
                    textColor = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatComposer(state: HermesUiState, model: HermesViewModel) {
    val context = LocalContext.current
    var profilePicker by remember { mutableStateOf(false) }
    var workspacePicker by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        model.addAttachment(uri)
    }
    val isDraft = state.selectedSessionId == "new" && state.runtimeSessionId == null

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.pendingAttachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.pendingAttachments, key = { it.uri }) { attachment ->
                        InputChip(
                            selected = true,
                            onClick = { model.removeAttachment(attachment.uri) },
                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp)) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    "Remove ${attachment.name}",
                                    Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 3.dp,
            ) {
                Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                        enabled = !state.sending && !state.loadingChat,
                    ) { Icon(Icons.Outlined.Add, "Attach a file") }
                    TextField(
                        value = state.composerText,
                        onValueChange = model::setComposerText,
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        enabled = state.selectedSessionId != null && !state.sending && !state.loadingChat,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    FilledIconButton(
                        onClick = model::sendMessage,
                        enabled = state.selectedSessionId != null &&
                            (state.composerText.isNotBlank() || state.pendingAttachments.isNotEmpty()) &&
                            !state.sending && !state.loadingOptions && !state.loadingChat,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Outlined.Send, "Send")
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    SelectorPill(
                        icon = Icons.Outlined.PersonOutline,
                        label = state.selectedProfile.ifBlank { "default" },
                        enabled = isDraft && !state.sending,
                        onClick = { profilePicker = true },
                    )
                }
                item {
                    SelectorPill(
                        icon = Icons.Outlined.FolderOpen,
                        label = state.selectedWorkspaceLabel.ifBlank { "No project" },
                        enabled = isDraft && !state.sending,
                        onClick = { workspacePicker = true },
                    )
                }
                item {
                    SelectorPill(
                        icon = Icons.Outlined.AutoAwesome,
                        label = state.selectedModel.ifBlank { if (state.loadingOptions) "Loading models…" else "Default model" },
                        enabled = state.modelOptions.isNotEmpty() && !state.sending && !state.loadingOptions,
                        onClick = { modelPicker = true },
                    )
                }
            }
        }
    }

    if (profilePicker) {
        ModalBottomSheet(onDismissRequest = { profilePicker = false }) {
            PickerHeader("Choose profile", "Skills, memory, tools, and defaults come from this profile.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(state.profiles.ifEmpty { listOf(com.hermes.mobile.data.HermesProfile("default")) }, key = { it.name }) { profile ->
                    PickerRow(
                        title = profile.name,
                        subtitle = profile.description,
                        selected = profile.name == state.selectedProfile,
                    ) {
                        model.selectDraftProfile(profile.name)
                        profilePicker = false
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (workspacePicker) {
        ModalBottomSheet(onDismissRequest = { workspacePicker = false }) {
            PickerHeader("Choose project or folder", "Hermes runs terminal and file tools from this folder.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item {
                    PickerRow("No project", "Use the profile's default working folder", state.selectedCwd.isBlank()) {
                        model.selectDraftWorkspace("No project", "")
                        workspacePicker = false
                    }
                }
                state.projects.forEach { project ->
                    val folders = project.selectableFolders
                    items(folders, key = { "${project.id}:${it.path}" }) { folder ->
                        val title = if (folders.size == 1) project.name else "${project.name} · ${folder.label.ifBlank { folder.path.substringAfterLast('/').substringAfterLast('\\') }}"
                        PickerRow(title, folder.path, folder.path == state.selectedCwd) {
                            model.selectDraftWorkspace(title, folder.path)
                            workspacePicker = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (modelPicker) {
        val visibleModels = state.modelOptions.filter {
            modelQuery.isBlank() || it.model.contains(modelQuery, true) || it.providerName.contains(modelQuery, true)
        }
        ModalBottomSheet(onDismissRequest = { modelPicker = false; modelQuery = "" }) {
            PickerHeader("Choose model", "Tap a row to switch this chat. Set default writes this profile's provider and model for future chats.")
            OutlinedTextField(
                value = modelQuery,
                onValueChange = { modelQuery = it },
                placeholder = { Text("Search models") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(visibleModels, key = { "${it.provider}/${it.model}" }) { option ->
                    ModelPickerRow(
                        option = option,
                        selected = option.model == state.selectedModel && option.provider == state.selectedProvider,
                        savingProfileDefault = state.savingProfileDefault,
                        onSelect = {
                            model.selectModel(option)
                            modelPicker = false
                            modelQuery = ""
                        },
                        onSetDefault = { model.setProfileDefaultModel(option) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    state.profileDefaultConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = model::cancelProfileDefaultConfirmation,
            title = { Text("Confirm default model") },
            text = { Text(confirmation.message) },
            dismissButton = {
                TextButton(onClick = model::cancelProfileDefaultConfirmation) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.setProfileDefaultModel(confirmation.option, confirmExpensiveModel = true)
                }) { Text("Set default") }
            },
        )
    }
}

@Composable
private fun ModelPickerRow(
    option: ModelOption,
    selected: Boolean,
    savingProfileDefault: Boolean,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = option.available, onClick = onSelect)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.model, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (option.isProfileDefault) "${option.providerName} · Profile default" else option.providerName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = HermesYellow)
        TextButton(
            onClick = onSetDefault,
            enabled = option.available && !option.isProfileDefault && !savingProfileDefault,
        ) {
            if (savingProfileDefault) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(if (option.isProfileDefault) "Default" else "Set default")
        }
    }
}

@Composable
private fun SelectorPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        trailingIcon = { if (enabled) Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
        border = AssistChipDefaults.assistChipBorder(enabled = enabled, borderColor = MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun PickerHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle.isNotBlank()) {
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = HermesYellow)
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = HermesYellow) }
        }
        Text("What can I help with?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("Ask Hermes to research, build, debug, or automate something.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp).size(46.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description) }
    }
}

@Composable
private fun ConnectScreen(state: HermesUiState, model: HermesViewModel) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("HERMES", style = MaterialTheme.typography.displaySmall, color = HermesYellow)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connect your agent", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Models, tools, memory, projects, and sessions stay on your Hermes host.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = model::setBaseUrl,
                    label = { Text("Remote URL") },
                    placeholder = { Text("https://hermes.example.com") },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, null) },
                    singleLine = true,
                    enabled = !state.needsAuthentication,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.needsAuthentication) {
                    val nousProvider = state.providers.firstOrNull {
                        it.name.equals("nous", true) || it.displayName.contains("Nous", true)
                    }
                    if (nousProvider != null) {
                        Button(
                            onClick = { model.beginOAuth(nousProvider) },
                            enabled = !state.loading,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) { LoadingOrLabel(state.loading, "Sign in with Nous Research") }
                    } else {
                        Text("This Hermes host has not enabled Nous Research sign-in.", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = model::connect,
                        enabled = !state.loading && state.baseUrl.isNotBlank(),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { LoadingOrLabel(state.loading, "Continue") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun LoadingOrLabel(loading: Boolean, label: String) {
    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(label)
}

private object ColorTokens {
    val userBubble = androidx.compose.ui.graphics.Color(0xFF4A4A4E)
}

private fun relativeTime(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val seconds = Duration.between(Instant.ofEpochSecond(epochSeconds.toLong()), Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        else -> "${seconds / 604_800}w"
    }
}
