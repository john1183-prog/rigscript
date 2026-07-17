package com.example.data

import kotlinx.serialization.Serializable

/**
 * A single imported sound effect clip in a project's sound-effect library.
 *
 * Unlike [ReferenceOverlay] or [BackgroundMusicSettings] (single-slot,
 * built into [ProjectDef] directly), sound effects are a growable list —
 * a project can have several distinct clips ("whoosh", "ding", "applause"),
 * each triggered independently from [ScriptEvent.soundEffect] by [id].
 *
 * There's no bundled built-in sound library shipped with the app: clips are
 * user-imported per project, same as background music, rather than picked
 * from a fixed enumerable set the AI prompt could reference by name. (A
 * bundled CC0 library was considered — see PROMPT_CONSIDERATIONS.md — but
 * user-imported keeps the AI prompt's job simple: it only ever references
 * ids the project actually has, which the prompt is given explicitly per
 * project rather than needing to hardcode a fixed catalog.)
 *
 * [id]        Short reference name the AI-generated script uses in
 *             [ScriptEvent.soundEffect] to trigger this clip. User-assigned
 *             at import time (defaults to a sanitized filename), editable
 *             afterward. Must be unique within a project's library —
 *             [com.example.viewmodel.MainViewModel.importSoundEffect]
 *             de-duplicates by suffixing a number if needed.
 * [filePath]  Absolute on-device path to the imported audio file.
 * [volume]    This clip's own default volume (0..1). Combined multiplicatively
 *             with [ScriptEvent.soundEffectVolume] at trigger time, so a
 *             clip's baseline level and an individual trigger's emphasis are
 *             independent adjustments.
 */
@Serializable
data class SoundEffectClip(
    val id: String,
    val filePath: String,
    val volume: Float = 1.0f
)
