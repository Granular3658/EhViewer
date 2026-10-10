/*
 * Copyright 2022-2024 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * EhViewer. If not, see <https://www.gnu.org/licenses/>.
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include <archive.h>
#include <archive_entry.h>

#define LOG_TAG "libarchive_wrapper"

#include "natsort/strnatcmp.h"
#include "zip_central_dir.h"
#include "ehviewer.h"

typedef struct {
    int using;
    int next_index;
    struct archive *arc;
    struct archive_entry *entry;
} archive_ctx;

typedef struct {
    const char *filename;
    int index;
    ssize_t size;
    void *addr;
    /* Where the entry lives, from the central directory. Lets a stored entry be
     * read straight from its offset instead of walking to it. Zero when the
     * entry came from libarchive's walk or is not stored. */
    uint64_t offset;
    uint64_t csize;
    uint16_t method;
} entry;

#define CTX_POOL_SIZE 20
#define MAX_PARALLEL_DECOMP 4
#define max(a, b) ((a) > (b) ? (a) : (b))

static pthread_mutex_t ctx_pool_mutex = PTHREAD_MUTEX_INITIALIZER;
static archive_ctx **ctx_pool = NULL;
static pthread_mutex_t buffer_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *decode_buffer[MAX_PARALLEL_DECOMP];
static bool need_encrypt = false;
static char *passwd = NULL;
static void *archiveAddr = MAP_FAILED;
static size_t archiveSize = 0;
static bool archiveIsSmb = false;
static uint64_t archiveSmbHandle = 0;
static entry *entries = NULL;
static size_t entryCount = 0;
static size_t entries_capacity = 0;
static ssize_t max_file_size = 0;

/*
 * Remote sources cannot be mapped, so libarchive pulls from them through the
 * callbacks below instead of a memory buffer. The reader is per-context (the
 * pool can hold CTX_POOL_SIZE of them) and the window is allocated on first
 * read, so an idle context costs nothing.
 *
 * The window has to serve two access patterns that want opposite things.
 *
 * Listing the entries reads each entry's local header and then skips its data.
 * libarchive consumes the rest of whatever block the callback returned, so a
 * large window means the skipped data was transferred anyway: measured against
 * a 1985-entry archive, a 256 KiB window made the listing read 71% of the file
 * and a 4 KiB one 3.4%. On a share that is the difference between waiting for
 * the whole archive and waiting for a few megabytes.
 *
 * Showing a page reads one entry's data straight through, where a small window
 * costs a round trip per block.
 *
 * So grow the window while the reads stay contiguous, and drop it back to the
 * minimum whenever the reader skips -- a skip is the reader saying it is
 * discarding, not consuming, and there is no point reading ahead into it.
 */
#define SMB_WINDOW_MIN (4 * 1024)
#define SMB_WINDOW_MAX (256 * 1024)

typedef struct {
    uint64_t handle;
    int64_t size;
    int64_t pos;
    int64_t last_end;
    size_t window_size;
    uint8_t *window;
} smb_reader;

/* Implemented in Rust; see app/src/main/rust/src/ffi/archive.rs. */
extern ssize_t smb_archive_read(uint64_t handle, uint64_t offset, uint8_t *buf, size_t len);

static la_int64_t smb_seek_cb(struct archive *a, void *client_data, la_int64_t offset, int whence) {
    EH_UNUSED(a);
    smb_reader *reader = client_data;
    la_int64_t base;
    switch (whence) {
        case SEEK_SET:
            base = 0;
            break;
        case SEEK_CUR:
            base = reader->pos;
            break;
        case SEEK_END:
            base = reader->size;
            break;
        default:
            return -1;
    }
    la_int64_t next = base + offset;
    if (next < 0)
        return -1;
    reader->pos = next;
    /* A seek breaks the run: the next read must not be treated as a
     * continuation of the previous one. */
    reader->last_end = -1;
    return next;
}

