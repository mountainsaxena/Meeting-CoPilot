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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.unit.sp
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

data class AIResponse(
    val summary: String, 
    val actionItems: String, 
    val classification: String = "Unknown",
    val decision: String = "",
    val confidence: String = "",
    val missing: String = "",
    val whatChanged: String = "",
    val suggestedDelay: Int? = null
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

    return if (provider == "OpenAI") {
        fetchFromOpenAI(transcript, lastTranscript, apiKey, isAdaptive, includeClassification)
    } else {
        fetchFromClaude(transcript, lastTranscript, apiKey, isAdaptive, includeClassification)
    }
}

suspend fun fetchFromOpenAI(transcript: String, lastTranscript: String, apiKey: String, isAdaptive: Boolean, includeClassification: Boolean): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    
    val adaptiveInstr = if (isAdaptive) "5. SMART SAMPLING: Assess discussion density. If the meeting is quiet or repetitive, suggest a higher next_delay (up to 300s). If it's intense or changing fast, suggest a lower next_delay (min 20s). Add key 'suggested_delay'." else ""

    val systemPrompt = "You are a helpful assistant. Tasks:\n" +
        "1. Summarize the transcript.\n" +
        "2. Extract action items as a bulleted list string.\n" +
        "3. Identify decisions in the last 2 mins. Keys: 'decision', 'confidence', 'missing'.\n" +
        "4. Track changes vs LAST window. Key: 'what_changed'.\n" +
        adaptiveInstr + "\n" +
        (if (includeClassification) "6. Classify meeting state.\n" else "") +
        "Respond ONLY in JSON format."

    val jsonBody = JSONObject().apply {
        put("model", "gpt-3.5-turbo")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", "LAST: $lastTranscript\nCURRENT: $transcript") })
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
                
                val rawActionItems = parsed.opt("action_items")
                val formattedActionItems = if (rawActionItems is JSONArray) {
                    val sb = StringBuilder()
                    for (i in 0 until rawActionItems.length()) {
                        sb.append("* ").append(rawActionItems.getString(i)).append("\n")
                    }
                    sb.toString().trim()
                } else {
                    rawActionItems?.toString()?.replace(Regex("[\\[\\]\"]"), "")?.replace(",", "\n* ")?.let { if (!it.startsWith("* ")) "* $it" else it } ?: ""
                }

                AIResponse(
                    summary = parsed.getString("summary"),
                    actionItems = formattedActionItems,
                    classification = if (includeClassification) parsed.optString("classification", "Unknown") else "Updating...",
                    decision = parsed.optString("decision", ""),
                    confidence = parsed.optString("confidence", ""),
                    missing = parsed.optString("missing", ""),
                    whatChanged = parsed.optString("what_changed", ""),
                    suggestedDelay = if (isAdaptive) parsed.optInt("suggested_delay") else null
                )
            }
        } catch (e: Exception) { AIResponse("Parsing Error: ${e.message}", "Error") }
    }
}

