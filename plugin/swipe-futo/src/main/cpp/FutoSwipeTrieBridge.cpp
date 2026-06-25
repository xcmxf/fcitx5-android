/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * This is an implementation of FUTO Swipe's public ITrie ABI. It is kept
 * deliberately small so the FUTO decoder and all GPL-covered code remain in
 * this separately-installed plugin APK.
 */
#include <jni.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

using TrieId = uint32_t;

struct ITrieVTable {
    int (*num_chars)(void *);
    TrieId (*root)(void *);
    int (*get_char_idx)(void *, TrieId);
    uint32_t (*get_child_count)(void *, TrieId);
    TrieId (*get_child)(void *, TrieId, uint32_t);
    bool (*is_word)(void *, TrieId);
    float (*get_log_frequency)(void *, TrieId);
    uint16_t (*get_depth)(void *, TrieId);
    const char *(*get_word)(void *, TrieId);
    void (*end_search)(void *);
};

struct ITrie {
    void *userdata;
    const ITrieVTable *vtable;
};

namespace {

constexpr int kAlphabetSize = 26;

struct Node {
    std::array<TrieId, kAlphabetSize> children{};
    uint8_t parent_char = 0;
    uint16_t depth = 0;
    bool word = false;
    float frequency = 0.0f;
    std::string value;
};

class Dictionary {
public:
    Dictionary() : native_{this, &kVTable} {
        nodes_.emplace_back();
    }

    bool load(const std::string &path) {
        std::ifstream file(path);
        if (!file) return false;

        std::string line;
        while (std::getline(file, line)) {
            const auto start = line.find_first_not_of(" \t");
            if (start == std::string::npos || line.compare(start, 5, "word=") != 0) continue;

            const auto word_start = start + 5;
            const auto word_end = line.find(',', word_start);
            if (word_end == std::string::npos || word_end == word_start) continue;
            const auto frequency_start = line.find(",f=", word_end);
            if (frequency_start == std::string::npos) continue;

            std::string word = line.substr(word_start, word_end - word_start);
            word.erase(
                std::remove_if(word.begin(), word.end(), [](unsigned char c) {
                    return !std::isalpha(c) || c > 0x7f;
                }),
                word.end()
            );
            std::transform(word.begin(), word.end(), word.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            if (word.empty()) continue;

            float frequency = 1.0f;
            try {
                frequency = std::stof(line.substr(frequency_start + 3));
            } catch (...) {
                continue;
            }
            insert(word, frequency);
        }
        return nodes_.size() > 1;
    }

    ITrie *native() { return &native_; }

private:
    static int numChars(void *) { return kAlphabetSize; }
    static TrieId root(void *) { return 0; }
    static int charIndex(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        return id < dictionary->nodes_.size() && id != 0 ? dictionary->nodes_[id].parent_char : -1;
    }
    static uint32_t childCount(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        if (id >= dictionary->nodes_.size()) return 0;
        return static_cast<uint32_t>(std::count_if(
            dictionary->nodes_[id].children.begin(),
            dictionary->nodes_[id].children.end(),
            [](TrieId child) { return child != 0; }
        ));
    }
    static TrieId child(void *self, TrieId id, uint32_t index) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        if (id >= dictionary->nodes_.size()) return 0;
        uint32_t seen = 0;
        for (TrieId candidate : dictionary->nodes_[id].children) {
            if (candidate == 0) continue;
            if (seen++ == index) return candidate;
        }
        return 0;
    }
    static bool isWord(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        return id < dictionary->nodes_.size() && dictionary->nodes_[id].word;
    }
    static float frequency(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        return id < dictionary->nodes_.size() ? dictionary->nodes_[id].frequency : 0.0f;
    }
    static uint16_t depth(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        return id < dictionary->nodes_.size() ? dictionary->nodes_[id].depth : 0;
    }
    static const char *word(void *self, TrieId id) {
        const auto *dictionary = static_cast<Dictionary *>(self);
        return id < dictionary->nodes_.size() ? dictionary->nodes_[id].value.c_str() : nullptr;
    }
    static void endSearch(void *) {}

    void insert(const std::string &word, float frequency) {
        TrieId id = 0;
        for (char character : word) {
            const auto index = static_cast<size_t>(character - 'a');
            if (index >= kAlphabetSize) return;
            auto next = nodes_[id].children[index];
            if (next == 0) {
                next = static_cast<TrieId>(nodes_.size());
                nodes_[id].children[index] = next;
                Node node;
                node.parent_char = static_cast<uint8_t>(index);
                node.depth = static_cast<uint16_t>(nodes_[id].depth + 1);
                nodes_.push_back(std::move(node));
            }
            id = next;
        }
        auto &node = nodes_[id];
        if (!node.word || frequency > node.frequency) {
            node.word = true;
            node.frequency = frequency;
            node.value = word;
        }
    }

    static const ITrieVTable kVTable;
    ITrie native_;
    std::vector<Node> nodes_;
};

const ITrieVTable Dictionary::kVTable = {
    &Dictionary::numChars,
    &Dictionary::root,
    &Dictionary::charIndex,
    &Dictionary::childCount,
    &Dictionary::child,
    &Dictionary::isWord,
    &Dictionary::frequency,
    &Dictionary::depth,
    &Dictionary::word,
    &Dictionary::endSearch
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_plugin_swipe_1futo_FutoSwipeTrieBridge_load(
    JNIEnv *env,
    jobject,
    jstring path
) {
    if (!path) return 0;
    const char *raw_path = env->GetStringUTFChars(path, nullptr);
    if (!raw_path) return 0;
    auto *dictionary = new Dictionary();
    const bool loaded = dictionary->load(raw_path);
    env->ReleaseStringUTFChars(path, raw_path);
    if (!loaded) {
        delete dictionary;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(dictionary->native()));
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_plugin_swipe_1futo_FutoSwipeTrieBridge_destroy(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto *native = reinterpret_cast<ITrie *>(static_cast<uintptr_t>(handle));
    delete static_cast<Dictionary *>(native ? native->userdata : nullptr);
}