static la_ssize_t smb_read_cb(struct archive *a, void *client_data, const void **buff) {
    EH_UNUSED(a);
    smb_reader *reader = client_data;
    if (reader->pos >= reader->size)
        return 0;
    if (!reader->window) {
        reader->window = malloc(SMB_WINDOW_MAX);
        if (!reader->window)
            return -1;
    }
    if (reader->window_size < SMB_WINDOW_MIN)
        reader->window_size = SMB_WINDOW_MIN;
    if (reader->pos == reader->last_end && reader->window_size < SMB_WINDOW_MAX)
        reader->window_size = reader->window_size * 2;
    size_t want = reader->window_size;
    if ((int64_t) want > reader->size - reader->pos)
        want = (size_t) (reader->size - reader->pos);
    ssize_t got = smb_archive_read(reader->handle, (uint64_t) reader->pos, reader->window, want);
    if (got < 0)
        return -1;
    /* A short result is not end-of-file. Only reaching the declared size is, and
     * the caller asked for a length, so a short block simply means libarchive
     * gets less than it hoped for and asks again. */
    reader->pos += got;
    reader->last_end = reader->pos;
    *buff = reader->window;
    return got;
}

static la_int64_t smb_skip_cb(struct archive *a, void *client_data, la_int64_t request) {
    EH_UNUSED(a);
    smb_reader *reader = client_data;
    reader->window_size = SMB_WINDOW_MIN;
    reader->last_end = -1;
    la_int64_t next = reader->pos + request;
    if (next > reader->size)
        next = reader->size;
    if (next < 0)
        return -1;
    la_int64_t skipped = next - reader->pos;
    reader->pos = next;
    return skipped;
}

static int smb_close_cb(struct archive *a, void *client_data) {
    EH_UNUSED(a);
    smb_reader *reader = client_data;
    free(reader->window);
    free(reader);
    return ARCHIVE_OK;
}

#define SUPPORT_EXT_COUNT 11

const char supportExt[SUPPORT_EXT_COUNT][5] = {
        "jpeg",
        "jpg",
        "png",
        "gif",
        "webp",
        "bmp",
        "ico",
        "wbmp",
        "heic",
        "heif",
        "avif"
};

static inline int filename_is_playable_file(const char *name) {
    if (!name)
        return false;
    const char *dotptr = strrchr(name, '.');
    if (!dotptr++)
        return false;
    int i;
    for (i = 0; i < SUPPORT_EXT_COUNT; i++)
        if (strcmp(dotptr, supportExt[i]) == 0)
            return true;
    return false;
}

static inline bool archive_entry_is_file(struct archive_entry *entry) {
    return archive_entry_filetype(entry) == AE_IFREG;
}

static inline bool archive_entry_is_playable(struct archive_entry *entry) {
    return archive_entry_is_file(entry) &&
           filename_is_playable_file(archive_entry_pathname(entry));
}

static inline int compare_entries(const void *a, const void *b) {
    const char *fa = ((entry *) a)->filename;
    const char *fb = ((entry *) b)->filename;
    return strnatcmp(fa, fb);
}

#define ADDR_IN_FILE_MAPPING(addr) (addr >= archiveAddr && addr < archiveAddr + archiveSize)

static bool fill_entry_zero_copy(struct archive *arc, entry *entry) {
    void *buffer = NULL;
    size_t buffer_size = 0;
    la_int64_t output_ofs = 0;
    archive_read_data_block(arc, (const void **) &buffer, &buffer_size, &output_ofs);
    bool zero_copy = ADDR_IN_FILE_MAPPING(buffer) && !output_ofs && buffer_size == entry->size;
    entry->addr = zero_copy ? buffer : NULL;
    return zero_copy;
}

/*
 * Read raw bytes from the archive source.
 */
static bool archive_read_at(void *ctx, uint64_t offset, void *buf, size_t len) {
    EH_UNUSED(ctx);
    if (offset > archiveSize || len > archiveSize - offset)
        return false;
    if (archiveIsSmb)
        return smb_archive_read(archiveSmbHandle, offset, buf, len) == (ssize_t) len;
    memcpy(buf, (const char *) archiveAddr + offset, len);
    return true;
}

