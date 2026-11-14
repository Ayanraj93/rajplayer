#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <vector>

#if __has_include(<lame/lame.h>)
#include <lame/lame.h>
#else
#include <lame.h>
#endif

namespace {
struct EncoderState {
    lame_global_flags* flags = nullptr;
    int channels = 2;
};

EncoderState* stateFrom(jlong handle) {
    return reinterpret_cast<EncoderState*>(static_cast<intptr_t>(handle));
}

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

jbyteArray toByteArray(JNIEnv* env, const std::vector<unsigned char>& bytes, int count) {
    if (count <= 0) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(count);
    if (result) {
        env->SetByteArrayRegion(
            result, 0, count, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_raj_rajplayer_Mp3Native_nativeCreate(
    JNIEnv* env, jobject /* thiz */, jint sampleRate, jint channels, jint bitrateKbps) {
    if (sampleRate < 8000 || sampleRate > 48000 ||
        (channels != 1 && channels != 2) || bitrateKbps < 32 || bitrateKbps > 320) {
        throwIllegalState(env, "Unsupported MP3 encoder settings");
        return 0;
    }

    auto* state = new EncoderState();
    state->channels = channels;
    state->flags = lame_init();
    if (!state->flags) {
        delete state;
        throwIllegalState(env, "LAME initialization failed");
        return 0;
    }

    int rc = 0;
    rc |= lame_set_num_channels(state->flags, channels);
    rc |= lame_set_in_samplerate(state->flags, sampleRate);
    rc |= lame_set_out_samplerate(state->flags, sampleRate);
    rc |= lame_set_brate(state->flags, bitrateKbps);
    rc |= lame_set_quality(state->flags, 3);
    rc |= lame_set_bWriteVbrTag(state->flags, 0);
    if (channels == 1) {
        rc |= lame_set_mode(state->flags, MONO);
    } else {
        rc |= lame_set_mode(state->flags, JOINT_STEREO);
    }
    if (rc < 0 || lame_init_params(state->flags) < 0) {
        lame_close(state->flags);
        delete state;
        throwIllegalState(env, "LAME could not configure this audio stream");
        return 0;
    }

    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_raj_rajplayer_Mp3Native_nativeEncode(
    JNIEnv* env, jobject /* thiz */, jlong handle, jshortArray pcm, jint samplesPerChannel) {
    EncoderState* state = stateFrom(handle);
    if (!state || !state->flags || !pcm || samplesPerChannel <= 0) {
        throwIllegalState(env, "Invalid MP3 encoder input");
        return nullptr;
    }

    const jsize pcmLength = env->GetArrayLength(pcm);
    const int expected = samplesPerChannel * state->channels;
    if (expected <= 0 || pcmLength < expected) {
        throwIllegalState(env, "PCM buffer is shorter than the declared sample count");
        return nullptr;
    }

    std::vector<short> samples(static_cast<size_t>(expected));
    env->GetShortArrayRegion(pcm, 0, expected, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    const size_t capacity = static_cast<size_t>(1.25 * samplesPerChannel + 7200);
    std::vector<unsigned char> encoded(capacity);
    int written = 0;
    if (state->channels == 1) {
        written = lame_encode_buffer(
            state->flags, samples.data(), samples.data(), samplesPerChannel,
            encoded.data(), static_cast<int>(encoded.size()));
    } else {
        written = lame_encode_buffer_interleaved(
            state->flags, samples.data(), samplesPerChannel,
            encoded.data(), static_cast<int>(encoded.size()));
    }

    if (written < 0) {
        throwIllegalState(env, "LAME failed while encoding PCM samples");
        return nullptr;
    }
    return toByteArray(env, encoded, written);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_raj_rajplayer_Mp3Native_nativeFlush(
    JNIEnv* env, jobject /* thiz */, jlong handle) {
    EncoderState* state = stateFrom(handle);
    if (!state || !state->flags) {
        throwIllegalState(env, "Invalid MP3 encoder handle");
        return nullptr;
    }

    std::vector<unsigned char> tail(7200);
    const int written = lame_encode_flush(
        state->flags, tail.data(), static_cast<int>(tail.size()));
    if (written < 0) {
        throwIllegalState(env, "LAME failed to flush the MP3 stream");
        return nullptr;
    }
    return toByteArray(env, tail, written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_raj_rajplayer_Mp3Native_nativeClose(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    EncoderState* state = stateFrom(handle);
    if (!state) return;
    if (state->flags) lame_close(state->flags);
    delete state;
}
