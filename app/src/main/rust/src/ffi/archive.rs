#![cfg(feature = "android")]

//! C entry points for the archive reader's callbacks.
//!
//! `archive.c` links this crate directly (`corrosion_link_libraries` in
//! `app/src/main/cpp/CMakeLists.txt`), so these are plain `extern "C"` functions
//! rather than JNI: libarchive calls the read callback once per block, and a JNI
//! round trip per block would be pure overhead.
//!
//! A callback takes an explicit offset, so the caller owns the position. That is
//! the opposite of the `dup` mistake documented in `smb_cache`: there is no
//! shared file position here for a second reader to disturb.

use crate::smb;
use log::error;

/// Read up to `len` bytes at `offset` into `buf`.
///
/// Returns the number of bytes written, `0` when `offset` is at or past the end
/// of the file, or `-1` on error. A short result is not end-of-file -- the SMB
/// layer only returns fewer bytes when the request reaches the end.
///
/// # Safety
///
/// `buf` must be valid for writes of `len` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn smb_archive_read(
    handle: u64,
    offset: u64,
    buf: *mut u8,
    len: usize,
) -> isize {
    if buf.is_null() {
        return -1;
    }
    if len == 0 {
        return 0;
    }
    match smb::read(handle, offset, len as u64) {
        Ok(data) => {
            let n = data.len().min(len);
            unsafe { std::ptr::copy_nonoverlapping(data.as_ptr(), buf, n) };
            n as isize
        }
        Err(e) => {
            error!(
                target: "smb",
                "smb_archive_read(handle={handle}, offset={offset}, len={len}) failed: {e:#}"
            );
            -1
        }
    }
}