suspend fun fetchFromClaude(transcript: String, lastTranscript: String, apiKey: String, isAdaptive: Boolean, includeClassification: Boolean): AIResponse {
    val client = OkHttpClient()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    
    val classificationInstr = if (includeClassification) "Also classify the meeting state as one of: Brainstorming, Decision-making, Status update, Conflict / debate, Drift / off-topic. Add a 'classification' key." else ""
    
    val prompt = "Analyze this meeting transcript parts. Tasks:\n" +
                 "1. Summarize CURRENT.\n" +
                 "2. Extract action items from CURRENT as a bulleted list string.\n" +
                 "3. Detect decisions in the last 2 minutes of CURRENT. Keys: 'decision' (specific result or empty), 'confidence' (High/Med/Low), 'missing' (what is needed if unclear).\n" +
                 "4. Track changes comparing CURRENT with LAST. Field 'what_changed' (bullet points on new topics, decision progress, repetition, or action items).\n" +
                 classificationInstr + "\n" +
                 "Format entire response as a single valid JSON object with keys: 'summary', 'action_items', 'decision', 'confidence', 'missing', 'what_changed'" + 
                 (if (includeClassification) ", 'classification'." else ".") + " No preamble.\n\n" +
                 "LAST TRANSCRIPT WINDOW: $lastTranscript\n\nCURRENT TRANSCRIPT: $transcript"

    val jsonBody = JSONObject().apply {
        put("model", "claude-3-haiku-20240307")
        put("max_tokens", 1024)
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
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
                if (!response.isSuccessful) return@withContext AIResponse("Error: ${response.code}", "Error")
                
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.getJSONArray("content").getJSONObject(0).getString("text")
                val start = content.indexOf("{")
                val end = content.lastIndexOf("}")
                if (start != -1 && end != -1) {
                    val parsed = JSONObject(content.substring(start, end + 1))
                    
                    val rawActionItems = parsed.opt("action_items")
                    val formattedActionItems = if (rawActionItems is JSONArray) {
                        val sb = StringBuilder()
                        for (i in 0 until rawActionItems.length()) {
                            sb.append("* ").append(rawActionItems.getString(i)).append("\n")
                        }
                        sb.toString().trim()
                    } else {
                        rawActionItems?.toString()?.replace(Regex("[\\[\\]\"]"), "")?.replace(",", "\n* ")?.let { if (!it.startsWith("* ")) "* $it" else it } ?: ""
                    }

                    AIResponse(
                        summary = parsed.getString("summary"),
                        actionItems = formattedActionItems,
                        classification = if (includeClassification) parsed.optString("classification", "Unknown") else "Updating...",
                        decision = parsed.optString("decision", ""),
                        confidence = parsed.optString("confidence", ""),
                        missing = parsed.optString("missing", ""),
                        whatChanged = parsed.optString("what_changed", "")
                    )
                } else { AIResponse("Parse Error", "Error") }
            }
        } catch (e: Exception) { AIResponse("Parsing Error: ${e.message}", "Error") }
    }
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    
    // Lift state up to MainNavigation so it survives tab switching
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
    var updateCount by remember { mutableIntStateOf(0) }
    
    val settingsManager = remember { SettingsManager(context) }
    val baseInterval by settingsManager.updateIntervalFlow.collectAsState(60)
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    
    // State for the AI query countdown
    var activeInterval by remember { mutableIntStateOf(baseInterval) }
    var secondsUntilNextUpdate by remember { mutableIntStateOf(activeInterval) }
    var isWaitingForAI by remember { mutableStateOf(false) }

    // Sync activeInterval when interval setting changes
    LaunchedEffect(baseInterval, isAdaptive) {
        if (!isAdaptive) {
            activeInterval = baseInterval
            if (secondsUntilNextUpdate > baseInterval) {
                secondsUntilNextUpdate = baseInterval
            }
        }
    }

    val provider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val openaiKey by settingsManager.openaiKeyFlow.collectAsState(null)
    val claudeKey by settingsManager.claudeKeyFlow.collectAsState(null)
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(false)
    val activeKey = if (provider == "OpenAI") openaiKey else claudeKey

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    // AI logic moved up
    LaunchedEffect(isRecording) {
        if (isRecording) {
            updateCount = 0
            secondsUntilNextUpdate = activeInterval
            while (isRecording) {
                while (secondsUntilNextUpdate > 0 && isRecording) {
                    delay(1000)
                    secondsUntilNextUpdate--
                }
                
                if (isRecording && transcript.isNotBlank() && !activeKey.isNullOrBlank()) {
                    isWaitingForAI = true
                    updateCount++
                    val shouldClassify = updateCount % 2 == 0
                    val response = fetchFromAI(transcript, lastTranscriptWindow, provider, activeKey ?: "", isAdaptive, includeClassification = shouldClassify)
                    
                    rollingSummary = response.summary
                    actionItems = response.actionItems
                    decisionText = response.decision
                    decisionConfidence = response.confidence
                    decisionMissing = response.missing
                    whatChangedText = response.whatChanged
                    
                    if (shouldClassify) {
                        meetingState = response.classification
                    }
                    
                    if (isAdaptive && response.suggestedDelay != null) {
                        activeInterval = response.suggestedDelay.coerceIn(20, 300)
                    } else if (!isAdaptive) {
                        activeInterval = baseInterval
                    }
                    
                    lastTranscriptWindow = transcript
                    isWaitingForAI = false
                    secondsUntilNextUpdate = activeInterval
                } else if (isRecording) {
                    secondsUntilNextUpdate = 5 // Retry sooner if keys missing or empty transcript
                }
            }
        }
    }

    // Recognizer listener moved up
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
                0 -> MeetingScreen(
                    isRecording = isRecording,
                    onRecordingChange = { isRecording = it },
                    transcript = transcript,
                    onTranscriptChange = { transcript = it },
                    rollingSummary = rollingSummary,
                    onSummaryChange = { rollingSummary = it },
                    actionItems = actionItems,
                    onActionItemsChange = { actionItems = it },
                    meetingState = meetingState,
                    onMeetingStateChange = { meetingState = it },
                    decisionText = decisionText,
                    onDecisionTextChange = { decisionText = it },
                    decisionConfidence = decisionConfidence,
                    onDecisionConfidenceChange = { decisionConfidence = it },
                    decisionMissing = decisionMissing,
                    onDecisionMissingChange = { decisionMissing = it },
                    whatChangedText = whatChangedText,
                    onWhatChangedTextChange = { whatChangedText = it },
                    lastTranscriptWindow = lastTranscriptWindow,
                    onLastTranscriptWindowChange = { lastTranscriptWindow = it },
                    speechRecognizer = speechRecognizer,
                    recognizerIntent = recognizerIntent,
                    isWaitingForAI = isWaitingForAI,
                    secondsToUpdate = secondsUntilNextUpdate,
                    totalInterval = activeInterval
                )
                1 -> HistoryScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

