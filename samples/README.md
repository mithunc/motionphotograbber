# Sample motion photos

This directory is intentionally empty in a fresh clone.

## Why there are no files here

Motion photos come straight off a phone camera and carry full EXIF metadata — including
**GPS coordinates and capture timestamps**. That metadata is the whole point of this
project (see the user story in `SPEC.md`), which also makes it exactly the thing you do
not want committed to a public repository. Git history is permanent: deleting a file in a
later commit does not remove its contents from the repo.

So `samples/*.jpg` is git-ignored, and everyone works against their own files.

**Please do not commit your own samples**, even scrubbed ones, without a deliberate
decision to do so. The ignore rules are there to make the safe path the default one.

## Supplying your own

Drop one or more Pixel motion photos into this directory. They are named `PXL_*.MP.jpg`
on-device — the `.MP` infix is what marks them as motion photos.

Two things matter:

1. **Copy the file directly off the device.** Use USB/MTP, `adb pull`, or any transfer
   that moves bytes verbatim.
2. **Do not let it pass through anything that re-encodes.** A Google/Pixel motion photo
   is a JPEG with an entire MP4 appended after it. Most photo editors, messaging apps,
   and cloud services parse to the JPEG `EOI` marker and write back only what they
   understood — silently discarding the video. The result still opens as a perfectly
   valid photo, so the damage is invisible until a parser test fails on it.

If a file looks like an ordinary JPEG to this app, re-encoding en route is the first
thing to suspect.

## Scope

Pixel/Google motion photos are the supported format for the MVP. Samsung and Apple files
are not handled yet; the parser is structured so they can be added without restructuring.
If you have samples from those devices, they are useful for future work — but the same
privacy caution applies, so raise an issue rather than committing them.

## Tests

Unit tests that need a real motion photo **skip** when this directory is empty rather
than fail, so a fresh clone builds green:

```
./gradlew test
```

A skipped test is not a passing test. If you are changing parsing logic in
`:core-motionphoto`, supply at least one sample locally and confirm those tests actually
run — including the negative case, where an ordinary JPEG with no embedded video must be
reported as "not a motion photo".
