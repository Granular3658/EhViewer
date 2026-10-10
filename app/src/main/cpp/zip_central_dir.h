/*
 * Reading a zip's central directory directly.
 *
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

#ifndef EHVIEWER_ZIP_CENTRAL_DIR_H
#define EHVIEWER_ZIP_CENTRAL_DIR_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Why this exists
 * ---------------
 *
 * libarchive's zip reader reads each entry's local header as it walks, so
 * listing an archive costs one round trip per entry -- 1985 of them for a
 * 1985-page archive, and there is no option that avoids it. The central
 * directory holds the same information (name, sizes, local header offset) in one
 * contiguous region, so reading it is a handful of requests no matter how many
 * entries there are.
 *
 * Entries come back in central directory order. libarchive walks them ordered by
 * local header offset, so callers that index into the archive have to sort by
 * `offset` to match.
 */

typedef struct {
    char *name;              /* decoded to UTF-8, owned by the zip_cd */
    uint64_t usize;          /* uncompressed size */
    uint64_t csize;          /* compressed size */
    uint64_t offset;         /* local header offset */
    uint32_t external_attrs; /* DOS byte plus Unix mode, for directory detection */
    uint16_t flags;
    uint16_t method;
    uint16_t disk_start;
} zip_cd_entry;

typedef struct {
    zip_cd_entry *entries;
    size_t count;
    size_t capacity;
} zip_cd;

/*
 * Read `len` bytes at `offset`. Returns false on failure. This is the only thing
 * the parser needs from its caller, so it can be driven by a local buffer or by
 * a network source.
 */
typedef bool (*zip_read_at_fn)(void *ctx, uint64_t offset, void *buf, size_t len);

/*
 * Fill `out` from the archive. Returns false if the archive is not a zip this
 * can parse, in which case the caller should fall back to libarchive's walk.
 */
bool zip_cd_read(zip_cd *out, zip_read_at_fn read_at, void *ctx, uint64_t file_size);

void zip_cd_free(zip_cd *cd);

/* Whether the entry names a directory rather than a file. */
bool zip_cd_is_directory(const zip_cd_entry *entry);

#ifdef __cplusplus
}
#endif

#endif /* EHVIEWER_ZIP_CENTRAL_DIR_H */
