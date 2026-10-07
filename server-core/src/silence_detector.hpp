/*
   Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

#ifndef SILENCE_DETECTOR_HPP
#define SILENCE_DETECTOR_HPP

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "client.pb.h"

namespace silence {

// Peak amplitude (relative to full scale) at or below which a buffer counts as
// silence. 0.0001 is about -80 dBFS: it ignores dither / noise-floor residue.
constexpr float threshold = 0.0001f;

/**
 * Returns true if every sample in the PCM buffer is at or below `threshold`.
 * PCM is little endian (see client.proto); 8 bit is unsigned.
 * Returns false (i.e. "not silent") for unknown encodings so audio is never dropped by mistake.
 */
inline bool is_silent(const uint8_t* p, size_t count, io::github::mkckr0::audio_share_app::pb::AudioFormat::Encoding encoding)
{
    using E = io::github::mkckr0::audio_share_app::pb::AudioFormat;
    switch (encoding) {
    case E::ENCODING_PCM_8BIT: {
        const int limit = (int)(threshold * 128.0f);
        for (size_t i = 0; i < count; ++i) {
            if (std::abs((int)p[i] - 128) > limit) {
                return false;
            }
        }
        return true;
    }
    case E::ENCODING_PCM_16BIT: {
        const int limit = (int)(threshold * 32768.0f);
        for (size_t i = 0; i + 1 < count; i += 2) {
            int v = (int16_t)(p[i] | (p[i + 1] << 8));
            if (std::abs(v) > limit) {
                return false;
            }
        }
        return true;
    }
    case E::ENCODING_PCM_24BIT: {
        const int limit = (int)(threshold * 8388608.0f);
        for (size_t i = 0; i + 2 < count; i += 3) {
            int32_t v = (int32_t)((uint32_t)p[i] << 8 | (uint32_t)p[i + 1] << 16 | (uint32_t)p[i + 2] << 24) >> 8;
            if (std::abs(v) > limit) {
                return false;
            }
        }
        return true;
    }
    case E::ENCODING_PCM_32BIT: {
        const int64_t limit = (int64_t)(threshold * 2147483648.0f);
        for (size_t i = 0; i + 3 < count; i += 4) {
            uint32_t u = (uint32_t)p[i] | (uint32_t)p[i + 1] << 8 | (uint32_t)p[i + 2] << 16 | (uint32_t)p[i + 3] << 24;
            int64_t v = (int32_t)u;
            if ((v < 0 ? -v : v) > limit) {
                return false;
            }
        }
        return true;
    }
    case E::ENCODING_PCM_FLOAT: {
        for (size_t i = 0; i + 3 < count; i += 4) {
            float f;
            std::memcpy(&f, p + i, sizeof(f));
            // `!(<=)` also treats NaN as non-silent
            if (!(f <= threshold && f >= -threshold)) {
                return false;
            }
        }
        return true;
    }
    default:
        return false;
    }
}

} // namespace silence

#endif // !SILENCE_DETECTOR_HPP
