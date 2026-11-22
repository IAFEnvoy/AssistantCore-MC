# Assistant Core

Assistant Core is a standalone NeoForge dependency mod for Minecraft 26.1.2. It provides local intent routing, OpenAI-compatible chat completions, request budgets, and DeepSeek and Aliyun speech providers.

| Capability | API |
| --- | --- |
| Local intent matching | `LexicalIndex`, `IntentRouter`, `RouteCache` |
| Model requests and accounting | `ModelClient`, `OpenAiCompatibleClient`, `ModelSettings`, `ModelGateway` |
| Request execution | `ExecutionBudget`, `TaskExecutor` |
| Text to speech | `TtsProvider`, `DeepSeekTtsProvider`, `AliyunTtsProvider`, `SpeechAudio` |
| Speech to text | `AsrProvider`, `DeepSeekAsrProvider`, `AliyunAsrProvider`, `PcmWav` |

The host supplies credentials, configuration and the available actions. It owns game logic, audio capture and playback, and usage storage. Protocol serialization uses Gson and Mojang DataFixerUpper, which are supplied by Minecraft in the mod runtime.

## Build and install

Use Java 25 and run `./gradlew jar` or `gradlew.bat jar`. The build produces `build/libs/assistant-core-<version>.jar`. Install it alongside System or Psychic on the client and server.

## Develop with System or Psychic

Place the `AssistantCore` checkout alongside the consumer's checkout. Their Gradle settings link it as `:assistant-core`. For a different checkout location, pass `-Passistant_core_dir=/path/to/AssistantCore`. Each Gradle root writes its own build outputs.

The consumer GitHub workflows check out `<owner>/AssistantCore` on `main` alongside their own source. The repository and ref can be set through the `ASSISTANT_CORE_REPOSITORY` and `ASSISTANT_CORE_REF` repository variables.
