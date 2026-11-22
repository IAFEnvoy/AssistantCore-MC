package com.iafenvoy.assistant.speech.asr;

import com.iafenvoy.assistant.speech.SpeechException;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Local PCM16/16 kHz capture boundary; optional microphone backends never leak into ASR providers.
 */
public interface RecognitionCapture extends AsrProvider.AudioSource, AutoCloseable {
    /**
     * Local lines close without a checked failure; narrow the inherited signature so the caller needs no catch.
     */
    @Override
    void close();

    void open() throws SpeechException;

    void finish();

    static boolean voicechatInstalled() {
        return FMLEnvironment.getDist().isClient() && ModList.get().isLoaded("voicechat");
    }

    static RecognitionCapture create() throws SpeechException {
        if (voicechatInstalled()) {
            try {
                return (RecognitionCapture) Class.forName("com.iafenvoy.assistant.compat.voicechat.VoicechatRecognitionCapture")
                        .getConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError error) {
                LogUtils.getLogger().warn("Voice chat microphone adapter unavailable ({})", error.getClass().getSimpleName());
                throw new SpeechException("voicechat");
            }
        }
        return new MicrophoneCapture();
    }
}
