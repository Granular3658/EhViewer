/*
 * Read a zip's central directory directly.
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

#include "zip_central_dir.h"

#include <stdlib.h>
#include <string.h>

#define EOCD_SIG 0x06054b50u
#define EOCD64_SIG 0x06064b50u
#define EOCD64_LOCATOR_SIG 0x07064b50u
#define CDH_SIG 0x02014b50u
#define ZIP64_EXTRA_ID 0x0001u
#define MAX_COMMENT 65535u
#define EOCD_MIN 22u
/* Flags bit 11: the name is UTF-8. Otherwise the spec says CP437. */
#define FLAG_UTF8 0x800u

static uint16_t rd16(const uint8_t *p) {
    return (uint16_t) (p[0] | (p[1] << 8));
}

static uint32_t rd32(const uint8_t *p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

static uint64_t rd64(const uint8_t *p) {
    return (uint64_t) rd32(p) | ((uint64_t) rd32(p + 4) << 32);
}

/*
 * CP437 for 0x80..0xFF. The zip spec makes this the default when the UTF-8 flag
 * is clear, which is what libarchive does too, so names decoded here match the
 * ones it would have produced.
 */
static const uint16_t cp437_high[128] = {
    0x00C7, 0x00FC, 0x00E9, 0x00E2, 0x00E4, 0x00E0, 0x00E5, 0x00E7, 0x00EA, 0x00EB, 0x00E8, 0x00EF, 0x00EE, 0x00EC, 0x00C4, 0x00C5,
    0x00C9, 0x00E6, 0x00C6, 0x00F4, 0x00F6, 0x00F2, 0x00FB, 0x00F9, 0x00FF, 0x00D6, 0x00DC, 0x00A2, 0x00A3, 0x00A5, 0x20A7, 0x0192,
    0x00E1, 0x00ED, 0x00F3, 0x00FA, 0x00F1, 0x00D1, 0x00AA, 0x00BA, 0x00BF, 0x2310, 0x00AC, 0x00BD, 0x00BC, 0x00A1, 0x00AB, 0x00BB,
    0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255D, 0x255C, 0x255B, 0x2510,
    0x2514, 0x2534, 0x252C, 0x251C, 0x2500, 0x253C, 0x255E, 0x255F, 0x255A, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256C, 0x2567,
    0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256B, 0x256A, 0x2518, 0x250C, 0x2588, 0x2584, 0x258C, 0x2590, 0x2580,
    0x03B1, 0x00DF, 0x0393, 0x03C0, 0x03A3, 0x03C3, 0x00B5, 0x03C4, 0x03A6, 0x0398, 0x03A9, 0x03B4, 0x221E, 0x03C6, 0x03B5, 0x2229,
    0x2261, 0x00B1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00F7, 0x2248, 0x00B0, 0x2219, 0x00B7, 0x221A, 0x207F, 0x00B2, 0x25A0, 0x00A0,
};

static size_t utf8_encode(uint32_t cp, char *out) {
    if (cp < 0x80) {
        out[0] = (char) cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = (char) (0xC0 | (cp >> 6));
        out[1] = (char) (0x80 | (cp & 0x3F));
        return 2;
    }
    out[0] = (char) (0xE0 | (cp >> 12));
    out[1] = (char) (0x80 | ((cp >> 6) & 0x3F));
    out[2] = (char) (0x80 | (cp & 0x3F));
    return 3;
}

/*
 * Decode a central directory name into UTF-8. With the UTF-8 flag set the bytes
 * are already UTF-8 and are copied through; otherwise they are CP437 code points
 * and are widened, which can triple the length.
 */
static char *decode_name(const uint8_t *raw, size_t len, uint16_t flags) {
    if (flags & FLAG_UTF8) {
        char *out = malloc(len + 1);
        if (!out)
            return NULL;
        memcpy(out, raw, len);
        out[len] = '\0';
        return out;
    }
    char *out = malloc(len * 3 + 1);
    if (!out)
        return NULL;
    size_t n = 0;
    for (size_t i = 0; i < len; i++) {
        uint8_t c = raw[i];
        n += utf8_encode(c < 0x80 ? c : cp437_high[c - 0x80], out + n);
    }
    out[n] = '\0';
    return out;
}

static bool read_exact(zip_read_at_fn read_at, void *ctx, uint64_t offset, void *buf, size_t len) {
    return len == 0 || read_at(ctx, offset, buf, len);
}

/*
 * Pull the size and offset for an entry out of its zip64 extra field, for the
 * fields the central directory left as 0xFFFFFFFF.
 */
static void apply_zip64_extra(const uint8_t *extra, size_t extra_len, zip_cd_entry *out) {
    size_t pos = 0;
    while (pos + 4 <= extra_len) {
        uint16_t id = rd16(extra + pos);
        uint16_t size = rd16(extra + pos + 2);
        pos += 4;
        if (pos + size > extra_len)
            return;
        if (id != ZIP64_EXTRA_ID) {
            pos += size;
            continue;
        }
        /* The fields appear in a fixed order, but only the ones that were
         * 0xFFFFFFFF are present, so consume them in that order. */
        const uint8_t *p = extra + pos;
        size_t left = size;
        if (out->usize == 0xFFFFFFFFu && left >= 8) {
            out->usize = rd64(p);
            p += 8;
            left -= 8;
        }
        if (out->csize == 0xFFFFFFFFu && left >= 8) {
            out->csize = rd64(p);
            p += 8;
            left -= 8;
        }
        if (out->offset == 0xFFFFFFFFu && left >= 8) {
            out->offset = rd64(p);
            p += 8;
            left -= 8;
        }
        if (out->disk_start == 0xFFFFu && left >= 4)
            out->disk_start = rd32(p);
        return;
    }
}

/* Locate the end-of-central-directory record and, if needed, its zip64 pair. */
static bool find_eocd(zip_read_at_fn read_at, void *ctx, uint64_t file_size,
                      uint64_t *cd_offset, uint64_t *cd_size, uint64_t *count) {
    uint64_t tail = file_size < MAX_COMMENT + EOCD_MIN ? file_size : MAX_COMMENT + EOCD_MIN;
    if (tail < EOCD_MIN)
        return false;
    uint8_t *buf = malloc(tail);
    if (!buf)
        return false;
    bool ok = false;
    if (!read_exact(read_at, ctx, file_size - tail, buf, tail))
        goto done;

    /* Scan backwards: a comment can contain anything, so the last match wins.
     * `at` is the offset just past the record, so the signature sits EOCD_MIN
     * bytes before it and the comment, if any, runs from `at` to the end. */
    for (size_t at = (size_t) tail; at >= EOCD_MIN; at--) {
        if (rd32(buf + at - EOCD_MIN) != EOCD_SIG)
            continue;
        uint16_t comment_len = rd16(buf + at - 2);
        /* The comment has to fit, but the record does not have to end exactly at
         * the end of the file: writers leave trailing bytes (this app's own
         * archives carry a few kilobytes of them), and libarchive and every
         * other reader tolerate that. */
        if ((uint64_t) at + comment_len > tail)
            continue;
        const uint8_t *e = buf + at - EOCD_MIN;
        *count = rd16(e + 10);
        *cd_size = rd32(e + 12);
        *cd_offset = rd32(e + 16);

        bool needs64 = *count == 0xFFFFu || *cd_size == 0xFFFFFFFFu || *cd_offset == 0xFFFFFFFFu;
        uint64_t eocd_pos = file_size - tail + at - EOCD_MIN;
        if (!needs64) {
            ok = true;
            goto done;
        }
        /* The zip64 locator sits immediately before the EOCD. */
        if (eocd_pos < 20)
            goto done;
        uint8_t loc[20];
        if (!read_exact(read_at, ctx, eocd_pos - 20, loc, sizeof(loc)))
            goto done;
        if (rd32(loc) != EOCD64_LOCATOR_SIG)
            goto done;
        uint64_t eocd64_pos = rd64(loc + 8);
        uint8_t e64[56];
        if (!read_exact(read_at, ctx, eocd64_pos, e64, sizeof(e64)))
            goto done;
        if (rd32(e64) != EOCD64_SIG)
            goto done;
        *count = rd64(e64 + 32);
        *cd_size = rd64(e64 + 40);
        *cd_offset = rd64(e64 + 48);
        ok = true;
        goto done;
    }
done:
    free(buf);
    return ok;
}

bool zip_cd_read(zip_cd *out, zip_read_at_fn read_at, void *ctx, uint64_t file_size) {
    out->entries = NULL;
    out->count = 0;
    out->capacity = 0;

    uint64_t cd_offset, cd_size, count;
    if (!find_eocd(read_at, ctx, file_size, &cd_offset, &cd_size, &count))
        return false;
    if (count == 0)
        return false;
    if (cd_offset + cd_size > file_size)
        return false;

    uint8_t *buf = malloc(cd_size);
    if (!buf)
        return false;
    if (!read_exact(read_at, ctx, cd_offset, buf, cd_size)) {
        free(buf);
        return false;
    }

    size_t pos = 0;
    while (pos + 46 <= cd_size) {
        const uint8_t *e = buf + pos;
        if (rd32(e) != CDH_SIG)
            break;
        uint16_t name_len = rd16(e + 28);
        uint16_t extra_len = rd16(e + 30);
        uint16_t comment_len = rd16(e + 32);
        if (pos + 46 + name_len + extra_len + comment_len > cd_size)
            break;

        zip_cd_entry entry;
        entry.name = NULL;
        entry.flags = rd16(e + 8);
        entry.method = rd16(e + 10);
        entry.csize = rd32(e + 20);
        entry.usize = rd32(e + 24);
        entry.disk_start = rd16(e + 34);
        entry.external_attrs = rd32(e + 38);
        entry.offset = rd32(e + 42);
        apply_zip64_extra(e + 46 + name_len, extra_len, &entry);

        entry.name = decode_name(e + 46, name_len, entry.flags);
        if (!entry.name) {
            zip_cd_free(out);
            free(buf);
            return false;
        }

        if (out->count == out->capacity) {
            size_t next = out->capacity ? out->capacity * 2 : 256;
            zip_cd_entry *grown = realloc(out->entries, next * sizeof(zip_cd_entry));
            if (!grown) {
                free(entry.name);
                zip_cd_free(out);
                free(buf);
                return false;
            }
            out->entries = grown;
            out->capacity = next;
        }
        out->entries[out->count++] = entry;
        pos += 46 + name_len + extra_len + comment_len;
    }

    free(buf);
    return out->count > 0;
}

void zip_cd_free(zip_cd *cd) {
    for (size_t i = 0; i < cd->count; i++)
        free(cd->entries[i].name);
    free(cd->entries);
    cd->entries = NULL;
    cd->count = 0;
    cd->capacity = 0;
}

/* Whether the entry is a directory, which the central directory records in the
 * external attributes (DOS bit 4, or a Unix mode with no write bits) rather than
 * in a dedicated field. */
bool zip_cd_is_directory(const zip_cd_entry *entry) {
    size_t len = strlen(entry->name);
    if (len > 0 && entry->name[len - 1] == '/')
        return true;
    uint32_t dos = entry->external_attrs & 0xFF;
    if (dos & 0x10)
        return true;
    uint32_t unix_mode = entry->external_attrs >> 16;
    return unix_mode != 0 && (unix_mode & 0xF000) == 0x4000;
}
