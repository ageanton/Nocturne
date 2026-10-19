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

    var currentInstrument = 0
    var sustainOn = true
    var octaveLocked = false
    var showNoteLabels = true
    var hapticsEnabled = true
    var transposeSemitones = 0
    var volumeLevel = 0.90f
    var reverbLevel = 0.30f
    var roomSize = 0.65f
    var stereoWidth = 1.25f

    private var isRecording = false
    private var isPlayingRecording = false
    private var recordStartTime = 0L
    private val recordedMidiEvents = mutableListOf<MidiEvent>()
    private val playbackHandler = Handler(Looper.getMainLooper())

    fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#121316"))
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(24, 10, 24, 10)
        topBar.setBackgroundColor(Color.parseColor("#18191D"))
        topBar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(52)
        )

        val brandTitle = TextView(this)
        brandTitle.text = "NOCTURNE"
        brandTitle.textSize = 13f
        brandTitle.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        brandTitle.letterSpacing = 0.22f
        brandTitle.setTextColor(Color.parseColor("#D9822B"))
        brandTitle.setPadding(0, 0, 24, 0)
        topBar.addView(brandTitle)

        instrumentBtn = createTopButton(instruments[currentInstrument], true)
        instrumentBtn.setOnClickListener { showInstrumentDialog() }
        topBar.addView(instrumentBtn)

        chordReadout = TextView(this)
        chordReadout.text = "—"
        chordReadout.textSize = 14f
        chordReadout.gravity = Gravity.CENTER
        chordReadout.typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        chordReadout.setTextColor(Color.parseColor("#E6E1D6"))
        chordReadout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

        pianoView = NocturneKeyboardView(this, this)
        pianoView.scrollLocked = octaveLocked
        pianoView.showLabels = showNoteLabels
        pianoView.keyScrollOffset = prefs.getFloat("scroll_offset", -1f)
        pianoView.whiteKeyW = prefs.getFloat("key_width", -1f)
        pianoView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        root.addView(pianoView)

        setContentView(root)
    }

    private fun buildOneTouchChordBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(Color.parseColor("#16171B"))
        bar.setPadding(16, 6, 16, 6)
        bar.visibility = View.GONE
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(44)
        )

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
            val btn = Button(this)
            btn.text = name
            btn.textSize = 11f
            btn.isAllCaps = false
            btn.setTextColor(Color.parseColor("#E6E1D6"))
            btn.setBackgroundColor(Color.parseColor("#24262C"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(6, 0, 6, 0)
            btn.layoutParams = lp
            btn.setOnTouchListener { v, event ->
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
            dpToPx(34)
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
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(48, 32, 48, 24)

        fun addSlider(label: String, initial: Float, minVal: Float, maxVal: Float, onChange: (Float) -> Unit) {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 13f
            val sb = SeekBar(this)
            sb.max = 100
            sb.progress = ((initial - minVal) / (maxVal - minVal) * 100f).toInt().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = minVal + (p / 100f) * (maxVal - minVal)
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
            })
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

        val labelsCheck = CheckBox(this)
        labelsCheck.text = "Show Note & Octave Labels"
        labelsCheck.setTextColor(Color.WHITE)
        labelsCheck.isChecked = showNoteLabels
        labelsCheck.setOnCheckedChangeListener { _, checked ->
            showNoteLabels = checked
            pianoView.showLabels = checked
            pianoView.invalidate()
            saveState()
        }
        layout.addView(labelsCheck)

        val hapticsCheck = CheckBox(this)
        hapticsCheck.text = "Tactile Key Haptics"
        hapticsCheck.setTextColor(Color.WHITE)
        hapticsCheck.isChecked = hapticsEnabled
        hapticsCheck.setOnCheckedChangeListener { _, checked ->
            hapticsEnabled = checked
            saveState()
        }
        layout.addView(hapticsCheck)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nocturne Acoustic Engine")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
    }

    fun updateChordDetection(activeNotes: Set<Int>) {
        if (activeNotes.isEmpty()) {
            chordReadout.text = "—"
            return
        }
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val sorted = activeNotes.sorted()
        if (sorted.size == 1) {
            val n = sorted.first()
            chordReadout.text = "${noteNames[n % 12]}${(n / 12) - 1}"
            return
        }

        val pitchClasses = sorted.map { it % 12 }.distinct()
        for (root in pitchClasses) {
            val intervals = pitchClasses.map { (it - root + 12) % 12 }.toSet()
            val rootName = noteNames[root]
            val quality = when {
                intervals.containsAll(listOf(0, 4, 7, 11)) -> "maj7"
                intervals.containsAll(listOf(0, 3, 7, 10)) -> "m7"
                intervals.containsAll(listOf(0, 4, 7, 10)) -> "7"
                intervals.containsAll(listOf(0, 4, 7)) -> "maj"
                intervals.containsAll(listOf(0, 3, 7)) -> "m"
                intervals.containsAll(listOf(0, 3, 6)) -> "dim"
                intervals.containsAll(listOf(0, 5, 7)) -> "sus4"
                intervals.containsAll(listOf(0, 2, 7)) -> "sus2"
                else -> null
            }
            if (quality != null) {
                chordReadout.text = "$rootName$quality"
                return
            }
        }
        chordReadout.text = sorted.take(4).joinToString(" ") { noteNames[it % 12] }
    }

    override fun onPause() {
        super.onPause()
        prefs.edit()
            .putFloat("scroll_offset", pianoView.keyScrollOffset)
            .putFloat("key_width", pianoView.whiteKeyW)
            .apply()
        saveState()
    }
}

