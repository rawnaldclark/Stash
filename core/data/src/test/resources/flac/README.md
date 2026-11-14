# FLAC fixtures

`tidal-preview-shape.flac` (21,271 bytes) has the shape of the clip Tidal's 30 s PREVIEW of
"Linger" became on the Pixel 5 on 2026-11-13: 322 frames of 4096 samples, 44.1 kHz stereo 16-bit
(29.907 s), under a STREAMINFO that says 12,117,504 samples (274.773 s, the whole song's, as the
preview's init segment carried it). Silent except the last two frames, which hold quiet noise so the
end of the file has real frame data in it.

Made with FFmpeg 8.1 from exact integer PCM (no dither), then the STREAMINFO total rewritten:

1. 320 frames of zeros, then 2 x 4096 stereo samples drawn from `random.Random(575545).randint(-96, 96)`,
   written as s16le.
2. `ffmpeg -f s16le -ar 44100 -ac 2 -i pcm.raw -c:a flac -frame_size 4096 -metadata_header_padding 0
   -fflags +bitexact -flags:a +bitexact out.flac`
3. STREAMINFO total samples (the low 4 bits of file byte 21 and bytes 22-25) set to 12,117,504.

`ffprobe` reads it as 274.773 s; decoded, it stops at 29.907 s.
