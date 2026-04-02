package com.example.meetingcopilot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.meetingcopilot.ui.theme.MeetingCoPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeetingCoPilotTheme {
                MainNavigation()
            }
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

data class AIResponse(val summary: String, val actionItems: String)

suspend fun fetchFromAI(transcript: String, provider: String, apiKey: String): AIResponse {
    if (apiKey.isBlank()) return AIResponse("Error: API Key not set", "Error: API Key not set")
    
    return if (provider == "OpenAI") {
        fetchFromOpenAI(transcript, apiKey)
    } else {
        fetchFromClaude(transcript, apiKey)
    }
}

suspend fun fetchFromOpenAI(transcript: String, apiKey: String): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val jsonBody = JSONObject().apply {
        put("model", "gpt-3.5-turbo")
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a helpful assistant. Summarize the transcript and extract action items. Respond in JSON format with keys 'summary' and 'action_items' (as a string).")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Transcript: $transcript")
            })
        })
        put("response_format", JSONObject().apply { put("type", "json_object") })
    }

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(jsonBody.toString().toRequestBody(mediaType))
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return@withContext AIResponse("Empty", "Empty")
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val parsed = JSONObject(content)
                AIResponse(parsed.getString("summary"), parsed.getString("action_items"))
            }
        } catch (e: Exception) { AIResponse("Error: ${e.message}", "Error: ${e.message}") }
    }
}

suspend fun fetchFromClaude(transcript: String, apiKey: String): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    
    val prompt = "Summarize this meeting transcript and extract action items. " +
                 "Format your entire response as a single valid JSON object with exactly two keys: " +
                 "'summary' (a concise paragraph) and 'action_items' (bullet points). " +
                 "Do not include any preamble or conversational text. " +
                 "Transcript: $transcript"

    val jsonBody = JSONObject().apply {
        put("model", "claude-3-haiku-20240307")
        put("max_tokens", 1024)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }

    val request = Request.Builder()
        .url("https://api.anthropic.com/v1/messages")
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", "2023-06-01")
        .post(jsonBody.toString().toRequestBody(mediaType))
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return@withContext AIResponse("Empty", "Empty")
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.getJSONArray("content").getJSONObject(0).getString("text")
                val cleanJson = content.trim().removePrefix("```json").removeSuffix("```").trim()
                val parsed = JSONObject(cleanJson)
                AIResponse(parsed.getString("summary"), parsed.getString("action_items"))
            }
        } catch (e: Exception) { AIResponse("Error: ${e.message}", "Error: ${e.message}") }
    }
}

