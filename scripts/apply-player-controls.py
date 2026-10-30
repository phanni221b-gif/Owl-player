from pathlib import Path

p = Path('app/src/main/java/is/xyz/mpv/MPVActivity.kt')
text = p.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global text
    if old not in text:
        raise SystemExit(f'Patch anchor not found: {label}')
    text = text.replace(old, new, 1)


replace_once(
'''    private var smoothSeekGesture = false
    /* * */''',
'''    private var smoothSeekGesture = false

    private var screenMode = SCREEN_MODE_FIT
    private var playerInitialized = false
    /* * */''',
'screen mode state')

replace_once(
'''            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { lockUI() }''',
'''            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            screenModeBtn.setOnClickListener { cycleScreenMode() }
            topLockBtn.setOnClickListener { lockUI() }''',
'screen mode click')

replace_once(
'''            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)''',
'''            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }
            screenModeBtn.setOnLongClickListener { pickScreenMode(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)''',
'screen mode long click')

replace_once(
'''        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        player.playFile(filepath)''',
'''        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        playerInitialized = true
        applyScreenMode(screenMode, notify = false)
        player.playFile(filepath)''',
'apply screen mode after init')

replace_once(
'''        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }''',
'''        if (includeTimePos) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
            result.putExtra("playlist_index", psc.playlistPos)
        }''',
'playlist result index')

replace_once(
'''        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)''',
'''        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
        this.screenMode = prefs.getString(KEY_SCREEN_MODE, SCREEN_MODE_FIT) ?: SCREEN_MODE_FIT''',
'read screen mode')

replace_once(
'''            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }''',
'''            Intent.ACTION_VIEW -> {
                // Owl opens the exact list currently visible in FolderActivity as a memory
                // playlist, so Previous/Next follows the user's chosen sort order top-to-bottom.
                val owlPlaylist = intent.getStringArrayListExtra(FolderActivity.EXTRA_OWL_PLAYLIST)
                if (!owlPlaylist.isNullOrEmpty()) {
                    val paths = owlPlaylist.mapNotNull { raw ->
                        try {
                            resolveUri(Uri.parse(raw))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (paths.isNotEmpty()) {
                        Log.v(TAG, "Created Owl sorted playlist (${paths.size})")
                        Utils.makeMemoryPlaylist(paths)
                    } else {
                        intent.data?.let { resolveUri(it) }
                    }
                } else {
                    // Normal file open or URL view
                    intent.data?.let { resolveUri(it) }
                }
            }''',
'Owl sorted playlist')

replace_once(
'''    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {''',
'''    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun screenModeLabel(mode: String): String = when (mode) {
        SCREEN_MODE_FILL -> "꽉참"
        SCREEN_MODE_STRETCH -> "늘림"
        SCREEN_MODE_ORIGINAL -> "100%"
        else -> "맞춤"
    }

    private fun updateScreenModeButton() {
        binding.screenModeBtn.text = screenModeLabel(screenMode)
    }

    private fun currentWindowAspect(): Double {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            if (b.height() > 0) b.width().toDouble() / b.height().toDouble() else 1.0
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            if (dm.heightPixels > 0) dm.widthPixels.toDouble() / dm.heightPixels.toDouble() else 1.0
        }
    }

    private fun resetVideoTransform() {
        MPVLib.setPropertyDouble("video-zoom", 0.0)
        MPVLib.setPropertyDouble("video-pan-x", 0.0)
        MPVLib.setPropertyDouble("video-pan-y", 0.0)
    }

    private fun applyScreenMode(mode: String, notify: Boolean = true) {
        val normalized = when (mode) {
            SCREEN_MODE_FILL, SCREEN_MODE_STRETCH, SCREEN_MODE_ORIGINAL -> mode
            else -> SCREEN_MODE_FIT
        }
        screenMode = normalized

        MPVLib.setPropertyString("video-unscaled", "no")
        MPVLib.setPropertyString("video-aspect-override", "-1")
        MPVLib.setPropertyDouble("panscan", 0.0)
        resetVideoTransform()

        when (screenMode) {
            SCREEN_MODE_FILL -> {
                // Preserve aspect ratio, crop the edges just enough to fill the display.
                MPVLib.setPropertyDouble("panscan", 1.0)
            }
            SCREEN_MODE_STRETCH -> {
                // Fill every pixel even when this distorts the source aspect ratio.
                MPVLib.setPropertyString("video-aspect-override", currentWindowAspect().toString())
            }
            SCREEN_MODE_ORIGINAL -> {
                // Native 100% pixels. Large sources can extend beyond the display.
                MPVLib.setPropertyString("video-unscaled", "yes")
            }
        }

        getDefaultSharedPreferences(applicationContext)
            .edit().putString(KEY_SCREEN_MODE, screenMode).apply()
        updateScreenModeButton()
        if (notify)
            showToast("화면: ${screenModeLabel(screenMode)}", true)
    }

    private fun cycleScreenMode() {
        val modes = arrayOf(SCREEN_MODE_FIT, SCREEN_MODE_FILL, SCREEN_MODE_STRETCH, SCREEN_MODE_ORIGINAL)
        val index = modes.indexOf(screenMode).let { if (it < 0) 0 else it }
        applyScreenMode(modes[(index + 1) % modes.size])
    }

    private fun pickScreenMode() {
        val restore = pauseForDialog()
        val modes = arrayOf(SCREEN_MODE_FIT, SCREEN_MODE_FILL, SCREEN_MODE_STRETCH, SCREEN_MODE_ORIGINAL)
        val labels = arrayOf(
            "영상에 맞춰 보기 — 전체 영상이 보이도록 비율 유지",
            "꽉 차게 보기 — 비율 유지, 가장자리 일부 잘림",
            "늘려서 채우기 — 화면 전체 사용, 비율 변형",
            "원본 100% — 원본 픽셀 크기"
        )
        val selected = modes.indexOf(screenMode).let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("화면 맞춤")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                applyScreenMode(modes[which])
                dialog.dismiss()
            }
            .setOnDismissListener { restore() }
            .show()
    }

    private fun pickSpeed() {''',
'screen mode functions')

replace_once(
'''        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)''',
'''        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.screenModeBtn, R.id.cycleSpeedBtn)''',
'video button ordering')

replace_once(
'''        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()''',
'''        updateDecoderButton()
        updateSpeedButton()
        updateScreenModeButton()
        updatePlaylistButtons()''',
'refresh screen mode button')

replace_once(
'''            rightMargin = leftMargin
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {''',
'''            rightMargin = leftMargin
        }

        // When a Fold changes size or the device rotates, stretch mode must track the new
        // window aspect immediately. Other modes are resolution independent.
        if (playerInitialized && screenMode == SCREEN_MODE_STRETCH) {
            MPVLib.setPropertyString("video-aspect-override", currentWindowAspect().toString())
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {''',
'fold stretch refresh')

replace_once(
'''        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
    }
}''',
'''        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2

        private const val KEY_SCREEN_MODE = "owl_screen_mode"
        private const val SCREEN_MODE_FIT = "fit"
        private const val SCREEN_MODE_FILL = "fill"
        private const val SCREEN_MODE_STRETCH = "stretch"
        private const val SCREEN_MODE_ORIGINAL = "original"
    }
}''',
'screen mode constants')

p.write_text(text, encoding='utf-8')
print('MPVActivity Owl controls patch applied successfully')
