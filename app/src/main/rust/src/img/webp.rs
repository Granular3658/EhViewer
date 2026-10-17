use libwebp_sys::{MODE_rgbA, WebPAnimDecoder, WebPAnimDecoderGetInfo, WebPAnimDecoderGetNext, WebPAnimDecoderHasMoreFrames, WebPAnimDecoderNew, WebPAnimDecoderOptions, WebPAnimDecoderReset, WebPAnimInfo, WebPData};
use std::mem::zeroed;
use std::ptr::null_mut;

/// Hand libwebp the largest prefix of `data` that is a whole file.
///
/// Some writers cut a webp short, often in the middle of a frame, and leave the
/// RIFF size describing the file they meant to write. The platform decoders and
/// Chrome play the frames that are there; libwebp's animation decoder rejects
/// the whole file over the size mismatch, which reaches the user as "failed to
/// create decoder" for a page every other app can show.
///
/// Dropping the partial tail and rewriting the RIFF size makes the file
/// decodable with exactly the frames the tolerant decoders show. The buffer is
/// the caller's and is patched in place -- four bytes at offset 4.
fn trim_to_complete_chunks(data: &mut [u8]) -> usize {
    if data.len() < 12 || &data[0..4] != b"RIFF" || &data[8..12] != b"WEBP" {
        return data.len();
    }
    let declared = u32::from_le_bytes([data[4], data[5], data[6], data[7]]) as usize + 8;
    if declared <= data.len() {
        return declared;
    }
    let mut pos = 12;
    let mut end_of_last = 12;
    while pos + 8 <= data.len() {
        let size = u32::from_le_bytes([data[pos + 4], data[pos + 5], data[pos + 6], data[pos + 7]]) as usize;
        let end = pos + 8 + size + (size & 1);
        if end > data.len() {
            break;
        }
        end_of_last = end;
        pos = end;
    }
    if end_of_last <= 12 {
        return data.len();
    }
    data[4..8].copy_from_slice(&((end_of_last - 8) as u32).to_le_bytes());
    end_of_last
}

pub fn create_decoder(data: &mut [u8]) -> *mut WebPAnimDecoder {
    let len = trim_to_complete_chunks(data);
    let webp_data = WebPData { bytes: data.as_ptr(), size: len };
    let dec_options = WebPAnimDecoderOptions { color_mode: MODE_rgbA, use_threads: 1, padding: [0; 7] };
    unsafe { WebPAnimDecoderNew(&webp_data, &dec_options) }
}

pub(crate) unsafe fn get_image_info(dec: *const WebPAnimDecoder) -> WebPAnimInfo {
    let mut info = unsafe { zeroed() };
    let _ = unsafe { WebPAnimDecoderGetInfo(dec, &mut info) };
    info
}

pub fn pack_image_info(info: WebPAnimInfo) -> u64 {
    (info.canvas_width as u64) << 40 | (info.canvas_height as u64) << 16 | (info.loop_count as u64)
}

pub(crate) unsafe fn decode_next_frame(dec: *mut WebPAnimDecoder, reset: bool) -> (*const u8, i32) {
    let mut buf = null_mut();
    let mut timestamp = 0;
    unsafe {
        if reset || WebPAnimDecoderHasMoreFrames(dec) == 0 {
            WebPAnimDecoderReset(dec);
        }
        let _ = WebPAnimDecoderGetNext(dec, &mut buf, &mut timestamp);
    }
    (buf, timestamp)
}
