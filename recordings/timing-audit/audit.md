# Timestamp verification

## What is verified

- Video timing below comes from actual stored packet timestamps; packets are sorted by presentation order, not decoder order.
- PTS identifies presentation time within the clip. It does not establish per-frame exposure start or UTC capture time.
- Frame counts were also checked by decoding every video frame.
- Extra Apple timed-metadata streams exist but were not decoded, so their contents are not claimed here.

| Video | Frames | Time base | PTS spacing |
| --- | ---: | --- | --- |
| IMG_3302.MOV | 1567 | 1/2400 s | 8.333333–8.750000 ms |
| IMG_3303.MOV | 6609 | 1/2400 s | 7.916667–8.750000 ms |
| IMG_3304.MOV | 751 | 1/2400 s | 8.333333–8.750000 ms |

## Watch UTC verification limit

Verified current source: QMI FIFO samples use the processor uptime clock, reconstructed inside batches at nominal 1793.6 Hz and anchored to host time. See high_rate.hpp and motion_service.cpp.

Verified source: startedUtc comes from the watch archive header startedUnix (whole Unix seconds at recording start), with Android import-time fallback when unset. This is not the first sample timestamp. CSV/session exports omit bootStartUs, and BLE clock-setting latency is unmeasured, so exact first-sample UTC is not established.

If startedUtc is assumed to be the first sample UTC, the implied watch boot times differ by 622.405 ms. That is compatible with coarse whole-second metadata, but does not verify frame-level synchronization or quantify watch clock drift.

Use the original monotonic t_us values for relative stitching and a shared physical event for cross-device alignment.

Sources: [FFmpeg framecrc format](https://ffmpeg.org/ffmpeg-formats.html#framecrc), [Apple sample-buffer timestamps](https://developer.apple.com/documentation/coremedia/cmsamplebuffer/presentationtimestamp).
