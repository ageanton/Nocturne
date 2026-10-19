#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <cmath>
#include <mutex>
#include <vector>
#include <algorithm>

#define TSF_IMPLEMENTATION
#include "tsf.h"

class StudioReverb {
private:
    std::vector<float> comb1, comb2, comb3, comb4;
    int idx1 = 0, idx2 = 0, idx3 = 0, idx4 = 0;
    float lp1 = 0.0f, lp2 = 0.0f, lp3 = 0.0f, lp4 = 0.0f;

public:
    StudioReverb() {
        comb1.resize(1687, 0.0f);
        comb2.resize(1933, 0.0f);
        comb3.resize(2251, 0.0f);
        comb4.resize(2579, 0.0f);
    }

    inline void process(float &left, float &right, float wet, float roomSize, float stereoWidth) {
        if (wet <= 0.001f) return;
        float input = (left + right) * 0.25f;
        float feedback = 0.72f + (roomSize * 0.22f);

        float o1 = comb1[idx1];
        lp1 = o1 * 0.65f + lp1 * 0.35f;
        comb1[idx1] = input + lp1 * feedback;
        if (++idx1 >= (int)comb1.size()) idx1 = 0;

        float o2 = comb2[idx2];
        lp2 = o2 * 0.65f + lp2 * 0.35f;
        comb2[idx2] = input + lp2 * feedback;
        if (++idx2 >= (int)comb2.size()) idx2 = 0;

        float o3 = comb3[idx3];
        lp3 = o3 * 0.65f + lp3 * 0.35f;
        comb3[idx3] = input + lp3 * feedback;
        if (++idx3 >= (int)comb3.size()) idx3 = 0;

        float o4 = comb4[idx4];
        lp4 = o4 * 0.65f + lp4 * 0.35f;
        comb4[idx4] = input + lp4 * feedback;
        if (++idx4 >= (int)comb4.size()) idx4 = 0;

        float wetL = (o1 + o3) * 0.5f;
        float wetR = (o2 + o4) * 0.5f;

        float mid = (wetL + wetR) * 0.5f;
        float side = (wetL - wetR) * 0.5f * stereoWidth;
        wetL = mid + side;
        wetR = mid - side;

        left = left * (1.0f - wet * 0.35f) + wetL * wet;
        right = right * (1.0f - wet * 0.35f) + wetR * wet;
    }
};

class NocturneAudioEngine {
private:
    AAudioStream *stream = nullptr;
    tsf *soundFont = nullptr;
    std::mutex synthMutex;
    StudioReverb reverb;

    bool sustainActive = false;
    bool softPedalActive = false;
    bool sustainedNotes[128] = {false};
    bool heldNotes[128] = {false};

    int currentPresetIndex = 0;
    float masterVolume = 0.88f;
    float reverbMix = 0.28f;
    float roomSize = 0.60f;
    float stereoWidth = 1.25f;

    const int gmProgramMap[11] = {
        0,  // 0: Concert Grand
        1,  // 1: Studio Grand
        3,  // 2: Upright Piano
        0,  // 3: Felt Piano
        4,  // 4: Rhodes Electric Piano
        24, // 5: Nylon Guitar
        49, // 6: Soft Strings
        0,  // 7: Lo-fi Piano
        2,  // 8: Tape Piano
        8,  // 9: Celeste
        5   // 10: Neon Synth Piano
    };

    static aaudio_data_callback_result_t dataCallback(
            AAudioStream *stream,
            void *userData,
            void *audioData,
            int32_t numFrames) {
        return static_cast<NocturneAudioEngine *>(userData)->renderAudio(
                static_cast<float *>(audioData), numFrames);
    }

    static void errorCallback(
            AAudioStream *stream,
            void *userData,
            aaudio_result_t error) {
        if (error == AAUDIO_ERROR_DISCONNECTED) {
            // Audio routing changed (e.g., headphones plugged/unplugged)
            static_cast<NocturneAudioEngine *>(userData)->restartStream();
        }
    }

public:
    bool init(AAssetManager *assetManager) {
        if (!soundFont) {
            AAsset *asset = AAssetManager_open(assetManager, "nocturne_studio.sf2", AASSET_MODE_BUFFER);
            if (!asset) return false;

            const void *data = AAsset_getBuffer(asset);
            off_t size = AAsset_getLength(asset);
            soundFont = tsf_load_memory(data, size);
            AAsset_close(asset);

            if (!soundFont) return false;
        }
        return startStream();
    }

    bool startStream() {
        AAudioStreamBuilder *builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);

        if (result != AAUDIO_OK || !stream) return false;

