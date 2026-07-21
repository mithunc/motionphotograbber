# PROMPTS.md

Copy-paste prompts, in order. Do not skip ahead — each assumes the previous one
landed and was committed.

---

## Prompt 0 — Session opener (use at the start of every new session)

```
Read CLAUDE.md and SPEC.md before doing anything else. Then read the API drift
warning in CLAUDE.md again and tell me, in one line each, what you are going to
verify against live docs before you write code.

Also re-read the "Ask, don't assume" section and confirm you'll flag open questions
to me rather than picking defaults yourself, including in this session.

Do not write any code yet.
```

*Why:* forces the assistant to surface stale-knowledge risk before it bakes a wrong
import into three files, and re-primes the ask-first behavior at the start of every
session rather than relying on it holding for an entire long conversation.

---

## Prompt 1 — Scaffold

```
Set up an empty Android project skeleton for this app: MotionPhotoGrabber (display
name "Motion Photo Grabber" — working title, treat as provisional per CLAUDE.md).

- Kotlin, Compose, Material 3, Gradle Kotlin DSL, version catalog in
  gradle/libs.versions.toml.
- Four modules per SPEC.md: :core-motionphoto, :core-extract, :core-exif, :app.
- :core-motionphoto must be a pure Kotlin/JVM module — if it can compile without the
  Android Gradle plugin, do it that way.
- No dependencies yet beyond Compose, kotlinx-coroutines, and JUnit. We will add
  Media3 and exifinterface in later milestones, deliberately, with pinned versions.
- AndroidManifest with no INTERNET permission.
- Stub Composable that renders "hello" so the build produces a runnable APK.

Then run ./gradlew assembleDebug and ./gradlew test and show me the output.
Stop after that. Do not implement any parsing.
```

---

## Prompt 2 — Sample fixtures (you do this part, not the assistant)

Before M1, put real files in `samples/`. This is the single highest-leverage thing
you can do for this project.

```
I have added sample files to samples/. Inventory them for me: for each file, run
exiftool if available (or read the bytes yourself) and report:
- file size
- whether XMP is present and what motion-photo-related keys it contains
- whether the ASCII string MotionPhoto_Data appears, and at what byte offset
- your best guess at the video's byte range

Do not write parser code yet. I want to see what we are actually dealing with
before we design around it. If any file is ambiguous, say so rather than guessing.
```

Get samples from: your own phone, a friend's phone of a different brand, and one
ordinary JPEG as a negative test case. **The parser will be wrong for any device you
don't have a sample from** — this is the part no amount of prompting substitutes for.

---

## Prompt 3 — M1, the parser

```
Implement M1 from SPEC.md in :core-motionphoto.

Constraints:
- Pure Kotlin. No Android imports. Input is an InputStream or File.
- Return the sealed MotionPhoto type from SPEC.md.
- Handle the flavors you actually found evidence for in samples/. Do not
  speculatively implement a format we have no sample of — leave a TODO and tell me
  which sample I still need to supply.
- Comment every piece of byte-offset arithmetic with what the offset is relative to.
  Google's offset is from EOF, which is a classic off-by-a-whole-file bug.

Write unit tests against every file in samples/, including the negative case.
Run ./gradlew test and show me the results. Then stop.
```

Follow-up once green:

```
Now show me the parser's behaviour on a deliberately truncated file and a file whose
XMP claims an offset larger than the file itself. Add tests for both. These should
return Malformed, never throw or return a nonsense range.
```

---

## Prompt 4 — M2, extraction

```
Implement M2 from SPEC.md in :core-extract.

Before writing code: check the current Media3 release notes and tell me the exact
artifact name, version, and fully-qualified import for the frame extraction class.
Do not proceed on memory — this API has moved modules at least twice. Show me what
you found, then wait for me to confirm.
```

Then, after you approve:

```
Good. Implement it with that version pinned in libs.versions.toml.

- Decode from the source file in place using a file descriptor + offset + length
  derived from M1's output. No temporary MP4 files.
- Two entry points: previewFrame(timestampUs, maxDimension) and
  fullFrame(timestampUs).
- Suspend functions, cancellable. A superseded preview request must be cancelled,
  not queued.
- Report clip duration.

Add an instrumented test. Tell me explicitly which parts I have to verify on-device
because you can't run them.
```

---

## Prompt 5 — M3, EXIF

```
Implement M3 from SPEC.md in :core-exif.

- Explicit list constant of supported tags, grouped and commented (GPS / time /
  camera / exposure / orientation).
- DateTimeOriginal takes the ORIGINAL shutter time, per SPEC.md. Add a comment
  explaining why, so nobody "fixes" it later.
- Set Software to identify this app.
- Round-trip test: write a JPEG, copy metadata from a sample motion photo, read back,
  assert GPS and timestamps match.

Run ./gradlew test. Then stop.
```

---

## Prompt 6 — M4, UI

```
Implement M4 from SPEC.md in :app. Wire together the three core modules.

- Compose, single screen, full-bleed preview + scrubber beneath.
- Debounce scrub input; cancel superseded preview decodes.
- Loading and error states for: not a motion photo, malformed file, decode failure.
- Save via MediaStore to Pictures/.
- Share-target intent filter for image/jpeg.

Do not add the thumbnail filmstrip. That is a stretch goal and I want the basic
path solid first.

Build it, then give me a numbered on-device test checklist — the specific things I
should try, including at least two things you expect might break.
```

---

## Prompt 7 — Review pass

Run this in a **fresh session** so the assistant re-reads the code rather than
trusting its own memory of writing it.

```
Fresh eyes. Read the whole codebase.

Review specifically for:
1. Byte-offset arithmetic errors in the parser.
2. Resource leaks — unclosed streams, file descriptors, undisposed extractors.
3. Coroutine cancellation correctness in the scrub path.
4. Anything in :core-motionphoto that accidentally depends on Android.
5. Silent failures — anywhere an error is swallowed instead of surfaced.

Report findings as a prioritised list. Do not fix anything yet.
```

---

## Prompt 8 — F-Droid readiness

```
Prepare this for F-Droid submission. Check:
- Reproducible build config
- No proprietary dependencies, no Play Services, no analytics
- No INTERNET permission anywhere in the merged manifest — verify against the
  merged manifest, not just the source one
- Apache 2.0 LICENSE and NOTICE files at repo root, and per-file Apache headers on
  new source files
- fastlane/metadata/android/en-US structure with description and changelog

List anything blocking submission.
```

---

## General technique notes

**One milestone per session, roughly.** Long sessions accumulate stale assumptions.
Fresh sessions re-read CLAUDE.md, which is the point of having it.

**Say "stop after that" often.** Otherwise the assistant races three milestones ahead
and you get a large diff you can't meaningfully review.

**Make it show you the failure, not just the fix.** "Run the test and show me the
output" catches the case where nothing was actually run.

**When it's wrong about an API,** don't argue — paste the current doc page into the
session. That's faster and more reliable than trying to correct it from memory.

**Watch for assumption-creep in long sessions.** "Ask, don't assume" in CLAUDE.md is
strongest right after Prompt 0 re-reads it. If a session runs long, it's easy for the
assistant to slide back into picking defaults. If you notice it deciding things
instead of asking, call it out directly — "you should have asked me about that" — and
consider ending the session there rather than continuing on a corrected-but-shaky
footing.

**Update CLAUDE.md as you learn.** When you discover that a particular manufacturer's
files break an assumption, that fact belongs in CLAUDE.md, not in a chat message that
scrolls away.