@Composable
fun MainNavigation() {
    var currentTab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Default.Mic, null) }, label = { Text("Meeting") })
                NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("History") })
                NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (currentTab) {
                0 -> MeetingScreen()
                1 -> HistoryScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun MeetingScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val db = remember(context) { MeetingDatabase.getDatabase(context) }
    val settingsManager = remember { SettingsManager(context) }
    
    val provider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val openaiKey by settingsManager.openaiKeyFlow.collectAsState(null)
    val claudeKey by settingsManager.claudeKeyFlow.collectAsState(null)
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(false)
    
    val activeKey = if (provider == "OpenAI") openaiKey else claudeKey
    
    var isRecording by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var rollingSummary by remember { mutableStateOf("No summary yet...") }
    var actionItems by remember { mutableStateOf("No action items yet...") }
    var permissionGranted by remember { mutableStateOf(false) }

    // Logic to keep screen on while on Meeting tab
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.setKeepScreenOn(true)
        }
        onDispose {
            activity?.setKeepScreenOn(false)
        }
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> permissionGranted = isGranted }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(30000)
                if (transcript.isNotBlank() && !activeKey.isNullOrBlank()) {
                    val response = fetchFromAI(transcript, provider, activeKey!!)
                    rollingSummary = response.summary
                    actionItems = response.actionItems
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (isRecording) speechRecognizer.startListening(recognizerIntent) }
            override fun onError(error: Int) { if (isRecording) speechRecognizer.startListening(recognizerIntent) }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let { transcript += " $it" }
                if (isRecording) speechRecognizer.startListening(recognizerIntent)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Meeting CoPilot", style = MaterialTheme.typography.headlineMedium)
        Text("Using: $provider", style = MaterialTheme.typography.bodySmall)
        if (keepScreenOn) {
            Text("Screen stay-on active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (activeKey.isNullOrBlank()) {
            Text("Set $provider Key in Settings", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else {
                    if (isRecording) {
                        isRecording = false; speechRecognizer.stopListening()
                        scope.launch {
                            db.meetingDao().insertMeeting(MeetingSession(
                                timestamp = System.currentTimeMillis(),
                                title = "Meeting ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())}",
                                transcript = transcript, summary = rollingSummary, actionItems = actionItems
                            ))
                        }
                    } else {
                        transcript = ""; rollingSummary = "Summarizing..."; actionItems = "Extracting..."
                        isRecording = true; speechRecognizer.startListening(recognizerIntent)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isRecording) "Stop & Save Meeting" else "Start Meeting") }

        Spacer(modifier = Modifier.height(16.dp))
        SelectionContainer {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Rolling Summary")
                Text(rollingSummary, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Action Items")
                Text(actionItems, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Live Transcript")
                Text(transcript)
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    
    val currentProvider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val openaiKey by settingsManager.openaiKeyFlow.collectAsState("")
    val claudeKey by settingsManager.claudeKeyFlow.collectAsState("")
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(false)
    
    var keyInput by remember { mutableStateOf("") }
    
    LaunchedEffect(currentProvider, openaiKey, claudeKey) {
        keyInput = if (currentProvider == "OpenAI") openaiKey ?: "" else claudeKey ?: ""
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Keep Screen On during meetings", style = MaterialTheme.typography.titleMedium)
            Switch(checked = keepScreenOn, onCheckedChange = { scope.launch { settingsManager.setKeepScreenOn(it) } })
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("AI Provider", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = currentProvider == "OpenAI", onClick = { scope.launch { settingsManager.setProvider("OpenAI") } })
            Text("OpenAI", modifier = Modifier.clickable { scope.launch { settingsManager.setProvider("OpenAI") } })
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = currentProvider == "Claude", onClick = { scope.launch { settingsManager.setProvider("Claude") } })
            Text("Claude", modifier = Modifier.clickable { scope.launch { settingsManager.setProvider("Claude") } })
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("$currentProvider API Key", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (currentProvider == "OpenAI") "sk-..." else "sk-ant-...") },
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("API Key") }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    if (currentProvider == "OpenAI") settingsManager.saveOpenAIKey(keyInput) else settingsManager.saveClaudeKey(keyInput)
                    Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Save, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save")
            }
            Button(onClick = {
                scope.launch {
                    settingsManager.deleteApiKey(currentProvider == "OpenAI")
                    keyInput = ""
                    Toast.makeText(context, "Key Deleted", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Delete")
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember(context) { MeetingDatabase.getDatabase(context) }
    val meetings by db.meetingDao().getAllMeetings().collectAsState(initial = emptyList())
    var selectedMeeting by remember { mutableStateOf<MeetingSession?>(null) }

    if (selectedMeeting != null) {
        MeetingDetailScreen(selectedMeeting!!) { selectedMeeting = null }
    } else {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Past Meetings", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn {
                items(meetings) { meeting ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedMeeting = meeting }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(meeting.title, fontWeight = FontWeight.Bold)
                            Text(SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(meeting.timestamp)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeetingDetailScreen(meeting: MeetingSession, onBack: () -> Unit) {
    val context = LocalContext.current
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Meeting Content", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
    }
    fun shareMeeting() {
        val shareText = "${meeting.title}\nDate: ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(meeting.timestamp))}\n\nSUMMARY:\n${meeting.summary}\n\nACTION ITEMS:\n${meeting.actionItems}\n\nTRANSCRIPT:\n${meeting.transcript}"
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, meeting.title); putExtra(Intent.EXTRA_TEXT, shareText) }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Row {
                IconButton(onClick = { copyToClipboard("${meeting.summary}\n\n${meeting.actionItems}") }) { Icon(Icons.Default.ContentCopy, null) }
                IconButton(onClick = { shareMeeting() }) { Icon(Icons.Default.Share, null) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SelectionContainer {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(meeting.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Summary")
                Text(meeting.summary)
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Action Items")
                Text(meeting.actionItems)
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle("Full Transcript")
                Text(meeting.transcript)
            }
        }
    }
}
