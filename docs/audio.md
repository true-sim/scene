# Audio: design

Sound for games: clicks and explosions, footsteps by surface, looping machines with a start and a stop, a car's
engine driven by its revolutions, music that changes with the game on the beat, dialogue that ducks the music and
drives subtitles and lips, reverb that follows the room, 3D sound in both ears of a headset, and a security camera
whose sound plays from a television in the scene. Sounds and music are described as data in the scene library and
played from game code through a per-scene store, the way effects are; the engine mixes them on an audio thread
written in Java, allocation-free, and hands finished blocks to the operating system through SDL3.

Decided with the user one question at a time (AU1 to AU16 below), after a survey of Unreal (Audio Mixer, MetaSounds,
Sound Cues, Quartz, Audio Modulation, submixes, attenuation, concurrency), Unity (AudioSource and Mixer, DSPGraph),
Godot 4 (AudioServer, buses, AudioStreamGenerator, interactive music), FMOD Studio, Wwise, CRI ADX, Steam Audio, Meta
XR Audio SDK, miniaudio, OpenAL Soft, SDL3 audio, LWJGL's audio bindings, Java's own options and public HRTF data
sets (sources in section 9), and a reading of engine `README.md`, `CLAUDE.md`, `docs/application-api.md`,
`docs/pitfalls.md`, the `engine-app`, `engine-platform`, `engine-settings` and `engine-xr` sources, scene `README.md`,
`docs/animation.md`, `docs/effects.md`, `scene-animation`, `scene-assets`, `scene-world`, `scene-collision`
(`TerrainHeights`) and the `scene-effects` store (`Effects`, `Effect`, as built) that this design copies. Then
reviewed at high effort against the code and the cited sources (section 7 lists what the review changed, the risks
left and the open questions).

Steps 1 to 7 of section 6 are built (section 8 says how, and where the code differs); the rest is not. The decisions
are AU1 to AU16. Sections 2 and 3 are written for them; section 5 keeps the
options weighed (Q1 to Q16, one per decision) with the reasons, and marks where the decision differs from the first
recommendation.

## Decisions made

