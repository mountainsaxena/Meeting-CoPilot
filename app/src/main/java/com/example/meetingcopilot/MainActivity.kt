package com.example.meetingcopilot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.meetingcopilot.ui.theme.MeetingCoPilotTheme
import com.google.ai.client.generativeai.GenerativeModel
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

data class AIResponse(
    val summary: String, 
    val actionItems: String, 
    val classification: String = "Unknown",
    val decision: String = "",
    val confidence: String = "",
    val missing: String = "",
    val whatChanged: String = "",
    val suggestedDelay: Int? = null,
    val nudges: String = "",
    val questions: String = ""
)

suspend fun fetchFromAI(
    transcript: String, 
    lastTranscript: String,
    provider: String, 
    apiKey: String, 
    isAdaptive: Boolean,
    includeClassification: Boolean = false
): AIResponse {
    if (apiKey.isBlank()) return AIResponse("Error: API Key not set", "Error: API Key not set")
    if (transcript.trim().isBlank()) return AIResponse("Waiting for enough data...", "Waiting...")

    return when (provider) {
        "OpenAI" -> fetchFromOpenAI(transcript, lastTranscript, apiKey, isAdaptive, includeClassification)
        "Claude" -> fetchFromClaude(transcript, lastTranscript, apiKey, isAdaptive, includeClassification)
        "Gemini" -> fetchFromGemini(transcript, lastTranscript, apiKey, isAdaptive, includeClassification)
        else -> AIResponse("Unknown provider", "Error")
    }
}

suspend fun fetchFromOpenAI(transcript: String, lastTranscript: String, apiKey: String, isAdaptive: Boolean, includeClassification: Boolean): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    
    val adaptiveInstr = if (isAdaptive) "5. SMART SAMPLING: Suggest a 'suggested_delay' (20-300s) based on density." else ""
    val systemPrompt = "You are a helpful assistant. Tasks: 1. Summarize. 2. Extract action items. 3. Detect decisions. 4. Track changes. 5. Live Nudges. 6. Questions. $adaptiveInstr Respond ONLY in JSON format with keys: 'summary', 'action_items', 'decision', 'confidence', 'missing', 'what_changed', 'nudges', 'questions'" + (if (includeClassification) ", 'classification'." else ".")

    val jsonBody = JSONObject().apply {
        put("model", "gpt-3.5-turbo")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", "LAST: $lastTranscript\nCURRENT: $transcript") })
        })
        put("response_format", JSONObject().apply { put("type", "json_object") })
    }

    val request = Request.Builder().url("https://api.openai.com/v1/chat/completions").addHeader("Authorization", "Bearer $apiKey").post(jsonBody.toString().toRequestBody(mediaType)).build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return@withContext AIResponse("Empty", "Empty")
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val parsed = JSONObject(content)
                AIResponse(
                    summary = parsed.getString("summary"),
                    actionItems = parsed.getString("action_items"),
                    classification = if (includeClassification) parsed.optString("classification", "Unknown") else "Updating...",
                    decision = parsed.optString("decision", ""),
                    confidence = parsed.optString("confidence", ""),
                    missing = parsed.optString("missing", ""),
                    whatChanged = parsed.optString("what_changed", ""),
                    suggestedDelay = if (isAdaptive) parsed.optInt("suggested_delay") else null,
                    nudges = parsed.optString("nudges", ""),
                    questions = parsed.optString("questions", "")
                )
            }
        } catch (e: Exception) { AIResponse("Parsing Error: ${e.message}", "Error") }
    }
}

