#include "../app/src/main/jni/render_pass_stats.h"
#include <cassert>
#include <climits>
mpv_node string_node(const char *text) { mpv_node n = {}; n.format = MPV_FORMAT_STRING; n.u.string = const_cast<char *>(text); return n; }
mpv_node int_node(int64_t value) { mpv_node n = {}; n.format = MPV_FORMAT_INT64; n.u.int64 = value; return n; }
mpv_node list_node(mpv_format format, mpv_node_list *list) { mpv_node n = {}; n.format = format; n.u.list = list; return n; }
int main() {
    mpv_node empty = {}; assert(owl_render_pass_stats(empty).passes == 0);
    char *keys[] = {const_cast<char *>("desc"), const_cast<char *>("avg")};
    mpv_node first_values[] = {string_node("Owl FSRCNNX 8 RGB reconstruction"), int_node(150)};
    mpv_node second_values[] = {string_node("/private/personal/movie.mp4"), int_node(250)};
    mpv_node_list first = {2, first_values, keys}, second = {2, second_values, keys};
    mpv_node passes[] = {list_node(MPV_FORMAT_NODE_MAP, &first), list_node(MPV_FORMAT_NODE_MAP, &second)};
    mpv_node_list fresh = {2, passes, nullptr};
    mpv_node root_values[] = {list_node(MPV_FORMAT_NODE_ARRAY, &fresh)};
    char *root_keys[] = {const_cast<char *>("fresh")};
    mpv_node_list root_list = {1, root_values, root_keys};
    auto root = list_node(MPV_FORMAT_NODE_MAP, &root_list);
    auto result = owl_render_pass_stats(root);
    assert(result.neural8 && !result.neural16 && result.passes == 2 && result.average_ns == 400);
    first_values[0] = string_node("Owl FSRCNNX 16 feature map 1");
    result = owl_render_pass_stats(root); assert(!result.neural8 && !result.neural16);
    first_values[0] = string_node("Owl FSRCNNX 16 RGB reconstruction");
    first_values[1] = int_node(LLONG_MAX);
    result = owl_render_pass_stats(root); assert(result.neural16 && !result.neural8 && result.average_ns == LLONG_MAX);
}