| | Decision | Notes |
|---|---|---|
| AU1 | **Output backend: SDL3 audio in push mode**, behind an `AudioOutput` interface, so the backend can be swapped (OpenAL Soft or miniaudio later) by replacing only that sink. The mixer is ours. | As recommended. SDL's WASAPI backend already registers its device thread with MMCSS ("Pro Audio") and uses `IAudioClient3`'s low-latency shared mode (§2.3); the draft's "no low-latency path" was wrong. |
| AU2 | **Mixer in Java**, on one GC-free audio thread (no allocation, no locks), rendering blocks ahead into SDL's queue. | As recommended. The collector and safepoints: §2.22. |
| AU3 | **DSP as Java `DspNode`s plus a `Patch` builder**, registered by name for plugins; data graph assets later. | As recommended. |
| AU4 | **Block size and queue depth adjustable** (settings), defaults 256 frames and 2 to 3 blocks queued. | Differs from the recommendation (fixed numbers). Changed at a safe point with a fade (§2.3); the offline renderer takes the block size as part of its format, so goldens name it. |
| AU5 | **Codecs:** WAV and Ogg Vorbis first, Opus next, FLAC (Java) later. | As recommended. Opus needs no Ogg reader of ours: LWJGL's opus module binds libopusfile (§2.14). |
| AU6 | **Our own HRTF in Java:** convolution with public HRTF data sets (SOFA files loaded as assets), panning as default and fallback; no native Steam Audio. | Differs from the recommendation (Steam Audio through Panama). How: §2.9 (partitioned FFT convolution, interpolation between measured directions, near field, budget); ambisonics and occlusion reworked without Steam Audio (§2.9, §2.11). |
| AU7 | **Voices:** defaults 64 real, 512 logical per world, 16 HRTF; five priorities; virtual below −60 dB; 10 ms steal fades. All adjustable. | As recommended, all as settings at the developer tier. |
| AU8 | **Music timing on the audio frame counter**, the game thread scheduling exact frames ahead. | As recommended. |
| AU9 | **Definitions built in code** now, runtime import of audio files by extension (D47); a file format later with the serialization repository. | As recommended. |
| AU10 | **Determinism:** offline renders bit-identical, live output not reproducible, container choices from a seeded hash. | As recommended. The hash moves to common-math (open question 3). |
| AU11 | **Game-facing state in a scene `Sounds` store** with a change log, like `Effects`. | As recommended, shaped on `Effects` as built (§2.2). |
| AU12 | **Multi-listener.** Each listener may render its own mix, routed to the output device or **played back as a sound source on a node in the world** (a remote camera heard from a television). A recursion guard (a listener never hears its own re-emitted output; a feedback path adds one block of delay); per-listener mixing cost only for rendering listeners. For voices reaching the device, split screen uses the nearest listener. | Differs from the recommendation (nearest listener only). How: §2.10. |
| AU13 | **Occlusion chosen per sound:** none; game rays through an `AudioWorld` interface (physics, or the engine's ray tracing structures); propagation later. | Differs: per sound, and the ray tracing structures as a second implementation; Steam Audio's simulation dropped with AU6. |
| AU14 | **Sample rate: the device's native rate.** | As recommended. A device whose rate changes is handled in §2.17. |
| AU15 | **Audio thread waits by polling SDL's queue with about a 1 ms park.** | As recommended. See the reviewer note: Windows timer resolution. |
| AU16 | **Bindings in engine-app's `Audio` plugin as `AudioBindings`**, next to `EffectBindings`. | As recommended. `EffectBindings` exists now, in engine-scene; the `Audio` plugin lives in engine-audio (§8, step 6). |

**Reviewer notes on decisions** (for the user to reconsider; the design follows the decisions as they stand):

- **Reviewer note on AU15 (and AU4's lower default).** A "1 ms park" is not 1 ms on Windows unless the process asks
  for a finer timer: the default timer period is 15.6 ms, and since Windows 11 a raised resolution
  (`timeBeginPeriod`) is no longer honoured for a process whose windows are minimised or fully occluded. A 15.6 ms
  oversleep empties a 2-block queue (10.7 ms at 48 kHz) and nearly empties a 3-block one. Alternatives, best first:
  (a) wait on a high-resolution waitable timer (`CreateWaitableTimerExW` with
  `CREATE_WAITABLE_TIMER_HIGH_RESOLUTION`, Windows 10 1803+) through a Panama downcall, sleeping until the queue is
  expected to fall to its low mark, which keeps AU15's polling model and its simplicity; (b) keep the park but make
  the queue depth adapt: measure park overshoot and raise the low mark to cover the 99.9th percentile; (c) SDL's
  get-callback as a pure signal (an upcall that only unparks), rejected by AU1's no-upcall rule. §2.3 designs (a)
  with (b) as the guard, so the default of 2 blocks only applies where measured overshoot allows it; otherwise the
  default becomes 3.
- **Reviewer note on AU6.** Our own HRTF is feasible (estimated at about 0.2 to 0.4 ms per 5.33 ms block for 16
  voices, §2.9) and keeps everything in Java and deterministic. What it gives up is Steam Audio's tuned
  interpolation, its reflections and pathing, and years of listening tests. The design keeps a `Spatializer`
  interface (one implementation, ours) so a native provider could be added later as a plugin without touching the
  mixer, and the HRTF step has a listening checklist besides the numeric tests. Alternative: keep the decision but
  name Steam Audio as the documented fallback if step 17's localization tests or budget fail.
- **Reviewer note on AU12.** Re-emitting a listener's mix is right, but a rendering listener with the full bus tree
  doubles the mix cost per listener and its HRTF would be wasted (the re-emitted sound is spatialized again in the
  outer world). The design gives each rendering listener a reduced mix: panning only, its own small voice budget
  (16), a chosen subset of buses, and no music or UI unless asked (§2.10). Alternative: allow full mixes only behind
  a developer setting.
- **Reviewer note on AU4.** Changing the block size live changes the parameter-ramp granularity and the offline
  goldens. Keep it changeable from settings, but apply it only at a restart of the output (a 20 ms fade out, rebuild,
  fade in), and pin goldens to 256.

---

## 1. Survey

### 1.1 Unreal Engine 5

**Audio Mixer.** Unreal's mixer is its own software renderer on every platform. Game-thread objects (audio components,
active sounds) send commands to an audio render thread, which mixes *sources* (voices) into *submixes*, a tree ending in
the master submix, with effect chains per submix and sends between them. Attenuation settings give each sound distance
curves, spatialization (panning or an HRTF plugin), air absorption (a low-pass by distance), occlusion (a trace on a
collision channel, then volume attenuation and a low-pass, interpolated), focus, reverb sends and *submix sends by
distance*. ([FSoundAttenuationSettings](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/Engine/FSoundAttenuationSettings),
[Python reference](https://dev.epicgames.com/documentation/en-us/unreal-engine/python-api/class/SoundAttenuationSettings))
- **Concurrency** assets cap how many sounds of a group play and say what happens at the cap (stop oldest, quietest,
  farthest, prevent new), with a voice-steal release time and volume scaling of older voices.
  ([concurrency reference](https://dev.epicgames.com/documentation/en-us/unreal-engine/sound-concurrency-reference-guide))
  *Virtualization* keeps inaudible looping sounds alive without a voice; when realized, they restart, or seek to
  where they would be (`EVirtualizationMode`: Restart, SeekRestart).
  ([EVirtualizationMode](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/Engine/EVirtualizationMode))
- **Sound Cues** are the older node graph of containers (random, mixer, modulator, concatenator, looping, switch by
  parameter). Lesson: containers as a tree are enough for most sound design.
- **MetaSounds** (5.0+) replace cues with a DSP graph per source: typed pins (audio buffers, floats, triggers,
  time), sample-accurate triggers ("execute on a precise sample index in the audio rendering block"), *patches*
  reusable inside other MetaSounds, graph inputs set from gameplay, and a Builder API. The graph is compiled into an
  operator list per instance.
  ([introduction](https://dev.epicgames.com/documentation/unreal-engine/metasounds-the-next-generation-sound-sources-in-unreal-engine),
  [reference guide](https://dev.epicgames.com/documentation/unreal-engine/metasounds-reference-guide-in-unreal-engine))
- **Quartz** schedules sounds on clocks that run on the audio render thread, by seconds or by bars and beats, ahead of
  time, so a sound can start mid-buffer. A clock handle on the game thread is a proxy; `PlayQuantized` starts a
  component on a clock's next boundary; latency is queryable.
  ([overview](https://dev.epicgames.com/documentation/unreal-engine/overview-of-quartz-in-unreal-engine),
  [FQuartzClock](https://dev.epicgames.com/documentation/unreal-engine/API/Runtime/AudioMixer/FQuartzClock))
  The problem it states is the one true-sim has: commands are consumed at a buffer's start, and the game thread's
  timing is set by rendering.
- **Audio Modulation** adds *control buses* (named values) and *control bus mixes* (sets of stages, each a bus value
  with attack and release), activated from gameplay, modulating volume, pitch and filter cutoffs on many sounds.
  ([reference](https://dev.epicgames.com/documentation/unreal-engine/audio-modulation-reference-guide-in-unreal-engine))
  This is Unreal's snapshot.

**Lessons:** a render thread fed by commands; voices apart from logical sounds; quantized scheduling on an audio-side
clock; one DSP graph model for sources; snapshots as named bus values with fades.

### 1.2 Unity

- **AudioSource, AudioListener, AudioMixer** (on FMOD's low-level runtime inside the engine): mixer groups with
  effects and sends, *snapshots* that blend parameter sets, exposed parameters, ducking by a "Duck Volume" effect fed
  by a send. One listener only.
- **Scheduling:** `AudioSource.PlayScheduled(dspTime)` starts at an absolute time on the DSP clock
  (`AudioSettings.dspTime`); the docs advise scheduling 100 to 200 ms ahead, and the standard gapless player alternates
  two sources. A time in the past starts as soon as possible, dropping what would have played.
  ([PlayScheduled](https://docs.unity.com/en-us/engine/7000.0/script-reference/unityengine/audiosource/playscheduled))
- **DSPGraph** (`com.unity.audio.dspgraph`) is a DAG of DSP kernels in C# on the job system with Burst, meant as the
  core of DOTS audio. Its last preview is 0.1.0-preview.22 (January 2022), and Unity staff said there are no plans for
  further releases.
  ([manual](https://docs.unity3d.com/Packages/com.unity.audio.dspgraph@0.1/manual/index.html),
  [changelog](https://docs.unity3d.com/Packages/com.unity.audio.dspgraph@0.1/changelog/CHANGELOG.html),
  [forum](https://discussions.unity.com/t/any-news-about-dspgraph-audio-framework/927157))

**Lessons:** scheduling against a DSP clock with lookahead works and is the documented way; a managed-language DSP
graph is feasible (DSPGraph ran C# kernels) but needs care with allocation; a single listener is a limit to avoid.

### 1.3 Godot 4

- **AudioServer** owns buses (volume, solo, mute, bypass, an effect chain, a send to another bus) laid out as data
  (`default_bus_layout.tres`); players pick a bus by name.
  ([AudioServer](https://docs.godotengine.org/en/4.2/classes/class_audioserver.html))
- **AudioStreamGenerator** lets game code push frames into a ring (`push_frame`, `push_buffer`,
  `get_frames_available`, `get_skips` counting underruns): procedural sound fed from the game thread, with the latency
  of the ring. ([AudioStreamGeneratorPlayback](https://docs.godotengine.org/es/4.3/classes/class_audiostreamgeneratorplayback.html))
- **Interactive music** (4.3+, module `interactive_music`): `AudioStreamInteractive` (clips with transitions: from
  immediately, the next beat, the next bar or the clip's end, with fades and an optional filler clip),
  `AudioStreamSynchronized` (stems in sync, a volume each) and `AudioStreamPlaylist` (sequential or shuffled, with
  beat-aware fades). Synchronized streams are commonly used as the clips of an interactive one.
  ([class source](https://remotebranch.eu/Stowage/godot/src/commit/b4c6cc7d82d1fcdb3bed734c4e22c19e012c84df/modules/interactive_music/doc_classes/AudioStreamInteractive.xml),
  [forum](https://forum.godotengine.org/t/help-combining-audiostreaminteractive-and-audiostreamsynchronized/100519))

**Lessons:** bus layouts as a data asset; procedural sound pushed from the game thread is simple but adds a frame of
latency and jitter (true-sim's DSP runs on the audio thread instead); stems in a layer plus transitions between
states is the whole of most adaptive music.

### 1.4 FMOD Studio

- **Events** are timelines or action sheets of instruments (single, multi with random or sequential playlists,
  scatterer, event, programmer), with *parameters* (game-controlled, built-in such as distance, direction and
  elevation, or automatic). Parameters drive automation and modulators; global parameters are shared by many events
  and can drive the mixer. Only game-controlled parameters are set from code.
  ([parameters](https://www.fmod.com/docs/2.03/studio/parameters.html),
  [Studio API common](https://www.fmod.com/docs/2.00/api/studio-api-common.html))
- **Timeline logic** for music: tempo markers, loop regions, transition markers and regions with *quantization*
  (beat, bar, N bars), destination markers, *transition timelines* (short timelines played during a jump, which since
  2.00 may overlap source and destination for per-track cross-fades) and named loop regions as destinations.
  ([new in 2.00](https://www.fmod.com/docs/2.03/studio/welcome-to-fmod-studio-new-in-200.html),
  [community walkthrough](https://sgg-modding.github.io/Hades2ModWiki/docs/creating-mods/audio/adding-dynamic-music))
- **Snapshots** are instanceable changes to the mix (overriding or blending), started and stopped like events, with
  an intensity. **Buses**: group buses, return buses (sends), VCAs (volume controls for players' settings).
- **Voices:** the Core API keeps *virtual channels* (cheap, tracked) and a smaller number of real software channels;
  channels go virtual by audibility (distance, volume, priority, occlusion) and below a volume threshold
  (`FMOD_INIT_VOL0_BECOMES_VIRTUAL`, on by default in Studio). Studio exposes only five priority levels, on purpose:
  "priority should not be used in a fine-grained way".
  ([managing resources](https://fmod.com/docs/2.03/api/managing-resources-in-the-core-api.html))
- LWJGL binds FMOD (natives supplied by the user under FMOD's licence).

**Lessons:** five priorities, not 256; audibility decides virtualization; VCAs for player volumes; snapshots with
intensity; quantized transitions with transition segments; parameters typed and declared.

### 1.5 Wwise

- **Events** run actions (play, stop, set state, set switch, post trigger) on objects in the Actor-Mixer hierarchy:
  random, sequence, switch and blend containers. **Game syncs:** RTPCs (game parameters mapped to curves on any
  property; game parameters drive RTPCs on objects, they are not attached to sounds directly), switches (per game
  object, e.g. surface), states (global, also driving the mix) and triggers (stingers).
  ([community answer on RTPCs](https://audiokinetic.com/qa/2370/how-do-you-assign-a-sfx-object-to-a-game-parameter?show=2387),
  [states for music, Godot integration wiki](https://github.com/alessandrofama/wwise-godot-integration/wiki/Setting-States-(23.1)))
- **Interactive music:** segments with entry and exit cues (pre-entry and post-exit play outside them), custom cues,
  music playlists and music switch containers, and a transition matrix (source, destination: exit at immediate, next
  grid, next bar, next beat, next cue, exit cue; destination sync to entry cue, same time, random or matching cue;
  fades with offsets; optional transition segments). By default a transition waits for the source's exit cue and
  starts the destination at its entry cue. A known trap: an intro in the pre-entry is the sync point, so intros belong
  in their own segment.
  ([forum: default transition](https://blog.audiokinetic.com/qa/2053/immediate-transition-for-music-track-switch),
  [forum: pre-entry](https://www.audiokinetic.com/qa/1367/issue-with-pre-entry-when-using-states-to-change-the-music),
  [forum: transition segments](https://blog.audiokinetic.com/qa/2611/transition-segments-alongisde-previous-playlist-containers))
- **Voices:** virtual voice behaviour per object (continue to play, kill, send to virtual, kill if finite else
  virtual) and on return (play from beginning, play from elapsed time, resume); playback limits per object and bus
  with priority and a distance offset. ("Kill if finite else virtual" as a default, per
  [Game Audio Programming 2, ch. 9](https://www.taylorfrancis.com/books/9781315157542/chapters/10.1201/b22247-9).)
- **Spatial audio rooms and portals:** the game defines rooms (orientation and an aux bus for their reverb; the game
  does the containment tests) and portals (oriented boxes open at both ends). Sound reaches the listener through
  portals with a diffraction angle (0 to 180 degrees) mapped to obstruction or a diffraction parameter, and through
  walls by transmission; reverb is spatialized at the portal; since 2023.1 two portals give three paths (one through
  the wall, one per portal).
  ([rooms and portals](https://audiokinetic.com/en/blog/rooms-and-portals-with-wwise-spatial-audio),
  [revised aux send model](https://www.audiokinetic.com/en/revised-aux-send-model))

**Lessons:** switches per object and states globally; a transition matrix with "exit at" and "sync to" is the clearest
description of music transitions; rooms and portals give believable propagation with little geometry, and the game,
not the audio library, owns the room shapes.

### 1.6 CRI ADX (ADX2)

Middleware strong in Japan, with its own codecs: ADX (low CPU), HCA (comparable to MP3 or AAC) and HCA-MX, which mixes
many streams in the compressed domain at a fraction of the CPU cost, but without per-voice filter, pitch or seamless
concatenation. AtomCraft authoring has randomization, RTPCs-like controls, selectors and interactive music.
([codecs](https://blog.criware.com/index.php/2016/04/11/meet-adx2s-powerful-proprietary-codecs/),
[product](https://www.cri-mw.co.jp/product/web/adx2/index.html))
**Lesson:** decoding cost, not mixing, dominates when hundreds of compressed voices play; keep many short sounds as
PCM (or ADPCM) in memory, and stream only the long ones.

### 1.7 Spatial audio for VR: Steam Audio, Resonance, Meta XR Audio SDK

- **Steam Audio** (Valve; fully open source under Apache 2.0 since 4.5.2, February 2024): a C API with a binaural
  (HRTF) effect, a default HRTF and SOFA files for custom ones, ambisonics encode and binaural decode, raycast
  occlusion (with partial occlusion for volumetric sources), transmission with material EQ, reflections (real-time or
  baked) and pathing (shortest paths around geometry, spatialized); a "user-defined" mode lets the engine supply its own
  occlusion values. Plugins for Unity, Unreal, FMOD and Wwise, and an example in miniaudio's repository.
  ([Steamworks overview](https://partner.steamgames.com/doc/features/steam_audio),
  [FMOD plugin docs](https://valvesoftware.github.io/steam-audio/doc/fmod/spatializer.html),
  [Wwise plugin docs](https://valvesoftware.github.io/steam-audio/doc/wwise/spatializer.html),
  [open source](https://phoronix.com/news/Steam-Audio-SDK-Fully-Open))
- **Resonance Audio** (Google, open source, archived): sources encoded into higher-order ambisonics and decoded once
  to binaural, so the HRTF cost does not grow with the number of sources. This is the standard trick for many sources.
- **Meta XR Audio SDK:** a proprietary HRTF that Meta says localizes better than public data sets, and *acoustic ray
  tracing* (reflections, reverb, occlusion, diffraction from baked static geometry) that runs on Quest chipsets.
  Integrations for Unity, Unreal, FMOD and Wwise; no public native API documentation found.
  ([UploadVR](https://www.uploadvr.com/meta-xr-audio-sdk-acoustic-ray-tracing-simulation/),
  [Wwise plugin](https://developers.meta.com/horizon/blog/meta-xr-audio-sdk-plugin-wwise))
- **Which device in VR.** OpenXR has no core audio API. `XR_OCULUS_audio_device_guid` (not ratified; Meta's runtime)
  returns the Windows endpoint id of the headset's output and input in `IMMDevice::GetId` form, which LWJGL binds
  (`OCULUSAudioDeviceGuid`).
  ([registry](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_OCULUS_audio_device_guid.html),
  [LWJGL](https://javadoc.lwjgl.org/org/lwjgl/openxr/OCULUSAudioDeviceGuid.html))
  The id is a string, not a raw GUID, and SDL 3.4 exposes no endpoint ids to match it against (names only). SteamVR
  does not offer the extension; its audio setting "when SteamVR is active, set playback device" switches Windows'
  default device to the headset and restores it on exit (since 1.8.7), so following the system default device is what
  works with Quest 3 through SteamVR.
  ([xrGetAudioOutputDeviceGuidOculus](https://registry.khronos.org/OpenXR/specs/1.1/man/html/xrGetAudioOutputDeviceGuidOculus.html),
  [Road to VR](https://www.roadtovr.com/?p=91058))
- **Not used here.** AU6 chose our own HRTF in Java and AU13 our own occlusion, so Steam Audio, Resonance and Meta's
  SDK are references for technique (minimum-phase HRTFs, ambisonics for many sources, ray-based occlusion), not
  dependencies.

### 1.8 Libraries for the output

- **miniaudio** (single C file, public domain or MIT-0): devices on WASAPI, CoreAudio, ALSA, PulseAudio and more, with
  a data callback on its device thread; a node graph, a high-level engine (each sound a node, optional
  spatialization), resource management and decoders (WAV, FLAC, MP3; Vorbis through stb_vorbis). Not bound by LWJGL:
  using it from Java means building and shipping the native library ourselves and writing the Panama bindings.
  ([site](https://miniaud.io/), [repository](https://github.com/mackron/miniaudio), [examples](https://miniaud.io/docs/examples/))
- **OpenAL Soft** (LGPL, bound and shipped by LWJGL): the OpenAL source and buffer model, HRTF with SOFA loading,
  ambisonic B-format buffers, UHJ, EFX (reverb, occlusion, obstruction, air absorption, chorus, EQ, compressor, pitch
  shifter and more), convolution reverb (improved in 1.24.3).
  ([site](https://openal-soft.org)) Its mixer is OpenAL's model; a Java mixer would use it only as a stream queue.
- **SDL3 audio** (bound and shipped by LWJGL, already loaded by `engine-platform`): logical devices on physical ones,
  `SDL_AudioStream` objects that convert format, channels and rate and are bound to a device, either fed by the app
  (`SDL_PutAudioStreamData`, `SDL_GetAudioStreamQueued`) or by a callback, devices opened paused, hot-plug events, and
  a default device that follows the system's choice (SDL migrates streams on the default device; a device opened by
  id becomes a silent "zombie" when unplugged and must be reopened). Its stream functions are documented as safe from
  any thread. On Windows its WASAPI device thread registers with MMCSS ("Pro Audio") and uses `IAudioClient3`'s
  low-latency shared mode when available. The `dummy` and `disk` drivers exist for headless use and are only chosen
  by name.
  ([SDL_OpenAudioDeviceStream](https://wiki.libsdl.org/SDL3/SDL_OpenAudioDeviceStream),
  [SDL_audio.h](https://raw.githubusercontent.com/libsdl-org/SDL/main/include/SDL3/SDL_audio.h),
  [WASAPI backend](https://raw.githubusercontent.com/libsdl-org/SDL/main/src/audio/wasapi/SDL_wasapi.c))
- **LWJGL 3.4 audio modules** (true-sim uses 3.4.3, which ships SDL 3.4.x): FMOD, OpenAL (OpenAL Soft), Opus
  (libopus 1.6 with multistream, projection, libopusenc and **libopusfile**, which reads Ogg Opus with seeking), stb
  (stb_vorbis, decoding Ogg Vorbis to float or 16-bit with seeking) and SDL. No miniaudio or Steam Audio; 3.4.0
  removed the OpenVR binding.
  ([LWJGL releases](https://github.com/LWJGL/lwjgl3/releases),
  [opus templates](https://github.com/LWJGL/lwjgl3/tree/master/modules/lwjgl/opus/src/templates/kotlin/opus/templates))
- **Java's own:** `javax.sound.sampled` has a `SourceDataLine` with blocking writes and buffer sizes the backend picks
  (DirectSound on Windows in the JDK's implementation), no device change notification and no positional audio; fine for
  tools, not for a game. **Panama (FFM, final in JDK 22)** gives cheap downcalls, which the engine already uses
  (`Commands`); upcalls work from foreign threads (a native thread is attached to the JVM on its first upcall) but the
  thread then takes part in safepoints. Pure Java codecs exist (Concentus: Opus, bit-exact with libopus, about 40 to
  50 % of its speed, no Ogg reader).
  ([JEP 454](https://openjdk.org/jeps/454), [Concentus](https://github.com/lostromb/concentus))

### 1.9 Concepts and engineering

- **Real-time rules** (Bencina, "Real-time audio programming 101: time waits for nothing"): the audio callback must
  not allocate, lock, do I/O, or wait on anything of unbounded time; talk to it through lock-free FIFOs.
  ([article, mirror](https://hn.jakechampion.name/reader/http://www.rossbencina.com/code/real-time-audio-programming-101-time-waits-for-nothing))
- **Java and GC:** generational ZGC targets pauses under 1 ms (JEP 439; the default ZGC mode since JDK 23, JEP 474,
  and the only one since JDK 24, JEP 490); G1 stays the JVM's default collector. C2 puts safepoint polls in counted
  loops by strip mining. The Vector API is still incubating (JDK 26's JEP 529, JDK 27's JEP 537).
  Every Java thread still stops at safepoints (GC, deoptimization, some JFR and class redefinition), and time to
  safepoint grows when some thread runs a long loop without a safepoint poll. A Java audio thread therefore needs
  output buffered beyond the worst pause, held by native code that does not stop.
  ([JEP 439](https://openjdk.org/jeps/439), [Inside Java explainer](https://inside.java/2023/11/28/gen-zgc-explainer/))
- **Parameter automation:** the Web Audio API's `AudioParam` (a-rate and k-rate parameters; events at times; linear
  and exponential ramps; target approach) is a clean, specified model for sample-accurate automation.
  ([Web Audio API](https://www.w3.org/TR/webaudio/))
- **Procedural audio:** Farnell's *Designing Sound* (MIT Press, 2010) builds wind, rain, fire, engines and impacts from
  oscillators, noise and filters; modal synthesis (banks of damped resonators excited per material) for impacts and
  scraping. **Engines in production** are mostly RPM loop banks recorded on and off load and cross-faded with pitch;
  granular engines (AudioMotors in WRC 5 through FMOD; Crankcase REV in Wwise) slice recordings into single engine
  cycles offline and retrigger them at the firing rate.
  ([Audiokinetic on engine modelling](https://www.audiokinetic.com/engine-sound-modeling-from-sampling-to-granular-synthesis-in-wwise),
  [AudioMotors in WRC 5](https://www.mcvuk.com/development/how-to-make-racing-car-engines-roar-using-audiomotors-fmod))
- **Loudness:** EBU R128 / ITU-R BS.1770 integrated loudness (LUFS) with gating; normalize assets at import, keep a
  true-peak limiter on the master. ([EBU R128](https://tech.ebu.ch/publications/r128))


### 1.10 HRTF data and convolution

- **SOFA** (AES69) stores HRIRs as netCDF-4, which is HDF5 underneath; the convention for game use is
  `SimpleFreeFieldHRIR` (directions as azimuth, elevation, distance; one impulse response per ear per direction).
  Java can read it without natives through jHDF (pure Java, MIT licence).
  ([jHDF](https://github.com/jamesmudd/jhdf))
- **Public data sets and licences** (all allow commercial use, with different conditions):

  | Set | Licence | Size |
  |---|---|---|
  | SADIE II (University of York) | Apache 2.0 | KU100 and KEMAR dummy heads and human subjects; 44.1, 48 and 96 kHz; 8,802 directions for the dummy heads |
  | MIT KEMAR | free to use, citation asked | 710 directions, 512-tap HRIRs at 44.1 kHz (OpenAL Soft's default HRTF derives from it, as 64 taps at 48 kHz) |
  | CIPIC | University of California notice kept; any purpose | 45 subjects, 1,250 directions, 200 taps at 44.1 kHz |
  | LISTEN (IRCAM) | public, acknowledgement asked | 51 subjects |
  | HUTUBS, FABIAN | CC BY 4.0 (FABIAN since 2020) | |
  | ARI | CC BY-SA 3.0 | share-alike: bundling a modified set is a licence question |

  ([SADIE II](https://www.york.ac.uk/sadie-project/database.html),
  [ARI](https://projects.ari.oeaw.ac.at/research/experimental_audiology/hrtf/database/hrtfItESOFA.html),
  [FABIAN](https://depositonce.tu-berlin.de/items/3b423df7-a764-4ce1-9065-4e6034bba759/full),
  [libmysofa's copyright file listing the sets](https://launchpad.net/ubuntu/jammy/+source/libmysofa/+copyright),
  [OpenAL Soft's HRTF notes](https://svn.thedarkmod.com/publicsvn/darkmod_src/branches/tdmsync/ExtLibs/openal-soft/docs/hrtf.txt))
- **Convolution practice** (standard technique, stated from the literature rather than one source): split each HRIR
  into a minimum-phase filter and an interaural time difference (ITD); interpolate the minimum-phase filters between
  the three measured directions around the source (barycentric weights on a triangulated sphere, as VBAP does for
  speakers) and the ITD separately as a fractional delay; convolve with overlap-save FFT convolution, uniformly
  partitioned when the filter is longer than the block; cross-fade old and new filter outputs over a block when the
  direction changes. Near the head (under about 1 m), interaural level differences grow and each ear sees the source
  from a different direction (acoustic parallax); a spherical-head model adds both where the data set has one
  distance only.

### 1.11 What the survey says, in short

1. **A render thread fed by commands, with logical sounds apart from voices** (Unreal, FMOD, Wwise). Budgets apply to
   real voices; virtual ones are tracked.
2. **Sounds are definitions** (events, cues): containers, parameters, attenuation, bus, concurrency. One call plays
   any of them; handles are optional.
3. **Timing is on the audio clock, scheduled ahead** (Quartz, `PlayScheduled`, Wwise and FMOD music). Anything driven
   by the frame loop jitters by a frame.
4. **Music is segments with cues, stems in sync, states, a transition matrix and stingers.**
5. **The mixer is a bus tree in data with snapshots** (every system). Player volumes are buses or VCAs.
6. **Procedural sound wants a DSP graph with sample-accurate parameters** (MetaSounds, DSPGraph, Web Audio).
7. **HRTF per source is costly;** a few nearest sources through HRTF, the rest through ambisonics with one binaural
   decode, or plain panning. Minimum phase plus ITD makes interpolation between measured directions clean.
8. **Occlusion and propagation:** rays for occlusion, rooms and portals for propagation; the game owns the geometry.
9. **For Java:** a Java mixer is viable only if a native buffer decouples it from safepoints; prefer downcalls into a
   native queue over native callbacks into Java.

---

## 2. Architecture for true-sim (as decided)

### 2.1 Principles

- **Two halves.** `scene-audio` holds sound data and game-facing logic (no native code, no DSP); `engine-audio` holds
  the renderer and the device output. Servers, tools and replication use the scene half without a sound card.
- **Commands one way, events the other**, through preallocated single-producer single-consumer rings; positions
  through a triple-buffered snapshot interpolated over a frame's blocks.
- **Every timed thing is stamped in audio frames** (AU8): the game schedules at "now plus the scheduling latency"
  and the audio thread starts it at that exact sample, inside a block.
- **A sound is a definition, a clip is samples;** a sound plays from one call that returns an optional handle;
  handles carry a generation and go inert when the sound ends, as `Effect` handles do.
- **Logical instances are not voices** (AU7): the renderer gives real voices to the most audible and important
  instances within a budget and keeps the rest virtual, still keeping time.
- **Buses and snapshots as data;** player volumes are settings applied as an extra gain per bus, so a snapshot never
  overwrites a player's choice.
- **Everything audible is ramped:** no setter, device change, steal or pause makes a click.
- **Sounds belong to a world** (paused and time-scaled with it), **to the UI** (never paused) or **to the music
  player** (the `Audio` plugin's, following no world unless told).

### 2.2 Where things live

**scene, new module `scene-audio`** (scene-core, scene-assets, common-math, common-state; no native code, no DSP,
nothing of the engine; servers and tools use it):
- `AudioClip`, an asset (`AudioAssets.CLIP`): channels, rate, frame count, loop points (WAV `smpl` chunk, Ogg
  `LOOPSTART`/`LOOPLENGTH` comments), markers (WAV `cue`/`LIST adtl` labels), integrated loudness and the gain to a
  target loudness, and either decoded samples (16-bit or float, off-heap, `AutoCloseable` so the cache's default
  `unload` frees them) or the encoded bytes with a `streamed` flag. A `ClipDecoder` interface (implemented in
  `scene-audio-codecs`) decodes it. `scene-audio` itself reads WAV.
- `Sound`, an asset (`AudioAssets.SOUND`, built in code, `ref()` from equal data as `EffectData`): a tree of
  `SoundNode`s:
  - `Play.clip(ref)`; `Random.of(...)` (weights, no-repeat window, shuffle); `Sequence.of(...)`;
    `Switch.on(SURFACE).when(GRAVEL, ...)`; `Layer.of(...)`; `Blend.by(SPEED).at(0, a).at(10, b)`;
    `Segments.intro(a).loop(b).outro(c)` (§2.7); `Scatter.of(...)` (one-shots around the listener, §2.18);
    `Patch.named("v8")` (procedural, §2.13); `Play.input()` (a live input: a listener's mix, §2.10, or a game ring);
  - per node: volume and pitch with random ranges, delay, parameter curves (`volume(LOAD, curve)`);
  - per sound: bus, attenuation, concurrency group, priority (five levels), virtual behaviour, spatial mode (`2D`,
    `3D`, `3D_HRTF`), occlusion mode (AU13: `NONE`, `RAYS`), time scaling (§2.16), Doppler amount, caption (§2.17),
    max start delay, max duration for one-shots.
- `Attenuation`, `SoundParameter`, `SwitchGroup<E>`, `SoundMarker`; `Bus`, `BusLayout`, `Snapshot` as data (effect
  chains as records by name and field, resolved by the engine's registry, unknown names failing with "did you mean");
  `Score` (§2.12); `Line` and `Dialogue` (§2.15).
- `Sounds`: the sounds of one world, a store shaped on `Effects` as built (§3.2): `new Sounds(SceneGraph)`, slots with
  generations (`id = generation << 32 | slot`), the scene's `ChangeClock` and owner-thread check, microsecond ticks
  advanced by `advance(long ticks)` after `scene.update()`, a change log read with
  `changesSince(long since, Visitor)` (flags `ADDED`, `STOPPED`, `REMOVED`, `PARAMETERS`, `TIME`, `MOVED`, `SEED`,
  plus `SWITCHES` and `INPUT`), returning false when the log overflowed so the consumer rebuilds from `forEach`.
  `play(...)` returns a `SoundInstance` handle (allocating it, `@AllowAllocation` as in `Effects.play`); `fire(...)`
  returns the `long` id and allocates nothing (as `Effects.burst`). It also holds the world's `Listeners` (§2.10) and
  `ReverbZones` (§2.11).
- `Music`, the music state machine over an `AudioClock` interface (the engine's clock, or a test's fake one);
  `AudioWorld`, the occlusion interface (§2.11), thread-safe and allocation-free like `ParticleWorld` and animation's
  `Ground`.
- **Components.** The application API plans components on nodes (D46, D129) but the scene has none yet; listeners
  and reverb zones are therefore entries in stores keyed by node, as effects are, and gain a component form when D46
  is built.

**scene, new module `scene-audio-codecs`** (scene-audio, LWJGL `stb` and `opus` with natives; as
`scene-meshbuild` takes meshoptimizer): importers registered by extension through `PathAssets.importers()`
(`new AudioImporters(paths).register()`: `.wav`, `.ogg`, `.opus`, `.hrtf`, later `.flac`), loudness measurement, the
`ClipDecoder`s (Vorbis through stb_vorbis's pull API; Opus through libopusfile, which LWJGL binds: `op_open_memory`,
`op_read_float`, `op_pcm_seek`), and the SOFA reader producing an `HrtfSet` (§2.9).

**engine, new module `engine-audio`** (`com.truesim.engine.audio`; scene-audio, scene-audio-codecs,
engine-settings, common-jobs; LWJGL SDL for the output only):
- `AudioRenderer`: the audio thread's work, pure Java: commands in, blocks out. It renders offline as well
  (`renderer.render(float[] out, frames)`), which is what tests and tools use.
- `Voices`, `Mixer` (the bus graph from a `BusLayout`), `Spatializer` (panner, VBAP, `Hrtf`, ambisonics),
  `ListenerGraph` (rendering listeners, §2.10), `Streams` (the streaming thread and rings), `MusicRenderer`.
- `dsp`: `DspNode`, `Patch`, `Param`, built-in nodes, the node and patch registries, `Fft` (our own real FFT,
  allocation-free, twiddles from `Maths` at setup).
- `AudioOutput`: where blocks go. `SdlOutput` (default), `NullOutput` (renders and drops, or does not render),
  `OfflineOutput` (into memory or a WAV file).
- `AudioSync`: on the game thread, once a frame (§2.4).

**engine-app:** the `Audio` plugin (optional, D125: no device or no SDL means `NullOutput` and a log line) with its
settings group, `ui()` (a `Sounds` with no scene: 2D, never paused), `music()`, `sounds(world)`, `listeners()`
defaults from views (D131: "Audio listens from every player's camera"), and `AudioBindings` (§2.19). When BaseGame
lands (D120), `audio()` and `music()` shortcuts.

**engine-platform:** audio device events mapped (today they reach `PlatformListener.other(type, event)`), and the
audio subsystem started on the platform's thread (§4).

**engine-xr:** optionally `XR_OCULUS_audio_device_guid` and `headset.audioOutputDevice()`; the head pose as the
listener (§2.10).

### 2.3 The audio thread and the output

```
game thread (frame loop)                     audio thread (Java platform thread)                     SDL device thread (native,
-----------------------                      ----------------------------------                       MMCSS "Pro Audio")
app.update: sounds.play / set / stop         loop:                                                   pulls the SDL_AudioStream
Audio.afterUpdate -> AudioSync:                wait until queued < high mark (timed park, AU15)       into WASAPI / CoreAudio /
  change logs -> commands --- SPSC ring --->   drain commands (stamped in audio frames)              PulseAudio / PipeWire
  positions   -> snapshot --- triple buf -->   newest position snapshot
  Music decisions -> stamped commands          render one block (default 256 frames):
Audio.beforeUpdate:                              rendering listeners (topological order, §2.10)
  drain events <------------ SPSC ring -----     device voices -> buses -> master -> limiter
  (ended, markers, beats, meters, devices)     push events (ended, marker, beat, starved, underrun)
                                               SDL_PutAudioStreamData(block) ------------------------>
                                               publish clock (frames rendered, nanoTime, queued)
streaming thread: decodes into per-voice rings, requests through a ring, never blocking the audio thread
```

- **Push mode, no upcalls.** The device is opened with `SDL_OpenAudioDeviceStream(device, spec, NULL, NULL)`: no
  callback, the stream starts paused and is resumed after a warm-up (below). The stream's format is the device's own
  (`SDL_GetAudioDeviceFormat`: float32, its channel count and native rate, AU14), so SDL converts nothing and
  `SDL_GetAudioStreamQueued` (which counts input bytes) equals what is waiting. With a callback, SDL's device thread
  would call into Java and stop at every safepoint inside the device's deadline.
- **SDL's side.** On Windows SDL's WASAPI device thread registers with MMCSS as "Pro Audio" and opens shared mode
  through `IAudioClient3` (`InitializeSharedAudioStream` at the engine's minimum period, falling back to the classic
  path), so the device side is already low latency; `SDL_HINT_AUDIO_DEVICE_STREAM_ROLE` is set to "Game".
  `SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES` is a request only.
- **Queue marks.** The audio thread keeps the queue between a low mark (default 2 blocks, 10.7 ms at 48 kHz) and a
  high mark (low plus one). Both, and the block size, are settings at the developer tier (AU4); a change restarts the
  output: fade out over 10 ms, drain, rebuild buffers, fade in. Underruns are counted, reported as events and in a
  meter, and raise the low mark by one block for the session (an automatic guard, logged) when two happen within a
  minute.
- **Waiting (AU15) with a precise timer.** After pushing, the thread computes when the queue will fall to the low
  mark (from `SDL_GetAudioStreamQueued` and the rate) and parks until about 1 ms before that, then polls with 1 ms
  parks. On Windows the park is a high-resolution waitable timer (`CreateWaitableTimerExW` with
  `CREATE_WAITABLE_TIMER_HIGH_RESOLUTION`, waited on through a kept downcall handle), because `LockSupport.parkNanos`
  rides the system timer (15.6 ms by default, and Windows 11 ignores a raised resolution for minimised or occluded
  windows). The thread measures its oversleep; when the 99.9th percentile exceeds half a block, the low mark rises.
  It never waits on the game thread.
- **Priority.** A platform thread at `Thread.MAX_PRIORITY` (on Windows `THREAD_PRIORITY_HIGHEST`; ignored on Linux
  without root) and, on Windows, `AvSetMmThreadCharacteristicsW("Pro Audio")` from avrt.dll through a downcall.
- **No allocation, no locks, no I/O.** Voice slots, DSP graphs (built and warmed on the game thread, handed over
  ready), bus graphs (a new layout swapped at a block boundary, the old one handed back for freeing) and the
  off-heap block buffer (one `MemorySegment`, float32 interleaved) are made before use.
- **Native calls.** LWJGL wraps a function address in a `MemorySegment` on every call unless fully inlined
  (pitfalls), so `SDL_PutAudioStreamData`, `SDL_GetAudioStreamQueued` and the timer calls get their own kept
  downcall handles, as `Commands` does, without `Linker.Option.critical`. SDL's audio stream functions are documented
  as safe to call from any thread.
- **Warm-up.** Before the device is resumed, the renderer renders about 200 silent blocks offline so C2 compiles the
  hot loops; a new DSP node type (a plugin loading mid-game) is warmed the same way on the game thread before it is
  handed over, so the audio thread never runs interpreted code for long.
- **Command ring:** fixed-size records (opcode, target id with generation, frame stamp, up to eight floats or longs)
  in a `long[]` ring; single producer (`AudioSync`), single consumer. When full, parameter changes are coalesced and
  the overflow reported; starts are never dropped silently.
- **Position snapshot:** per active emitter and listener pair (§2.10), listener-relative position and velocity as
  floats in a triple buffer; the audio thread interpolates from the previous snapshot over the frame's duration.
- **Event ring** back to the game: ended, marker, music beat and bar, transition done, subtitle and viseme cues,
  stream starved, underrun, device lost or changed, meters at 30 Hz. Drained in the `Audio` plugin's `beforeUpdate`;
  listeners run on the main thread there.
- **The audio clock:** frames rendered so far (a `long`), with the `System.nanoTime` and queue fill at the last push,
  published through a seqlock. The game reads "the frame being rendered", "the frame being heard" (minus the queue
  and the device buffer from `SDL_GetAudioDeviceFormat`'s sample frames) and converts game time to audio frames.

### 2.4 A frame

The engine's frame today is `beforeUpdate` (plugins) → `app.update` → `afterUpdate` (plugins). The phases of D90
(`FIXED`, `UPDATE`, `LATE`) and the `ANIMATE` phase that the animation design proposes are not built yet; audio needs
only that `AudioSync` runs after the game has updated the scene graph, advanced `Animators` and `Effects`, and before
rendering is recorded. The `Audio` plugin's `afterUpdate` does it, and declares the plugins that update the scene in
`requires()` when they exist.

`AudioSync`, on the game thread, allocation-free:
1. **Clock.** Read the audio clock; this frame's *schedule point* = the frame being rendered + the scheduling latency
   (one block plus the high mark). Everything started this frame is stamped at the schedule point plus its own
   delay, so a frame's sounds keep their relative timing exactly, and a slow frame shifts them together.
2. **Follow each store's change log** (the worlds' `Sounds`, the UI store): added (evaluate the container tree with
   the instance's seed; reserve logical voices; request stream priming), stopped, removed, parameters, switches,
   inputs, time, moved. Write commands.
3. **Listeners.** Each listener's node world transform (doubles) from `SceneGraph.worldTransforms()`; in VR the head
   pose (`XrInput.head`), the same predicted pose the eye cameras use. Velocity from the last two frames, zeroed on a
   teleport (a jump larger than a threshold).
4. **Positions** in doubles, per device listener and per rendering listener that hears the emitter (§2.10):
   emitter minus listener, rotated into the listener's frame, then floats. Distance culling: an instance beyond its
   attenuation's max distance (plus a margin) from every listener goes virtual without the renderer looking at it.
5. **Occlusion** (§2.11): a ray budget on the job system, results smoothed, written as commands.
6. **Music** (§2.12): `Music.update(clock)` decides transitions and schedules exact frames ahead.
7. **Publish** the snapshot and the commands.

At the next frame's `beforeUpdate`, the event ring is drained: ended instances leave their store (handles go inert),
markers, beats, subtitle cues and device events reach listeners.

### 2.5 The voice pipeline

A logical instance becomes one or more *voices* (a layer of three clips is three voices). Per block, per real voice:

```
source            clip reader (PCM in memory) | stream ring reader | DSP patch | input (listener mix, game ring)
  -> resample     ratio = clip rate / output rate x pitch x Doppler x time scale; 4-point cubic Hermite by default,
                  polyphase windowed sinc for music streams at a fixed ratio
  -> filters      distance low-pass (air), occlusion gain and low-pass per band, the sound's own effects
  -> envelope     fades (start, stop, pause, steal 10 ms), gain ramps per sample
  -> spatialize   per listener that hears it (§2.10): 2D pan | 3D equal-power or VBAP | HRTF (§2.9) | ambisonic encode
  -> bus          into the sound's bus input of that listener's mix; sends post-fader
```

- **Virtual and real** (AU7). Every 4 blocks the renderer computes each voice's audibility (gain after attenuation,
  occlusion, bus volumes and fades, times a priority weight) and keeps the most audible as real within the budget
  (default 64). Below −60 dB a voice goes virtual even with budget left; 3 dB of hysteresis stops flapping.
- **Virtual voices keep time:** the position advances without reading samples; a returning loop resumes where it
  would be (streams re-prime there; 20 ms fade in). A one-shot that would end before returning is killed.
- **Concurrency groups** (max count; on overflow: oldest, quietest, farthest, lowest priority, or reject new; a
  retrigger time) apply in `AudioSync` at start, so "reject new" never reaches the renderer; stealing in the renderer
  fades the stolen voice over 10 ms.
- **Priority:** five levels; the highest is never virtualized by budget (dialogue, the player's own weapon).
- **Order:** voices are mixed in slot order and slots are assigned first fit, so an offline render of the same
  commands sums the same floats in the same order (AU10).

### 2.6 The mixer graph

```
voices --> SFX --+-> WEAPONS --+                      sends --> REVERB_SMALL (aux) --+
                 +-> FOOTSTEPS +-> SFX --+                      REVERB_LARGE (aux) --+
                 +-> VEHICLES -+         |                                           |
voices --> AMBIENCE ---------------------+--> MASTER --> accessibility --> limiter --> output
voices --> VOICE ------(sidechain ducks MUSIC)
music  --> MUSIC ------------------------+
voices --> UI (2D, never spatialized, never paused with a world)
listener mixes routed to the device --> their bus (e.g. PIP)
```

- **A bus** has a volume (dB, ramped), mute, an effect chain (built-in or plugin `DspNode`s by name), sends to aux
  buses (pre or post fader) and a meter (peak and RMS at 30 Hz). Processed in topological order computed when the
  layout is built; cycles rejected.
- **Ducking:** "VOICE ducks MUSIC by 9 dB, attack 50 ms, release 600 ms" is an envelope follower on the sidechain bus
  driving the ducked bus's gain.
- **Snapshots** are targets (a bus volume, an effect parameter) with fades and a priority; several blend by
  intensity in priority order (FMOD's model).
- **Player volumes** are settings (`audio.volume.master`, `.music`, `.sfx`, `.voice`, `.ui`, and one per bus the
  game declares) applied as an extra gain per bus. They are player-layer settings, so `AppConfig.saveSettings()`
  writes them to the app's settings file (D6, D7) when the app saves settings.
- **Master:** the accessibility stage (§2.17: mono downmix, balance, dynamic range preset), then a true-peak-aware
  look-ahead limiter (1.5 ms) at −1 dBTP. Clips arrive at a target loudness measured at import (default −23 LUFS for
  effects, an offset per bus for music and dialogue; ITU-R BS.2434 reports typical game content around −24 LKFS on
  consoles and −18 on portables).
- **Output layout:** stereo by default; 5.1 and 7.1 when the device reports them and the output setting allows
  (VBAP); binaural is stereo with HRTF.

### 2.7 Loops with intro and outro

`Segments.intro(START).loop(LOOP).outro(END)`: the intro plays once, the loop repeats with a sample-exact seam (the
next segment is scheduled at the frame the previous ends), and `stop()` chooses, per sound:
- `Release.AT_LOOP_END`: finish the loop pass, then the outro (machines, sirens);
- `Release.crossfade(seconds)`: the outro now, cross-fading the loop out (automatic weapons);
- `Release.AT_MARKER`: the next marker in the loop;
- `stop(fadeSeconds)` skips the outro and fades.
Streams read across the loop point into the ring (the seam is stitched there), so a streamed loop is as seamless as
one in memory.

### 2.8 Attenuation, Doppler and panning

- **Attenuation:** gain by distance from the curve (inverse distance clamped to min and max by default), spread,
  low-pass by distance, reverb send by distance, an optional cone; per voice per block from the interpolated
  listener-relative position.
- **Doppler:** pitch ratio = (c + v_l·d) / (c − v_s·d) with c = 343 m/s from listener-relative velocities, clamped to
  [0.5, 2], scaled per sound (0 for music, UI and dialogue by default), smoothed over 50 ms.
- **Large worlds:** relative positions and velocities are computed in doubles on the game thread (the scene's rule:
  double positions, no origin shifting) and only then become floats, so a car 7,000 km from the origin has the same
  Doppler as one at the origin; velocities come from differences of *relative* positions over the frame time, so
  float cancellation never happens. Teleports zero the velocity.
- **Panning:** equal-power stereo with a gentle high shelf behind (a front/back cue), VBAP for speaker layouts.
  Elevation needs HRTF.

### 2.9 HRTF in Java (AU6)

**Data.** An `HrtfSet` asset (scene-audio) holds measured directions (azimuth, elevation, distance), one HRIR per
ear per direction, and the rate. `.sofa` files (`SimpleFreeFieldHRIR`) are converted at build time to a small
format of our own (`.hrtf`: directions, minimum-phase HRIRs and ITDs, already prepared) by a converter tool, so the
runtime needs no HDF5 reader; the default set ships converted with the engine (SADIE II's KU100 at 48 kHz, Apache
2.0). Players pick a set in the settings (`audio.hrtf.set`), and a game may ship its own, converted.

**Preparation** (once per set and output rate, on a background lane, never on the audio thread):
1. resample the HRIRs to the output rate (windowed sinc, `Maths` for the window);
2. split each into a minimum-phase filter (real cepstrum) and an ITD (onset difference, refined by
   cross-correlation);
3. truncate the minimum-phase filters to N taps (default 128 at 48 kHz, 2.7 ms; a setting), with a short fade;
4. triangulate the sphere of directions (convex hull) and build a lookup grid (2° steps) from direction to triangle,
   so finding the three neighbours and their barycentric weights is a table read and a 3×3 solve;
5. keep the filters in the time domain (128 × 2 ears × directions floats: 9 MB for SADIE's 8,802 directions; the
   loader may decimate dense sets to about 2,000 directions).

**Per HRTF voice per block** (block B = 256, N = 128, overlap-save with a 512-point real FFT, one partition):
1. if the direction moved by more than 0.5° (or the voice just started), interpolate the three neighbours'
   minimum-phase filters (barycentric) and the ITD, and FFT the new filter for each ear (2 forward FFTs);
2. FFT the voice's mono input block once (1 forward FFT, shared by both ears);
3. multiply by each ear's spectrum and inverse FFT (2 inverse FFTs); when the filter changed this block, also with
   the previous spectra and cross-fade the two outputs over the block (2 more inverse FFTs);
4. apply the ITD as a fractional delay per ear (third-order Lagrange), the delay ramped per sample;
5. near field (under 1 m): per-ear directions from the ear positions (acoustic parallax) and a spherical-head
   interaural level shelf by distance; sets with several measured distances interpolate between shells instead.

Filters longer than the block (a set kept at 256 or 512 taps, or B lowered to 128) use uniformly partitioned
overlap-save: the input spectra go into a frequency-domain delay line and each ear's output is the sum of partition
products, so the cost grows with the partition count, not the filter length times the block.

**Cost estimate** (to be measured in step 17; FFTs at about 2.5 N log2 N flops for a real FFT of N points, about
11 kflop for 512):

| Per block, 16 HRTF voices | Flops | Time at 2 Gflop/s (scalar Java, conservative) |
|---|---|---|
| Steady (1 forward, 2 inverse, 2 spectrum products per voice: about 38 kflop) | about 0.6 M | about 0.3 ms |
| With the update cap: 4 of 16 voices change filter this block (about 87 kflop each) | about 0.8 M | about 0.4 ms |
| Uncapped, all 16 change at once | about 1.4 M | about 0.7 ms |
| Direct-form FIR instead, steady (256 × 128 taps × 2 ears × 2 flops per voice) | about 2.1 M | about 1.05 ms |

Budget: 0.6 ms of the 5.33 ms block (11 %) for 16 HRTF voices. The update cap (at most 4 voices change filter per
block, the others on the next blocks, a direction error under 1° at head-turn speeds) keeps the worst case at about
0.4 ms; the margin covers a slower-than-assumed FFT.

**SIMD.** C2's auto-vectorization handles the complex products when spectra are stored split (real and imaginary
arrays) and the FIR-free loops are simple; Java's floating-point semantics forbid reordering, so vectorized loops give
the same bits and AU10 holds. The Vector API is still incubating in JDK 27 and needs `--add-modules
jdk.incubator.vector` for every game, so it is not used in v1; an optional faster `Fft` can come as a plugin later,
used only where the module is present and never in reductions whose order the API leaves open.

**The cap and fallbacks.** At most 16 HRTF voices (AU7, a setting), chosen by audibility; a `3D_HRTF` voice past the
cap goes to the **ambisonics bus**: third-order ambisonic encoding (16 gains per voice per block, cheap), decoded once
to binaural by convolving each spherical-harmonic channel with an SH-domain HRTF filter pair derived from the same
`HrtfSet` at preparation (left/right symmetry halves the filters). The decode costs about as much as 8 HRTF voices,
fixed, however many voices it carries. Positions are already listener-relative, so the field needs no rotation.
Without headphones (the output setting), HRTF is off and everything pans; in VR it is on by default.

### 2.10 Listeners (AU12)

**Kinds.** A `Listener` (in the world's `Sounds.listeners()`) sits on a node:
- a **device listener** contributes to the device mix. The `Audio` plugin creates one per view that has a player
  (D131), and in VR one on the head; a game may add or replace them;
- a **rendering listener** renders a mix of its own, described by a `ListenerMix`: channels (mono by default, or
  stereo), the buses it includes (by default the world's buses except `MUSIC` and `UI`), a
  voice budget (16 by default), panning only (no HRTF: its output is spatialized again where it plays), a gain, and
  an optional max distance. Its output goes to one or more routes:
  - **into the world** through any sound whose source is `Play.input()` with the listener as input (a television, a
    radio, an intercom): the re-emitted mix is a voice like any other, attenuated, occluded and spatialized for the
    listeners that hear it;
  - **to the device**, into a named bus (picture-in-picture audio for a spectator view);
  - **to a tap** (a ring the game or a tool reads: recording, voice-chat loopback, tests).

**The device mix with several device listeners.** Each voice is spatialized for its nearest device listener and
attenuated by that distance (split screen). Nearest is chosen per voice every 4 blocks with hysteresis (10 % of the
distance) and a 50 ms cross-fade when it changes, so a sound between two players does not flip.

**The listener graph.** An edge M → L means listener L hears a voice that re-emits M's mix. Every block the renderer
renders rendering listeners in topological order of this graph, so a chain without cycles (a camera feeding a TV that
another camera films) adds no delay. Edges that close a cycle (two cameras watching each other's TVs) read the
source's *previous* block: one block of delay per such edge (5.33 ms at the defaults), which is also what makes
feedback well defined. Guards:
- **self:** a listener never hears a voice that re-emits its own mix (excluded from its voice set, not attenuated);
- **feedback:** every cycle passes a per-route gain (default −6 dB) and a limiter, and a howl detector (RMS growth
  across 8 blocks) lowers the loop's gain until it decays; chains deeper than 4 rendering listeners are cut at the
  deepest edge and reported;
- **lazy rendering:** a rendering listener renders only when something consumes it this block: an audible (real)
  re-emitting voice, a device route or a tap. A camera feed whose TV is far away, virtual or off costs nothing.

**Cost.** For each rendering listener that renders: AudioSync computes relative positions for emitters within its
max distance (doubles, on the game thread), and the renderer spatializes and mixes up to its voice budget (a voice
heard by two listeners is read and resampled once, then panned and mixed per listener) through its own copy of the
included buses' gains and sends; aux reverbs are shared with the device mix's, fed by a send per listener. Estimate:
about 0.05 ms per rendering listener at 16 voices; at most 4 rendering listeners by default (a setting).

**Latency.** A re-emitted sound reaches the device in the same block when the graph has no cycle, plus a block per
cycle edge; plus any delay the re-emitting sound adds on purpose (a broadcast delay effect).

### 2.11 Occlusion, reverb zones, propagation

- **Per sound (AU13):** `Occlusion.NONE` (UI, music, the player's own sounds, most small sounds) or
  `Occlusion.RAYS`. Propagation (rooms and portals) is later and will be a third mode.
- **`AudioWorld`** (scene-audio), implemented by the game or the engine:
  `boolean trace(double x0, double y0, double z0, double x1, double y1, double z1, Transmission out)`, filling a
  transmission per band (low, mid, high) from the surfaces crossed; thread-safe, allocation-free.
  - **Physics:** `CollisionAudioWorld` over common-collision, a `CollisionQuery` per worker
    (`CollisionWorld.newQuery()`), materials from `RaycastHit.material()` mapped to an `AudioMaterial` table
    (transmission per band per metre or per crossing).
  - **Ray tracing structures:** an engine implementation that batches the frame's rays into a compute pass with ray
    queries against the top-level structure where the device traces rays, and reads the results back a frame later
    (latency is hidden by the 100 ms smoothing). It falls back to physics when the device has no ray queries.
- **Budget:** 64 rays per frame by default, round-robin over audible occluding voices and their listeners (the
  device listeners' nearest, and rendering listeners when their mix asks for occlusion), on the job system; results
  smoothed (attack 100 ms, release 300 ms). Volumetric sources cast up to 4 jittered rays across their radius for
  partial occlusion. Obstruction (direct path blocked, reverb not) versus occlusion (both) by whether listener and
  emitter share a reverb zone.
- **Reverb zones:** a `ReverbZones` store per world (box or sphere on a node, fade distance, a preset or parameters,
  a priority); cell loaders add zones in `attach` and remove them in `detach`, so they stream with cells. The
  listener's zone sets the aux reverb (blended across fades); each emitter sends to its own zone's aux.
- **Later:** rooms and portals as a scene-world `Layer` (`AUDIO`) that streams with cells, diffraction paths through
  portals, and reflections from the ray tracing structures; all behind `AudioWorld` or a sibling interface.

### 2.12 Music: clocks, transitions, stems

- **`Score`**: a tempo map; **segments** (a clip or a set of stem clips, entry and exit cues in bars and beats,
  pre-entry and post-exit tails, markers); **playlists**; **states**; **stems** (a segment's clips in sync, each
  stem's volume following a parameter curve, ramped on the beat or immediately); **transitions** (when: immediately,
  next beat, bar, N bars, marker, exit cue, end; fades; an optional transition segment; where the destination
  starts: entry cue, same position, matching marker, random); **stingers**.
- **The clock is the audio frame counter** (AU8). `Music` (scene-audio, pure logic over `AudioClock`) knows "segment s
  started at frame F"; when the game asks for a state, it finds the first allowed sync point at or after the schedule
  point and schedules, as frame-stamped commands, the fade, the transition segment, the destination's start (minus its
  pre-entry) and stem volumes.
- **Readiness:** destination streams are primed when their state becomes reachable; a transition waits for the next
  sync point after priming rather than playing late, and reports it.
- **Beats to the game:** `music.listen(MusicListener)` gets bar, beat and marker events at the time they are heard;
  `music.position()` gives bar, beat and fraction, latency-compensated.
- **Pause and time scale.** Music belongs to the `Audio` plugin and follows no world's pause or time scale: a world
  paused for a menu leaves the music playing (the pause menu snapshot filters it). `music.pauseWith(world)` opts in:
  pausing that world pauses the music on the next beat with a short fade and resumes on its own clock; time scale is
  never applied to music (a slow-motion music change is a state, or a snapshot with a pitch effect).
- **Determinism:** decisions depend only on requested states, parameters, the score and the clock frames.

### 2.13 The DSP and procedural layer

- **`DspNode`** (Java): `prepare(DspSetup)` once on the game thread (rate, maximum block, channels; all buffers
  allocated there), `process(DspBlock)` per block on the audio thread, `reset()` when a pooled voice is reused.
  Buffers are planar `float[]` of the block size.
- **Parameters** (`Param`) are sample-accurate: constant for the block (most of the time) or carrying events at sample
  offsets (set, linear and exponential ramps, Web Audio's `AudioParam`); k-rate parameters read once per block; a
  default 5 ms ramp.
- **`Patch`**: a graph built by a builder, once per voice on the game thread (or from a pool), handed over ready.
  Registered by name so `Sound` data can name it; plugins add patches and node types.
- **Built-in nodes:** sources (sample player, granular player, oscillators by phase accumulator and wavetable,
  polyBLEP saw, square and triangle, noise from a hashed PCG generator, impulse and pulse trains); envelopes; filters
  (RBJ biquads, trapezoidal state-variable, one-pole, comb, all-pass, modal resonator bank); dynamics; delays; an FDN
  reverb (8 lines, Hadamard); distortion (2× oversampled waveshaper, bit crusher); granular pitch shifter; utilities.
- **Math on the audio thread:** `java.lang.Math` is forbidden in production code across the build (only exact
  functions such as `abs`, `min`, `round`, `sqrt`, `fma` pass `checkPlatformIndependence`); coefficients come from
  `Maths` at parameter changes; per-sample sines from phase accumulators and wavetables, never `Maths.sin` per sample
  (Fdlibm is too slow there).
- **Procedural examples** (patches): wind, rain, fire, impacts by material (modal banks), procedural footsteps.

**Worked example: a procedural vehicle engine** (§3.7 has the code): inputs `RPM`, `LOAD` (−1 overrun to 1 full
throttle), a `SHIFT` trigger; firing rate = RPM / 60 × cylinders / 2 (a V8 at 3,000 rpm fires 200 times a second);
a `FiringPulses` node with per-cylinder variation from the hashed random; exhaust as a tube waveguide plus muffler;
intake noise gated by the firing phase; mechanical whine; overrun crackle; RPM and load ramped over 20 ms. The
sample-based alternative (`RpmBank`) cross-fades loops recorded at fixed RPMs on and off load.

### 2.14 Streaming

- **Which clips stream:** set at import or by the sound (default: longer than 10 s or larger than 1 MB decoded); a
  new path fragment `#stream` forces it (`game://music/theme.ogg#stream`). Short clips decode whole at load on the
  asset lane (16-bit PCM off-heap by default; float for clips marked high quality).
- **The streaming thread** serves requests from a ring: prime (the first 0.5 s, decoded when the clip loads and kept
  while it is held), start, seek, stop. Each streamed voice owns a ring of 1 s of float samples kept at least half
  full.
- **Starvation:** requests are served by priority (music, then dialogue, then ambience, then the rest; nearest
  first), with at most 32 concurrent streams (a setting: past it, the least audible streamed voice goes virtual). A
  ring that runs dry holds its last 5 ms fading to silence (no click), counts a starvation, reports it, and the voice
  resumes at the position it should be at (time kept, as a virtual voice). Three starvations in a second on one
  voice raise its ring to 2 s.
- **Decoders:** Vorbis through stb_vorbis (exact sample seeks); Opus through libopusfile (`op_pcm_seek` in 48 kHz
  samples, pre-skip and end trim handled by the library); PCM directly. Never on the audio thread.
- **Bytes:** a streamed clip's encoded bytes are held in memory by its `AudioClip` in v1, counted by the asset
  cache's budget; reading from disk in chunks through `AssetInput` comes when memory says so.
- **Asset cache and cells:** a playing sound acquires its clips (main thread, in `AudioSync`) and releases them when
  it ends; sounds a cell's loader starts are acquired with the cell.

### 2.15 Dialogue: lines, subtitles, lip sync

- **`Line`** (scene-audio data): a clip path with a `{locale}` placeholder (`game://vo/{locale}/guard_halt.ogg`,
  resolved with the audio language setting, falling back to a default locale), a speaker key, a subtitle text key
  (localisation itself is not designed yet: a `Subtitles` provider interface turns keys into text), subtitle cues
  (times within the clip, from a WebVTT sidecar `.vtt` next to the clip or from markers; one cue for the whole line
  by default), and an optional viseme track (from a sidecar in Rhubarb Lip Sync's JSON output, imported with the
  clip).
- **`Dialogue`** (per world, in `Sounds.dialogue()`): plays lines on speakers (a node, usually the head joint) with
  rules per speaker and globally: one line per speaker at a time; a priority per line; a higher priority interrupts
  (the interrupted line fades over 100 ms and reports `INTERRUPTED`), an equal one queues, a lower one is dropped;
  queued lines expire after a time; barks have cooldowns per key. Lines play on the `VOICE` bus, which ducks music,
  at the highest priority (never virtualized), 3D at the speaker or 2D (radio, narrator).
- **Events at heard time:** `LINE_STARTED`, `CUE` (subtitle start and end, with the text key and speaker),
  `LINE_ENDED`, `INTERRUPTED`. The UI draws subtitles from them; durations come from the cues, so text and voice stay
  in step even when the audio queue is deep.
- **Lip sync hooks:** `line.visemes(float[] weights)` fills the current viseme weights at the heard time (the viseme
  track interpolated over 40 ms), and `line.envelope()` gives the line's smoothed amplitude for a jaw-only fallback,
  computed on the audio thread and published with the meters. A face rig reads either each frame and writes
  blend-shape weights or a jaw joint override (animation's `Animator.override`).

### 2.16 Time: pause, time scale, UI

- **Pause** (D128 pauses a world's clock; until clocks are built, the game calls `sounds.pause(true)`): the world's
  instances hold their positions with a 10 ms fade and resume where they were; sounds marked `ignoresPause` keep
  playing. The UI store and the music player are not in a world.
- **Time scale** (`sounds.timeScale(s)`, and per instance as on `Effect`): each sound says how it follows it:
  `TimeScaling.PITCH` (the default for world sounds: playback rate and Doppler times `s`, like tape in slow motion;
  patches get the scale as a parameter), `TimeScaling.NONE` (dialogue, UI, the default for 2D sounds) or, later,
  `TimeScaling.STRETCH` (granular, pitch kept). Delays and scheduled starts given in game time are converted with
  the scale. A scale of 0 is a pause.
- **UI sounds:** `audio.ui().play(CLICK)`: 2D, the `UI` bus, a real-time clock, never paused, time-scaled or
  occluded; they keep working on a pause menu.

### 2.17 Devices, focus, settings, accessibility

- **The default device.** The output opens `SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK`, which SDL migrates when the system
  default changes (headphones unplugged, SteamVR setting the headset as the playback device while it runs, the user
  picking another device): the stream keeps playing, no reopen is needed, and the game gets a `DEVICE_CHANGED` event
  from the `SDL_EVENT_AUDIO_DEVICE_*` events mapped by the platform.
- **A chosen device** (setting `audio.device`, a name, since SDL 3.4 has no stable endpoint id; SDL 3.6 adds
  `SDL_PROP_AUDIO_DEVICE_UNIQUE_ID_STRING`): opened by id. When it is removed SDL turns it into a zombie that drains
  silently, so on `SDL_EVENT_AUDIO_DEVICE_REMOVED` for our device the output closes it, reopens the default and fades
  in (the queued blocks are lost: a gap of about 20 ms); when a device with the chosen name is added again, the output
  moves back.
- **Format changes** (`SDL_EVENT_AUDIO_DEVICE_FORMAT_CHANGED`, or a migration to a device with another rate): AU14
  says native rate, so the output restarts at the new rate at a safe point: the stream is fed by SDL's converter
  until the game thread has re-prepared DSP graphs, resamplers and the HRTF set for the new rate (open question 6),
  then switches with a fade.
- **Channels and headphones:** SDL does not tell headphones from speakers; `audio.output` = `AUTO` (stereo, or the
  device's layout), `HEADPHONES` (binaural HRTF), `STEREO_SPEAKERS`, `SURROUND`. In VR the default is `HEADPHONES`.
- **Focus and visibility** (D127: nothing pauses by itself): `audio.background` = `PLAY` (default), `LOWER`
  (−12 dB) or `MUTE`, applied as a master gain ramp when the engine reports focus or visibility lost
  (`Engine.focusChanged`, `visibilityChanged`). Headset idle should count as hidden (D127); engine-xr does not report
  it yet (§4).
- **Settings** (group "audio", D8 to D10): the volumes, `audio.device`, `audio.output`, `audio.background`, mono,
  balance, dynamic range, subtitles and visualized sound at the basic tier; `audio.hrtf.set` at advanced; block size,
  queue marks, voice budgets, the HRTF cap, rendering listener cap and stream cap as hidden developer settings (the
  engine's existing practice, `addHidden`).
- **Accessibility:** mono downmix (after spatialization, −3 dB per channel; HRTF off while on), balance, dynamic range
  presets (`FULL`, `HOME`, `NIGHT`: a master compressor), subtitles with speaker names (§2.15), **captions for
  sounds** (`Sound.caption(...)`: "[explosion]" events with direction), and **visualized sound**:
  `audio.indicators(listener, out)` fills the most prominent captioned or flagged sounds this frame with their
  direction relative to the listener and loudness, for a HUD ring (the way games show footsteps and gunfire to deaf
  players).

### 2.18 Very many sounds

- **Scatter:** `Scatter.of(sound).around(radius).rate(perSecond)` spawns one-shots at random positions around the
  listener (rain drops, birds, crickets) inside one logical instance: the renderer runs up to k grains (default 8)
  in a voice group, each panned, without a logical instance per drop.
- **Sound fields:** `sounds.field(sound, field)` plays one sound from many places: points (a crowd, a forest), a
  polyline (a river, a road) or an area. The renderer plays real voices at the k nearest points (default 4) or the
  nearest point on the line, and the rest as a diffuse 2D or ambisonic bed whose level follows their summed
  attenuation. One logical instance, a few voices.
- **Beds plus details:** rain is a 2D bed layered by an intensity parameter, a scatter of near drops and surfaces
  (roofs, leaves) as fields; crowds are density-layered beds plus a few nearest individual voices. Virtualization
  does the rest.

### 2.19 Hooks into animation and effects

`AudioBindings` (engine-app, AU16):
- `onEvent(LEFT_FOOT_DOWN, FOOTSTEPS, footL)` plays a sound when the event fires (`Animators.listen`), scaled by the
  event's weight. `Animators.Listener.fired(long node, AnimationEvent event, float weight)` gets the skeleton's
  first node, not a joint, so the binding names the joint (a `Joint` from the rig, placed with
  `animator.node(joint.index())`).
- `.surface(SURFACE, terrain::materialAt)` sets the switch from `TerrainHeights.materialAt(x, z)` (main thread; `NONE`
  where unloaded maps to the default switch value).
- `curve(animator, BREATH, sound, INTENSITY)`: `Animator.curve(AnimationCurve)` drives a parameter every frame.
- `onEffect(MUZZLE_FLASH, GUNSHOT)`: an `ADDED` change for that asset in the `Effects` log plays the sound at its
  node or position. Effect bursts (`Effects.burst`) are read from the burst arrays the same way; GPU particle events
  never reach the CPU.
- `Animator.updateEvery(n)` fires events on the animator's turns only, so far footsteps may arrive up to n frames
  late; bindings may set a max distance.

### 2.20 Networking, servers, determinism

- **Sounds are cosmetic by default.** A server runs no renderer and loads no native audio library: `scene-audio` and
  its stores are plain Java, `engine-audio` is not on a server's classpath (D98).
- **What replicates:** most sounds follow from replicated events (a shot, a footstep from replicated animation, an
  effect start) and play locally. Sounds that are gameplay state (an alarm, a radio, music state in a shared space,
  dialogue lines that drive subtitles for everyone) replicate as `Sounds` changes: asset id, node or position, seed,
  start tick, parameters, switches, stop. Late joiners seek loops to the right position and skip ended one-shots.
- **The seed** makes container choices identical on every client and the server (AU10). The hashed random is today
  `scene.effects.Hash` (PCG); scene-audio must not depend on scene-effects, so it moves to common-math (open question
  3).
- **Offline renders bit-identical** for the same commands, block size and rate: fixed voice order, `Maths` and
  exact operations only, no reductions in unspecified order. Live output is not reproducible (the game-to-audio
  clock mapping jitters).
- **AI hearing** is gameplay: games raise their own noise events; they may reuse attenuation data.
- **Voice chat** is out of scope; a tap on a rendering listener and `Play.input()` with a game ring are the hooks a
  voice-chat plugin would use (positional voice is a 3D sound fed by a ring).

### 2.21 Assets and importers

- **Clips** import at runtime by extension (D47) through `PathAssets.importers()`: `.wav` (PCM 8/16/24/32-bit and
  float, any rate, mono to 8 channels; `smpl` loops; `cue` markers), `.ogg` (Vorbis; loop comments), `.opus`, `.sofa`
  (HRTF sets), later `.flac`. Importing measures loudness (BS.1770 gated) and stores the gain to the target.
- **Sounds, bus layouts, snapshots, scores and lines** are built in code (AU9), immutable, equal data giving one
  asset, as `EffectData`; a file format comes with the serialization repository.
- **A cooker later** (D22): banks of pre-decoded or re-encoded clips where decoding cost matters.

### 2.22 The JVM: collector, safepoints, JIT

- **The collector.** Generational ZGC (the only ZGC mode since JDK 24) keeps pauses under a millisecond and is the
  recommended collector for games (`-XX:+UseZGC`); the demo's `run` task and the README say so (the engine sets no
  collector today). With G1 (the JVM's default), young pauses of several milliseconds are normal and can exceed the
  default queue on large heaps, so when the `Audio` plugin sees a non-ZGC collector (`GarbageCollectorMXBean`
  names), it starts with the low mark at 4 blocks (21 ms) and logs why.
- **Safepoints.** Every Java thread stops at safepoints (GC phases, deoptimization, some JFR events). The audio
  thread's loops are counted loops, which C2 strip-mines with safepoint polls, so the audio thread itself never
  delays a safepoint; the queue covers the time to safepoint of other threads. Long native calls on other threads do
  not block safepoints.
- **JIT.** Warm-up before resume (§2.3); new node types warmed on the game thread; allocation-free tests run with
  `-XX:-BackgroundCompilation` as the build already does.
- **Proven, not assumed:** `assertAllocationFree` around `renderer.render(...)` with the audio thread named as a
  worker, `AudioSync.update()`, `Music.update()`, `Sounds.advance()`; the GC stress test (§2.24).

### 2.23 Budgets and costs

At 48 kHz and 256 frames a block, the audio thread has 5.33 ms per block. Target: under 1.5 ms (28 %) for the heavy
scene below, measured by `AudioBenchmark` on the Ryzen 9 9950X3D, and `AudioSync` under 0.2 ms of the game frame.

| Part | Estimate per block | Notes |
|---|---|---|
| 64 real voices, PCM, cubic resample, filters, stereo pan | 0.1 to 0.2 ms | About 30 operations per sample per voice; vectorized loops. |
| 16 HRTF voices | 0.3 ms steady, 0.4 ms with the update cap | §2.9. |
| Ambisonics decode (third order) | about 0.15 ms | Fixed; any number of voices. |
| 2 rendering listeners, 16 voices each | about 0.1 ms | §2.10. |
| FDN reverb ×2, EQ, compressors, limiter | about 0.1 ms | |
| One procedural engine | about 0.05 ms | |
| Vorbis and Opus decode, 4 stems + 8 ambiences | streaming thread | Many times real time. |

Memory: 16-bit PCM is 192 kB per stereo second at 48 kHz; streamed rings 384 kB per stereo voice; an HRTF set of
2,000 directions at 128 taps, 2 MB.

### 2.24 Testing audio

- **Offline rendering:** `AudioRenderer` with `OfflineOutput` renders any number of blocks from a scripted command
  stream, no device and no threads.
- **Golden files:** short renders compared with stored WAVs, bit-exact on the same JDK, a −90 dB RMS tolerance
  across JDKs; regenerated by a Gradle property, the diff listened to.
- **Spectral and level checks:** oscillator FFT peaks, filter responses against analytic curves, attenuation by
  distance, Doppler pitch by zero crossings, null tests, BS.1770 reference loudness.
- **Timing:** a click at frame F appears at sample F; seams have no discontinuity; music transitions land on the
  computed bar; a cycle in the listener graph delays by exactly one block, and a listener's own re-emission is
  absent from its mix.
- **HRTF:** interaural time and level differences of rendered impulses against the data set's for measured
  directions, continuity while a source circles the head (no clicks at triangle edges), the cap falling back to the
  ambisonics bus; plus a listening checklist (front/back, elevation, externalization) at the step's review.
- **CI without a device:** SDL's `dummy` driver is never auto-selected, so tests that open the output set
  `SDL_HINT_AUDIO_DRIVER=dummy` themselves (and CI may set `SDL_AUDIO_DRIVER=dummy`); the `disk` driver
  (`SDL_HINT_AUDIO_DISK_OUTPUT_FILE`) gives an end-to-end test that what the audio thread pushes is what SDL writes.
  Everything else is offline.
- **Real device:** latency (command to render frame exact, plus queue and device buffer), underruns over 10 minutes,
  the GC stress test (garbage at a high rate on another thread for 30 s, zero underruns under ZGC), headphones
  unplugged, behind `TRUESIM_AUDIO=true` like `TRUESIM_XR`.

---

## 3. The public API

### 3.1 Names, declared once

```java
// Typed constants, compared by identity, as animation's events and effects' parameters are.
static final SoundParameter RPM = SoundParameter.of("rpm", 800, 0, 9000);
static final SoundParameter LOAD = SoundParameter.of("load", 0, -1, 1);
static final SoundParameter INTENSITY = SoundParameter.of("intensity", 0, 0, 1);
enum Surface { DIRT, GRAVEL, WOOD, METAL, WATER }
static final SwitchGroup<Surface> SURFACE = SwitchGroup.of("surface", Surface.class, Surface.DIRT);

static final Bus MUSIC = Bus.of("music"), SFX = Bus.of("sfx"), VOICE = Bus.of("voice"), UI = Bus.of("ui");
static final Bus FOOTSTEPS = Bus.child(SFX, "footsteps"), VEHICLES = Bus.child(SFX, "vehicles");
static final Bus AMBIENCE = Bus.of("ambience"), REVERB = Bus.aux("reverb"), PIP = Bus.of("pip");
```

### 3.2 One-liners

```java
Audio audio = engine.get(Audio.KEY);                      // BaseGame: audio() once it exists (D120)
Sounds sounds = audio.sounds(world);                      // one store per world
AssetRef<AudioClip> CLICK = paths.ref(AudioAssets.CLIP, "game://ui/click.wav");

audio.ui().play(CLICK);                                   // 2D, UI bus, never paused
sounds.play(EXPLOSION, node);                             // a Sound at a node: 3D, follows the node
sounds.play(EXPLOSION, x, y, z);                          // at a world position (doubles)
sounds.fire(GUNSHOT, muzzleNode);                         // hot path: returns a long id, allocates nothing
```

`play` takes an `AssetRef<AudioClip>` (played with defaults: 2D on `UI` from `ui()`, 3D on `SFX` in a world) or a
`Sound`, and returns a `SoundInstance` that may be ignored. A sound whose assets are still loading starts when they
arrive within its `maxStartDelay` (100 ms by default for one-shots), else it is dropped and counted, never played late
out of sync with its cause.

### 3.3 A sound definition and an instance handle

```java
static final Attenuation OUTDOOR = Attenuation.builder()
    .distance(2, 120).curve(Curve.INVERSE).lowPass(20_000, 4_000)
    .spread(3, 0).reverbSend(0.1f, 0.6f).build();

static final Sound FOOTSTEPS_SOUND = Sound.builder()
    .bus(FOOTSTEPS).attenuation(Attenuation.near(1, 25)).priority(Priority.LOW)
    .concurrency(Concurrency.max(12).steal(Steal.FARTHEST))
    .root(Switch.on(SURFACE)
        .when(Surface.GRAVEL, Random.of(GRAVEL_1, GRAVEL_2, GRAVEL_3, GRAVEL_4).noRepeat(2))
        .when(Surface.WOOD, Random.of(WOOD_1, WOOD_2, WOOD_3))
        .otherwise(Random.of(DIRT_1, DIRT_2, DIRT_3)))
    .volume(Range.db(-2, 0)).pitch(Range.semitones(-0.5, 0.5))
    .caption(Caption.of("footsteps"))
    .build();

static final Sound EXPLOSION = Sound.builder()
    .bus(SFX).attenuation(OUTDOOR).priority(Priority.HIGH).occlusion(Occlusion.RAYS).spatial(Spatial.HRTF)
    .root(Random.of(BOOM_1, BOOM_2, BOOM_3)).caption(Caption.of("explosion")).build();

static final Sound RAIN = Sound.builder()
    .bus(AMBIENCE).spatial(Spatial.TWO_D).timeScaling(TimeScaling.NONE)
    .root(Layer.of(
        Play.clip(RAIN_LIGHT).volume(INTENSITY, Curve.points(0, 1, 0.6, 0)),   // fades out as it gets heavy
        Play.clip(RAIN_HEAVY).volume(INTENSITY, Curve.points(0.3, 0, 1, 1)),
        Scatter.of(DROPS).around(6).rate(INTENSITY, Curve.points(0, 2, 1, 40))))  // near drops, one instance
    .loop().build();

SoundInstance rain = sounds.play(RAIN);
rain.set(INTENSITY, 0.8f);                                // ramped by the parameter's default ramp
rain.set(INTENSITY, 0.2f, 4.0);                           // over 4 s
sounds.play(FOOTSTEPS_SOUND, footNode).set(SURFACE, Surface.GRAVEL);
rain.volume(-6, 1.0).pitch(1.0f).pause(true);             // dB over 1 s; pitch ratio; pause
rain.stop();                                              // outro if any, else the sound's release fade
rain.stop(2.0);                                           // fade out over 2 s
rain.kill();                                              // now (10 ms anti-click fade)
boolean playing = rain.alive();                           // false once ended: the handle is inert
sounds.listen(SoundEvent.ENDED, (id, event) -> ...);      // one listener, made once; ids, no handles
```

### 3.4 Looping with intro and outro

```java
static final Sound MINIGUN = Sound.builder()
    .bus(SFX).attenuation(OUTDOOR).priority(Priority.HIGH)
    .root(Segments.intro(SPIN_UP).loop(FIRE_LOOP).outro(SPIN_DOWN_TAIL).release(Release.crossfade(0.03)))
    .build();

SoundInstance gun = sounds.play(MINIGUN, muzzle);          // intro, then the loop, seam sample-exact
gun.stop();                                                // trigger released: the tail starts now
```

### 3.5 Listeners: split screen, and a camera heard from a television

```java
// Device listeners come from players' views (D131); explicit when the game wants them elsewhere.
Listener p1 = sounds.listeners().device(player1Camera);
Listener p2 = sounds.listeners().device(player2Camera);    // each voice: its nearest device listener

// A security camera whose sound plays from a TV in the control room.
Listener cctv = sounds.listeners().render(cameraNode, ListenerMix.mono()
    .buses(SFX, AMBIENCE).voices(16).maxDistance(40));

static final Sound TV_SPEAKER = Sound.builder()
    .bus(SFX).attenuation(Attenuation.near(0.5, 15)).occlusion(Occlusion.RAYS)
    .root(Play.input())                                     // plays whatever input the instance is given
    .effect(Eq.bandPass(250, 5_000)).effect(Distortion.soft(0.15))   // a small speaker
    .build();

SoundInstance tv = sounds.play(TV_SPEAKER, tvNode).input(cctv);
tv.volume(-3, 0.2);                                         // the TV's volume knob
tv.input(otherCamera);                                      // switch channel: a 30 ms cross-fade
tv.stop();                                                  // TV off: cctv stops rendering (nothing consumes it)

// A spectator picture-in-picture: the listener's mix into a device bus instead of the world.
Listener spectator = sounds.listeners().render(droneNode, ListenerMix.stereo().toBus(PIP));
audio.mixer().bus(PIP).volume(-12, 0.3);
```

### 3.6 Music: a state machine with beat-synced transitions

```java
static final MusicState EXPLORE = MusicState.of("explore"), COMBAT = MusicState.of("combat"),
    BOSS = MusicState.of("boss"), VICTORY = MusicState.of("victory");
static final Stinger LEVEL_UP = Stinger.of("level-up");

Score score = Score.builder()
    .tempo(Tempo.bpm(112).meter(4, 4))
    .segment("explore-a", s -> s.clip(EXPLORE_A).entry(Bar.of(1)).exit(Bar.of(33)))
    .segment("explore-b", s -> s.clip(EXPLORE_B).entry(Bar.of(1)).exit(Bar.of(25)))
    .segment("combat", s -> s.stems(COMBAT_DRUMS, COMBAT_BASS, COMBAT_STRINGS, COMBAT_BRASS)
        .stem(COMBAT_BRASS, INTENSITY, Curve.points(0.5, 0, 1, 1), Quantize.NEXT_BEAT)
        .stem(COMBAT_STRINGS, INTENSITY, Curve.points(0.2, 0, 0.6, 1), Quantize.NEXT_BEAT)
        .exit(Bar.of(17)).loop())
    .segment("combat-intro", s -> s.clip(COMBAT_HIT).entry(Bar.of(1)).exit(Bar.of(2)))
    .state(EXPLORE, Playlist.shuffle("explore-a", "explore-b"))
    .state(COMBAT, Playlist.of("combat"))
    .state(BOSS, Playlist.of("boss"))
    .state(VICTORY, Playlist.once("victory"))
    .transition(EXPLORE, COMBAT, t -> t.at(Quantize.NEXT_BAR).fadeOut(0.5).via("combat-intro"))
    .transition(COMBAT, EXPLORE, t -> t.at(Quantize.bars(4)).crossfade(Beats.of(4)))
    .transition(MusicState.ANY, VICTORY, t -> t.at(Quantize.NEXT_BEAT).fadeOut(0.25).startAt(Start.ENTRY))
    .transition(COMBAT, BOSS, t -> t.at(Quantize.NEXT_MARKER).startAt(Start.SAME_POSITION))
    .stinger(LEVEL_UP, LEVEL_UP_CLIP, Quantize.NEXT_BEAT).duck(-6)
    .build();

Music music = audio.music();
music.play(score, EXPLORE);                                // primes the streams, starts on its entry cue
music.state(COMBAT);                                       // on the next bar, through the intro hit
music.set(INTENSITY, threat);                              // each frame: stems follow, on the beat
music.stinger(LEVEL_UP);
music.listen((event, bar, beat) -> pulseLights(beat));     // BAR, BEAT, MARKER, TRANSITION: as heard
MusicPosition p = music.position();                        // latency-compensated
music.pauseWith(world);                                    // opt in: pauses with that world, on the beat
music.stop(Quantize.NEXT_BAR, 2.0);
```

### 3.7 A procedural engine sound driven by RPM and load

```java
static final Patch V8 = Patch.builder("v8")
    .input(RPM).input(LOAD).trigger(SHIFT)
    .graph(g -> {
        Signal rpm = g.param(RPM).smooth(0.02), load = g.param(LOAD).smooth(0.02);
        Signal firing = g.add(FiringPulses.fourStroke(8, FiringOrder.CROSS_PLANE_V8)
            .jitter(0.03f).seed(17)).rpm(rpm).hardness(load.remap(-1, 1, 0.3, 1));
        Signal exhaust = g.add(Tube.of(1.6 /* m */).damping(0.35f)).in(firing);
        Signal muffled = g.add(Filter.lowPass()).in(exhaust).cutoff(load.remap(-1, 1, 900, 5_000)).q(0.9);
        Signal intake = g.add(Filter.bandPass(1_800, 1.2)).in(g.add(Noise.pink()))
            .gain(firing.envelope(0.002, 0.01).times(load.clamp(0, 1)).times(0.4));
        Signal whine = g.add(Oscillator.sine()).frequency(rpm.times(4.0 / 60)).gain(0.05);
        Signal crackle = g.add(Crackle.of().seed(5))
            .rate(Signal.when(load.below(-0.2), rpm.remap(3_000, 7_000, 0, 12), 0));
        g.output(g.mix(muffled, intake, whine, g.add(Tube.of(0.4)).in(crackle)).gain(0.8));
    })
    .build();

static final Sound ENGINE = Sound.builder()
    .bus(VEHICLES).attenuation(OUTDOOR).priority(Priority.HIGH).doppler(1)
    .root(Patch.named("v8")).loop().build();

audio.patches().register(V8);                              // or a plugin registers it
SoundInstance engineSound = sounds.play(ENGINE, carNode);
engineSound.set(RPM, (float) car.rpm()).set(LOAD, (float) car.throttleLoad());   // each frame, primitives only
if (car.shifted()) engineSound.trigger(SHIFT);
```

### 3.8 A custom DSP node

```java
/** Reduces bit depth and sample rate: a plugin's node, usable in patches and in bus effect chains. */
public final class BitCrusher implements DspNode {
    public static final NodeType<BitCrusher> TYPE = NodeType.of("bitCrusher", BitCrusher::new,
        NodeParam.of("bits", 8, 1, 16), NodeParam.of("downsample", 1, 1, 64));

    private Param bits, downsample;
    private float[] held;
    private int[] counter;

    @Override public void prepare(DspSetup setup) {          // game thread, once: allocate everything here
        bits = setup.param("bits");
        downsample = setup.param("downsample");
        held = new float[setup.channels()];
        counter = new int[setup.channels()];
    }

    @Override public void process(DspBlock block) {          // audio thread: no allocation, no locks, no I/O
        for (int c = 0; c < block.channels(); c++) {
            float[] in = block.input(c), out = block.output(c);
            for (int i = 0; i < block.frames(); i++) {
                float steps = (float) (1 << (int) bits.at(i));   // sample-accurate: events land at their sample
                if (counter[c]-- <= 0) {
                    counter[c] = (int) downsample.at(i) - 1;
                    held[c] = Math.round(in[i] * steps) / steps; // Math.round is an exact function: allowed
                }
                out[i] = held[c];
            }
        }
    }

    @Override public void reset() { Arrays.fill(held, 0); Arrays.fill(counter, 0); }
}

audio.nodes().register(BitCrusher.TYPE);                     // warmed on the game thread, then usable by name
BusLayout.builder().bus(UI, b -> b.effect(Effect.named("bitCrusher").set("bits", 6)));
```

### 3.9 Buses, snapshots and player settings

```java
BusLayout mix = BusLayout.builder()
    .bus(MUSIC, b -> b.volume(-4))
    .bus(SFX, b -> b.effect(Compressor.of(-18, 3).attack(0.005).release(0.15)))
    .bus(FOOTSTEPS).bus(VEHICLES).bus(PIP)
    .bus(VOICE, b -> b.effect(Eq.highPass(90)))
    .bus(UI, b -> b.ignoresPause())
    .aux(REVERB, b -> b.effect(Reverb.fdn(ReverbPreset.ROOM)))
    .duck(MUSIC, VOICE, Duck.by(-9).attack(0.05).release(0.6))
    .build();
audio.mixer().layout(mix);                                           // swapped at a block boundary

static final Snapshot PAUSE_MENU = Snapshot.builder("pause").set(SFX, Bus.VOLUME, -60).set(MUSIC, Bus.LOW_PASS, 1_200)
    .fadeIn(0.2).fadeOut(0.4).priority(10).build();
SnapshotInstance paused = audio.mixer().start(PAUSE_MENU);
paused.stop();

settings.set(AudioSettings.MUSIC_VOLUME, 0.6f);                     // player layer: saved with the app's settings
settings.set(AudioSettings.MONO, true);                             // accessibility
settings.set(AudioSettings.BACKGROUND, Background.MUTE);            // when the window loses focus
```

### 3.10 Dialogue, subtitles, lip sync

```java
static final Line HALT = Line.of("game://vo/{locale}/guard_halt.ogg").speaker(GUARD).subtitle("guard.halt");

LineInstance line = sounds.dialogue().say(HALT, guardHead, Priority.HIGH);   // interrupts lower, queues equal
sounds.dialogue().listen((event, speaker, textKey, start, end) -> subtitles.show(event, speaker, textKey));
// each frame, for the face rig:
line.visemes(weights);                                       // at the heard time; or line.envelope() for a jaw
```

### 3.11 Time, many sounds, accessibility hooks

```java
sounds.timeScale(0.25f);                                     // slow motion: world sounds pitch down, dialogue not
sounds.pause(true);                                          // world paused: its sounds hold; ui() and music go on

sounds.field(RIVER, SoundField.polyline(riverPoints));       // one instance, the nearest point plays
sounds.play(RAIN).set(INTENSITY, storm);                     // beds + a Scatter of drops: one instance

int n = audio.indicators(p1, indicators);                    // visualized sound for a HUD ring
```

### 3.12 Wiring, and tools with no device

```java
public void configure(AppConfig app) {
    app.use(new Desktop(), new Rendering(), Xr.ifAvailable(), new Audio());   // optional: no device, silent
}
public void start(Engine engine) {
    Audio audio = engine.get(Audio.KEY);
    new AudioImporters(assets.paths()).register();           // .wav, .ogg, .opus, .sofa
    Sounds sounds = audio.sounds(world);
    sounds.world(new CollisionAudioWorld(collision, AUDIO_MATERIALS));   // occlusion rays (optional)
    AudioBindings bindings = audio.bindings(animators, effects);
    bindings.onEvent(LEFT_FOOT_DOWN, FOOTSTEPS_SOUND, footL).surface(SURFACE, terrain::materialAt);
    bindings.onEffect(MUZZLE_FLASH, GUNSHOT);
}

// Tools and tests, with no device and no threads:
try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(48_000).block(256))) {
    Sounds sounds = new Sounds(scene);
    AudioSync sync = new AudioSync(r, cache).add(sounds);
    sounds.listeners().device(cameraNode);
    sounds.play(ENGINE, car).set(RPM, 3000);
    sync.update();                                           // what the plugin does each frame
    float[] samples = r.render(48_000);                      // one second
}
```

**The API's rules,** in the style of animation's and effects':
- Setup objects (`Sound`, `Score`, `BusLayout`, `Snapshot`, `Attenuation`, `Line`, parameters, patches) are
  immutable and declared once; per-frame calls take primitives, look nothing up by name and allocate nothing, except
  `play`'s handle (`fire` exists for hot paths).
- Handles of ended sounds are inert (`alive()` false, setters do nothing, reads NaN), as `Effect` handles are.
- Everything audible is ramped: no setter causes a click.
- Sounds belong to a world (paused and time-scaled with it), to `ui()` (never), or are music (the plugin's).

---

## 4. What changes in existing code

- **engine-platform:** `Platform` gains `startAudio()` (on its owning thread: `SDL_InitSubSystem(SDL_INIT_AUDIO)`
  and the hints: driver from settings or the environment, stream role "Game"); `PlatformListener` gains
  `audioDeviceAdded(int device, boolean playback)`, `audioDeviceRemoved(int device)` and
  `audioDeviceFormatChanged(int device)` defaults (today these events reach `other`). After start, only SDL's audio
  stream and device functions documented as thread-safe are called from the audio thread. Without `Desktop` (a tool),
  `engine-audio` starts SDL's audio subsystem on the thread that starts it.
- **engine-xr:** optional `XR_OCULUS_audio_device_guid` and `Headset.audioOutputDevice()` (an endpoint id string,
  matched to SDL devices by name until SDL 3.6's unique ids); report session idle and loss of focus to the engine
  (`visibilityChanged`, `focusChanged`) as D127 asks, which audio's background rule uses.
- **engine-app:** the `Audio` plugin and `AudioBindings`; the "audio" settings group; BaseGame shortcuts when BaseGame
  exists; the demo's `run` task adds `-XX:+UseZGC`.
- **scene-animation:** nothing required (`Animator.node(int)` and `curve(AnimationCurve)` suffice).
- **scene-effects:** `Hash` delegates to common-math's (open question 3); bindings read its change log and burst
  arrays.
- **scene-collision / common-collision:** nothing; `CollisionAudioWorld` uses `CollisionQuery`;
  `TerrainHeights.materialAt` feeds footstep switches.
- **scene-assets:** nothing; `#stream` is a fragment the audio importers read.
- **build-logic:** nothing for math (`java.lang.Math` is already forbidden in production code); `scene-audio` and
  `engine-audio` take `truesim.allocation-free`; the `setup-vulkan` action, or the workflows, may set
  `SDL_AUDIO_DRIVER=dummy`.
- **Versions:** LWJGL 3.4.3 (engine and scene build files; SDL 3.4.x, libopus 1.6 with libopusfile, stb), Java 27.

---

## 5. Options weighed (the decisions are AU1 to AU16 above)

Each question Qn led to decision AUn. The recommended option was first; a line under each question says what was decided.

**Q1. The native output backend**
*Decided (AU1): option 1, behind an `AudioOutput` interface so OpenAL Soft or miniaudio can replace only the sink.*
1. **SDL3 audio, push mode** (recommended). Pros: already loaded by `engine-platform` through LWJGL, no new native;
   hot-plug and the system default device followed (what SteamVR relies on); format and rate conversion if needed;
   WASAPI, CoreAudio, PulseAudio and PipeWire; push mode needs no upcall. Cons: shared mode only (no WASAPI exclusive mode;
   SDL does use `IAudioClient3`'s low-latency shared mode, a review correction); no endpoint ids before SDL 3.6 to
   match Meta's headset id (names only).
2. miniaudio through Panama. Pros: exclusive mode available, endpoint ids match
   `XR_OCULUS_audio_device_guid` exactly, decoders included. Cons: not in LWJGL, so a native build per platform in
   build-logic and hand-made or jextract bindings; its callback is an upcall unless we write a small C ring buffer
   too.
3. OpenAL Soft (LWJGL). Pros: shipped; HRTF and EFX included. Cons: its own mixer model; a Java mixer would use it
   as a stream queue with its own latency; sample-accurate scheduling across sources is not its model.
4. FMOD through LWJGL. Pros: everything done, a professional authoring tool. Cons: proprietary licence and a
   per-title agreement, its data in its banks, not testable offline in the engine's way, not the engine's own DSP.

**Q2. The language of the mixer**
*Decided (AU2): option 1.*
1. **Java, one GC-free audio thread** (recommended). Pros: one language; offline rendering for tests; plugins write
   nodes in Java; the allocation checker covers it. Cons: safepoints stop it (covered by the native queue, Q4); no
   SIMD intrinsics beyond what the JIT vectorizes (the Vector API is still incubating).
2. A native mixer (miniaudio's node graph or OpenAL Soft), Java only sending commands. Pros: immune to GC. Cons: DSP
   nodes in C, plugins in C, two build systems; tests need natives.
3. Java DSP in a native thread through upcalls per block. Cons: the worst of both: the native deadline thread becomes
   a Java thread at safepoints.

**Q3. The DSP graph model**
*Decided (AU3): option 1.*
1. **Java code nodes wired by a builder (`Patch`), registered by name; data graph assets later** (recommended). Pros:
   fast to build, typed, debuggable; data can already name patches and nodes; a graph format can serialize builder
   graphs of built-in nodes later. Cons: designers need code until a format and editor exist.
2. Data graph assets now (MetaSounds-like: nodes, pins, values in a file). Pros: tools and hot reload. Cons: a format,
   a validator and an editor before the first engine sound; nothing to author them with yet.
3. A fixed set of procedural sound types with parameters (engine, wind, rain). Pros: simplest. Cons: closes the door
   plugins need.

**Q4. Queue depth and block size**
*Decided (AU4): differs. Block size and queue depth are settings, defaults 256 frames and 2 to 3 blocks; §2.3 adds the
timer and the automatic guard.*
1. **256-frame blocks, queue kept between 2 and 3 blocks (10.7 to 16 ms), a setting at the developer tier**
   (recommended). Pros: covers ZGC pauses (under 1 ms) and typical time to safepoint with margin. Cons: about 20 ms
   from call to ear on WASAPI shared mode.
2. 128 frames, 2 to 4 blocks. Pros: lower latency. Cons: more native calls; less margin; measure first.
3. Device-sized blocks. Pros: no rebuffering. Cons: block size varies by device; parameter ramps and tests become
   device-dependent.

**Q5. Codecs**
*Decided (AU5): option 1.*
1. **WAV and Ogg Vorbis (stb_vorbis) in v1; Opus (libopus, Java Ogg reader) next; FLAC in Java later** (recommended).
   Pros: no new native; Vorbis seeks exactly and is everywhere in tools; Opus at 48 kHz is the best quality per bit,
   and LWJGL's libopusfile reads its Ogg container (the draft planned a Java Ogg reader, not needed).
   Cons: two compressed formats to support.
2. Opus only (plus WAV). Pros: one codec, best compression. Cons: fewer tools export it with loop comments.
3. Vorbis and FLAC, no Opus. Pros: lossless option early. Cons: FLAC decoder in Java to write; larger files.
4. MP3 as well. Cons: no LWJGL binding (minimp3 would be a new native or a Java decoder), poor gapless looping.

**Q6. The HRTF provider**
*Decided (AU6): differs. Option 2, our own HRTF in Java, panning as default and fallback; §2.9.*
1. **Steam Audio through Panama downcalls, behind a `Spatializer` interface; built-in panning by default and as
   fallback** (recommended). Pros: Apache 2.0, C API, default HRTF and SOFA, and the same library later gives
   occlusion with transmission, reflections and pathing; downcalls only. Cons: a new native library to ship
   (prebuilt by Valve for Windows, Linux, macOS) and bind; its per-voice cost to measure.
2. Our own Java HRTF convolution from a SOFA data set. Pros: no native, deterministic offline. Cons: SOFA is HDF5
   (read in pure Java with jHDF), data set licences differ (§1.10), partitioned convolution in Java to write and
   optimize.
3. OpenAL Soft's HRTF. Cons: only through OpenAL's mixer.
4. Meta XR Audio SDK. Pros: Meta's HRTF, acoustic ray tracing tuned for Quest. Cons: proprietary, no documented
   native API found, Meta-only runtime behaviour; the target here is SteamVR.

**Q7. Voice limits and virtualization**
*Decided (AU7): option 1, every number adjustable.*
1. **64 real voices, 512 logical instances per world, 16 HRTF voices; five priorities; virtual below −60 dB with 3 dB
   hysteresis; loops keep time, one-shots that would end are killed** (recommended). Pros: FMOD and Wwise defaults
   in spirit; cheap. Cons: dense scenes may want 128; settings at the developer tier.
2. 128 real, 2048 logical. Pros: headroom. Cons: double the mixing cost on Quest-class budgets.
3. No virtualization, a hard cap with stealing only. Pros: simplest. Cons: loops restart audibly; big worlds waste
   voices.

**Q8. The music timing source**
*Decided (AU8): option 1.*
1. **The audio frame counter, with `Music` deciding on the game thread and scheduling exact frames a fixed latency
   ahead** (recommended). Pros: sample-accurate (Quartz, `PlayScheduled`), deterministic, testable with a fake clock,
   the state machine stays in `scene-audio`. Cons: a request takes effect no sooner than the scheduling latency (about
   15 ms) plus the wait for the sync point.
2. The state machine on the audio thread. Pros: reacts within a block. Cons: game logic on the audio thread, harder to
   test, more state crossing threads.
3. The game clock (frames). Cons: jitter of a frame (11 ms in VR, more on hitches): beats drift audibly.

**Q9. Asset format**
*Decided (AU9): option 1.*
1. **Source files imported at runtime by extension; definitions built in code; a file format with the serialization
   repository** (recommended). Pros: D22; no cooker; same as effects and materials. Cons: designers need code for
   sound definitions until then.
2. Our own bank files (cooked definitions and clips). Pros: fast loading, designer-authored. Cons: a cooker and a tool
   first.
3. Middleware banks (FMOD or Wwise). Cons: Q1 option 4's licence and dependence.

**Q10. Determinism**
*Decided (AU10): option 1.*
1. **Offline renders bit-identical for the same commands; realtime not reproducible; container choices hashed from
   the instance's seed** (recommended). Pros: golden tests, identical choices on server and clients. Cons: a replay
   sounds like the original only up to the clock jitter (inaudible).
2. Fully deterministic realtime (commands stamped from game ticks with a fixed large latency). Pros: replays
   bit-exact. Cons: 50 ms or more added latency.
3. None. Cons: no golden tests, clients pick different footstep samples.

**Q11. Where game-facing state lives**
*Decided (AU11): option 1.*
1. **A `Sounds` store per scene in `scene-audio`, with a change log, like `Effects`** (recommended). Pros: servers,
   replication and tests without the engine; one pattern across the library. Cons: one more hop (store, then sync)
   than calling the renderer directly.
2. Only an engine API (calls go straight to the renderer's command ring). Pros: one hop. Cons: no replication or
   server use; game code depends on the engine.

**Q12. Several listeners**
*Decided (AU12): differs. Multi-listener: rendering listeners with their own mixes, routed to the device or re-emitted
in the world, with the nearest-listener rule for device voices; §2.10.*
1. **Each voice spatialized for its nearest listener, attenuated by the nearest distance** (recommended). Pros: one
   mix, split screen works, cheap (FMOD's approach). Cons: a sound between two players pans for one of them.
2. A mix per listener (each player's own headphones). Pros: correct per player. Cons: N mixes; only useful with one
   output per player, which local multiplayer rarely has.

**Q13. Occlusion**
*Decided (AU13): differs. Occlusion per sound (none or rays), rays through `AudioWorld` over physics or the ray
tracing structures; Steam Audio's simulation dropped; propagation later; §2.11.*
1. **The game's `AudioWorld` rays, budgeted and smoothed, with material transmission; Steam Audio's simulation later
   as another implementation** (recommended). Pros: scene-audio free of collision; works today with common-collision.
   Cons: rays only (no diffraction around corners until rooms and portals or pathing).
2. Steam Audio's own geometry and simulation from the start. Pros: diffraction-like pathing, reflections. Cons:
   geometry export from scene-collision and its streaming first; more natives in the first steps.
3. None in v1. Cons: sounds through walls.

**Q14. Output sample rate**
*Decided (AU14): option 1.*
1. **The device's native rate (48 kHz almost always), assets resampled per voice** (recommended). Pros: SDL does no
   second resampling; Opus is native 48 kHz. Cons: clips at 44.1 kHz always go through the resampler (cheap).
2. A fixed 48 kHz with SDL converting when the device differs. Pros: one rate for all DSP and tests. Cons: SDL's
   resampler adds latency and cost on 44.1 kHz devices.

**Q15. Where the device thread's waiting comes from**
*Decided (AU15): option 1 (see the reviewer note: a high-resolution timer on Windows).*
1. **Poll the queue fill with a short park (about 1 ms) between blocks** (recommended to start; measured). Pros:
   simple, no upcall. Cons: wake-up jitter of the OS timer; a bit of CPU.
2. A small native helper (a tiny audio native of our own) signalling a semaphore the Java thread
   waits on through a downcall. Pros: precise wake-ups. Cons: a native of our own.

**Q16. Bindings location**
*Decided (AU16): option 1, in engine-app's `Audio` plugin.*
1. **engine-app's `AudioBindings`, next to `EffectBindings`** (recommended). Pros: no dependency between scene
   modules. Cons: games without the engine wire listeners by hand (a few lines).
2. A small scene module depending on scene-audio, scene-animation and scene-effects. Pros: usable by tools and servers.
   Cons: one more module for little code.

---

## 6. Steps (a PR each, tested)

Each leaves every repository building and releasable; scene steps are device-free and come before the engine steps
that use them; engine steps take the scene release with their types. "Engine" means `engine-audio` unless named.

| # | Repo | What | Tested with |
|---|---|---|---|
| 1 | common-math, scene | The PCG `Hash` moves to common-math; scene-effects delegates (no behaviour change) | The existing effects hash vectors CSV passes unchanged against the moved class |
| 2 | scene | `scene-audio` data: `AudioClip` (PCM off-heap, loops, markers), WAV reader and importer (`smpl`, `cue`), `Sound` and its container nodes, `Attenuation`, `SoundParameter`, `SwitchGroup`, `Bus`/`BusLayout`/`Snapshot` as data with effect records by name; validation | WAV variants (8 to 32-bit, float, 1 to 8 channels, odd chunks) against reference samples; `ref()` equality; every validation error with its suggestion |
| 3 | scene | `Sounds` store: slots, generations, inert handles, `play` and `fire`, change log on the scene's `ChangeClock`, ticks, pause and time scale with `TimeScaling`, seeded container evaluation, concurrency at start, `maxStartDelay`; `Listeners` (device and rendering, routes, graph validation: self edges, depth); `ReverbZones`; the UI store (no scene) | Change-log order and overflow (false, rebuild from `forEach`); same seed, same clips on any thread; no-repeat windows; reused slots; listener graph cycles found and marked; zero allocation per frame |
| 4 | scene | `Music` over `AudioClock`: `Score`, states, quantized transitions, transition segments, stems by parameter, stingers, `pauseWith` | A fake clock: transitions scheduled at the computed bar frame; pre-entry tails; a destination not primed waits for the next sync point; determinism across runs |
| 5 | engine | `engine-audio` core, offline only: `AudioRenderer`, rings, PCM voices, cubic resampler, gain ramps, stereo pan, the bus graph from a `BusLayout` (volume, mute, sends), limiter, `OfflineOutput`, the audio clock, `Fft` | Golden renders (a click at an exact frame, a pitched loop, two buses); null tests; ramp continuity; FFT against a reference DFT; `assertAllocationFree` on `render`; bit-identical across runs |
| 6 | engine, platform, app | `SdlOutput` (push, queue marks, high-resolution timer park, kept downcall handles, MMCSS, warm-up), `Platform.startAudio` and audio device events, default-device migration and chosen-device reopen, `NullOutput`, `AudioSync` (2D and positions in doubles), the `Audio` plugin with settings (volumes saved, background rule, block and queue), the collector check; demo: rocks that knock when they land | CI on SDL's dummy driver and an end-to-end disk-driver test; latency report and 10 minutes without underrun on the dev machine; GC stress 30 s under ZGC with 0 underruns; headphones unplugged keeps playing; park oversleep measured on Windows with the window minimised |
| 7 | engine | 3D: attenuation, spread, distance low-pass, Doppler (relative velocities in doubles), panning and VBAP, device listeners with nearest selection and hysteresis, virtual voices keeping time, stealing with fades, priorities, time scale as pitch, pause | Levels against the curve; Doppler pitch by zero crossings; 1,000 instances with 64 real (loudest real, loops resume in place); a source 7,000 km out pans and Dopplers like one at the origin; split screen with a sound crossing between players has no step |
| 8 | scene, engine | Streaming: `scene-audio-codecs` with stb_vorbis (importer, loop comments, decoder), the streaming thread, rings, priming, seek, stitched loops, `#stream`, starvation handling and stream priorities; intro, loop and outro segments | Seams without discontinuity for memory and streamed loops; seeks exact to the sample; an induced starvation fades, is counted and resumes at the right position; decoding only on the streaming thread |
| 9 | engine | Mixer: bus effect chains (biquad EQ, compressor, limiter, delay), ducking, snapshots with intensity and priority, meters, the accessibility stage (mono, balance, dynamic range presets); loudness at import (scene-audio-codecs) | Filter responses against analytic curves; ducking timing; two snapshots blended; EBU test signals within 0.1 LU; mono downmix null test |
| 10 | engine | Reverb: the FDN, `ReverbZones` in the mix, aux sends by emitter zone, listener zone blending; zones from cell loaders | RT60 from an impulse response against the preset; continuous zone fades; zones attach and detach with cells |
| 11 | scene, engine | Music rendering: `MusicRenderer` playing `Music`'s schedule, beat events at heard time, latency-compensated position; Opus through libopusfile | Offline renders land transitions on the bar sample; stems aligned for 10 minutes; Opus seek and pre-skip against reference decodes |
| 12 | engine | DSP layer: `DspNode`, `Param` events and ramps, `Patch`, node and patch registries with warm-up, built-in nodes, the V8 patch and `RpmBank`; demo: a car following the fly camera's speed | Oscillator spectra (polyBLEP aliasing bound); events at the exact sample; the V8's 200 Hz firing peak at 3,000 rpm; a custom node from a test project; allocation-free |
| 13 | engine | Rendering listeners: `ListenerMix`, lazy rendering, topological order, one-block cycle delay, self guard, feedback gain and howl detector, `Play.input()`, routes to the world, a device bus and a tap; demo: a camera heard from a TV | A cycle delays by exactly one block; a listener's own re-emission absent from its mix; two facing camera-TV pairs decay instead of howling; a TV turned off stops its listener's rendering (cost counter); channel switch cross-fades |
| 14 | app | `AudioBindings`: animation events at joints with surface switches, curves to parameters, effect starts and bursts to sounds; demo: the Fox's footsteps on terrain materials | A footstep per foot-down event of the walk and run; the switch follows `materialAt`; an effect start plays its sound once |
| 15 | scene, engine | Dialogue: `Line`, `Dialogue` rules (priority, interrupt, queue, expiry, cooldowns), WebVTT cues, viseme sidecars, envelope, captions and `indicators` | Cues at heard time in offline renders; interruption fades and reports; visemes interpolated at the heard time; indicators' directions for sources around the listener |
| 16 | engine | Occlusion: `AudioWorld`, `CollisionAudioWorld`, the ray budget on the job system, smoothing, material transmission per band, obstruction versus occlusion, partial occlusion | A wall lowers level and cutoff as configured; the budget holds with 500 emitters; results independent of thread count |
| 17 | scene, engine | HRTF: `HrtfSet`, the build-time SOFA converter (a tool reading HDF5 at build time only) and the `.hrtf` importer, preparation (resample, minimum phase and ITD, triangulation), the default set, partitioned FFT convolution with interpolation, cross-fades and near field; the cap with panning fallback | ITD and ILD of rendered impulses against the set at measured directions; no click while a source circles the head; cap fallback; CPU per block within 0.6 ms for 16 voices; the listening checklist |
| 18 | engine, xr | VR and ambisonics: the head as listener, the ambisonics bus with its SH-domain binaural decode for voices past the cap, `XR_OCULUS_audio_device_guid` matched by name where offered, headset idle and focus reported | Ambisonic directions; CPU flat in the number of ambisonic voices; Quest 3 through SteamVR plays on the headset (manual, `TRUESIM_XR=true`); idle lowers or mutes per the background rule |
| 19 | engine | Many sounds: `Scatter`, sound fields (points, polyline, area) with nearest-k voices and a bed; the ray-tracing `AudioWorld` | A crowd of 5,000 points costs k voices; a listener walking along a river hears a continuous source; ray-traced occlusion matches physics on a test scene |
| 20 | both | `AudioBenchmark` (voices, HRTF voices, rendering listeners, streams, patches; CPU per block percentiles), README tables dated, debug overlay (real and virtual voices, buses, meters, listener graph) | Numbers on the Ryzen 9 9950X3D; under 1.5 ms per block for the heavy scene |
| later | both | Rooms and portals as an `AUDIO` layer; diffraction and reflections from the ray tracing structures; convolution reverb; granular engines; time stretch; FLAC in Java; a data graph format and editor for patches; streaming encoded bytes from disk; a cooker; a second output device per player | Each with its own design note |

---

## 7. Review (2026-11-02)

A high-effort review checked the draft against the engine (`origin/main`: `engine-app`, `engine-platform`,
`engine-settings`, `engine-xr`, `docs/application-api.md`, `docs/pitfalls.md`, build-logic's conventions and CI),
the scene library (`Effects` and `Effect` as built, scene-animation, scene-assets, scene-world, scene-collision,
common-math, common-state) and the external sources (SDL3's headers and WASAPI source, LWJGL's release notes and
templates, the HRTF data sets' licences, the JEPs). AU1 to AU16 are unchanged; reviewer notes sit under the decision
table.

**What changed in this document:**
- **Rewritten for the decisions:** every Steam Audio assumption removed (HRTF, ambisonics, occlusion simulation, VR,
  steps, budgets); our own HRTF designed (§2.9: data, preparation, interpolation, partitioned FFT convolution, near
  field, SIMD, cap and ambisonics fallback, cost); multi-listener designed (§2.10: kinds, routes, the graph,
  guards, lazy rendering, cost, latency); occlusion per sound with physics and ray-tracing implementations (§2.11);
  adjustable block and queue (§2.3).
- **Wrong claims fixed:**
  - SDL's WASAPI backend already uses MMCSS "Pro Audio" and `IAudioClient3` low-latency shared mode (the draft said
    shared mode only, no low-latency path, and left MMCSS to be checked);
  - LWJGL's opus module binds libopusfile, so no Java Ogg reader or seek table is needed for Opus;
  - LWJGL is 3.4.3 (SDL 3.4.x); SDL has no endpoint id before 3.6 (unreleased), so headset matching is by name;
  - `SDL_GetAudioStreamQueued` counts input bytes, correct for fill only because the stream's format is the
    device's (now stated);
  - a device opened by id becomes a silent zombie when removed: the output must reopen (the draft assumed SDL moves
    it);
  - engine-platform does not init `SDL_INIT_AUDIO` and maps no audio events; the platform's owner-thread rule means
    the audio subsystem starts through `Platform` (§4);
  - decision numbers: import by extension is D47 (not D22), optional plugins D125 (not D31 to D35), D19 concerns
    asset callbacks, D128 has no time scale, D131 already says audio listens from every player's camera;
  - not built yet and no longer presented as existing: phases `FIXED`/`ANIMATE`/`LATE`, BaseGame and its shortcuts,
    world clocks, components (listeners and reverb zones are now stores), `EffectBindings`;
  - `java.lang.Math` is already forbidden in all production code (only exact functions pass), so no new build rule;
  - the effects store as built: `new Sounds(scene)` with no world argument, `changesSince` returning false on
    overflow, flags rather than an enum, `fire` returning an id like `Effects.burst`, ids rather than handles in
    listeners;
  - the hashed random lives in scene-effects, which scene-audio must not depend on (open question 3);
  - `Thread.MAX_PRIORITY` is weak on Windows and ignored on Linux (now said; MMCSS is what matters);
  - `#stream` is a new fragment, not an existing one.
- **New design text for gaps:** dialogue with subtitles and lip-sync hooks (§2.15); pause, time scale and UI, and
  music's relation to pause (§2.16, §2.12); device migration, zombie reopen, format changes, focus loss, saved
  volumes, headphones setting and accessibility with visualized sound (§2.17); streaming starvation (§2.14); very
  many sounds (§2.18); Doppler and large worlds (§2.8); voice chat hooks (§2.20); CI with the dummy and disk drivers
  (§2.24); the collector, safepoints and JIT warm-up (§2.22); the Windows timer behind AU15 (§2.3).
- **Steps re-cut** to 20 PR-sized ones in buildable order: the hash move first, `Music` logic before rendering,
  device output before 3D, rendering listeners after the DSP layer they reuse, HRTF after occlusion with VR and
  ambisonics after it.

**Risks that remain:**
- **Timer precision on Windows** decides whether the 2-block default holds; the high-resolution waitable timer is the
  plan, measured in step 6 with the window minimised.
- **HRTF quality** of our own implementation (externalization, front/back confusion) is a listening matter, not only
  numbers; step 17 has a checklist, and Steam Audio stays the documented fallback.
- **HRTF and FFT speed in scalar Java** is an estimate (2 Gflop/s); if C2 does not vectorize the FFT's butterflies,
  the 16-voice budget may need 12 voices or the cap's update spreading.
- **G1 users** get a deeper queue (21 ms) and more latency; a game that keeps G1 for other reasons pays it.
- **Device rate changes** rebuild DSP state on the game thread; plugin nodes that allocate in `prepare` make the
  switch take a frame or two.
- **Rendering listener feedback** can still sound harsh before the howl detector reacts (8 blocks, 43 ms).
- **SteamVR's device switching** was checked from its settings and reports, not tested; Quest Link and Virtual
  Desktop appear as their own endpoints and follow the same default-device path.
- **No components** yet: listeners and reverb zones as stores will need an adapter when D46 lands.
- **Effects' change-log consumers** in engine-app do not exist yet (no `EffectBindings`), so `AudioBindings` is the
  first engine consumer of a scene store's log and sets the pattern.

**Decided with the user after the review:**
1. **Default HRTF set:** SADIE II KU100 at 48 kHz (Apache 2.0).
2. **SOFA files:** converted at build time to our own `.hrtf` format by a converter tool; no HDF5 reader at runtime
   (differs from the recommendation: players can't drop in raw SOFA files, but converted sets load like any asset).
3. **Hashed random:** `Hash` moves to common-math; scene-effects delegates.
4. **Time scale:** world sounds follow it in pitch by default, with a per-sound opt-out; dialogue, 2D and UI sounds
   don't follow by default.
5. **Focus and visibility:** a setting, default `PLAY`, with `LOWER` and `MUTE`.
6. **Device rate changes:** restart at the new rate.
7. **SDL audio:** started through `Platform.startAudio()` on the platform thread.
8. **Rendering listeners:** mono by default, stereo on request.
9. **Collector:** recommend generational ZGC (set in the app launcher); under other collectors the queue deepens
   automatically. The audio thread is garbage-free, but stop-the-world pauses freeze every Java thread, so the
   collector still matters.

The reviewer's refinements are adopted with the decisions they refine: the high-resolution waitable timer for AU15's
polling, a `Spatializer` interface keeping Steam Audio as a documented fallback for AU6, reduced mixes for rendering
listeners (AU12), and block-size changes applied at an output restart with goldens pinned to 256 frames (AU4).

---

## 8. As built

Details steps 1 to 7 settled where sections 2 to 6 left them open, and where the code differs from the text above
(marked **deviation**). Built on local branches `audio` of common-math, scene and engine; not released yet.

### Step 1: the hashed random moves (common-math, scene)

- `com.truesim.common.math.random.Hash` (`pcg`, `combine`, `unit`, `random`) in common-math, with the effects' vectors
  CSV copied unchanged into its tests (26 vectors pass). scene-effects' `Hash` keeps its name and delegates, so its
  simulator, the Slang kernel's vectors and the engine's particle tests are untouched.

### Step 2: `scene-audio` data (scene)

- **Clips:** `AudioClip` holds 16-bit or float PCM in a shared arena (closed by the cache's default `unload`), loop
  points, markers and the loudness fields (NaN and gain 1 until step 9 measures them). `streamed()` is always false:
  streaming is step 8. `WavReader` reads RIFF WAVE: PCM 8 (as 16-bit), 16, 24 and 32 bits, float 32 and 64, plain or
  `WAVE_FORMAT_EXTENSIBLE`, 1 to 8 channels, the first `smpl` loop (end inclusive in the file), `cue` points named by
  `LIST adtl labl` (`cue<id>` without), odd chunks with their pad byte, and a `data` chunk whose size was never
  patched. `WavImporter.INSTANCE` registers for `.wav`; the `AudioImporters` of `scene-audio-codecs` come with step 8.
- **Sounds:** the nodes are records (`Play`, `Random`, `Sequence`, `Switch`, `Layer`, `Blend`, `Segments`, `Scatter`,
  `Patch`) sharing a `Shaping` (random volume and pitch, delay, volume and pitch curves by parameter). `Sequence`
  concatenates (each child starts as the one before ends). `Random`'s component is `shuffled` (a record accessor named
  `shuffle` would clash with the builder method: pitfalls, Java). `Sound` resolves its defaults where the decisions
  say so: time scaling none and Doppler 0 for 2D sounds and the UI, voice and music buses, else pitch and 1
  (decision 4). Release fade 50 ms, max start delay 100 ms.
- **Deviation:** effect records are `AudioEffect.named(name).set(field, value)`, not `Effect`, which would clash with
  scene-effects' handle in a game importing both; the factories of §3 (`Eq`, `Compressor`, `Reverb`, `Distortion`)
  come with their DSP in step 9. `AudioEffectTypes.builtIn()` names the built-in nodes and their fields for
  validation ("did you mean"); plugins add theirs.
- **Attenuation:** `Curve.INVERSE` is min / d rolled off to reach 0 at the maximum (so culling there is silent), and
  `Curve.LINEAR`; a points curve is gain by metres. The low-pass interpolates in octaves from near to far; the spread
  is the part of the field a source of the given radius fills (`spread(radius, least)`); the cone is data only (the
  renderer needs the emitter's orientation, step 16's occlusion work or later).
- **Buses:** `Bus` is a record by name, parent and aux flag, so a game's `Bus.of("sfx")` is `Bus.SFX`; the standard
  master, sfx, music, voice, ui and ambience buses are constants and `BusLayout.DEFAULT`. A layout orders its buses
  feeders first, the master last, and reports send loops, unknown targets and effects. `Snapshot` targets are
  `Bus.VOLUME`, `Bus.LOW_PASS` and `Bus.HIGH_PASS`, checked against a layout with `check`.

### Step 3: the stores (scene)

- **`Sounds`** follows `Effects` as built: slots and generations, `play` (a `SoundInstance`) and `fire` (an id), the
  scene's clock and owner check, ticks in microseconds, flags `ADDED`, `STOPPED`, `REMOVED`, `PARAMETERS` (also volume
  and pitch), `TIME`, `MOVED`, `SEED`, `SWITCHES`, `INPUT`, and `changesSince` returning false after an overflow.
- **Plans:** each instance's voices are planned from its seed once, at the first `advance` or read after it was
  played, in the order played, so switches set right after `play` count; later switch changes do not re-plan. Each
  sound is flattened once per store (its nodes numbered in walk order, each draw salted by its node), and `Random`
  keeps its no-repeat window and shuffle round per store. Planning allocates nothing after a sound's first play.
- **Ending:** the store ends an instance when the engine reports its voices ended (`ended`), when killed, after a stop
  fade (without an outro), or at its max duration; it does not know clip lengths, so one-shots end by the engine's
  report (a server's store keeps them until killed). The max start delay is the sync's (step 6), through `drop`.
- **Concurrency at start:** a group at its cap kills the instance its rule picks (the engine fades it over 10 ms), or
  with `REJECT_NEW` (and within a retrigger time) the play is rejected: `fire` returns 0 and the handle is inert.
  `QUIETEST` and `FARTHEST` measure from the nearest device listener.
- **Listeners:** `Listeners` (device and rendering, ids with generations) and the listener graph, rebuilt each
  `advance`: an edge M → L for each sound playing M's mix within L's reach and on a bus L hears; a depth-first search
  marks edges closing cycles as delayed and the reverse finishing order is the rendering order; edges past a chain of
  4 are cut. `ReverbZones`: boxes (oriented by their node) and spheres with a fade distance, `weight` and
  `strongest`. `Sounds.ui()` has no scene and refuses places, pause and time scale.
- **Deviation:** instances on nodes have no offset (effects have one): place a child node instead.

### Step 4: music (scene)

- `Music` writes frame-stamped commands (`START` from an offset, `STOP` with a fade, `GAIN` with a ramp, `PAUSE`,
  `RESUME`, `STINGER` with its duck) for the music renderer of step 11; nothing plays them yet. Bars, beats, markers and
  transitions reach listeners at heard time from the clock, on the game thread.
- A segment has one tempo (a score's, or its own); positions are bars and beats from its clip's start, so an entry at
  `Bar.of(1, 3)` leaves two beats of pickup. The first segment starts with its pickup from the schedule point; a
  destination's pickup plays before the sync point when there is room, else it starts late into the clip.
- `state()` reports the requested state once its transition is scheduled. The transition without a rule is on the
  next bar with a one-beat cross-fade at the entry. Priming covers the destination's first segment and the transition
  segment, not every reachable state. `END` needs the clip's length from `MusicClips` (the exit cue while unknown).
- After a gap longer than whole segments (a hitch, a test's jump), segments that would be over are skipped, keeping
  time; segments shorter than the half-second look-ahead are scheduled several an update.
- A pause shifts the timeline after it by its length; the renderer has to do the same with what it holds (step 11).

### Step 5: the offline renderer (engine)

- `AudioRenderer` takes commands through `commands()` (a single-producer ring of 32,768 fixed records: opcode, slot,
  frame, tag, eight doubles, two references) and returns events (`ENDED`) through `poll`; `render(out, offset,
  frames)` takes any length (whole blocks are rendered, the rest kept). Voices are slots the producer picks first fit.
- Voices: Catmull-Rom cubic Hermite resampling at clip rate / output rate × pitch, gain ramps of 64 samples at least,
  an envelope for stops, kills (10 ms), pauses (10 ms) and starts from an offset (20 ms), equal-power pan (a balance
  for stereo clips in 2D), loops across the clip's loop region.
- The bus graph has volume, mute, sends (pre or post) and parents, ramped per block; effect chains and ducks are step
  9. A voice on a bus the layout lacks plays into the master. A layout swaps at a block boundary.
- **Deviation:** the limiter is a sample-peak look-ahead limiter (1.5 ms, −1 dBFS, the gain falling at most 1/72 a
  sample so it has reached the need when the peak comes out), not true-peak: inter-sample overs of up to about 0.7 dB
  stay inside the margin. Its delay is compensated: a voice stamped at output frame F starts 72 mix frames early, so
  it is heard at sample F.
- `RenderClock` publishes frames rendered, the time and the queue under a seqlock; the schedule point is rendered
  plus the scheduling latency. `Fft` is a radix-2 complex transform with a real transform through it (the split
  trick); it matches a double DFT to 1e-4 √N for N = 4 to 2,048.
- Goldens (`golden_click`, `golden_pitched_loop`, `golden_two_buses`: float WAVs of 9,984 frames at 256-frame blocks)
  compare bit for bit; `-Ptruesim.audio.goldens=write` regenerates them.

### Step 6: the device, the sync and the plugin (engine, platform, app)

- **Deviation:** the `Audio` plugin lives in engine-audio (as `EffectsPlugin` lives in engine-scene), since
  engine-app depends on nothing of the scene or SDL. It needs `audio.assets(cache)` before `ui()` and `sounds(scene)`,
  and advances its stores by the frame's delta before syncing them (as `EffectsPlugin` does). `music()` comes with its
  renderer (step 11), `bindings()` with step 14. Device listeners are the game's to add (no API gives players' views
  yet, D131).
- `Platform.startAudio(driver)` starts SDL's audio on the platform's thread (hints: driver, stream role "Game");
  `PlatformListener` has `audioDeviceAdded`, `audioDeviceRemoved` and `audioDeviceFormatChanged`, forwarded by
  `Desktop`. Without a desktop, `SdlOutput.startSdlAudio` starts it on the caller's thread.
- `SdlOutput` opens the device in its own format (float32), asks `SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES` for a block,
  calls `SDL_PutAudioStreamData` and `SDL_GetAudioStreamQueued` through kept downcall handles, renders 200 blocks
  before resuming the device, prefills the queue to its low mark before resuming (else the device's first pulls play
  silence), registers MMCSS "Pro Audio" and sleeps on a high-resolution waitable timer on Windows.
- **Deviation:** the queue's low mark is at least the device's buffer plus a block. SDL's device thread takes a
  whole device buffer from the stream at once: 1,024 frames on the dummy driver and on this machine's WASAPI device,
  which ignored the sample-frames hint. With the design's 2 blocks (512 frames) the guard raised the mark to 5 blocks
  within 0.1 s on the dummy driver; with the floor it stays at 2 with no underrun.
- **Deviation:** the scheduling latency is the limiter's look-ahead plus 4 blocks (22.8 ms at the defaults), not the
  queue's high mark: frames rendered are already the queue ahead of the ear, so adding it counted it twice.
- An underrun is counted when the queue is empty and more time passed since the last push than that push and the
  device's buffer covered (an empty queue alone happens whenever the device takes a buffer the size of the queue).
  Two within a minute, or an oversleep 99.9th percentile over half a block, raise the low mark a block (to 6).
- A chosen device going away reopens the default device in the renderer's format (SDL converts if it differs). A
  format change is logged; restarting at the new rate (decision 6) is not built.
- `AudioSync` follows each store's log (rebuilding after an overflow), acquires an instance's clips, starts its voices
  when they are loaded (a sequence's next voice from the one before's start and length), drops and counts instances
  whose clips miss their max start delay, sends gains, pitches (with the time scale), pauses and stops (an outro
  starts as the loop fades over 30 ms; `Release` kinds other than that are step 8), frees slots on `ENDED` and ends
  instances whose voices all ended or failed to load. Inputs, patches and scatters are skipped (their steps).
- The collector check sets the low mark to 4 blocks without ZGC; the demo's `run` task uses `-XX:+UseZGC`.
- Settings group "audio": the five player volumes, `audio.device` and `audio.background` (`LOWER` is −12 dB) at the
  basic tier; `audio.block`, `audio.queue` (0 automatic), `audio.voices` and `audio.driver` hidden.
- Demo: `KnockingRocks` drops six rocks ahead of the camera; each landing faster than 1 m/s fires a knock made in code
  (a thump, a body and a click from hashed noise) at the landing place, louder for harder landings, heard from the
  camera. A 12 s run on this machine: 54 knocks, 0 underruns.

### Step 7: 3D (engine)

- `AudioSync` picks each placed instance's nearest device listener (keeping its own unless another is 10 % nearer),
  computes the listener-relative position in doubles and the Doppler ratio from the change of distance over the
  frame (c / (c + radial speed), clamped to [0.5, 2], scaled by the sound's amount; none after a jump over 50 m or a
  change of listener), and sends them with the attenuation each frame.
- **Deviation:** positions go as `SPATIAL` commands each frame, not through a triple-buffered snapshot interpolated
  over the frame; the renderer ramps gain and pan over a block (50 ms after a change of listener) and the Doppler
  ratio over 50 ms. Placed voices are mixed to mono, attenuated, low-passed by a one-pole filter at the attenuation's
  cutoff, and panned: equal power by the sine of the azimuth in stereo (front and back alike: the high shelf behind
  is not built), pairwise VBAP on the ring of quad, 5.1 and 7.1 (LFE left out), with the spread blending towards an
  even spread.
- Every 4 blocks the renderer chooses the real voices: audibility is the target gain, envelope target, attenuation and
  bus gain times the priority's weight (1/16 to 16); below −60 dB virtual, back above −57 dB; the most audible within
  the budget (`commands().budget(n)`, 64 by default; a quickselect, no allocation), the highest priority always but
  counted in the budget. Going virtual fades over 10 ms, then the voice keeps time (a loop wraps) or ends (`KILL`,
  and `KILL_IF_FINITE` for one-shots); a stopping voice going virtual ends at once; coming back fades in over 20 ms
  where it would be. Paused voices go virtual and hold.
- The renderer's slots are the logical voices (1,024 by default); the 512 logical per world of AU7 is not enforced
  per store.

### Step 8: streaming (scene, engine)

- `scene-audio-codecs`: `Vorbis` reads Ogg Vorbis through stb_vorbis (LWJGL's JNI calls into a decoder's own float
  buffer: no wrapping per call), with the loop comments `LOOPSTART` and `LOOPLENGTH` or `LOOPEND`; a clip streams when
  longer than 10 s or over 1 MB decoded, or when its path ends in `#stream` (`#decode` keeps it whole).
  `AudioImporters(paths).register()` adds `.wav` and `.ogg`. The encoded bytes live off-heap, freed by a cleaner once
  the clip is unreachable; every stream opens its own stb_vorbis state on them. Decoders seek exactly (checked against
  a whole decode at four positions).
- `AudioClip` reads through `ClipDecoder`s (`open()`): a streamed clip's opener decodes; a decoded clip reads its
  memory, so any clip can stream (the tests stream memory clips through a decoder that records its thread and stalls).
- **Deviation:** stream priorities and the 32-stream cap are not built: every streamed voice gets a ring on the one
  streaming thread, which wakes every 2 ms. A ring is 1 s; priming is half a second (or the whole clip), and the sync
  starts the voice once primed, counting the wait against the max start delay.
- A starved voice holds its last frame fading out over 5 ms, keeps time, asks the ring to start again 0.1 s ahead of
  where it is (again if it passes that point first), and fades back in over 5 ms once 50 ms are there; each starvation
  is counted (`renderer.starvations()`). The design's "three in a second raise the ring to 2 s" is not built.
- **Deviation:** a starvation is not yet an event to the game (only counted).
- Loops are stitched by the ring (the decoder sent back to the loop's start at its end), so the voice reads straight
  on. Segments end as their `Release` says: `AT_LOOP_END` stops the loop sample-exactly at its next pass end and starts
  the outro there; `AT_MARKER` at the loop region's next marker; `crossfade(s)` now, fading the loop over s. An
  explicit `stop(seconds)` still plays the outro (the sync cannot tell it from `stop()`; the store knows).
- Fixed on the way: the renderer counted a voice whose stop was still to come as silent and ended it at the next
  choice; a fade to come or under way now counts as heard.

### Step 9: the mixer (engine, scene)

- Bus chains run the built-in effects made from `AudioEffect` records (`BusEffects`): RBJ biquads (`eq.lowPass`,
  `eq.highPass`, `eq.bandPass` as low and high edges, `eq.peak`), a feed-forward `compressor` (peak envelope, gain
  every 16 frames, ramped), the look-ahead `limiter` (its delay not compensated on a bus) and a feedback `delay`.
  Responses match the cookbook's analytic curves within 0.005 from 125 Hz to 8 kHz.
- Ducks follow the sidechain bus's peak of the block before (active above about −50 dBFS), with the duck's attack and
  release as one-pole time constants: one block late whatever the bus order (**deviation:** a threshold, not a level
  follower: the depth is all or nothing, smoothed).
- `Mixer` (game thread) holds the layout, the player volumes and the snapshots: each fades in and out, and those
  playing blend in priority order, each moving a bus's volume (dB from 0) or filter cutoff (from off) towards its
  target by its intensity times its fade; the result times the player's volume is each bus's extra gain, and the
  cutoffs the bus's snapshot low-pass and high-pass (biquads, retuned when they change). The `Audio` plugin's volumes
  and background rule go through it; `audio.mixer()`.
- Meters: peak and RMS per bus, post-fader, published every 1/30 s (`renderer.meterPeak(bus)`, `meterRms`).
- The master's accessibility stage before the limiter: mono (the channels' mean at −3 dB each), balance (equal power,
  unity in the middle), dynamic range presets `HOME` (2:1 above −24 dBFS, +3 dB) and `NIGHT` (4:1 above −30, +9 dB).
  Settings `audio.mono`, `audio.balance`, `audio.dynamicRange`.
- Loudness (`scene-audio`'s `Loudness`, BS.1770-4: K-weighting designed for any rate as libebur128 does, 400 ms
  blocks overlapping 75 %, channel weights, absolute and relative gates) is measured at import (WAV, and Ogg: a
  streamed one by decoding once) and the gain to −23 LUFS rides on each voice. **Deviation:** no per-bus target
  offsets for music and dialogue yet; sine tests read their level within 0.1 LU, the gates and the surround weights
  are checked against analytic values, not the EBU 3341 files (not fetched).

### Step 10: reverb (engine)

- `reverb` is a feedback delay network of 8 lines (29.7 to 79.3 ms at size 1, scaled by `size`) mixed by a Hadamard
  matrix, each line's gain 10^(−3 length / (decay × rate)), a one-pole low-pass per line for damping, a 12 ms
  pre-delay, stereo out from two decorrelated sums. Presets: room 0.6 s, hall 2.2 s, cave 4 s, small room 0.3 s.
  Undamped, the decay measured from impulse responses (Schroeder integration, −5 to −25 dB, times 3) is within 15 %
  of the preset's; damping shortens the broadband decay (the highs die first).
- **Deviation:** one aux reverb, `AudioSync.REVERB` (`Bus.aux("reverb")`), not one per zone: each placed voice sends to
  it post-fader at the attenuation's `reverbSend` by distance times how much its emitter is inside its strongest
  zone (everything sends where the world has no zones), and the listener's strongest zone blends its decay and
  damping over the aux's own reverb by the zone's weight (sent when they move by 1 %, the lines unchanged: a zone's
  size is not blended). Walking into a hall's fade changes the decay by under 0.2 s a step of 0.2 m.
- Zones stream with cells as game code adds them in a cell loader's `attach` and removes them in `detach` (or with
  their node: a zone whose node was removed goes, tested in step 3). **Deviation:** no adapter to scene-world's cell
  loaders, as scene-audio does not depend on scene-world.

### Step 11: music and Opus

- `AudioSync.music(Music)` (and `Audio.music()`, made once over the renderer's clock and `CachedMusicClips`) turns
  Music's commands into the renderer's: starts at their exact frames on `Bus.MUSIC` at the highest priority (streamed
  clips through rings, primed when they start), stops with their fades. Stem gains, pauses and the stinger duck are
  queued and applied by the update whose schedule point reaches their frame, so they land within one update (and the
  renderer's gain ramp) of the bar, not to the sample; starts and stops are to the sample. A transition measured on
  clicks lands on the bar line to the sample; a stem and its negation re-scheduled every 4 bars cancel below 1e-6
  for 10 minutes.
- **Deviation:** a stinger ducks the other music through their voice gains (to the duck at its start, back over
  0.3 s at its end), not through a duck on the music bus: the bus's ducks are keyed by bus, and the stinger plays on
  the music bus itself.
- `Music.play` primes the score's stingers as well as the first playlist, so a stinger asked for is ready; `Score`
  lists its stingers.
- Opus decodes through libopusfile (always 48 kHz, pre-skip and end trim by the library). A seek is exact in
  position but not bit-exact to a decode from the start (the decoder's pre-roll converges): tested by alignment at lag
  0 and an error below 1e-3 after the first half of a 4,096-frame window.
- Offline renders run faster than real time, so a listening proof waits for loading between frames; a game's frame
  does not, and a late clip starts on the next bar line after it is ready.

### Step 12: the DSP layer (engine, scene)

- Package `com.truesim.engine.audio.dsp`: `DspNode` (`params()`, `prepare`, `process`, `reset`), `DspSetup`,
  `DspBlock`, `Param` (set, linear and exponential ramps at sample frames, up to 32 pending; or driven by a signal),
  `NodeParam`, `NodeType`, `Nodes` and `Patches` (registries; registering warms the type or patch for half a second
  on the calling thread; patches keep a pool of voices, reset when given back), `Patch` and `PatchVoice`.
- **Deviation (API):** nodes are added to a patch as factories or by type name (`g.add(Oscillator::saw)`,
  `g.add(() -> Tube.of(1.6))`, `g.add("bitCrusher")`), since each voice makes its own; parameters are wired by name
  (`.set("cutoff", signal)`) rather than typed setters (`.cutoff(...)`). Signals: `smooth`, `remap`, `times`, `plus`,
  `clamp`, `below`, `envelope`, `Signal.when`, `g.mix`. Nodes run in the order their inputs and parameters need
  (a depth-first sort), not the order added; a node reading its own output is rejected (feedback needs a delay node).
- Built-in nodes: `Oscillator` (sine from a 4,096-point table; saw, square and triangle by polyBLEP), `Noise` (white,
  pink; hashed PCG), `Filter` (RBJ low, high, band-pass, notch), `Delay` (and comb), `Tube` (waveguide),
  `FiringPulses` (four-stroke, firing orders), `Crackle`, `Envelope` (AD, triggered), `Shaper` (2× oversampled soft
  clip), `RpmBank`. **Deviation:** not yet the granular player and pitch shifter, state-variable and modal filters,
  the FDN as a node, the bit crusher (a test's plugin node instead) or custom nodes in bus effect chains.
- Measured: the polyBLEP saw at 2,489 Hz has its aliases below 10 kHz at −52 dB (the naive saw −17 dB; over the
  whole band −27 dB, the two-point polyBLEP leaving more near Nyquist); square −57 dB, triangle −65 dB; the sine's
  spurious power under −100 dB. The test bounds are −48, −52, −60 and −100 dB.
- The V8 patch (`Vehicles.v8()`, inputs `RPM`, `LOAD`, trigger `SHIFT`) follows §3.7; its spectrum peaks at the
  200 Hz firing rate at 3,000 rpm (and 400 Hz at 6,000 within 300 to 500 Hz: the 1.6 m tube's 589 Hz resonance is
  louder there). A shift dips the load through an envelope.
- **Scene:** `Patch.named(name, inputs...)` names the parameters a patch reads, so instances can set them;
  `SoundParameter.trigger(name)` is a parameter counting `SoundInstance.trigger` calls, and a patch fires on each
  change at the sample it is heard.
- Patch voices are voices like clip voices (gain, pitch, Doppler, 3D, virtual, stealing): mono at the mix rate,
  rendered into a ring of 8,192 frames as the voice reads it; a voice that was virtual for a while skips ahead rather
  than rendering what was missed. Input changes are sent at the schedule point with at least the 5 ms ramp, mapped
  into the patch's own frames through the voice's position and rate; an input set for a frame lands on that sample.
- Demo: `CameraCar`, the V8 heard as if the fly camera were a car, a five-speed gearbox on the camera's speed.

### Step 13: rendering listeners (engine)

- The renderer keeps four rendering listeners (`Voices.LISTENERS`; the sync counts more as `listenersDropped`).
  Each block: voices playing clips and patches render first, writing what listeners hear (before the device's
  attenuation and pan) into a per-voice buffer, so each is read and resampled once; then the listeners mix in the
  order the scene's graph gives; then the voices re-emitting their mixes (`Play.input()`) render for the device, and
  device-bus routes and taps are written. A mix is soft-clipped (a rational tanh) after its gain.
- A re-emitting voice heard by a listener reads its source's mix of this block when that listener already rendered,
  else its previous block (exactly one block of delay: tested), at −6 dB times the loop's howl guard. The howl
  detector lowers a listener's loop gain by 30 % a block while its level grows by a fifth over 8 blocks, and lets
  it back only in silence: two camera and television pairs at +6 dB each decay below −60 dB within a second.
- Lazy: a listener renders only while a real, audible voice re-emits it, or it has a device bus or a tap; idle, its
  ring is silent (`AudioRenderer.listenerBlocks` counts its blocks; a television turned off stops it).
- An input switch cross-fades 30 ms; re-emitting voices play by mix frame (**deviation:** no pitch or Doppler on a
  re-emitted mix). Taps (`AudioSync.tap(listener)`, `ListenerTap`) and listener mixes are not delayed by the device
  limiter's look-ahead, so they lead the device output by its latency.
- The sync tells each rendering listener how loud it hears each placed voice: the sound's attenuation curve at the
  distance (within the mix's max distance, on buses its mix hears), panned for a stereo mix, sent when it changes. The
  renderer counts a listener's level as audibility, so voices only a camera hears stay real.
- **Deviations:** no per-listener voice budget yet (every voice in range is mixed); a listener's mix does not apply
  the buses' volumes, effects or sends (only the voice's own gain); 2D (unplaced) sounds are not heard by rendering
  listeners; the distance low-pass is the device's (heard by listeners as well).

### Step 14: `AudioBindings` (engine)

- **Deviation (where):** `AudioBindings` is in engine-audio (next to the `Audio` plugin, as step 6 placed it), not in
  an engine-app module; engine-audio now depends on scene-animation and scene-effects for it.
- `onEvent(event, sound, joint)` plays the sound at the joint's node of the animator that fired (by id, allocating
  nothing), its volume the event's weight as a linear gain (`scaled(false)` to keep it), `minimumWeight` as in
  `EffectBindings`; `.surface(group, materialAt)` sets the switch from a `MaterialAt` (`TerrainHeights::materialAt`
  fits) at the joint's position as of the last `scene.update()`: the material number is the enum ordinal, negative
  (`NONE`, unloaded) or past the values gives the group's fallback.
- `curve(animator, curve, instance, parameter).map(scale, bias)` sets the parameter every `update()` (no ramp).
- `onEffect(effect, sound)` (after `effects(Effects)`) plays the sound once per instance added to the effects' log
  (at its node, else its position; shared burst instances excepted) and once per burst call (at the burst's
  position), read by serial number. A log that overflowed between updates loses those starts (not replayed).
- Demo: `FoxFootsteps`, the fox's four paws bound to the walk's and run's foot-down events, on bands of grass, stone
  and gravel standing in for terrain materials (the demo ground has none); steps synthesized, three per surface.

### Step 15: dialogue (scene, engine)

- Scene: `Line` (a path with `{locale}`, or a clip; speaker, subtitle key, cues, visemes), `Cue`, `WebVtt.parse`
  (headers, notes, identifiers and cue settings skipped), `Visemes.rhubarb` (Rhubarb's nine shapes; weights
  cross-faded over the first 40 ms of each cue), `Dialogue` in `Sounds.dialogue()` with `LineInstance` and
  `DialogueEvent` (`LINE_STARTED`, `CUE_STARTED`, `CUE_ENDED`, `LINE_ENDED`, `INTERRUPTED`, plus `DROPPED` and
  `EXPIRED`). Paths resolve through `dialogue().clips(path -> paths.ref(CLIP, path))` and `locale(...)`.
- Rules as designed: one line per speaker; higher interrupts (100 ms fade, its open cue ended first), equal queues
  (expiring after `queueExpiry`, 5 s), lower is dropped; `cooldown(key, seconds)` per `Line.key()` (its subtitle key,
  else its path or clip). A line plays on `VOICE` at the highest priority, `KEEP_TIME` when virtual.
- **Deviation:** sidecars are not read by the importers yet: the game parses a `.vtt` or Rhubarb `.json` with
  `WebVtt.parse` / `Visemes.rhubarb` and sets them on the line; no cues from clip markers. A line without cues has one
  cue for its whole length under its subtitle key (ended when the line ends).
- Heard time: the sync tells each line where its listener hears it (`heard`: the clock's heard frame against the
  voice's start frame, −1 while loading) and `heardByEngine()` stops the store's own time standing in; events fire in
  `Dialogue.update()` (run by `Sounds.advance` and after the sync's update), within an update of the heard time
  (tested against offline renders: within two blocks). `LineInstance.envelope()` is the voice's level from the audio
  thread (block RMS falling over about 100 ms; `AudioRenderer.level(slot)`).
- `Audio.indicators(listener, out)` / `AudioSync.indicators` fill `Indicators` (made once with a capacity) with the
  loudest captioned, placed, playing sounds a device listener hears: caption, unit direction in the listener's frame,
  azimuth, loudness (attenuation times voice gain). Allocation-free.

### Measured (2026-11-02, Ryzen 9 9950X3D, Windows 11, WASAPI through SDL 3.4, 48 kHz stereo, 256-frame blocks)

- **Render cost per block** (offline, JIT warmed, `AudioDeviceProbe`): 64 voices of a looping clip at varied pitches,
  cubic resampling, panned, two buses: median 170 us, p99 210 us, max 269 us (3.2 % of the 5,333 us block). 1,000
  placed loops with attenuation, low-pass and spread, 64 of them real: median 210 us, p99 350 us, max 450 us.
- **Allocation:** `assertAllocationFree` passes for `AudioRenderer.render` with 64 voices, `AudioSync.update` with 20
  placed loops (with the render), `Sounds.fire` and `advance`, `Music.update` and `Fft.forward`. The demo allocates
  nothing new per frame for its knocks after the clip loaded (not measured with JFR).
- **Latency:** the device's buffer is 1,024 frames (21.3 ms; the sample-frames hint was ignored), so the low mark is
  1,280 frames (26.7 ms); the schedule point is 1,096 frames (22.8 ms) ahead of the rendered frame, and a rendered
  frame is heard at most 2,560 frames (53.3 ms) later: a command reaches the ear in at most about 76 ms, most of it the
  device's buffer.
- **Underruns:** 0 in 600 s under G1 (112,487 blocks; the low mark forced to 2 blocks), 0 in 30 s under ZGC with a
  thread allocating 4 KiB arrays as fast as it can, 0 in 60 s with the probe's only window minimised. The
  high-resolution timer and MMCSS registration both took.
- **Oversleep** (how much later than asked the thread woke, 99.9th percentile, 50 us steps): 750 us in the 600 s run,
  850 us under garbage, 800 us minimised: the waitable timer keeps its resolution with the window minimised, where
  `parkNanos` would ride the 15.6 ms system timer (that alternative was not measured).
- **Not measured** (needs a person): unplugging headphones while playing (the default device is followed by SDL, the
  chosen-device reopen is tested only as code), listening to the renders beyond the files below, and the demo's
  sound in VR.
- **Renders to listen to** (`ListeningProofs`, `-Ptruesim.audio.proofs=<dir>`): knocking rocks, a car passing at
  30 m/s with Doppler and panning, 400 loops for 64 voices as the listener walks through them.

---

## 9. Sources

Reached for this design (links inline above, collected here):
- Unreal: [Quartz overview](https://dev.epicgames.com/documentation/unreal-engine/overview-of-quartz-in-unreal-engine),
  [FQuartzClock](https://dev.epicgames.com/documentation/unreal-engine/API/Runtime/AudioMixer/FQuartzClock),
  [MetaSounds introduction](https://dev.epicgames.com/documentation/unreal-engine/metasounds-the-next-generation-sound-sources-in-unreal-engine),
  [MetaSounds reference](https://dev.epicgames.com/documentation/unreal-engine/metasounds-reference-guide-in-unreal-engine),
  [concurrency](https://dev.epicgames.com/documentation/en-us/unreal-engine/sound-concurrency-reference-guide),
  [EVirtualizationMode](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/Engine/EVirtualizationMode),
  [Audio Modulation](https://dev.epicgames.com/documentation/unreal-engine/audio-modulation-reference-guide-in-unreal-engine),
  [FSoundAttenuationSettings](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Runtime/Engine/FSoundAttenuationSettings).
- Unity: [PlayScheduled](https://docs.unity.com/en-us/engine/7000.0/script-reference/unityengine/audiosource/playscheduled),
  [DSPGraph manual](https://docs.unity3d.com/Packages/com.unity.audio.dspgraph@0.1/manual/index.html) and
  [changelog](https://docs.unity3d.com/Packages/com.unity.audio.dspgraph@0.1/changelog/CHANGELOG.html),
  [DSPGraph status thread](https://discussions.unity.com/t/any-news-about-dspgraph-audio-framework/927157).
- Godot: [AudioServer](https://docs.godotengine.org/en/4.2/classes/class_audioserver.html),
  [AudioStreamGeneratorPlayback](https://docs.godotengine.org/es/4.3/classes/class_audiostreamgeneratorplayback.html),
  [AudioStreamInteractive doc source](https://remotebranch.eu/Stowage/godot/src/commit/b4c6cc7d82d1fcdb3bed734c4e22c19e012c84df/modules/interactive_music/doc_classes/AudioStreamInteractive.xml),
  [forum on combining interactive and synchronized](https://forum.godotengine.org/t/help-combining-audiostreaminteractive-and-audiostreamsynchronized/100519).
- FMOD: [parameters](https://www.fmod.com/docs/2.03/studio/parameters.html),
  [Studio API common](https://www.fmod.com/docs/2.00/api/studio-api-common.html),
  [Core API resources and virtual voices](https://fmod.com/docs/2.03/api/managing-resources-in-the-core-api.html),
  [new in 2.00](https://www.fmod.com/docs/2.03/studio/welcome-to-fmod-studio-new-in-200.html).
- Wwise: [rooms and portals](https://audiokinetic.com/en/blog/rooms-and-portals-with-wwise-spatial-audio),
  [revised aux send model](https://www.audiokinetic.com/en/revised-aux-send-model),
  [engine sound modelling](https://www.audiokinetic.com/engine-sound-modeling-from-sampling-to-granular-synthesis-in-wwise),
  community answers on [transitions](https://blog.audiokinetic.com/qa/2053/immediate-transition-for-music-track-switch),
  [pre-entry](https://www.audiokinetic.com/qa/1367/issue-with-pre-entry-when-using-states-to-change-the-music),
  [transition segments](https://blog.audiokinetic.com/qa/2611/transition-segments-alongisde-previous-playlist-containers),
  [RTPCs](https://audiokinetic.com/qa/2370/how-do-you-assign-a-sfx-object-to-a-game-parameter?show=2387);
  [Game Audio Programming 2, ch. 9](https://www.taylorfrancis.com/books/9781315157542/chapters/10.1201/b22247-9).
- CRI: [ADX2 codecs](https://blog.criware.com/index.php/2016/04/11/meet-adx2s-powerful-proprietary-codecs/),
  [product page](https://www.cri-mw.co.jp/product/web/adx2/index.html).
- Spatial: [Steam Audio on Steamworks](https://partner.steamgames.com/doc/features/steam_audio),
  [Steam Audio FMOD docs](https://valvesoftware.github.io/steam-audio/doc/fmod/spatializer.html),
  [Steam Audio open source](https://phoronix.com/news/Steam-Audio-SDK-Fully-Open),
  [Meta XR acoustic ray tracing](https://www.uploadvr.com/meta-xr-audio-sdk-acoustic-ray-tracing-simulation/),
  [XR_OCULUS_audio_device_guid](https://registry.khronos.org/OpenXR/specs/1.1/man/html/xrGetAudioOutputDeviceGuidOculus.html),
  [LWJGL's OCULUS_audio_device_guid template](https://raw.githubusercontent.com/LWJGL/lwjgl3/master/modules/lwjgl/openxr/src/templates/kotlin/openxr/templates/OCULUS_audio_device_guid.kt),
  [SteamVR playback device switching (Road to VR)](https://www.roadtovr.com/?p=91058).
- HRTF data: [jHDF](https://github.com/jamesmudd/jhdf), [SADIE II](https://www.york.ac.uk/sadie-project/database.html),
  [ARI](https://projects.ari.oeaw.ac.at/research/experimental_audiology/hrtf/database/hrtfItESOFA.html),
  [FABIAN](https://depositonce.tu-berlin.de/items/3b423df7-a764-4ce1-9065-4e6034bba759/full),
  [libmysofa copyright file (KEMAR, CIPIC, LISTEN terms)](https://launchpad.net/ubuntu/jammy/+source/libmysofa/+copyright),
  [OpenAL Soft HRTF notes](https://svn.thedarkmod.com/publicsvn/darkmod_src/branches/tdmsync/ExtLibs/openal-soft/docs/hrtf.txt).
- SDL3: [SDL_audio.h](https://raw.githubusercontent.com/libsdl-org/SDL/main/include/SDL3/SDL_audio.h),
  [SDL_hints.h](https://raw.githubusercontent.com/libsdl-org/SDL/main/include/SDL3/SDL_hints.h),
  [SDL_audio.c (device loss, migration)](https://raw.githubusercontent.com/libsdl-org/SDL/main/src/audio/SDL_audio.c),
  [WASAPI backend](https://raw.githubusercontent.com/libsdl-org/SDL/main/src/audio/wasapi/SDL_wasapi.c),
  [dummy driver](https://raw.githubusercontent.com/libsdl-org/SDL/main/src/audio/dummy/SDL_dummyaudio.c),
  [SDL_PROP_AUDIO_DEVICE_UNIQUE_ID_STRING](https://wiki.libsdl.org/SDL3/SDL_PROP_AUDIO_DEVICE_UNIQUE_ID_STRING),
  [SDL releases](https://github.com/libsdl-org/SDL/releases),
  [SDL_OpenAudioDeviceStream](https://wiki.libsdl.org/SDL3/SDL_OpenAudioDeviceStream).
- Libraries: [miniaudio](https://miniaud.io/), [OpenAL Soft](https://openal-soft.org),
  [LWJGL releases](https://github.com/LWJGL/lwjgl3/releases),
  [LWJGL opus templates (libopusfile)](https://github.com/LWJGL/lwjgl3/tree/master/modules/lwjgl/opus/src/templates/kotlin/opus/templates),
  [Concentus](https://github.com/lostromb/concentus), [JEP 454 (FFM)](https://openjdk.org/jeps/454),
  [RFC 7845 (Ogg Opus)](https://www.rfc-editor.org/rfc/rfc7845).
- Java: [JEP 474](https://openjdk.org/jeps/474), [JEP 490](https://openjdk.org/jeps/490),
  [JEP 519](https://openjdk.org/jeps/519), [JEP 529 (Vector API, 11th incubator)](https://openjdk.org/jeps/529),
  [JEP 537](https://openjdk.org/jeps/537), [Gen ZGC explainer](https://inside.java/2023/11/28/gen-zgc-explainer/).
- Engineering: [Bencina, real-time audio 101 (mirror)](https://hn.jakechampion.name/reader/http://www.rossbencina.com/code/real-time-audio-programming-101-time-waits-for-nothing),
  [Web Audio API](https://www.w3.org/TR/webaudio/), [EBU R128](https://tech.ebu.ch/publications/r128),
  [ITU-R BS.2434](https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BS.2434-2018-PDF-E.pdf),
  [AudioMotors in WRC 5](https://www.mcvuk.com/development/how-to-make-racing-car-engines-roar-using-audiomotors-fmod);
  Farnell, *Designing Sound* (MIT Press, 2010).

**Not reached or not verified:** wiki.libsdl.org refused connections during the review (SDL facts are from its
headers and source on GitHub). Audiokinetic's official Wwise help pages and the FMOD Studio manual pages on
transitions (from blogs, forums and release notes). Steam Audio's default HRTF origin (undocumented). The Sony
ASWG-R001 loudness text. Stated from knowledge and marked to be measured: the Windows timer behaviour of
`LockSupport.parkNanos` in current JDKs, Java thread priority mapping on Windows in current HotSpot, Quest Link and
Virtual Desktop endpoint behaviour, and every cost in §2.9 and §2.23 (measured in steps 6, 12, 17 and 20).
