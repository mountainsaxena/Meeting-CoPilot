# Meeting CoPilot 🚀

Meeting CoPilot is an AI-powered Android application designed to help you capture, summarize, and extract value from your meetings in real-time. It uses on-device speech recognition coupled with state-of-the-art LLMs (OpenAI & Claude) to provide live transcripts and rolling intelligence.

## ✨ Features

- **🔴 Live Transcription**: Real-time speech-to-text conversion directly on your device.
- **🧠 Rolling Intelligence**: Automatically generates summaries and extracts action items every 30 seconds during the meeting.
- **🤖 Dual AI Support**: Choose between **OpenAI (GPT-3.5/4)** or **Claude (Haiku/Sonnet)** based on your preference.
- **📁 Meeting History**: Saves all your sessions locally using a Room database.
- **📋 Smart History Detail**: Review past transcripts, summaries, and tasks at any time.
- **📤 Easy Sharing**: Copy summaries to your clipboard or share full meeting notes via Email, Slack, or SMS.
- **⚙️ Secure Settings**: Personalize your experience and securely store your own API keys locally using Jetpack DataStore.
- **🚫 Anti-Sleep Mode**: Toggle "Keep Screen On" to monitor your meeting without the device dimming.

## 🛠️ Tech Stack

- **UI**: Jetpack Compose (Modern Declarative UI)
- **Database**: Room Persistence Library
- **Storage**: Jetpack DataStore (Preferences)
- **Networking**: OkHttp
- **Speech**: Android SpeechRecognizer API
- **Language**: 100% Kotlin

## 🚀 Getting Started

### Prerequisites
1.  **Android Device**: A physical device is recommended for the best microphone/speech recognition experience.
2.  **API Keys**: You will need an API key from [OpenAI](https://platform.openai.com/) and/or [Anthropic (Claude)](https://console.anthropic.com/).

### Installation
1.  Clone the repository or open the project in **Android Studio**.
2.  Build and deploy the app to your device.
3.  Grant the **Microphone (Record Audio)** permission when prompted.

### Configuration
1.  Open the app and navigate to the **Settings** tab.
2.  Select your preferred **AI Provider** (OpenAI or Claude).
3.  Paste your **API Key** into the input field and tap **Save**.
4.  (Optional) Enable **Keep Screen On** to prevent the device from sleeping during live sessions.

## 📝 How to Use
1.  **Start**: Tap the "Start Meeting" button on the main tab.
2.  **Monitor**: Watch the live transcript grow. Every 30 seconds, the "Rolling Summary" and "Action Items" will update automatically.
3.  **Finish**: Tap "Stop & Save Meeting". The session is now safely stored in your history.
4.  **Review**: Go to the **History** tab to see a list of past meetings. Tap one to see details, copy content, or share it with your team.

## 🔒 Privacy & Security
- **Local Storage**: All transcripts and meeting data are stored locally on your device's private database.
- **API Security**: Your API keys are stored securely using Jetpack DataStore and are never hardcoded or sent to any server other than the official OpenAI/Anthropic APIs.
- **Permissions**: The app only requires `RECORD_AUDIO` for transcription and `INTERNET` to communicate with the AI providers.

---
*Created with ❤️ for better productivity.*
