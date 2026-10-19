package com.nocturne.piano

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

data class MidiEvent(val timestampMs: Long, val type: Int, val note: Int, val velocity: Int)

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("nocturne")
        }
    }

    external fun initEngine(assetManager: AssetManager): Boolean
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

    private var isRecording = false
    private var isPlayingRecording = false
    private var recordStartTime = 0L
    private val recordedMidiEvents = mutableListOf<MidiEvent>()
    private val playbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            setBackgroundColor(Color.parseColor("#121316"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

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
            setTextColor(Color.parseColor("#D9822B"))
            setPadding(0, 0, 24, 0)
        }
        topBar.addView(brandTitle)

        instrumentBtn = createTopButton(instruments[currentInstrument], true)
        instrumentBtn.setOnClickListener { showInstrumentDialog() }
        topBar.addView(instrumentBtn)

        chordReadout = TextView(this).apply {
            text = "—"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E1D6"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(chordReadout)

        val chordToggleBtn = createTopButton("CHORDS", false)
        chordToggleBtn.setOnClickListener {
            if (chordPadBar.visibility == View.VISIBLE) {
                chordPadBar.visibility = View.GONE
                chordToggleBtn.setTextColor(Color.parseColor("#9E9B95"))
            } else {
                chordPadBar.visibility = View.VISIBLE
                chordToggleBtn.setTextColor(Color.parseColor("#D9822B"))
            }
        }
        topBar.addView(chordToggleBtn)

        sustainBtn = createTopButton(if (sustainOn) "SUSTAIN ●" else "SUSTAIN ○", sustainOn)
        sustainBtn.setOnClickListener {
            sustainOn = !sustainOn
            nativeSetSustain(sustainOn)
            updateSustainButtonStyle()
            saveState()
        }
        topBar.addView(sustainBtn)

        recordBtn = createTopButton("● REC", false)
        recordBtn.setOnClickListener { toggleMidiRecording() }
        topBar.addView(recordBtn)

        playMidiBtn = createTopButton("▶ PLAY", false)
        playMidiBtn.visibility = View.GONE
        playMidiBtn.setOnClickListener { playRecordedMidi() }
        playMidiBtn.setOnLongClickListener {
            exportMidiFile()
            true
        }
        topBar.addView(playMidiBtn)

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

        val settingsBtn = createTopButton("SOUND", false)
        settingsBtn.setOnClickListener { showSoundSettingsDialog() }
        topBar.addView(settingsBtn)

        root.addView(topBar)

        chordPadBar = buildOneTouchChordBar()
        root.addView(chordPadBar)

        pianoView = NocturneKeyboardView(this).apply {
            scrollLocked = octaveLocked
            showLabels = showNoteLabels
            keyScrollOffset = prefs.getFloat("scroll_offset", -1f)
            whiteKeyW = prefs.getFloat("key_width", -1f)
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

        val chords = listOf(
            Pair("Cmaj9", intArrayOf(48, 55, 59, 62, 64)),
            Pair("Am9", intArrayOf(45, 52, 55, 59, 60)),
            Pair("Fmaj7", intArrayOf(41, 48, 52, 57, 60)),
            Pair("G13", intArrayOf(43, 50, 53, 59, 64)),
            Pair("Dm9", intArrayOf(50, 57, 60, 64, 65)),
            Pair("Em7", intArrayOf(40, 47, 50, 55, 59)),
            Pair("Bbmaj7", intArrayOf(46, 53, 57, 58, 62)),
            Pair("E7alt", intArrayOf(40, 47, 52, 56, 60))
        )

        for (chord in chords) {
            val name = chord.first
            val notes = chord.second
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
                            for (n in notes) triggerNoteOn(n + transposeSemitones, 0.78f)
                            chordReadout.text = name
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.setBackgroundColor(Color.parseColor("#24262C"))
                            for (n in notes) triggerNoteOff(n + transposeSemitones)
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
        val btn = Button(this)
        btn.text = title
        btn.textSize = 11f
        btn.isAllCaps = false
        btn.minimumHeight = 0
        btn.minimumWidth = 0
        btn.setPadding(24, 6, 24, 6)
        btn.setTextColor(if (highlighted) Color.parseColor("#D9822B") else Color.parseColor("#9E9B95"))
        btn.setBackgroundColor(Color.parseColor("#22242A"))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        )
        lp.setMargins(6, 0, 6, 0)
        btn.layoutParams = lp
        return btn
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
            val velInt = (velocity * 127f).toInt().coerceIn(1, 127)
            recordedMidiEvents.add(MidiEvent(timestamp, 1, clamped, velInt))
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
            recordedMidiEvents.clear()
            recordStartTime = SystemClock.elapsedRealtime()
            isRecording = true
            recordBtn.text = "■ STOP"
            recordBtn.setTextColor(Color.parseColor("#FF5252"))
            playMidiBtn.visibility = View.GONE
            Toast.makeText(this, "Recording MIDI...", Toast.LENGTH_SHORT).show()
        } else {
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
        val lastTimestamp = if (eventsCopy.isNotEmpty()) eventsCopy.last().timestampMs else 0L
        playbackHandler.postDelayed({
            isPlayingRecording = false
            playMidiBtn.text = "▶ PLAY"
        }, lastTimestamp + 600L)
    }

    private fun exportMidiFile() {
        try {
            val file = File(getExternalFilesDir(null), "Nocturne_${System.currentTimeMillis()}.mid")
            FileOutputStream(file).use { out ->
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
                writeVarLen(trackData, 0)
                trackData.add(0xFF.toByte())
                trackData.add(0x2F.toByte())
                trackData.add(0x00.toByte())

                val header = byteArrayOf(
                    0x4D, 0x54, 0x68, 0x64,
                    0, 0, 0, 6,
                    0, 0,
                    0, 1,
                    0x01, 0xE0.toByte(),
                    0x4D, 0x54, 0x72, 0x6B
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
            Toast.makeText(this, "MIDI Recorded (${recordedMidiEvents.size / 2} notes)", Toast.LENGTH_SHORT).show()
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
                progress = ((initial - minVal) / (maxVal - minVal) * 100f).toInt().coerceIn(0, 100)
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
        addSlider("Acoustic Hall Reverb", reverbLevel, 0.0f, 0.85f) {
            reverbLevel = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
        addSlider("Room Size & Resonance Decay", roomSize, 0.1f, 0.98f) {
            roomSize = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }
        addSlider("Stereo Imaging Width", stereoWidth, 0.5f, 1.8f) {
            stereoWidth = it
            nativeSetControls(volumeLevel, reverbLevel, roomSize, stereoWidth)
        }

        val labelsCheck = CheckBox(this).apply {
            text = "Show Note & Octave Labels"
            setTextColor(Color.WHITE)
            isChecked = showNoteLabels
            setOnCheckedChangeListener { _, checked ->
                showNoteLabels = checked
                pianoView.showLabels = checked
                pianoView.invalidate()
                saveState()
            }
        }
        layout.addView(labelsCheck)

        val hapticsCheck = CheckBox(this).apply {
     