suspend fun fetchFromClaude(transcript: String, lastTranscript: String, apiKey: String, isAdaptive: Boolean, includeClassification: Boolean): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val adaptiveInstr = if (isAdaptive) "SMART SAMPLING: Suggest a 'suggested_delay' (20-300s)." else ""
    val prompt = "Analyze transcript parts. Tasks: 1. Summarize. 2. Extract action items. 3. Detect decisions. 4. Track changes. 5. Live Nudges. 6. Questions. $adaptiveInstr " + (if (includeClassification) "7. Classify meeting." else "") + " No preamble. JSON ONLY. LAST: $lastTranscript\nCURRENT: $transcript"

    val jsonBody = JSONObject().apply {
        put("model", "claude-3-haiku-20240307")
        put("max_tokens", 1024)
        put("messages", JSONArray().apply { put(JSONObject().apply { put("role", "user"); put("content", prompt) }) })
    }

    val request = Request.Builder().url("https://api.anthropic.com/v1/messages").addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01").post(jsonBody.toString().toRequestBody(mediaType)).build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return@withContext AIResponse("Empty", "Empty")
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.getJSONArray("content").getJSONObject(0).getString("text")
                val start = content.indexOf("{")
                val end = content.lastIndexOf("}")
                val parsed = JSONObject(content.substring(start, end + 1))
                
                AIResponse(
                    summary = parsed.getString("summary"),
                    actionItems = parsed.getString("action_items"),
                    classification = if (includeClassification) parsed.optString("classification", "Unknown") else "Updating...",
                    decision = parsed.optString("decision", ""),
                    confidence = parsed.optString("confidence", ""),
                    missing = parsed.optString("missing", ""),
                    whatChanged = parsed.optString("what_changed", ""),
                    suggestedDelay = if (isAdaptive) parsed.optInt("suggested_delay") else null,
                    nudges = parsed.optString("nudges", ""),
                    questions = parsed.optString("questions", "")
                )
            }
        } catch (e: Exception) { AIResponse("Parsing Error: ${e.message}", "Error") }
    }
}

