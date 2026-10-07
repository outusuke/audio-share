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

#ifndef OPUS_ENCODER_HPP
#define OPUS_ENCODER_HPP

#ifdef AUDIO_SHARE_WITH_OPUS

#include <opus.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "client.pb.h"

/**
 * Turns a stream of raw PCM chunks (arbitrary size, any AudioFormat encoding)
 * into fixed 20 ms Opus packets.
 *
 * The Opus stream is always 48 kHz (Android's decoder always outputs 48 kHz), so any
 * other capture rate (typically 44.1 kHz) is linearly resampled first. Only mono and
 * stereo are supported.
 *
 * Not thread safe: use from the capture thread only.
 */
class opus_stream_encoder {
public:
    using Encoding = io::github::mkckr0::audio_share_app::pb::AudioFormat::Encoding;

    static constexpr int frame_ms = 20;
    static constexpr int max_packet_size = 1275; // maximum size of one Opus packet

    static constexpr int output_rate = 48000;

    /** Returns nullptr (and sets `error`) if the format can't be compressed. */
    static std::unique_ptr<opus_stream_encoder> create(Encoding encoding, int channels, int sample_rate, int bitrate, std::string& error)
    {
        if (channels < 1 || channels > 2) {
            error = "opus compression only supports mono and stereo";
            return nullptr;
        }
        if (bytes_per_sample(encoding) == 0) {
            error = "unsupported PCM encoding";
            return nullptr;
        }
        if (sample_rate < 8000 || sample_rate > 384000) {
            error = "unsupported sample rate";
            return nullptr;
        }

        auto e = std::unique_ptr<opus_stream_encoder>(new opus_stream_encoder());
        e->_encoding = encoding;
        e->_channels = channels;
        e->_in_rate = sample_rate;
        e->_out_rate = output_rate;
        e->_frame_samples = e->_out_rate * frame_ms / 1000;
        e->_step = (double)e->_in_rate / e->_out_rate;

        int err = OPUS_OK;
        e->_enc = opus_encoder_create(e->_out_rate, channels, OPUS_APPLICATION_AUDIO, &err);
        if (err != OPUS_OK || !e->_enc) {
            error = opus_strerror(err);
            return nullptr;
        }
        if (bitrate > 0) {
            opus_encoder_ctl(e->_enc, OPUS_SET_BITRATE(bitrate));
        }
        return e;
    }

    ~opus_stream_encoder()
    {
        if (_enc) {
            opus_encoder_destroy(_enc);
        }
    }

    opus_stream_encoder(const opus_stream_encoder&) = delete;
    opus_stream_encoder& operator=(const opus_stream_encoder&) = delete;

    /** Sample rate of the decoded stream the client will see. */
    int output_sample_rate() const { return _out_rate; }
    int channels() const { return _channels; }

    /**
     * Feed raw PCM. `emit(const uint8_t* packet, size_t size)` is invoked once per
     * completed 20 ms Opus packet. Leftover audio (<20 ms) is kept for the next call.
     */
    template <typename Emit>
    void encode(const uint8_t* data, size_t count, Emit&& emit)
    {
        const int bps = bytes_per_sample(_encoding);
        const size_t samples = count / bps / _channels * _channels; // whole frames only
        convert_to_float(data, samples);

        if (_out_rate == _in_rate) {
            _pcm.insert(_pcm.end(), _conv.begin(), _conv.end());
        } else {
            resample();
        }

        const size_t frame_len = (size_t)_frame_samples * _channels;
        size_t offset = 0;
        uint8_t packet[max_packet_size];
        while (_pcm.size() - offset >= frame_len) {
            int n = opus_encode_float(_enc, _pcm.data() + offset, _frame_samples, packet, (opus_int32)sizeof(packet));
            offset += frame_len;
            if (n > 1) { // n == 1 means DTX, nothing worth sending; n < 0 is an error
                emit((const uint8_t*)packet, (size_t)n);
            }
        }
        _pcm.erase(_pcm.begin(), _pcm.begin() + offset);
    }

private:
    opus_stream_encoder() = default;

