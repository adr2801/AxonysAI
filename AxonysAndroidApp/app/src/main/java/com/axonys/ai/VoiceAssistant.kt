package com.axonys.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceAssistant(
    private val context: Context, 
    private val onSpeakStatusChanged: (Boolean) -> Unit = {},
    private val onListeningStatusChanged: (Boolean) -> Unit = {},
    private val onResult: (String) -> Unit
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(this)

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(Locale.FRENCH)
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeakStatusChanged(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        onSpeakStatusChanged(false)
                    }
                    override fun onError(utteranceId: String?) {
                        onSpeakStatusChanged(false)
                    }
                })
                isTtsReady = true
            }
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Je vous écoute...")
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun speak(text: String) {
        if (isTtsReady) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis_speech")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_speech")
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    // RecognitionListener callbacks
    override fun onReadyForSpeech(params: Bundle?) { 
        Log.d("VoiceAssistant", "Prêt") 
        onListeningStatusChanged(true)
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        onListeningStatusChanged(false)
    }
    override fun onError(error: Int) {
        onListeningStatusChanged(false)
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Erreur audio"
            SpeechRecognizer.ERROR_CLIENT -> "Erreur client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions insuffisantes"
            SpeechRecognizer.ERROR_NETWORK -> "Erreur réseau"
            SpeechRecognizer.ERROR_NO_MATCH -> "Aucun match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconnaissance occupée"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
            else -> "Erreur inconnue: $error"
        }
        Log.e("VoiceAssistant", message)
    }

    override fun onResults(results: Bundle?) {
        onListeningStatusChanged(false)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            onResult(matches[0])
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