static int by_local_offset(const void *a, const void *b) {
    uint64_t oa = ((const zip_cd_entry *) a)->offset;
    uint64_t ob = ((const zip_cd_entry *) b)->offset;
    return oa < ob ? -1 : oa > ob ? 1 : 0;
}

/*
 * Build the entry list from the zip central directory instead of walking.
 *
 * Only worth doing for a remote archive. libarchive's walk reads each entry's
 * local header, which over a share costs a round trip per entry -- 1985 of them
 * for a 1985-page archive -- while the central directory is one contiguous
 * region read in a handful of requests. Locally the walk is free, and it is what
 * finds the zero-copy addresses, so it stays for that case.
 *
 * Returns 0 if the archive is not a zip this can read, leaving the caller to
 * fall back to the walk.
 */
static size_t archive_map_entries_central_dir(bool sort) {
    zip_cd cd;
    if (!zip_cd_read(&cd, archive_read_at, NULL, archiveSize))
        return 0;
    /* libarchive walks entries ordered by local header offset, and `index` is
     * the position in that order, so the same ordering has to be used here. */
    qsort(cd.entries, cd.count, sizeof(zip_cd_entry), by_local_offset);
    size_t count = 0;
    for (size_t i = 0; i < cd.count; i++) {
        const zip_cd_entry *e = &cd.entries[i];
        if (zip_cd_is_directory(e) || !filename_is_playable_file(e->name))
            continue;
        if (count == entries_capacity) {
            entries_capacity = entries_capacity ? entries_capacity * 2 : 256;
            entries = realloc(entries, entries_capacity * sizeof(entry));
        }
        entries[count].filename = strdup(e->name);
        entries[count].index = count;
        entries[count].size = (ssize_t) e->usize;
        /* No mapping to point at, so no zero copy here. */
        entries[count].addr = NULL;
        entries[count].offset = e->offset;
        entries[count].csize = e->csize;
        entries[count].method = e->method;
        max_file_size = max((ssize_t) e->usize, max_file_size);
        count++;
    }
    zip_cd_free(&cd);
    if (sort) qsort(entries, count, sizeof(entry), compare_entries);
    return count;
}

/*
 * Fill `entries` with the playable entries, growing the array as it goes, and
 * return how many there are.
 *
 * Growing rather than counting first matters over a share: counting meant a
 * first walk of the whole archive, and every entry in a walk costs a round trip
 * for its local header. One pass halves that.
 */
static size_t archive_map_entries_index(archive_ctx *ctx, bool sort) {
    size_t count = 0;
    bool zero_copy = true;
    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname(ctx->entry);
        if (archive_entry_is_file(ctx->entry) && filename_is_playable_file(name)) {
            if (count == entries_capacity) {
                entries_capacity = entries_capacity ? entries_capacity * 2 : 256;
                entries = realloc(entries, entries_capacity * sizeof(entry));
            }
            entries[count].filename = strdup(name);
            entries[count].index = count;
            ssize_t size = archive_entry_size(ctx->entry);
            max_file_size = max(size, max_file_size);
            entries[count].size = size;
            entries[count].offset = 0;
            entries[count].csize = 0;
            entries[count].method = 0;
            // We don't expect zero copy if first content can't do zero copy
            if (zero_copy) zero_copy = fill_entry_zero_copy(ctx->arc, &entries[count]);
            count++;
        }
    }
    if (sort) qsort(entries, count, sizeof(entry), compare_entries);
    return count;
}

static void *acquire_decode_buffer() {
    void *addr = NULL;
    pthread_mutex_lock(&buffer_mutex);
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        addr = decode_buffer[i];
        if (addr) {
            decode_buffer[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&buffer_mutex);
    if (!addr) addr = malloc(max_file_size);
    return addr;
}

static void release_decode_buffer(void *buffer) {
    pthread_mutex_lock(&buffer_mutex);
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        void *addr = decode_buffer[i];
        if (!addr) {
            decode_buffer[i] = buffer;
            pthread_mutex_unlock(&buffer_mutex);
            return;
        }
    }
    pthread_mutex_unlock(&buffer_mutex);
    free(buffer);
}