@Composable
fun MeetingScreen(
    isRecording: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    transcript: String,
    onTranscriptChange: (String) -> Unit,
    rollingSummary: String,
    onSummaryChange: (String) -> Unit,
    actionItems: String,
    onActionItemsChange: (String) -> Unit,
    meetingState: String,
    onMeetingStateChange: (String) -> Unit,
    decisionText: String,
    onDecisionTextChange: (String) -> Unit,
    decisionConfidence: String,
    onDecisionConfidenceChange: (String) -> Unit,
    decisionMissing: String,
    onDecisionMissingChange: (String) -> Unit,
    whatChangedText: String,
    onWhatChangedTextChange: (String) -> Unit,
    lastTranscriptWindow: String,
    onLastTranscriptWindowChange: (String) -> Unit,
    speechRecognizer: SpeechRecognizer,
    recognizerIntent: Intent,
    isWaitingForAI: Boolean,
    secondsToUpdate: Int,
    totalInterval: Int
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val db = remember(context) { MeetingDatabase.getDatabase(context) }
    val settingsManager = remember { SettingsManager(context) }
    val haptic = LocalHapticFeedback.current
    
    val provider by settingsManager.selectedProviderFlow.collectAsState("OpenAI")
    val openaiKey by settingsManager.openaiKeyFlow.collectAsState(null)
    val claudeKey by settingsManager.claudeKeyFlow.collectAsState(null)
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(false)
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    
    val activeKey = if (provider == "OpenAI") openaiKey else claudeKey
    
    var permissionGranted by remember { mutableStateOf(false) }

    val progress by animateFloatAsState(targetValue = secondsToUpdate.toFloat() / totalInterval.toFloat(), label = "CountdownProgress")

    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity?.setKeepScreenOn(true)
        onDispose { activity?.setKeepScreenOn(false) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> permissionGranted = isGranted }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Meeting CoPilot", style = MaterialTheme.typography.headlineMedium)
                Text("Using: $provider ${if (isAdaptive) "(Adaptive)" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            
            if (isRecording) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 4.dp
                    )
                    if (isWaitingForAI) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = "${secondsToUpdate}s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            if (isWaitingForAI) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                Text("AI analyzing current discussion...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                Text("Next intelligence update in ${secondsToUpdate}s", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (keepScreenOn) {
            Text("Screen stay-on active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card {
            Text(
                text = meetingState,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (activeKey.isNullOrBlank()) {
            Text("Set $provider Key in Settings", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else {
                    if (isRecording) {
                        onRecordingChange(false)
                        speechRecognizer.stopListening()
                        scope.launch {
                            db.meetingDao().insertMeeting(MeetingSession(
                                timestamp = System.currentTimeMillis(),
                                title = "Meeting ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())}",
                                transcript = transcript, summary = rollingSummary, actionItems = actionItems,
                                classification = meetingState,
                                latestDecision = decisionText,
                                decisionConfidence = decisionConfidence,
                                decisionMissing = decisionMissing,
                                whatChanged = whatChangedText
                            ))
                        }
                    } else {
                        onTranscriptChange("")
                        onLastTranscriptWindowChange("")
                        onSummaryChange("Summarizing...")
                        onActionItemsChange("Extracting...")
                        onMeetingStateChange("Initializing...")
                        onDecisionTextChange("")
                        onDecisionConfidenceChange("")
                        onDecisionMissingChange("")
                        onWhatChangedTextChange("")
                        
                        onRecordingChange(true)
                        speechRecognizer.startListening(recognizerIntent)
                    }
                }
            },
            colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isRecording) "Stop & Save Meeting" else "Start Meeting") }

        Spacer(modifier = Modifier.height(16.dp))
        SelectionContainer {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (whatChangedText.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("What Changed?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(whatChangedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (decisionText.isNotBlank() && decisionText.lowercase() != "no decision made") {
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Latest Decision Detected", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Decision: \"$decisionText\"", style = MaterialTheme.typography.bodyMedium)
                            Text("Confidence: $decisionConfidence", style = MaterialTheme.typography.labelSmall)
                            if (decisionMissing.isNotBlank()) {
                                Text("Missing: \"$decisionMissing\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

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
    val updateInterval by settingsManager.updateIntervalFlow.collectAsState(60)
    val isAdaptive by settingsManager.adaptiveIntervalFlow.collectAsState(false)
    
    var keyInput by remember { mutableStateOf("") }
    
    LaunchedEffect(currentProvider, openaiKey, claudeKey) {
        keyInput = if (currentProvider == "OpenAI") openaiKey ?: "" else claudeKey ?: ""
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Keep Screen On")
            Switch(checked = keepScreenOn, onCheckedChange = { scope.launch { settingsManager.setKeepScreenOn(it) } })
        }
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Adaptive AI Frequency (Smart Sampling)")
            Switch(checked = isAdaptive, onCheckedChange = { scope.launch { settingsManager.setAdaptiveInterval(it) } })
        }
        
        if (!isAdaptive) {
            Text("AI Intelligence Frequency: ${updateInterval}s")
            Slider(
                value = updateInterval.toFloat(),
                onValueChange = { scope.launch { settingsManager.setUpdateInterval(it.toInt()) } },
                valueRange = 20f..300f,
                steps = 13
            )
        } else {
            Text("AI will automatically adjust frequency based on discussion density.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
        
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (currentProvider == "OpenAI") "sk-..." else "sk-ant-...") },
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("$currentProvider Key") }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    if (currentProvider == "OpenAI") settingsManager.saveOpenAIKey(keyInput) else settingsManager.saveClaudeKey(keyInput)
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Delete")
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
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
            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(meetings) { meeting ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedMeeting = meeting }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(meeting.title, fontWeight = FontWeight.Bold)
                            Text(SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(meeting.timestamp)), style = MaterialTheme.typography.bodySmall)
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Meeting Content", text))
        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
    }
    fun shareMeeting() {
        val shareText = "${meeting.title}\nState: ${meeting.classification}\n\nSUMMARY:\n${meeting.summary}\n\nACTION ITEMS:\n${meeting.actionItems}"
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
        SelectionContainer {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(meeting.title, style = MaterialTheme.typography.headlineSmall)
                Text("Meeting State: ${meeting.classification}", color = MaterialTheme.colorScheme.tertiary)
                if (meeting.latestDecision.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(12.dp)) { Text("Decision Made", fontWeight = FontWeight.Bold); Text(meeting.latestDecision) }
                    }
                }
                SectionTitle("Summary"); Text(meeting.summary)
                SectionTitle("Action Items"); Text(meeting.actionItems)
                SectionTitle("Full Transcript"); Text(meeting.transcript)
            }
        }
    }
}
