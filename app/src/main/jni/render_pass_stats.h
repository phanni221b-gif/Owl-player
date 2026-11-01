#pragma once
#include <mpv/client.h>
#include <cstring>
#include <cstdint>
#include <limits>

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
    const auto *fresh = owl_map_value(root, "fresh");
    if (!fresh || fresh->format != MPV_FORMAT_NODE_ARRAY || !fresh->u.list) return result;
    for (int i = 0; i < fresh->u.list->num; ++i) {
        const auto &pass = fresh->u.list->values[i];
        ++result.passes;
        const auto *desc = owl_map_value(pass, "desc");
        if (desc && desc->format == MPV_FORMAT_STRING && desc->u.string) {
            // Only the completed RGB reconstruction proves the CNN reached the output.
            result.neural8 |= std::strstr(desc->u.string, "Owl FSRCNNX 8 RGB reconstruction") != nullptr;
            result.neural16 |= std::strstr(desc->u.string, "Owl FSRCNNX 16 RGB reconstruction") != nullptr;
        }
        const auto *avg = owl_map_value(pass, "avg");
        if (avg && avg->format == MPV_FORMAT_INT64 && avg->u.int64 >= 0 &&
            avg->u.int64 <= std::numeric_limits<int64_t>::max() - result.average_ns)
            result.average_ns += avg->u.int64;
    }
    return result;
}