static void archive_release_ctx(archive_ctx *ctx) {
    if (ctx) {
        archive_read_close(ctx->arc);
        archive_read_free(ctx->arc);
        free(ctx);
    }
}

static archive_ctx *archive_alloc_ctx() {
    archive_ctx *ctx = calloc(1, sizeof(archive_ctx));
    ctx->arc = archive_read_new();
    ctx->using = 1;
    archive_read_support_format_tar(ctx->arc);
    archive_read_support_format_7zip(ctx->arc);
    archive_read_support_format_rar5(ctx->arc);
    archive_read_support_format_zip(ctx->arc);
    archive_read_support_filter_gzip(ctx->arc);
    archive_read_support_filter_xz(ctx->arc);
    archive_read_set_option(ctx->arc, "zip", "ignorecrc32", "1");
    if (passwd)
        archive_read_add_passphrase(ctx->arc, passwd);
    int err;
    if (archiveIsSmb) {
        smb_reader *reader = calloc(1, sizeof(smb_reader));
        if (!reader) {
            archive_read_free(ctx->arc);
            free(ctx);
            return NULL;
        }
        reader->handle = archiveSmbHandle;
        reader->size = (int64_t) archiveSize;
        reader->last_end = -1;
        reader->window_size = SMB_WINDOW_MIN;
        /* The callbacks have to be in place before open1, because the format
         * bidders run during it and the seekable Zip bidder is the one that
         * decides to read through the central directory. */
        archive_read_set_callback_data(ctx->arc, reader);
        archive_read_set_read_callback(ctx->arc, smb_read_cb);
        archive_read_set_skip_callback(ctx->arc, smb_skip_cb);
        archive_read_set_seek_callback(ctx->arc, smb_seek_cb);
        archive_read_set_close_callback(ctx->arc, smb_close_cb);
        err = archive_read_open1(ctx->arc);
    } else {
        err = archive_read_open_memory(ctx->arc, archiveAddr, archiveSize);
    }
    if (err < ARCHIVE_OK) {
        LOGE("%s%s", "Open archive failed: ", archive_error_string(ctx->arc));
        archive_read_free(ctx->arc);
        free(ctx);
        return NULL;
    }
    return ctx;
}

static int archive_skip_to_index(archive_ctx *ctx, int index) {
    while (archive_read_next_header(ctx->arc, &ctx->entry) == ARCHIVE_OK) {
        if (!archive_entry_is_playable(ctx->entry))
            continue;
        if (ctx->next_index++ == index) {
            return ctx->next_index - 1;
        }
    }
    return ARCHIVE_FATAL;
}

static int archive_get_ctx(archive_ctx **ctxptr, int idx) {
    int ret;
    archive_ctx *ctx = NULL;
    pthread_mutex_lock(&ctx_pool_mutex);
    for (int i = 0; i < CTX_POOL_SIZE; i++) {
        if (!ctx_pool[i])
            continue;
        if (ctx_pool[i]->using)
            continue;
        if (ctx_pool[i]->next_index > idx)
            continue;
        if (!ctx || ctx_pool[i]->next_index > ctx->next_index)
            ctx = ctx_pool[i];
        if (ctx->next_index == idx)
            break;
    }
    if (ctx)
        ctx->using = 1;
    pthread_mutex_unlock(&ctx_pool_mutex);

    if (!ctx) {
        archive_ctx *victimCtx = NULL;
        int victimIdx = 0;
        int replace = 1;
        ctx = archive_alloc_ctx();
        pthread_mutex_lock(&ctx_pool_mutex);
        for (int i = 0; i < CTX_POOL_SIZE; i++) {
            if (!ctx_pool[i]) {
                ctx_pool[i] = ctx;
                replace = 0;
                break;
            }
            if (ctx_pool[i]->using)
                continue;
            if (!victimCtx || ctx_pool[i]->next_index > victimCtx->next_index) {
                victimCtx = ctx_pool[i];
                victimIdx = i;
            }
        }
        if (replace) ctx_pool[victimIdx] = ctx;
        pthread_mutex_unlock(&ctx_pool_mutex);
        if (replace) archive_release_ctx(victimCtx);
    }
    ret = archive_skip_to_index(ctx, idx);
    if (ret != idx) {
        ret = archive_errno(ctx->arc);
        LOGE("Skip to index failed: %s", archive_error_string(ctx->arc));
        archive_release_ctx(ctx);
        return ret;
    }
    *ctxptr = ctx;
    return 0;
}