class NocturneKeyboardView(context: Context, private val host: MainActivity) : View(context) {

    private val whiteNotes = mutableListOf<Int>()
    private val allKeyRects = mutableMapOf<Int, RectF>()
    private val pointerToNote = mutableMapOf<Int, Int>()
    private val activeNotes = mutableSetOf<Int>()

    var whiteKeyW = -1f
    var keyScrollOffset = -1f
    var scrollLocked = false
    var showLabels = true

    private val ribbonHeightPx = 34f * resources.displayMetrics.density
    private var isDraggingRibbon = false
    private val scaleDetector: ScaleGestureDetector

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#121316") }
    private val whiteKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whiteKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val copperAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D9822B") }
    private val feltStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5E1919") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6A63")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val ribbonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#181A1F") }
    private val ribbonViewportPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33D9822B")
        style = Paint.Style.FILL
    }
    private val ribbonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9822B")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    init {
        for (midi in 21..108) {
            if (!isBlackKey(midi)) whiteNotes.add(midi)
        }
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val minW = width / 20f
                val maxW = width / 6f
                whiteKeyW = (whiteKeyW * detector.scaleFactor).coerceIn(minW, maxW)
                clampScroll()
                invalidate()
                return true
            }
        })
    }

    private fun isBlackKey(midi: Int): Boolean {
        val m = midi % 12
        return m == 1 || m == 3 || m == 6 || m == 8 || m == 10
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (whiteKeyW <= 0f) whiteKeyW = w / 10.5f
        if (keyScrollOffset < 0f) {
            val c3Index = whiteNotes.indexOf(48).coerceAtLeast(0)
            keyScrollOffset = c3Index * whiteKeyW
        }
        clampScroll()

        val keyTop = ribbonHeightPx
        val keyBottom = h.toFloat()

        whiteKeyPaint.shader = LinearGradient(
            0f, keyTop, 0f, keyBottom,
            Color.parseColor("#EAE5DC"),
            Color.parseColor("#FDFBF7"),
            Shader.TileMode.CLAMP
        )
        whiteKeyPressedPaint.shader = LinearGradient(
            0f, keyTop, 0f, keyBottom,
            Color.parseColor("#CFC9BE"),
            Color.parseColor("#E3DDD3"),
            Shader.TileMode.CLAMP
        )
        blackKeyPaint.shader = LinearGradient(
            0f, keyTop, 0f, keyTop + (keyBottom - keyTop) * 0.63f,
            Color.parseColor("#2A2C32"),
            Color.parseColor("#17181C"),
            Shader.TileMode.CLAMP
        )
        blackKeyPressedPaint.shader = LinearGradient(
            0f, keyTop, 0f, keyTop + (keyBottom - keyTop) * 0.63f,
            Color.parseColor("#38332E"),
            Color.parseColor("#26221E"),
            Shader.TileMode.CLAMP
        )
    }

    private fun clampScroll() {
        val totalWidth = whiteNotes.size * whiteKeyW
        val maxScroll = max(0f, totalWidth - width.toFloat())
        keyScrollOffset = keyScrollOffset.coerceIn(0f, maxScroll)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        drawOctaveRibbon(canvas)

        val keyTop = ribbonHeightPx
        canvas.drawRect(0f, keyTop, width.toFloat(), keyTop + 6f, feltStripPaint)

        val keyBottom = height.toFloat() - 6f
        val blackKeyHeight = (keyBottom - keyTop) * 0.62f
        val blackKeyWidth = whiteKeyW * 0.60f

        allKeyRects.clear()

        for (i in whiteNotes.indices) {
            val midi = whiteNotes[i]
            val left = i * whiteKeyW - keyScrollOffset
            val right = left + whiteKeyW
            if (right < -whiteKeyW || left > width + whiteKeyW) continue

            val pressed = activeNotes.contains(midi)
            val rect = RectF(left + 2f, keyTop + 6f, right - 2f, if (pressed) keyBottom + 3f else keyBottom)
            allKeyRects[midi] = rect

            canvas.drawRoundRect(rect, 10f, 10f, if (pressed) whiteKeyPressedPaint else whiteKeyPaint)

            if (pressed) {
                canvas.drawRoundRect(
                    RectF(rect.left + 8f, rect.bottom - 14f, rect.right - 8f, rect.bottom - 4f),
                    4f, 4f, copperAccentPaint
                )
            }

            if (showLabels && (midi % 12 == 0 || whiteKeyW > 95f)) {
                val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                val label = if (midi % 12 == 0) "C${(midi / 12) - 1}" else noteNames[midi % 12]
                labelPaint.color = if (midi == 60) Color.parseColor("#D9822B") else Color.parseColor("#78736A")
                canvas.drawText(label, rect.centerX(), rect.bottom - 26f, labelPaint)
            }
        }

        for (i in whiteNotes.indices) {
            val whiteMidi = whiteNotes[i]
            val blackMidi = whiteMidi + 1
            if (blackMidi <= 108 && isBlackKey(blackMidi)) {
                val center = (i + 1) * whiteKeyW - keyScrollOffset
                val left = center - blackKeyWidth / 2f
                val right = center + blackKeyWidth / 2f
                if (right < -blackKeyWidth || left > width + blackKeyWidth) continue

                val pressed = activeNotes.contains(blackMidi)
                val rect = RectF(left, keyTop + 6f, right, keyTop + blackKeyHeight + (if (pressed) 4f else 0f))
                allKeyRects[blackMidi] = rect

                canvas.drawRoundRect(
                    RectF(rect.left + 3f, rect.top, rect.right + 4f, rect.bottom + 8f),
                    8f, 8f, bgPaint
                )
                canvas.drawRoundRect(rect, 8f, 8f, if (pressed) blackKeyPressedPaint else blackKeyPaint)

                if (pressed) {
                    canvas.drawRoundRect(
                        RectF(rect.left + 6f, rect.bottom - 12f, rect.right - 6f, rect.bottom - 4f),
                        4f, 4f, copperAccentPaint
                    )
                }
            }
        }
    }

    private fun drawOctaveRibbon(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), ribbonHeightPx, ribbonBgPaint)
        val totalW = whiteNotes.size * whiteKeyW
        if (totalW <= 0f) return

        val miniWhiteW = width.toFloat() / whiteNotes.size
        for (i in whiteNotes.indices) {
            val midi = whiteNotes[i]
            if (midi % 12 == 0) {
                val x = i * miniWhiteW
                canvas.drawLine(x, 6f, x, ribbonHeightPx - 6f, labelPaint)
            }
        }

        val viewStartFraction = (keyScrollOffset / totalW).coerceIn(0f, 1f)
        val viewSpanFraction = (width.toFloat() / totalW).coerceIn(0.05f, 1f)
        val boxLeft = viewStartFraction * width.toFloat()
        val boxRight = ((viewStartFraction + viewSpanFraction) * width.toFloat()).coerceAtMost(width.toFloat())

        val boxRect = RectF(boxLeft, 3f, boxRight, ribbonHeightPx - 3f)
        canvas.drawRoundRect(boxRect, 6f, 6f, ribbonViewportPaint)
        canvas.drawRoundRect(boxRect, 6f, 6f, ribbonBorderPaint)
    }

    private fun findMidiAtPoint(x: Float, y: Float): Pair<Int, Float>? {
        if (y < ribbonHeightPx) return null
        for (entry in allKeyRects.entries) {
            val midi = entry.key
            val rect = entry.value
            if (isBlackKey(midi) && rect.contains(x, y)) {
                val relY = ((y - rect.top) / rect.height()).coerceIn(0.2f, 1.0f)
                return Pair(midi, 0.42f + (relY * 0.58f))
            }
        }
        for (entry in allKeyRects.entries) {
            val midi = entry.key
            val rect = entry.value
            if (!isBlackKey(midi) && rect.contains(x, y)) {
                val relY = ((y - rect.top) / rect.height()).coerceIn(0.2f, 1.0f)
                return Pair(midi, 0.38f + (relY * 0.62f))
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getY(0) < ribbonHeightPx || isDraggingRibbon) {
            if (!scrollLocked) scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingRibbon = true
                    if (!scrollLocked) jumpRibbonTo(event.x)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scrollLocked && !scaleDetector.isInProgress) jumpRibbonTo(event.x)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingRibbon = false
                }
            }
            return true
        }

        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val hit = findMidiAtPoint(event.getX(actionIndex), event.getY(actionIndex))
                if (hit != null) {
                    pointerToNote[pointerId] = hit.first
                    pressKey(hit.first, hit.second)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val hit = findMidiAtPoint(event.getX(i), event.getY(i))
                    val prevNote = pointerToNote[pid]
                    val newNote = hit?.first
                    if (prevNote != newNote) {
                        if (prevNote != null) {
                            pointerToNote.remove(pid)
                            if (!pointerToNote.containsValue(prevNote)) releaseKey(prevNote)
                        }
                        if (newNote != null && hit != null) {
                            pointerToNote[pid] = newNote
                            pressKey(newNote, hit.second)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val prevNote = pointerToNote.remove(pointerId)
                if (prevNote != null && !pointerToNote.containsValue(prevNote)) {
                    releaseKey(prevNote)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerToNote.clear()
                val copy = activeNotes.toList()
                activeNotes.clear()
                for (n in copy) host.triggerNoteOff(n + host.transposeSemitones)
                host.updateChordDetection(activeNotes)
                invalidate()
            }
        }
        return true
    }

    private fun jumpRibbonTo(x: Float) {
        val totalW = whiteNotes.size * whiteKeyW
        val targetCenter = (x / width.toFloat()) * totalW
        keyScrollOffset = targetCenter - (width.toFloat() / 2f)
        clampScroll()
        invalidate()
    }

    private fun pressKey(midiNote: Int, velocity: Float) {
        activeNotes.add(midiNote)
        host.triggerNoteOn(midiNote + host.transposeSemitones, velocity)
        host.updateChordDetection(activeNotes)
        invalidate()
    }

    private fun releaseKey(midiNote: Int) {
        activeNotes.remove(midiNote)
        host.triggerNoteOff(midiNote + host.transposeSemitones)
        host.updateChordDetection(activeNotes)
        invalidate()
    }
}
