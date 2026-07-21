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

```
./gradlew test
```

Parser coverage comes in three tiers, because this directory is empty in a fresh clone
and tests that quietly skip are worse than no tests at all.

| Tier | Needs samples? | What it gives you |
| --- | --- | --- |
| Synthetic fixture | No | A motion photo built in test code, asserted to exact byte offsets. Runs everywhere, including a fresh clone. |
| Sample invariants | Any sample | Runs the parser over **whatever** `*.jpg` you drop in here. No filenames or offsets are hardcoded, so your own photos work as-is. |
| Exact fixtures | Specific samples | Regression pinning against the maintainer's own files. Skips for everyone else — by design. |

So **you do not need to rename anything.** Drop in your own Pixel motion photos under
whatever names they already have and the invariant tier will exercise them: it checks
that the items tile the file exactly, that the still and gain map each begin with a JPEG
`SOI` marker, and that the computed video offset lands on a real ISO-BMFF `ftyp` box.

The exact-offset fixtures in `MotionPhotoParserTest` name specific files and assert
specific byte offsets. Those describe one particular set of photos — renaming your file
to match would make them *fail*, not pass. Leave them skipping; that is expected.

A skipped test is not a passing test. If you are changing parsing logic in
`:core-motionphoto`, check the counts rather than trusting `BUILD SUCCESSFUL`:

```
grep -o 'tests="[0-9]*" skipped="[0-9]*"' \
  core-motionphoto/build/test-results/test/TEST-*.xml
```

The synthetic tier must always report `skipped="0"`. If it does not, it has failed at
the one job it exists to do.

Note that the synthetic fixture validates the parser against *our model* of the format,
not against reality — only real samples catch a real file doing something we did not
anticipate. That is why supplying one still matters even though the build is green
without it.
