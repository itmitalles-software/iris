# Image diagnostics (debug builds)

Debug builds of Apollo log how chat images are loaded and merged under the
logcat tag `ChatImageDiag` (`ui/chat/ChatImageDiagnostics.kt`). Use it when an
image in a chat stays blank, shows **Could not load image**, or disappears after
scrolling or reopening a session. Release and Play builds do not write these
lines.

The diagnostics come from Hermes Mobile, where they were added to investigate
Hy4ri/hermes-mobile#1459.

## Capture on the affected device

1. Install the debug APK (`io.wutz.apollo.dev`, "Apollo Dev"; see
   [BUILD.md](../BUILD.md)). It installs next to the release app. Configure the
   same server and login in it. Do not uninstall the release app or clear its
   data.
2. Open a fresh session, send one image with a short caption, and wait for the
   reply. Note whether the image stays, shows **Could not load image**, or
   disappears entirely. If an error card appears, tap **Retry** once.
3. Leave and reopen that session once to exercise cached and REST history as well.
4. Export only the diagnostic tag:

   ```sh
   adb logcat -d -v threadtime -s ChatImageDiag:D '*:S' > image-diagnostics.log
   ```

   Capture promptly after reproducing; logcat buffers are finite.
5. Attach `image-diagnostics.log` and the observed behavior to the bug report.
   The filtered tag contains no captions, file names, paths, URLs, tokens,
   cookies, image bytes, or exception messages. Do not substitute a full
   unfiltered log: other tags may contain host names or other sensitive data.

## Reading the trace

`row` is a process-independent hash of message identity, **not** content.
Thumbnail rows add an attachment index (`row=<key>:0`). History rows also show a
hashed `canonical` alias, so optimistic and server rows can be correlated
without printing session IDs. Hashes are correlation aids, not cryptographic
anonymization. Markdown/standalone thumbnails have `row=standalone`.

- `merge=rest|cache stage=before|incoming|after`: whether the text still
  contains an `@image:` marker, attachment count, and source scheme (`content`,
  `file`, `http`, `https`, `data`, `other`). A before/incoming attachment
  followed by an after row with zero attachments points to state/merge loss.
  `imageRowsAfter=0` identifies complete row loss within that snapshot.
- `phase=start|success|error`: actual Coil thumbnail request callbacks. An error
  includes exception **types** and the HTTP status when Coil supplies an
  `HttpException`. `http=unknown` means unavailable, not success.
- A `source=content|file` error points to local access; `source=http|https`
  with 401/403 points to access/auth, 404 to the file/route, and
  decoder-related exceptions to payload/format handling. These are
  investigation directions, not automatic diagnoses.
- `phase=success` followed by disappearance without a new error points toward
  layout/state rather than that request failing. Compare history stages and
  image source changes.
- `phase=dispose` means the thumbnail left composition; normal scrolling,
  navigation, or a changed image model can do this. It is not itself an error.

The history trace is emitted only after a history merge successfully applies,
never from a retried `StateFlow.update` lambda. It is restricted to
image-bearing user rows and their aliases. Logging changes no database or
network behavior.

## Regression tests

- `UserImageHydrationTest`: history JSON through deserialization, mapping, live
  merge, entity round-trip, cache merge and repeated REST refresh; confirmed-set
  handoff, partial sets, mixed attachments, caption-only cached alias.
- `ChatViewModelTest.retiredReceiptKeepsStagedImageReadableWhileBubbleStillReferencesIt`:
  a staged file stays readable through receipt retirement.
- `ChatImageDiagnosticsTest`: allowlisted source/error/status metadata, loss
  visibility, and redaction.
- `GifImageThumbnailTest` (instrumented): real Coil loading; missing local file
  → visible error → Retry → recovered image; HTTP 403 → visible error → Retry →
  valid PNG; identical frame bounds across error, retry, success and disposal.
- `FullBleedImageScrollTest` (instrumented): the row below an image keeps its
  pixel offset while the image loads, fails, and across repeated
  dispose/re-enter cycles.