suspend fun fetchFromGemini(transcript: String, lastTranscript: String, apiKey: String, isAdaptive: Boolean, includeClassification: Boolean): AIResponse {
    val generativeModel = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = apiKey)
    val adaptiveInstr = if (isAdaptive) "SMART SAMPLING: Suggest a 'suggested_delay' (20-300s) based on density." else ""
    val promptText = "Analyze transcript parts. Tasks: 1. Summarize. 2. Extract action items. 3. Detect decisions. 4. Track changes. 5. Live Nudges. 6. Questions. $adaptiveInstr " + (if (includeClassification) "7. Classify meeting." else "") + " No preamble. Respond ONLY with a valid JSON object. LAST: $lastTranscript\nCURRENT: $transcript"

    return withContext(Dispatchers.IO) {
        try {
            val response = generativeModel.generateContent(promptText)
            val content = response.text ?: return@withContext AIResponse("Empty", "Empty")
            val start = content.indexOf("{")
            val end = content.lastIndexOf("}")
            val parsed = JSONObject(content.substring(start, end + 1))
            
            AIResponse(
                summary = parsed.getString("summary"),
                actionItems = parsed.getString("action_items"),
                classification = if (includeClassification) parsed.optString("classification", "Unknown") else "Updating...",
                decision = parsed.optString("decision", ""),
                confidence = parsed.optString("confidence", ""),
                missing = parsed.optString("missing", ""),
                whatChanged = parsed.optString("what_changed", ""),
                suggestedDelay = if (isAdaptive) parsed.optInt("suggested_delay") else null,
                nudges = parsed.optString("nudges", ""),
                questions = parsed.optString("questions", "")
            )
        } catch (e: Exception) { AIResponse("Error: ${e.message}", "Error") }
    }
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var lastTranscriptWindow by remember { mutableStateOf("") }
    var rollingSummary by remember { mutableStateOf("No summary yet...") }
    var actionItems by remember { mutableStateOf("No action items yet...") }
    var meetingState by remember { mutableStateOf("Unknown") }
    var decisionText by remember { mutableStateOf("") }
    var decisionConfidence by remember { mutableStateOf("") }
    var decisionMissing by remember { mutableStateOf("") }
    var whatChangedText by remember { mutableStateOf("") }
    var nudgesText by remember { mutableStateOf("") }
    var questionsText by remember { mutableStateOf("") }
    var updateCount by remember { mutableIntStateOf(0) }
    
    val settingsManager = remember { SettingsManager(context) }
    val baseInterval by settingsManager.updateIntervalFlow.collectAsState(60)
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    var activeInterval by remember { mutableIntStateOf(baseInterval) }
    var secondsUntilNextUpdate by remember { mutableIntStateOf(activeInterval) }
    var isWaitingForAI by remember { mutableStateOf(false) }

    LaunchedEffect(baseInterval, isAdaptive) {
        if (!isAdaptive) {
            activeInterval = baseInterval
            if (secondsUntilNextUpdate > baseInterval) secondsUntilNextUpdate = baseInterval
        }
    }

    val provider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val openaiKey by settingsManager.openaiKeyFlow.collectAsState(null)
    val claudeKey by settingsManager.claudeKeyFlow.collectAsState(null)
    val geminiKey by settingsManager.geminiKeyFlow.collectAsState(null)
    val activeKey = when(provider) {
        "OpenAI" -> openaiKey
        "Claude" -> claudeKey
        "Gemini" -> geminiKey
        else -> null
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault()); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) } }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            updateCount = 0
            secondsUntilNextUpdate = activeInterval
            while (isRecording) {
                while (secondsUntilNextUpdate > 0 && isRecording) { delay(1000); secondsUntilNextUpdate-- }
                if (isRecording && transcript.isNotBlank() && !activeKey.isNullOrBlank()) {
                    isWaitingForAI = true
                    updateCount++
                    val response = fetchFromAI(transcript, lastTranscriptWindow, provider, activeKey ?: "", isAdaptive, includeClassification = (updateCount % 2 == 0))
                    rollingSummary = response.summary; actionItems = response.actionItems; decisionText = response.decision; decisionConfidence = response.confidence; decisionMissing = response.missing; whatChangedText = response.whatChanged; nudgesText = response.nudges; questionsText = response.questions
                    if (updateCount % 2 == 0) meetingState = response.classification
                    activeInterval = if (isAdaptive && response.suggestedDelay != null) response.suggestedDelay.coerceIn(20, 300) else baseInterval
                    lastTranscriptWindow = transcript; isWaitingForAI = false; secondsUntilNextUpdate = activeInterval
                } else if (isRecording) { secondsUntilNextUpdate = 5 }
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
            override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)?.let { transcript += " $it" }; if (isRecording) speechRecognizer.startListening(recognizerIntent) }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

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
                0 -> MeetingScreen(isRecording, { isRecording = it }, transcript, { transcript = it }, rollingSummary, { rollingSummary = it }, actionItems, { actionItems = it }, meetingState, { meetingState = it }, decisionText, { decisionText = it }, decisionConfidence, { decisionConfidence = it }, decisionMissing, { decisionMissing = it }, whatChangedText, { whatChangedText = it }, nudgesText, { nudgesText = it }, questionsText, { questionsText = it }, speechRecognizer, recognizerIntent, isWaitingForAI, secondsUntilNextUpdate, activeInterval)
                1 -> HistoryScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun MeetingScreen(isRecording: Boolean, onRecordingChange: (Boolean) -> Unit, transcript: String, onTranscriptChange: (String) -> Unit, rollingSummary: String, onSummaryChange: (String) -> Unit, actionItems: String, onActionItemsChange: (String) -> Unit, meetingState: String, onMeetingStateChange: (String) -> Unit, decisionText: String, onDecisionTextChange: (String) -> Unit, decisionConfidence: String, onDecisionConfidenceChange: (String) -> Unit, decisionMissing: String, onDecisionMissingChange: (String) -> Unit, whatChangedText: String, onWhatChangedTextChange: (String) -> Unit, nudgesText: String, onNudgesTextChange: (String) -> Unit, questionsText: String, onQuestionsTextChange: (String) -> Unit, speechRecognizer: SpeechRecognizer, recognizerIntent: Intent, isWaitingForAI: Boolean, secondsToUpdate: Int, totalInterval: Int) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val db = remember(context) { MeetingDatabase.getDatabase(context) }
    val settingsManager = remember { SettingsManager(context) }
    val haptic = LocalHapticFeedback.current
    val provider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    val activeKey = when(provider) { "OpenAI" -> settingsManager.openaiKeyFlow; "Claude" -> settingsManager.claudeKeyFlow; "Gemini" -> settingsManager.geminiKeyFlow; else -> settingsManager.openaiKeyFlow }.collectAsState(null).value
    val progress by animateFloatAsState(targetValue = secondsToUpdate.toFloat() / totalInterval.toFloat(), label = "Countdown")

    DisposableEffect(Unit) { onDispose { activity?.setKeepScreenOn(false) } }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Meeting CoPilot", style = MaterialTheme.typography.headlineMedium); Text("Using: $provider ${if (isAdaptive) "(Adaptive)" else ""}", style = MaterialTheme.typography.bodySmall) }
            if (isRecording) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 4.dp)
                    if (isWaitingForAI) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) else Text(text = "${secondsToUpdate}s", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (isRecording && isWaitingForAI) { Spacer(modifier = Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp)) }
        Column {
            AnimatedVisibility(visible = isRecording && nudgesText.isNotBlank(), enter = expandVertically(), exit = shrinkVertically()) { Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text("CoPilot Suggestion", fontWeight = FontWeight.Bold); Text(nudgesText, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { onNudgesTextChange("") }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) } } } }
            AnimatedVisibility(visible = isRecording && questionsText.isNotBlank(), enter = expandVertically(), exit = shrinkVertically()) { Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.QuestionAnswer, null); Spacer(modifier = Modifier.width(12.dp)); Column(modifier = Modifier.weight(1f)) { Text("Participation Amplifier", fontWeight = FontWeight.Bold); Text(questionsText, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { onQuestionsTextChange("") }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) } } } }
        }
        Card { Text(text = meetingState, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(16.dp))
        if (activeKey.isNullOrBlank()) { Text("Set $provider Key in Settings", color = MaterialTheme.colorScheme.error); Spacer(modifier = Modifier.height(8.dp)) }
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (isRecording) {
                    onRecordingChange(false); speechRecognizer.stopListening()
                    scope.launch { db.meetingDao().insertMeeting(MeetingSession(timestamp = System.currentTimeMillis(), title = "Meeting ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())}", transcript = transcript, summary = rollingSummary, actionItems = actionItems, classification = meetingState, latestDecision = decisionText, decisionConfidence = decisionConfidence, decisionMissing = decisionMissing, whatChanged = whatChangedText, nudges = nudgesText, generatedQuestions = questionsText)) }
                } else {
                    onTranscriptChange(""); onSummaryChange("Summarizing..."); onActionItemsChange("Extracting..."); onMeetingStateChange("Initializing..."); onDecisionTextChange(""); onDecisionConfidenceChange(""); onDecisionMissingChange(""); onWhatChangedTextChange(""); onNudgesTextChange(""); onQuestionsTextChange("")
                    onRecordingChange(true); speechRecognizer.startListening(recognizerIntent)
                }
            },
            colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = Color.Red) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isRecording) "Stop & Save Meeting" else "Start Meeting") }
        Spacer(modifier = Modifier.height(16.dp))
        SelectionContainer {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (whatChangedText.isNotBlank()) Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Column(modifier = Modifier.padding(12.dp)) { Text("What Changed?", fontWeight = FontWeight.Bold); Text(whatChangedText, style = MaterialTheme.typography.bodySmall) } }
                if (decisionText.isNotBlank() && decisionText.lowercase() != "no decision made") Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(modifier = Modifier.padding(12.dp)) { Text("Latest Decision", fontWeight = FontWeight.Bold); Text(decisionText); if (decisionMissing.isNotBlank()) Text("Missing: $decisionMissing", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                SectionTitle("Rolling Summary"); Text(rollingSummary, color = MaterialTheme.colorScheme.primary)
                SectionTitle("Action Items"); Text(actionItems, color = MaterialTheme.colorScheme.secondary)
                HorizontalDivider(); SectionTitle("Live Transcript"); Text(transcript)
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
    val geminiKey by settingsManager.geminiKeyFlow.collectAsState("")
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(false)
    val updateInterval by settingsManager.updateIntervalFlow.collectAsState(60)
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    var keyInput by remember { mutableStateOf("") }
    LaunchedEffect(currentProvider, openaiKey, claudeKey, geminiKey) { keyInput = when(currentProvider) { "OpenAI" -> openaiKey ?: ""; "Claude" -> claudeKey ?: ""; "Gemini" -> geminiKey ?: ""; else -> "" } }
    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Keep Screen On"); Switch(checked = keepScreenOn, onCheckedChange = { scope.launch { settingsManager.setKeepScreenOn(it) } }) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Adaptive AI Frequency"); Switch(checked = isAdaptive, onCheckedChange = { scope.launch { settingsManager.setAdaptiveInterval(it) } }) }
        if (!isAdaptive) { Text("AI Intelligence Frequency: ${updateInterval}s"); Slider(value = updateInterval.toFloat(), onValueChange = { scope.launch { settingsManager.setUpdateInterval(it.toInt()) } }, valueRange = 20f..300f, steps = 13) }
        Spacer(modifier = Modifier.height(24.dp)); Text("AI Provider")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = currentProvider == "OpenAI", onClick = { scope.launch { settingsManager.setProvider("OpenAI") } }); Text("OpenAI")
            Spacer(modifier = Modifier.width(12.dp)); RadioButton(selected = currentProvider == "Claude", onClick = { scope.launch { settingsManager.setProvider("Claude") } }); Text("Claude")
            Spacer(modifier = Modifier.width(12.dp)); RadioButton(selected = currentProvider == "Gemini", onClick = { scope.launch { settingsManager.setProvider("Gemini") } }); Text("Gemini")
        }
        OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), label = { Text("$currentProvider Key") })
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { when(currentProvider) { "OpenAI" -> settingsManager.saveOpenAIKey(keyInput); "Claude" -> settingsManager.saveClaudeKey(keyInput); "Gemini" -> settingsManager.saveGeminiKey(keyInput) }; Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() } }, modifier = Modifier.weight(1f)) { Text("Save") }
            Button(onClick = { scope.launch { settingsManager.deleteApiKey(currentProvider); keyInput = ""; Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show() } }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        }
    }
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember(context) { MeetingDatabase.getDatabase(context) }
    val meetings by db.meetingDao().getAllMeetings().collectAsState(initial = emptyList())
    var selectedMeeting by remember { mutableStateOf<MeetingSession?>(null) }
    if (selectedMeeting != null) { MeetingDetailScreen(selectedMeeting!!) { selectedMeeting = null } } else {
        Column(modifier = Modifier.padding(16.dp)) { Text("Past Meetings", style = MaterialTheme.typography.headlineMedium); LazyColumn(modifier = Modifier.padding(top = 16.dp)) { items(meetings) { meeting -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedMeeting = meeting }) { Column(modifier = Modifier.padding(16.dp)) { Text(meeting.title, fontWeight = FontWeight.Bold); Text(SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(meeting.timestamp)), style = MaterialTheme.typography.bodySmall) } } } } }
    }
}

