#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

static uint32_t read32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
        ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int is_prologue(uint32_t ins) {
    uint32_t masked = ins & 0xffc07fffU;
    return masked == 0xa9807bfdU || masked == 0xa9007bfdU ||
        (ins & 0xffc003ffU) == 0xd10003ffU;
}

int main(int argc, char **argv) {
    const uint64_t text_file_offset = 0x64c64e0;
    const uint64_t text_address = 0x64c64e0;
    const uint64_t text_size = 0xc44afcc;
    if (argc < 3) {
        fprintf(stderr, "usage: %s libminecraftpe.so target-address ...\n", argv[0]);
        return 2;
    }

    uint64_t targets[128];
    int target_count = argc - 2;
    if (target_count > 128) {
        fprintf(stderr, "too many targets\n");
        return 2;
    }
    for (int i = 0; i < target_count; i++) {
        targets[i] = strtoull(argv[i + 2], NULL, 0) - 0x100000;
    }

    FILE *file = fopen(argv[1], "rb");
    if (!file) {
        perror(argv[1]);
        return 1;
    }
    if (fseek(file, (long)text_file_offset, SEEK_SET) != 0) {
        perror("fseek");
        fclose(file);
        return 1;
    }
    unsigned char *text = malloc((size_t)text_size);
    if (!text || fread(text, 1, (size_t)text_size, file) != text_size) {
        fprintf(stderr, "failed to read .text section\n");
        free(text);
        fclose(file);
        return 1;
    }
    fclose(file);

    unsigned long hits = 0;
    unsigned long page_hits = 0;
    for (uint64_t offset = 0; offset + 16 <= text_size; offset += 4) {
        uint32_t adrp = read32(text + offset);
        if ((adrp & 0x9f000000U) != 0x90000000U) {
            continue;
        }
        int64_t immediate = (int64_t)((((adrp >> 5) & 0x7ffffU) << 2) |
            ((adrp >> 29) & 3U));
        if (immediate & (1 << 20)) {
            immediate |= ~((1 << 21) - 1);
        }
        uint64_t pc = text_address + offset;
        uint64_t page = (pc & ~0xfffULL) + (immediate << 12);
        uint32_t base_register = adrp & 0x1fU;

        int page_match = 0;
        for (int t = 0; t < target_count; t++) {
            if (page == (targets[t] & ~0xfffULL)) {
                page_match = 1;
                break;
            }
        }
        if (page_match && page_hits < 300) {
            uint32_t next[3] = {0, 0, 0};
            uint64_t resolved[3] = {0, 0, 0};
            for (int step = 1; step <= 3; step++) {
                next[step - 1] = read32(text + offset + step * 4);
                uint32_t ins = next[step - 1];
                if ((ins & 0x7f000000U) == 0x11000000U &&
                        ((ins >> 5) & 0x1fU) == base_register) {
                    resolved[step - 1] = page + (((ins >> 10) & 0xfffU) <<
                        (((ins >> 22) & 1U) ? 12 : 0));
                }
            }
            printf("adrp=0x%llx page=0x%llx next=%08x,%08x,%08x targets=0x%llx,0x%llx,0x%llx\n",
                (unsigned long long)(pc + 0x100000),
                (unsigned long long)(page + 0x100000),
                next[0], next[1], next[2],
                (unsigned long long)(resolved[0] ? resolved[0] + 0x100000 : 0),
                (unsigned long long)(resolved[1] ? resolved[1] + 0x100000 : 0),
                (unsigned long long)(resolved[2] ? resolved[2] + 0x100000 : 0));
            page_hits++;
        }

        for (int step = 1; step <= 3; step++) {
            uint32_t add = read32(text + offset + step * 4);
            if ((add & 0x7f000000U) != 0x11000000U ||
                    ((add >> 5) & 0x1fU) != base_register) {
                continue;
            }
            uint64_t target = page + (((add >> 10) & 0xfffU) <<
                (((add >> 22) & 1U) ? 12 : 0));
            for (int t = 0; t < target_count; t++) {
                if (target != targets[t]) {
                    continue;
                }
                uint64_t floor = offset > 0x4000 ? offset - 0x4000 : 0;
                uint64_t start = offset & ~3ULL;
                while (start >= floor) {
                    if (is_prologue(read32(text + start))) {
                        break;
                    }
                    if (start < 4) {
                        break;
                    }
                    start -= 4;
                }
                printf("xref=0x%llx target=0x%llx start_guess=0x%llx\n",
                    (unsigned long long)(pc + step * 4 + 0x100000),
                    (unsigned long long)(target + 0x100000),
                    (unsigned long long)(text_address + start + 0x100000));
                hits++;
                break;
            }
            break;
        }
    }
    printf("references=%lu page_references=%lu\n", hits, page_hits);
    free(text);
    return 0;
}