    static int bytes_per_sample(Encoding e)
    {
        using E = io::github::mkckr0::audio_share_app::pb::AudioFormat;
        switch (e) {
        case E::ENCODING_PCM_8BIT: return 1;
        case E::ENCODING_PCM_16BIT: return 2;
        case E::ENCODING_PCM_24BIT: return 3;
        case E::ENCODING_PCM_32BIT:
        case E::ENCODING_PCM_FLOAT: return 4;
        default: return 0;
        }
    }

    // All PCM encodings are little endian (see client.proto); 8 bit is unsigned.
    void convert_to_float(const uint8_t* p, size_t samples)
    {
        using E = io::github::mkckr0::audio_share_app::pb::AudioFormat;
        _conv.resize(samples);
        switch (_encoding) {
        case E::ENCODING_PCM_8BIT:
            for (size_t i = 0; i < samples; ++i) {
                _conv[i] = ((int)p[i] - 128) / 128.0f;
            }
            break;
        case E::ENCODING_PCM_16BIT:
            for (size_t i = 0; i < samples; ++i) {
                int16_t v = (int16_t)(p[2 * i] | (p[2 * i + 1] << 8));
                _conv[i] = v / 32768.0f;
            }
            break;
        case E::ENCODING_PCM_24BIT:
            for (size_t i = 0; i < samples; ++i) {
                int32_t v = (int32_t)((uint32_t)p[3 * i] << 8 | (uint32_t)p[3 * i + 1] << 16 | (uint32_t)p[3 * i + 2] << 24) >> 8;
                _conv[i] = v / 8388608.0f;
            }
            break;
        case E::ENCODING_PCM_32BIT:
            for (size_t i = 0; i < samples; ++i) {
                uint32_t u = (uint32_t)p[4 * i] | (uint32_t)p[4 * i + 1] << 8 | (uint32_t)p[4 * i + 2] << 16 | (uint32_t)p[4 * i + 3] << 24;
                _conv[i] = (int32_t)u / 2147483648.0f;
            }
            break;
        case E::ENCODING_PCM_FLOAT:
            std::memcpy(_conv.data(), p, samples * sizeof(float));
            break;
        default:
            std::fill(_conv.begin(), _conv.end(), 0.0f);
            break;
        }
    }

    // Streaming linear resampler. `_rs_in` holds input frames not yet fully consumed;
    // `_rs_pos` is the fractional read position (in input frames) inside it.
    void resample()
    {
        _rs_in.insert(_rs_in.end(), _conv.begin(), _conv.end());
        const size_t ch = _channels;
        const size_t frames = _rs_in.size() / ch;

        while ((size_t)_rs_pos + 1 < frames) {
            const size_t i = (size_t)_rs_pos;
            const float frac = (float)(_rs_pos - (double)i);
            for (size_t c = 0; c < ch; ++c) {
                const float a = _rs_in[i * ch + c];
                const float b = _rs_in[(i + 1) * ch + c];
                _pcm.push_back(a + (b - a) * frac);
            }
            _rs_pos += _step;
        }

        const size_t consumed = std::min((size_t)_rs_pos, frames);
        _rs_in.erase(_rs_in.begin(), _rs_in.begin() + consumed * ch);
        _rs_pos -= (double)consumed;
    }

    OpusEncoder* _enc = nullptr;
    Encoding _encoding {};
    int _channels = 0;
    int _in_rate = 0;
    int _out_rate = 0;
    int _frame_samples = 0;
    double _step = 1.0;
    double _rs_pos = 0.0;
    std::vector<float> _conv;
    std::vector<float> _rs_in;
    std::vector<float> _pcm;
};

#endif // AUDIO_SHARE_WITH_OPUS

#endif // !OPUS_ENCODER_HPP