        int32_t sampleRate = AAudioStream_getSampleRate(stream);
        {
            std::lock_guard<std::mutex> lock(synthMutex);
            tsf_set_output(soundFont, TSF_STEREO_INTERLEAVED, sampleRate, 0.0f);
            tsf_set_max_voices(soundFont, 64);
            tsf_channel_set_presetnumber(soundFont, 0, gmProgramMap[currentPresetIndex], 0);
        }

        AAudioStream_requestStart(stream);
        return true;
    }

    void restartStream() {
        if (stream) {
            AAudioStream_close(stream);
            stream = nullptr;
        }
        startStream();
    }

    void noteOn(int midiNote, float velocity) {
        if (midiNote < 0 || midiNote > 127 || !soundFont) return;
        std::lock_guard<std::mutex> lock(synthMutex);

        heldNotes[midiNote] = true;
        sustainedNotes[midiNote] = false;

        float shapedVel = std::pow(std::clamp(velocity, 0.05f, 1.0f), 0.85f);
        if (softPedalActive) shapedVel *= 0.62f;

        if (currentPresetIndex == 3) {
            shapedVel *= 0.58f;
        } else if (currentPresetIndex == 7) {
            tsf_channel_set_pitchwheel(soundFont, 0, 8140);
        } else {
            tsf_channel_set_pitchwheel(soundFont, 0, 8192);
        }

        tsf_channel_note_off(soundFont, 0, midiNote);
        tsf_channel_note_on(soundFont, 0, midiNote, shapedVel);
    }

    void noteOff(int midiNote) {
        if (midiNote < 0 || midiNote > 127 || !soundFont) return;
        std::lock_guard<std::mutex> lock(synthMutex);

        heldNotes[midiNote] = false;
        if (sustainActive) {
            sustainedNotes[midiNote] = true;
        } else {
            tsf_channel_note_off(soundFont, 0, midiNote);
        }
    }

    void setSustain(bool active) {
        std::lock_guard<std::mutex> lock(synthMutex);
        sustainActive = active;
        if (!sustainActive && soundFont) {
            for (int i = 0; i < 128; ++i) {
                if (sustainedNotes[i] && !heldNotes[i]) {
                    tsf_channel_note_off(soundFont, 0, i);
                    sustainedNotes[i] = false;
                }
            }
        }
    }

    void setSoftPedal(bool active) {
        std::lock_guard<std::mutex> lock(synthMutex);
        softPedalActive = active;
    }

    void setInstrument(int index) {
        if (index < 0 || index >= 11 || !soundFont) return;
        std::lock_guard<std::mutex> lock(synthMutex);
        currentPresetIndex = index;
        tsf_channel_set_presetnumber(soundFont, 0, gmProgramMap[index], 0);
    }

    void setControls(float vol, float rev, float room, float width) {
        std::lock_guard<std::mutex> lock(synthMutex);
        masterVolume = std::clamp(vol, 0.0f, 1.5f);
        reverbMix = std::clamp(rev, 0.0f, 1.0f);
        roomSize = std::clamp(room, 0.1f, 1.0f);
        stereoWidth = std::clamp(width, 0.5f, 2.0f);
    }

    aaudio_data_callback_result_t renderAudio(float *output, int32_t numFrames) {
        if (!soundFont) {
            std::fill(output, output + numFrames * 2, 0.0f);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }

        {
            std::lock_guard<std::mutex> lock(synthMutex);
            tsf_render_float(soundFont, output, numFrames, 0);
        }

        float vol = masterVolume;
        float rev = reverbMix;
        float room = roomSize;
        float width = stereoWidth;

        for (int i = 0; i < numFrames; ++i) {
            float L = output[i * 2] * vol;
            float R = output[i * 2 + 1] * vol;

            reverb.process(L, R, rev, room, width);

            output[i * 2] = std::tanh(L * 1.4f);
            output[i * 2 + 1] = std::tanh(R * 1.4f);
        }

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
};

static NocturneAudioEngine engine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nocturne_piano_MainActivity_initEngine(JNIEnv *env, jobject, jobject assetManager) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    return engine.init(mgr);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeNoteOn(JNIEnv *, jobject, jint note, jfloat velocity) {
    engine.noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeNoteOff(JNIEnv *, jobject, jint note) {
    engine.noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeSetSustain(JNIEnv *, jobject, jboolean active) {
    engine.setSustain(active);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeSetSoftPedal(JNIEnv *, jobject, jboolean active) {
    engine.setSoftPedal(active);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeSetInstrument(JNIEnv *, jobject, jint index) {
    engine.setInstrument(index);
}

JNIEXPORT void JNICALL
Java_com_nocturne_piano_MainActivity_nativeSetControls(JNIEnv *, jobject, jfloat vol, jfloat rev, jfloat room, jfloat width) {
    engine.setControls(vol, rev, room, width);
}

}
