package com.example.data

/**
 * One entry in the app's bundled starter sound-effect library — a curated
 * subset of Kenney's CC0 "Interface Sounds" pack, shipped as Android assets
 * (`assets/sfx/*.wav`; see `assets/sfx/LICENSE.txt` for provenance).
 *
 * This is distinct from [SoundEffectClip]: a [BuiltInSoundEffect] lives in
 * the APK and isn't tied to any project. Adding one to a project copies it
 * from assets into that project's own `sound_effects` directory (via
 * `MainViewModel.importBuiltInSoundEffect`), at which point it becomes a
 * normal [SoundEffectClip] like any user-imported clip — same playback path
 * in preview and export either way, no special-casing needed past that
 * one-time copy.
 *
 * [id]        Suggested clip id once added to a project (still editable
 *             afterward like any [SoundEffectClip.id]).
 * [assetPath] Path under `assets/`, e.g. "sfx/click.wav".
 * [label]     Human-readable name shown in the "browse built-in sounds" UI.
 */
data class BuiltInSoundEffect(val id: String, val assetPath: String, val label: String)

/** The bundled starter library — see [BuiltInSoundEffect]'s doc comment. */
object BuiltInSoundEffects {
    val ALL: List<BuiltInSoundEffect> = listOf(
        BuiltInSoundEffect("click",   "sfx/click.wav",   "Click"),
        BuiltInSoundEffect("confirm", "sfx/confirm.wav", "Confirm"),
        BuiltInSoundEffect("error",   "sfx/error.wav",   "Error"),
        BuiltInSoundEffect("glitch",  "sfx/glitch.wav",  "Glitch"),
        BuiltInSoundEffect("drop",    "sfx/drop.wav",    "Drop"),
        BuiltInSoundEffect("tick",    "sfx/tick.wav",    "Tick"),
        BuiltInSoundEffect("toggle",  "sfx/toggle.wav",  "Toggle"),
        BuiltInSoundEffect("bong",    "sfx/bong.wav",    "Bong"),
        BuiltInSoundEffect("switch",  "sfx/switch.wav",  "Switch"),
        BuiltInSoundEffect("pluck",   "sfx/pluck.wav",   "Pluck"),
        BuiltInSoundEffect("question","sfx/question.wav","Question"),
        BuiltInSoundEffect("select",  "sfx/select.wav",  "Select"),
        BuiltInSoundEffect("open",    "sfx/open.wav",    "Open"),
        BuiltInSoundEffect("close",   "sfx/close.wav",   "Close")
    )
}
