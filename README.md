# Assistant Core

Assistant Core is a standalone NeoForge dependency mod for Minecraft 26.1.2. It provides Agent networking, local and
model-assisted routing, workflow and tool execution, AI access controls, and speech providers.

| Capability                    | API                                                                                                                       |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| Routing and commands          | `HybridRouter`, `SemanticRouter`, `IntentRouter`, `CommandRegistry`                                                       |
| Model requests and accounting | `ModelClient`, `OpenAiCompatibleClient`, `ModelSettings`, `ModelGateway`                                                  |
| Network transport             | `AssistantChannel`, `ConnectionSessions`, `AgentRequest`, `AgentResponse`, `ResultResponses`                              |
| Execution and persistence     | `InteractiveRequests`, `WorkflowExecutor`, `EventExecutor`, `ToolExecutor`, `ModelExecutor`, `AgentStore`, `AgentJournal` |
| ReAct                         | `ReActLoop`, `ReActContext`, `ReActTool`                                                                                  |
| AI access                     | `ConversationMode`, `AiAccess`, `AiAccessData`, `AiStatus`, `ModelQuota`, `AiManagementCommands`                          |
| Registration                  | `AssistantResourceKeys`, `AgentAction`, `Credential`, `CredentialManager`, `ScopedRegistry`                               |
| Text to speech                | `TtsProvider`, `DeepSeekTtsProvider`, `AliyunTtsProvider`, `SpeechAudio`                                                  |
| Speech to text                | `AsrProvider`, `DeepSeekAsrProvider`, `AliyunAsrProvider`, `PcmWav`                                                       |
| Client microphone             | `RecognitionRecording`, `RecognitionCapture`, optional Simple Voice Chat integration                                      |

The host supplies credentials, configuration and the available actions. It owns game logic, microphone session lifetime,
audio playback, and usage storage. Protocol serialization uses Gson and Mojang DataFixerUpper, which Minecraft supplies.

## Register actions, credentials and messages

Assistant Core creates `assistant_core:action_type`, `assistant_core:credential_type`,
`assistant_core:agent_request_type` and `assistant_core:agent_response_type` during NeoForge registry creation. Use
`DeferredRegister.create(AssistantResourceKeys.ACTION_TYPE, MOD_ID)` or `AssistantResourceKeys.CREDENTIAL_TYPE` on your
mod bus to contribute typed codecs. Actions serialize their entry ID as `moduleId`, and credentials serialize theirs as
`type`. Each mod supplies its own entry namespace.

Use `ModuleRegistry` and `ToolRegistry` for per-instance workflow and tool registrations. It resolves an enabled local
or exported implementation and can be frozen once assembly is complete. Use a `CredentialManager` for each service to
map credential implementations to provider factories.

`AssistantServices.TTS` and `AssistantServices.ASR` are shared provider factories. Assistant Core registers DeepSeek for
both capabilities, DashScope for TTS, and Aliyun NLS for ASR. Supply a matching `SpeechCredentials` value with the
host's credentials, or register another credential class and its factory on the relevant manager. Each call to `create`
returns a new provider that the caller closes after use.

## Extend text annotations

Register a `TextAnnotationProcessor` in `assistant_core:annotation_processor` to annotate input for your mod's scope.
For a custom payload, implement `TextAnnotations.Annotation` and register its `StreamCodec` in the synchronized
`assistant_core:annotation_type` registry on both sides. Return that same codec from `streamCodec()` and use `withSpan()`
to support slicing. Consumers can retrieve the payload with `TextAnnotations.ofType`. Offsets refer to the original text
in UTF-16 units. The host validates annotation data against its current game state before using it.

Reference and quantity annotations are built in. A reference candidate can identify a registry entry or a tag.
`RegistryTextNames.collectTags` indexes localized tag names from the supplied registry.

## Connect a host mod

Create an `AssistantChannel` with the host namespace and a codec for its optional request attachment. Register the
channel with the host's NeoForge payload registrar and bind the client handler on the physical client. Register extra
message codecs in the shared request and response registries. `AgentResult.codec` takes the host's card codec, and
`ResultResponses` supplies reply and notice codecs for that result type. `ConnectionSessions` associates asynchronous
replies with the original player connection.

Supply enabled workflow scopes, storage transactions and prompts to the execution components. The host owns gameplay
authority, conversation history presentation and the choice of persistent storage. ReAct takes a `MapCodec` and JSON
Schema for each tool and answer.

Attach `AiManagementCommands` to the host's Brigadier root. Its host adapter supplies world policy storage, player
permissions and the callback that refreshes online sessions. The four modes are `offline`, `standard`, `extended` and
`free`. The host supplies its default daily limit and SavedData ID. System uses `/system ai` and keeps
`system:ai_access` as its SavedData ID.

## Client microphone

Create a `RecognitionRecording` per user-started ASR session. Call `open()` on a background worker, then pass the
recording
to the provider as its audio source. `finish()` ends capture and leaves the buffered frames available for recognition.
`close()` cancels capture and clears the buffer.

With Simple Voice Chat installed, Assistant Core uses its selected input device for independent 16 kHz PCM capture
and suppresses voice chat transmission during capture. Java Sound supplies the default input when Simple Voice Chat
is absent. The integration is built against Simple Voice Chat 2.6.26+26.1.2, a compile-only and local development
dependency.

## Build and install

Use Java 25 and run `./gradlew build` or `gradlew.bat build`. The build produces
`build/libs/assistant-core-<version>.jar`.
ModDev provides client, server, game-test and data generation runs. `publish` writes to the local `repo/` directory.
Install it alongside System or Psychic on the client and server.

## Develop with System or Psychic

Place the `AssistantCore` checkout alongside the consumer's checkout. Their Gradle settings link it as
`:assistant-core`. For a different checkout location, pass `-Passistant_core_dir=/path/to/AssistantCore`. Each Gradle
root writes its own build outputs.

The consumer GitHub workflows check out `<owner>/AssistantCore` on `main` alongside their own source. The repository and
ref can be set through the `ASSISTANT_CORE_REPOSITORY` and `ASSISTANT_CORE_REF` repository variables.
