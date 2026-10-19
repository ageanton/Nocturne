package com.nocturne.piano

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MidiEvent(val timestampMs: Long, val type: Int, val note: Int, val velocity: Int)

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("nocturne")
        }
    }

    external fun initEngine(assetManager: android.content.res.AssetManager): Boolean
    external fun nativeNoteOn(note: Int, velocity: Float)
    external fun nativeNoteOff(note: Int)
    external fun nativeSetSustain(active: Boolean)
    external fun nativeSetSoftPedal(active: Boolean)
    external fun nativeSetInstrument(index: Int)
    external fun nativeSetControls(vol: Float, rev: Float, room: Float, width: Float)

    private lateinit var prefs: SharedPreferences
    private lateinit var pianoView: NocturneKeyboardView
    private lateinit var chordReadout: TextView
    private lateinit var instrumentBtn: Button
    private lateinit var sustainBtn: Button
    private lateinit var recordBtn: Button
    private lateinit var playMidiBtn: Button
    private lateinit var chordPadBar: LinearLayout

    private val instruments = arrayOf(
        "Concert Grand",
        "Studio Grand",
        "Upright Piano",
        "Felt Piano",
        "Rhodes Electric",
        "Nylon Guitar",
        "Soft Strings",
        "Lo-fi Piano",
        "Tape Piano",
        "Celeste",
        "Neon Synth Piano"
    )

    private var currentInstrument = 0
    private var sustainOn = true
    private var octaveLocked = false
    private var showNoteLabels = true
    private var hapticsEnabled = true
    private var transposeSemitones = 0
    private var volumeLevel = 0.90f
    private var reverbLevel = 0.30f
    private var roomSize = 0.65f
    private var stereoWidth = 1.25f

    // MIDI Recording State
    private var isRecording = false
    private var isPlayingRecording = false
    private var recordStartTime = 0L
    private val recordedMidiEvents = mutableListOf<MidiEvent>()
    private val playbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        prefs = getSharedPreferences("NocturneState", Context.MODE_PRIVATE)
        loadPersistedState()

        // Initialize native Oboe + SoundFont engine
        initEngine(assets)
        nativeSetInstrument(currentInstrument)
        nativeSetSustain(sustainOn)
        nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)

        buildInterface()
    }

    private fun loadPersistedState() {
        sustainOn = prefs.getBoolean("sustain_on", true)
        currentInstrument = prefs.getInt("instrument_idx", 0)
        octaveLocked = prefs.getBoolean("octave_locked", false)
        showNoteLabels = prefs.getBoolean("show_labels", true)
        hapticsEnabled = prefs.getBoolean("haptics", true)
        transposeSemitones = prefs.getInt("transpose", 0)
        volumeLevel = prefs.getFloat("volume", 0.90f)
        reverbLevel = prefs.getFloat("reverb", 0.30f)
        roomSize = prefs.getFloat("room_size", 0.65f)
        stereoWidth = prefs.getFloat("stereo_width", 1.25f)
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean("sustain_on", sustainOn)
            .putInt("instrument_idx", currentInstrument)
            .putBoolean("octave_locked", octaveLocked)
            .putBoolean("show_labels", showNoteLabels)
            .putBoolean("haptics", hapticsEnabled)
            .putInt("transpose", transposeSemitones)
            .putFloat("volume", volumeLevel)
            .putFloat("reverb", reverbLevel)
            .putFloat("room_size", roomSize)
            .putFloat("stereo_width", stereoWidth)
            .apply()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121316")) // Deep Charcoal
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Compact Top Studio Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 10, 24, 10)
            setBackgroundColor(Color.parseColor("#18191D"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        }

        val brandTitle = TextView(this).apply {
            text = "NOCTURNE"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.22f
            setTextColor(Color.parseColor("#D9822B")) // Copper Accent
            setPadding(0, 0, 24, 0)
        }
        topBar.addView(brandTitle)

        // Instrument Selector Button
        instrumentBtn = createTopButton(instruments[currentInstrument], true).apply {
            setOnClickListener { showInstrumentDialog() }
        }
        topBar.addView(instrumentBtn)

        // Detected Chord Display
        chordReadout = TextView(this).apply {
            text = "—"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E1D6"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(chordReadout)

        // One-Touch Chord Strip Toggle
        val chordToggleBtn = createTopButton("CHORDS", false).apply {
            setOnClickListener {
                if (chordPadBar.visibility == View.VISIBLE) {
                    chordPadBar.visibility = View.GONE
                    setTextColor(Color.parseColor("#9E9B95"))
                } else {
                    chordPadBar.visibility = View.VISIBLE
                    setTextColor(Color.parseColor("#D9822B"))
                }
            }
        }
        topBar.addView(chordToggleBtn)

        // Persistent Sustain Button
        sustainBtn = createTopButton(if (sustainOn) "SUSTAIN ●" else "SUSTAIN ○", sustainOn).apply {
            setOnClickListener {
                sustainOn = !sustainOn
                nativeSetSustain(sustainOn)
                updateSustainButtonStyle()
                saveState()
            }
        }
        topBar.addView(sustainBtn)

        // Instant One-Touch MIDI Record Button
        recordBtn = createTopButton("● REC", false).apply {
            setOnClickListener { toggleMidiRecording() }
        }
        topBar.addView(recordBtn)

        // Play / Export Recorded MIDI Button
        playMidiBtn = createTopButton("▶ PLAY", false).apply {
            visibility = View.GONE
            setOnClickListener { playRecordedMidi() }
            setOnLongClickListener {
                exportMidiFile()
                true
            }
        }
        topBar.addView(playMidiBtn)

        // Octave Lock Button
        val lockBtn = createTopButton(if (octaveLocked) "LOCKED" else "SCROLL", octaveLocked)
        lockBtn.setOnClickListener {
            octaveLocked = !octaveLocked
            pianoView.scrollLocked = octaveLocked
            lockBtn.text = if (octaveLocked) "LOCKED" else "SCROLL"
            lockBtn.setTextColor(
                if (octaveLocked) Color.parseColor("#D9822B") else Color.parseColor("#9E9B95")
            )
            saveState()
        }
        topBar.addView(lockBtn)

        // Studio Sound & Settings Button
        val settingsBtn = createTopButton("SOUND", false).apply {
            setOnClickListener { showSoundSettingsDialog() }
        }
        topBar.addView(settingsBtn)

        root.addView(topBar)

        // 2. Optional One-Touch Expressive Chord Strip (Hidden unless toggled)
        chordPadBar = buildOneTouchChordBar()
        root.addView(chordPadBar)

        // 3. Main Playable Keyboard View (Takes all remaining screen height)
        pianoView = NocturneKeyboardView(this).apply {
            scrollLocked = octaveLocked
            showLabels = showNoteLabels
            scrollOffset = prefs.getFloat("scroll_offset", -1f)
            whiteKeyWidth = prefs.getFloat("key_width", -1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(pianoView)

        setContentView(root)
    }

    private fun buildOneTouchChordBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#16171B"))
            setPadding(16, 6, 16, 6)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }

        // Rich musical voicings playable with a single tap
        val chords = listOf(
            "Cmaj9" to intArrayOf(48, 55, 59, 62, 64),
            "Am9" to intArrayOf(45, 52, 55, 59, 60),
            "Fmaj7" to intArrayOf(41, 48, 52, 57, 60),
            "G13" to intArrayOf(43, 50, 53, 59, 64),
            "Dm9" to intArrayOf(50, 57, 60, 64, 65),
            "Em7" to intArrayOf(40, 47, 50, 55, 59),
            "Bbmaj7" to intArrayOf(46, 53, 57, 58, 62),
            "E7alt" to intArrayOf(40, 47, 52, 56, 60)
        )

        for ((name, notes) in chords) {
            val btn = Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.parseColor("#E6E1D6"))
                setBackgroundColor(Color.parseColor("#24262C"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.setBackgroundColor(Color.parseColor("#D9822B"))
                            notes.forEach { n -> triggerNoteOn(n + transposeSemitones, 0.78f) }
                            chordReadout.text = name
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.setBackgroundColor(Color.parseColor("#24262C"))
                            notes.forEach { n -> triggerNoteOff(n + transposeSemitones) }
                        }
                    }
                    true
                }
            }
            bar.addView(btn)
        }
        return bar
    }

    private fun createTopButton(title: String, highlighted: Boolean): Button {
        return Button(this).apply {
            text = title
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 8, 24, 8)
            setTextColor(if (highlighted) Color.parseColor("#D9822B") else Color.parseColor("#9E9B95"))
            setBackgroundColor(Color.parseColor("#22242A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                setMargins(6, 0, 6, 0)
            }
        }
    }

    private fun updateSustainButtonStyle() {
        sustainBtn.text = if (sustainOn) "SUSTAIN ●" else "SUSTAIN ○"
        sustainBtn.setTextColor(
            if (sustainOn) Color.parseColor("#D9822B") else Color.parseColor("#9E9B95")
        )
    }

    fun triggerNoteOn(midiNote: Int, velocity: Float) {
        val clamped = midiNote.coerceIn(21, 108)
        nativeNoteOn(clamped, velocity)
        if (hapticsEnabled) {
            pianoView.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
        if (isRecording) {
            val timestamp = SystemClock.elapsedRealtime() - recordStartTime
            recordedMidiEvents.add(MidiEvent(timestamp, 1, clamped, (velocity * 127).toInt().coerceIn(1, 127)))
        }
    }

    fun triggerNoteOff(midiNote: Int) {
        val clamped = midiNote.coerceIn(21, 108)
        nativeNoteOff(clamped)
        if (isRecording) {
            val timestamp = SystemClock.elapsedRealtime() - recordStartTime
            recordedMidiEvents.add(MidiEvent(timestamp, 0, clamped, 0))
        }
    }

    private fun toggleMidiRecording() {
        if (!isRecording) {
            // Start MIDI capture
            recordedMidiEvents.clear()
            recordStartTime = SystemClock.elapsedRealtime()
            isRecording = true
            recordBtn.text = "■ STOP"
            recordBtn.setTextColor(Color.parseColor("#FF5252"))
            playMidiBtn.visibility = View.GONE
            Toast.makeText(this, "Recording MIDI...", Toast.LENGTH_SHORT).show()
        } else {
            // Stop MIDI capture
            isRecording = false
            recordBtn.text = "● REC"
            recordBtn.setTextColor(Color.parseColor("#9E9B95"))
            if (recordedMidiEvents.isNotEmpty()) {
                playMidiBtn.visibility = View.VISIBLE
                exportMidiFile()
            }
        }
    }

    private fun playRecordedMidi() {
        if (recordedMidiEvents.isEmpty()) return
        if (isPlayingRecording) {
            playbackHandler.removeCallbacksAndMessages(null)
            isPlayingRecording = false
            playMidiBtn.text = "▶ PLAY"
            return
        }
        isPlayingRecording = true
        playMidiBtn.text = "■ STOP"
        val eventsCopy = recordedMidiEvents.toList()
        for (ev in eventsCopy) {
            playbackHandler.postDelayed({
                if (ev.type == 1) {
                    nativeNoteOn(ev.note, ev.velocity / 127f)
                } else {
                    nativeNoteOff(ev.note)
                }
            }, ev.timestampMs)
        }
        val totalDuration = (eventsCopy.lastOrNull()?.timestampMs ?: 0L) + 600L
        playbackHandler.postDelayed({
            isPlayingRecording = false
            playMidiBtn.text = "▶ PLAY"
        }, totalDuration)
    }

    private fun exportMidiFile() {
        try {
            val file = File(getExternalFilesDir(null), "Nocturne_${System.currentTimeMillis()}.mid")
            FileOutputStream(file).use { out ->
                // Write Standard MIDI File (Format 0, 1 track, 480 ticks/quarter)
                val trackData = mutableListOf<Byte>()
                var lastTime = 0L
                for (ev in recordedMidiEvents) {
                    val deltaMs = max(0L, ev.timestampMs - lastTime)
                    lastTime = ev.timestampMs
                    val deltaTicks = (deltaMs * 480L / 500L).toInt()
                    writeVarLen(trackData, deltaTicks)
                    if (ev.type == 1) {
                        trackData.add(0x90.toByte())
                        trackData.add(ev.note.toByte())
                        trackData.add(ev.velocity.toByte())
                    } else {
                        trackData.add(0x80.toByte())
                        trackData.add(ev.note.toByte())
                        trackData.add(0x00.toByte())
                    }
                }
                // End of Track Meta Event
                writeVarLen(trackData, 0)
                trackData.add(0xFF.toByte())
                trackData.add(0x2F.toByte())
                trackData.add(0x00.toByte())

                val header = byteArrayOf(
                    'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
                    0, 0, 0, 6,
                    0, 0,
                    0, 1,
                    0x01, 0xE0.toByte(),
                    'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte()
                )
                out.write(header)
                val len = trackData.size
                out.write(byteArrayOf(
                    ((len shr 24) and 0xFF).toByte(),
                    ((len shr 16) and 0xFF).toByte(),
                    ((len shr 8) and 0xFF).toByte(),
                    (len and 0xFF).toByte()
                ))
                out.write(trackData.toByteArray())
            }
            Toast.makeText(this, "Saved MIDI: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "MIDI Recorded in Memory (${recordedMidiEvents.size / 2} notes)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeVarLen(out: MutableList<Byte>, value: Int) {
        var buffer = value and 0x7F
        var v = value shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or ((v and 0x7F) or 0x80)
            v = v shr 7
        }
        while (true) {
            out.add((buffer and 0xFF).toByte())
            if ((buffer and 0x80) != 0) buffer = buffer shr 8 else break
        }
    }

    private fun showInstrumentDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Instrument")
            .setSingleChoiceItems(instruments, currentInstrument) { dialog, which ->
                currentInstrument = which
                instrumentBtn.text = instruments[which]
                nativeSetInstrument(which)
                saveState()
                dialog.dismiss()
            }
            .show()
    }

    private fun showSoundSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 24)
        }

        fun addSlider(label: String, initial: Float, minVal: Float, maxVal: Float, onChange: (Float) -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            val sb = SeekBar(this).apply {
                max = 100
                progress = ((initial - minVal) / (maxVal - minVal) * 100).toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = minVal + (p / 100f) * (maxVal - minVal)
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
                })
            }
            layout.addView(tv)
            layout.addView(sb)
        }

        addSlider("Master Volume", volumeLevel, 0.1f, 1.4f) {
            volumeLevel = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
      
