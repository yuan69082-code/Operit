# Gemini tool result boundary

The November 1 device log shows successful text-only tool exchanges, followed by HTTP 400 after parallel sticker view/send calls. The serialized final content has role `user` and parts `[functionResponse, functionResponse, inline_data]`. The server nevertheless reports `Requests ending with a model turn are not supported`. This is not evidence that the application's final role was `model`.

Gemini history serialization now places non-response parts before function responses in mixed user turns. It retains image bytes, text, all results, and the relative order of parallel responses. Ordinary user turns and model signatures are unchanged. It applies to historical turns too, so existing conversations are not left with the rejected ordering. This is wire ordering only; no tool is re-executed and no synthetic user message is added.

HTTP 400 is now terminal in GeminiProvider rather than replaying the same invalid request five times and labelling it a connection timeout. Other status handling is unchanged.

The same response-last ordering requirement has an independently reported direct Vertex reproduction for `gemini-4-argon`: https://github.com/decolua/9router/issues/4802. Google's parallel function calling documentation requires preserving the positional order of responses: https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/tools/function-calling. The stable partition retains that order.

Regression coverage includes the two-sticker-response/image case, plain image input, and parallel text-only results. Android CI runs the tests; device verification must confirm the original sticker exchange completes. The separate long waits for successful HTTP 200 responses are not addressed by this change.