static int archive_open_common(jboolean sort_entries) {
    archive_ctx *ctx = NULL;
    ctx_pool = calloc(CTX_POOL_SIZE, sizeof(archive_ctx **));
    ctx = archive_alloc_ctx();
    if (!ctx) return 0;

    /* A remote archive lists from its central directory; that is one contiguous
     * read instead of a round trip per entry. Anything the parser cannot handle
     * falls through to libarchive's walk, as does every local archive. */
    if (archiveIsSmb)
        entryCount = archive_map_entries_central_dir(sort_entries);
    if (!entryCount) {
        /* One walk for both the count and the map. Walking once to count and
         * again to fill meant paying for every entry's local header twice. */
        entryCount = archive_map_entries_index(ctx, sort_entries);
    }
    LOGI("%s%zu%s", "Found ", entryCount, " images in archive");
    if (!entryCount) {
        LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
        archive_release_ctx(ctx);
        return 0;
    }

    // We must read through the file|vm then we can know whether it is encrypted
    int encryptRet = archive_read_has_encrypted_entries(ctx->arc);
    switch (encryptRet) {
        case 1: // At lease 1 encrypted entry
            need_encrypt = true;
            break;
        case 0: // format supports but no encrypted entry found
        default:
            need_encrypt = false;
    }

    if (!archiveIsSmb) {
        int format = archive_format(ctx->arc);
        switch (format) {
            case ARCHIVE_FORMAT_ZIP:
            case ARCHIVE_FORMAT_RAR_V5:
                madvise_log_if_error(archiveAddr, archiveSize, MADV_SEQUENTIAL);
                break;
            case ARCHIVE_FORMAT_7ZIP: // Seek is bad
                madvise_log_if_error(archiveAddr, archiveSize, MADV_RANDOM);
                break;
            default:;
        }
    }
    archive_release_ctx(ctx);
    return (int) entryCount;
}

JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openArchive(JNIEnv *env, jclass thiz, jint fd, jlong size, jboolean sort_entries) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    archiveAddr = mmap(0, size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (archiveAddr == MAP_FAILED) {
        LOGE("%s%s", "mmap failed with error ", strerror(errno));
        return 0;
    }
    archiveSize = size;
    archiveIsSmb = false;
    archiveSmbHandle = 0;
    return archive_open_common(sort_entries);
}

/*
 * Open an archive that lives on a share, through an SMB handle rather than a
 * descriptor: a descriptor for a remote file is a pipe, and a pipe can neither
 * be mapped nor seeked, both of which reading an archive needs.
 */
JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_openArchiveSmb(JNIEnv *env, jclass thiz, jlong handle, jlong size, jboolean sort_entries) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (handle <= 0 || size <= 0) {
        LOGE("%s", "Invalid SMB archive source");
        return 0;
    }
    archiveAddr = MAP_FAILED;
    archiveSize = (size_t) size;
    archiveIsSmb = true;
    archiveSmbHandle = (uint64_t) handle;
    return archive_open_common(sort_entries);
}

/*
 * Read a stored entry's payload straight from its central directory offset.
 *
 * Walking to an entry costs a round trip for every entry before it, which is
 * what makes resuming at a late page slow: the reader remembers the page and
 * then has to walk there. A stored entry's payload sits at a known offset, so
 * two requests reach it regardless of position.
 *
 * Returns false when the entry is not stored (deflated needs libarchive) or a
 * read fails, leaving the caller to fall back to the walk.
 */
