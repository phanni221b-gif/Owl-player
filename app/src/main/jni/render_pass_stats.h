#pragma once
#include <mpv/client.h>
#include <cstring>
#include <cstdint>
#include <limits>

constexpr uint64_t OWL_RENDER_QUERY_TAG = 0x4F574C0000000000ULL;
constexpr uint64_t OWL_RENDER_QUERY_MASK = 0xFFFFFFFFULL;

// Read-only NODE traversal. Never returns pass descriptions or private file paths to Java.
struct OwlRenderPassStats {
    bool neural8 = false;
    bool neural16 = false;
    int passes = 0;
    int64_t average_ns = 0;
};
inline const mpv_node *owl_map_value(const mpv_node &node, const char *key) {
    if (node.format != MPV_FORMAT_NODE_MAP || !node.u.list) return nullptr;
    for (int i = 0; i < node.u.list->num; ++i)
        if (node.u.list->keys[i] && std::strcmp(node.u.list->keys[i], key) == 0)
            return &node.u.list->values[i];
    return nullptr;
}
inline OwlRenderPassStats owl_render_pass_stats(const mpv_node &root) {
    OwlRenderPassStats result;
    const char *types[] = {"fresh", "redraw"};
    for (const char *type : types) {
        const auto *frame = owl_map_value(root, type);
        if (!frame || frame->format != MPV_FORMAT_NODE_ARRAY || !frame->u.list) continue;
        int passes = 0;
        int64_t average_ns = 0;
        for (int i = 0; i < frame->u.list->num; ++i) {
            const auto &pass = frame->u.list->values[i];
            ++passes;
            const auto *desc = owl_map_value(pass, "desc");
            if (desc && desc->format == MPV_FORMAT_STRING && desc->u.string) {
                // Fresh/redraw are separate histories, not simultaneously stacked shaders.
                result.neural8 |= std::strstr(desc->u.string, "Owl FSRCNNX 8 RGB reconstruction") != nullptr;
                result.neural16 |= std::strstr(desc->u.string, "Owl FSRCNNX 16 RGB reconstruction") != nullptr;
            }
            const auto *avg = owl_map_value(pass, "avg");
            if (avg && avg->format == MPV_FORMAT_INT64 && avg->u.int64 >= 0 &&
                avg->u.int64 <= std::numeric_limits<int64_t>::max() - average_ns)
                average_ns += avg->u.int64;
        }
        // Prefer fresh frame timing; use redraw only before a fresh sample exists.
        if (result.passes == 0) { result.passes = passes; result.average_ns = average_ns; }
    }
    return result;
}