@Composable
fun MeetingDetailScreen(meeting: MeetingSession, onBack: () -> Unit) {
    val context = LocalContext.current
    fun copyToClipboard(text: String) { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Meeting Content", text)); Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() }
    fun shareMeeting() { val shareText = "${meeting.title}\nState: ${meeting.classification}\nDate: ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(meeting.timestamp))}\n\nSUMMARY:\n${meeting.summary}\n\nACTION ITEMS:\n${meeting.actionItems}\n\nNUDGES:\n${meeting.nudges}\n\nQUESTIONS:\n${meeting.generatedQuestions}"; val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, meeting.title); putExtra(Intent.EXTRA_TEXT, shareText) }; context.startActivity(Intent.createChooser(intent, "Share via")) }
    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Row { IconButton(onClick = { copyToClipboard("${meeting.summary}\n\n${meeting.actionItems}") }) { Icon(Icons.Default.ContentCopy, null) }; IconButton(onClick = { shareMeeting() }) { Icon(Icons.Default.Share, null) } }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SelectionContainer { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(meeting.title, style = MaterialTheme.typography.headlineSmall); Text("Meeting State: ${meeting.classification}", color = MaterialTheme.colorScheme.tertiary); if (meeting.nudges.isNotBlank()) Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(modifier = Modifier.padding(12.dp)) { Icon(Icons.Default.Lightbulb, null); Spacer(modifier = Modifier.width(8.dp)); Text(meeting.nudges, style = MaterialTheme.typography.bodySmall) } }; if (meeting.generatedQuestions.isNotBlank()) Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(modifier = Modifier.padding(12.dp)) { Icon(Icons.Default.QuestionAnswer, null); Spacer(modifier = Modifier.width(8.dp)); Text(meeting.generatedQuestions, style = MaterialTheme.typography.bodySmall) } }; if (meeting.latestDecision.isNotBlank()) Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(modifier = Modifier.padding(12.dp)) { Text("Decision Made", fontWeight = FontWeight.Bold); Text(meeting.latestDecision) } }; SectionTitle("Summary"); Text(meeting.summary); SectionTitle("Action Items"); Text(meeting.actionItems); SectionTitle("Full Transcript"); Text(meeting.transcript) } }
    }
}
