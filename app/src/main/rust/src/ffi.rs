mod android;
mod android_o;
mod archive;
// xxhash-rust's xxh3 does not compile for 32-bit ARM -- it names
// `core::arch::aarch64` unconditionally, in every published version -- so the
// reader only exists on 64-bit targets. The Kotlin side checks the same thing
// before choosing this loader, so nothing here is ever called without it.
#[cfg(target_pointer_width = "64")]
mod dwarfs;
#[cfg(target_pointer_width = "64")]
mod dwarfs_writer;
mod jvm;
mod smb;
