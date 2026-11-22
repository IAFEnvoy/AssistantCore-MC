package com.iafenvoy.assistant.registry;

import com.iafenvoy.assistant.credential.CredentialManager;
import com.iafenvoy.assistant.credential.SpeechCredentials;
import com.iafenvoy.assistant.speech.aliyun.AliyunAsrProvider;
import com.iafenvoy.assistant.speech.aliyun.AliyunTtsProvider;
import com.iafenvoy.assistant.speech.asr.AsrProvider;
import com.iafenvoy.assistant.speech.deepseek.DeepSeekAsrProvider;
import com.iafenvoy.assistant.speech.deepseek.DeepSeekTtsProvider;
import com.iafenvoy.assistant.speech.tts.TtsProvider;

public final class AssistantServices {
    public static final CredentialManager<TtsProvider> TTS = new CredentialManager<TtsProvider>()
            .register(SpeechCredentials.DeepSeek.class, credential -> new DeepSeekTtsProvider(credential.apiKey()))
            .register(SpeechCredentials.DashScope.class, credential -> new AliyunTtsProvider(credential.apiKey(), credential.workspace()));
    public static final CredentialManager<AsrProvider> ASR = new CredentialManager<AsrProvider>()
            .register(SpeechCredentials.DeepSeek.class, credential -> new DeepSeekAsrProvider(credential.apiKey()))
            .register(SpeechCredentials.AliyunNls.class, credential ->
                    new AliyunAsrProvider(credential.appKey(), credential.accessKeyId(), credential.accessKeySecret()));

    private AssistantServices() {
    }
}