static bool archive_read_stored_entry(const entry *entry, void *buf, size_t size) {
    if (entry->method != 0 || entry->offset == 0 || entry->csize != (uint64_t) entry->size)
        return false;
    uint8_t hdr[30];
    if (!archive_read_at(NULL, entry->offset, hdr, sizeof(hdr)))
        return false;
    uint16_t name_len = (uint16_t) (hdr[26] | (hdr[27] << 8));
    uint16_t extra_len = (uint16_t) (hdr[28] | (hdr[29] << 8));
    return archive_read_at(NULL, entry->offset + 30 + name_len + extra_len, buf, size);
}

JNIEXPORT jobject JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToByteBuffer(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    entry *entry = &entries[index];
    ssize_t size = entry->size;
    if (entry->addr) {
        return (*env)->NewDirectByteBuffer(env, entry->addr, size);
    }
    /* Stored entries are reachable directly, which is what keeps a resumed read
     * from walking the whole archive to get to a late page. */
    if (entry->method == 0 && entry->offset != 0) {
        void *direct = acquire_decode_buffer();
        if (archive_read_stored_entry(entry, direct, size)) {
            return (*env)->NewDirectByteBuffer(env, direct, size);
        }
        release_decode_buffer(direct);
    }
    {
        archive_ctx *ctx = NULL;
        /* archive_get_ctx returns 0 on success. Entering this block on failure
         * dereferenced a null context -- which is what a dropped SMB session
         * leads to, because the archive cannot be reopened after it. */
        if (archive_get_ctx(&ctx, entry->index) == 0) {
            void *addr = acquire_decode_buffer();
            ssize_t bytes = archive_read_data(ctx->arc, addr, size);
            ctx->using = 0;
            if (bytes == size) {
                return (*env)->NewDirectByteBuffer(env, addr, size);
            } else {
                if (bytes < 0) {
                    LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
                } else {
                    LOGE("%s", "No enough data read, WTF?");
                }
            }
            release_decode_buffer(addr);
        } else {
            LOGE("%s%d", "No archive context available for entry ", entry->index);
        }
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_closeArchive(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    if (ctx_pool) {
        for (int i = 0; i < CTX_POOL_SIZE; i++)
            archive_release_ctx(ctx_pool[i]);
        free(ctx_pool);
        ctx_pool = NULL;
    }
    free(passwd);
    passwd = NULL;
    need_encrypt = false;
    if (archiveAddr != MAP_FAILED) {
        munmap(archiveAddr, archiveSize);
        archiveAddr = MAP_FAILED;
    }
    for (int i = 0; i < MAX_PARALLEL_DECOMP; ++i) {
        free(decode_buffer[i]);
        decode_buffer[i] = NULL;
    }
    max_file_size = 0;
    if (entries) {
        for (int i = 0; i < entryCount; ++i) {
            free((void *) entries[i].filename);
        }
        free(entries);
        entries = NULL;
    }
    entryCount = 0;
    entries_capacity = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_needPassword(JNIEnv *env, jclass thiz) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    return need_encrypt;
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_providePassword(JNIEnv *env, jclass thiz, jstring str) {
    EH_UNUSED(thiz);
    struct archive_entry *entry;
    archive_ctx *ctx;
    jboolean ret = true;
    int len = (*env)->GetStringUTFLength(env, str);
    passwd = realloc(passwd, len + 1);
    (*env)->GetStringUTFRegion(env, str, 0, len, passwd);
    passwd[len] = 0;
    ctx = archive_alloc_ctx();
    char tmpBuf[4096];
    while (archive_read_next_header(ctx->arc, &entry) == ARCHIVE_OK) {
        if (!archive_entry_is_playable(entry))
            continue;
        if (!archive_entry_is_encrypted(entry))
            continue;
        if (archive_read_data(ctx->arc, tmpBuf, 4096) < ARCHIVE_OK) {
            LOGE("%s%s", "Archive read failed: ", archive_error_string(ctx->arc));
            ret = false;
        }
        break;
    }
    archive_release_ctx(ctx);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_getExtension(JNIEnv *env, jclass thiz, jint index) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    const char *ext = strrchr(entries[index].filename, '.') + 1;
    return (*env)->NewStringUTF(env, ext);
}

JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_extractToFd(JNIEnv *env, jclass thiz, jint index, jint fd) {
    EH_UNUSED(env);
    EH_UNUSED(thiz);
    entry *e = &entries[index];
    if (e->method == 0 && e->offset != 0) {
        void *buf = acquire_decode_buffer();
        bool ok = archive_read_stored_entry(e, buf, e->size) && write(fd, buf, e->size) == e->size;
        release_decode_buffer(buf);
        return ok;
    }
    index = e->index;
    archive_ctx *ctx = NULL;
    int ret;
    ret = archive_get_ctx(&ctx, index);
    if (!ret) {
        ret = archive_read_data_into_fd(ctx->arc, fd);
        ctx->using = 0;
    }
    return ret == ARCHIVE_OK;
}

JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_releaseByteBuffer(JNIEnv *env, jclass thiz, jobject buffer) {
    EH_UNUSED(thiz);
    void *addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!ADDR_IN_FILE_MAPPING(addr)) {
        release_decode_buffer(addr);
    }
}

JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_jni_ArchiveKt_archiveFdBatch(JNIEnv *env, jclass clazz, jintArray fd_batch, jlongArray sizes, jobjectArray names, jint arc_fd, jint size) {
    EH_UNUSED(clazz);
    struct archive *arc = archive_write_new();
    struct stat st;
    char buff[8192];
    jint fdBatch[size];
    jlong sizeBatch[size];
    (*env)->GetIntArrayRegion(env, fd_batch, 0, size, fdBatch);
    (*env)->GetLongArrayRegion(env, sizes, 0, size, sizeBatch);
    archive_write_set_format_zip(arc);
    archive_write_zip_set_compression_store(arc);
    if (archive_write_open_fd(arc, arc_fd) != ARCHIVE_OK) {
        LOGE("%s%s", "archive open failed: ", archive_error_string(arc));
        archive_write_free(arc);
        return -1;
    }
    int written = 0;
    struct archive_entry *entry = archive_entry_new();
    for (int i = 0; i < size; i++) {
        int fd = fdBatch[i];
        jobject name = (*env)->GetObjectArrayElement(env, names, i);
        const char *cname = (*env)->GetStringUTFChars(env, name, false);
        archive_entry_set_pathname(entry, cname);
        (*env)->ReleaseStringUTFChars(env, name, cname);
        if (sizeBatch[i] >= 0) {
            // A descriptor for a remote file is a pipe, and a pipe's stat reports
            // a FIFO of size 0. libarchive refuses to archive a FIFO at all
            // ("zip format cannot archive named pipes"), which silently produced an
            // empty archive. The caller knows the real length, so use it and skip
            // the stat entirely.
            archive_entry_set_filetype(entry, AE_IFREG);
            archive_entry_set_size(entry, sizeBatch[i]);
        } else {
            fstat(fd, &st);
            archive_entry_copy_stat(entry, &st);
        }
        archive_entry_set_perm(entry, 0644);
        if (archive_write_header(arc, entry) != ARCHIVE_OK) {
            LOGE("%s%s", "archive header failed: ", archive_error_string(arc));
            archive_entry_clear(entry);
            continue;
        }
        ssize_t len;
        do {
            len = read(fd, buff, sizeof(buff));
            if (len > 0 && archive_write_data(arc, buff, (size_t)len) < 0) {
                LOGE("%s%s", "archive data failed: ", archive_error_string(arc));
                break;
            }
        } while (len > 0);
        if (archive_write_finish_entry(arc) == ARCHIVE_OK) {
            written++;
        }
        archive_entry_clear(entry);
    }
    archive_entry_free(entry);
    archive_write_close(arc);
    archive_write_free(arc);
    return written;
}
