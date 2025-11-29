package com.example.selfhypnosis

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread

data class ToneSpec(val type: String, val carrier: Double, val mod: Double, val binaural: Boolean)
data class Session(val id: String, val title: String, val suggestion: String?, val stages: List<ToneSpec>)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var audioThread: Thread? = null
    @Volatile private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)

        val container = findViewById<LinearLayout>(R.id.container)
        val stopBtn = findViewById<Button>(R.id.btn_stop)
        stopBtn.setOnClickListener { stopAudio(); Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show() }

        val sessions = loadSessions()

        // create a button for each session
        sessions.forEach { s ->
            val btn = Button(this)
            btn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = (8 * resources.displayMetrics.density).toInt()
                setMargins(0, m, 0, m)
            }
            btn.text = s.title
            btn.setOnClickListener {
                // play session: speak and play stages sequentially
                playSession(s)
            }
            container.addView(btn)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        stopAudio()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    private fun loadSessions(): List<Session> {
        try {
            val isStream: InputStream = assets.open("frequency.json")
            val json = isStream.bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("sessions")
            val out = mutableListOf<Session>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val suggestion = if (obj.has("suggestion")) obj.getString("suggestion") else null
                val stages = mutableListOf<ToneSpec>()
                val keys = listOf("induction", "deepening", "reinforcement", "exit")
                for (k in keys) {
                    if (obj.has(k)) {
                        val t = obj.getJSONObject(k)
                        val type = t.optString("type", "isochronic")
                        val carrier = t.optDouble("carrier", 440.0)
                        val mod = t.optDouble("mod", 8.0)
                        val binaural = t.optBoolean("binaural", false)
                        stages.add(ToneSpec(type, carrier, mod, binaural))
                    }
                }
                out.add(Session(id, title, suggestion, stages))
            }
            return out
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Failed to load sessions: ${e.message}", Toast.LENGTH_LONG).show() }
            return emptyList()
        }
    }

    private fun playSession(session: Session) {
        // run stages sequentially: for simplicity, each stage plays for a short fixed duration while TTS speaks suggestion once
        stopAudio()
        tts?.speak(session.suggestion ?: "", TextToSpeech.QUEUE_FLUSH, null, session.id)
        thread {
            try {
                for (stage in session.stages) {
                    if (!playing) {
                        // start stage
                        startTone(stage)
                        // play stage for fixed duration
                        Thread.sleep(8000)
                        stopAudio()
                    }
                }
            } catch (e: InterruptedException) {
            }
        }
    }

    private fun startTone(spec: ToneSpec) {
        stopAudio()
        playing = true
        audioThread = thread {
            val sampleRate = 44100
            val stereo = spec.binaural
            val channelConfig = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, minBuf, AudioTrack.MODE_STREAM)
            track.play()

            val twoPi = 2.0 * Math.PI
            val dt = 1.0 / sampleRate
            val amplitude = 0.7 * Short.MAX_VALUE

            // if binaural, left = carrier - offset, right = carrier + offset
            val leftCarrier = if (spec.binaural) spec.carrier - 2.0 else spec.carrier
            val rightCarrier = if (spec.binaural) spec.carrier + 2.0 else spec.carrier

            var t = 0.0

            val frameSamples = 1024
            val bufferMono = ShortArray(frameSamples)
            val bufferStereo = ShortArray(frameSamples * 2)

            while (playing) {
                if (spec.type == "isochronic") {
                    // isochronic pulses: amplitude gated by a square wave at mod frequency
                    for (i in 0 until frameSamples) {
                        val env = if (Math.sin(twoPi * spec.mod * t) > 0) 1.0 else 0.05
                        val sample = amplitude * env * Math.sin(twoPi * spec.carrier * t)
                        bufferMono[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        if (stereo) {
                            val l = amplitude * env * Math.sin(twoPi * leftCarrier * t)
                            val r = amplitude * env * Math.sin(twoPi * rightCarrier * t)
                            val li = l.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            val ri = r.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            bufferStereo[i * 2] = li
                            bufferStereo[i * 2 + 1] = ri
                        }
                        t += dt
                    }
                    if (stereo) {
                        track.write(bufferStereo, 0, bufferStereo.size)
                    } else {
                        track.write(bufferMono, 0, bufferMono.size)
                    }
                } else if (spec.type == "burst") {
                    // burst / reinforcement: short high-frequency bursts
                    for (i in 0 until frameSamples) {
                        val env = (0.5 * (1.0 + Math.sin(twoPi * spec.mod * t)))
                        val sample = amplitude * env * Math.sin(twoPi * spec.carrier * t)
                        bufferMono[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        if (stereo) {
                            val l = amplitude * env * Math.sin(twoPi * leftCarrier * t)
                            val r = amplitude * env * Math.sin(twoPi * rightCarrier * t)
                            bufferStereo[i * 2] = l.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            bufferStereo[i * 2 + 1] = r.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        t += dt
                    }
                    if (stereo) track.write(bufferStereo, 0, bufferStereo.size) else track.write(bufferMono, 0, bufferMono.size)
                } else {
                    // default continuous tone
                    for (i in 0 until frameSamples) {
                        val sample = amplitude * Math.sin(twoPi * spec.carrier * t)
                        bufferMono[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        if (stereo) {
                            val l = amplitude * Math.sin(twoPi * leftCarrier * t)
                            val r = amplitude * Math.sin(twoPi * rightCarrier * t)
                            bufferStereo[i * 2] = l.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            bufferStereo[i * 2 + 1] = r.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        t += dt
                    }
                    if (stereo) track.write(bufferStereo, 0, bufferStereo.size) else track.write(bufferMono, 0, bufferMono.size)
                }
            }

            try {
                track.stop()
                track.release()
            } catch (_: Exception) { }
        }
    }

    private fun stopAudio() {
        playing = false
        try { audioThread?.join(200) } catch (_: Exception) { }
        audioThread = null
    }
}
