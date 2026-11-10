# Cantonese mode

Wirl can dictate Cantonese, often mixed with English. This file holds the cleanup instructions for Cantonese, so Wirl for Android and Wirl for Windows send Claude exactly the same text.

## How it works (Android)

- **Speech to text.** In Settings › Dictation, the **Cantonese (粵語)** model is SenseVoice (`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09`, int8), run on the phone with sherpa-onnx. It's downloaded on first use from the sherpa-onnx `asr-models` release (166 MB, unpacked to about 237 MB), never bundled in the app. It runs with language `yue` and `use_itn` on.
- **Google fallback.** Until the model is downloaded, or if it can't run, dictation uses Google's recognizer in Cantonese (Hong Kong), `yue-Hant-HK`.
- **Cleanup.** When the Cantonese model or Google's Cantonese recognizer produced the transcript, the block below is added to the built-in core prompt, after it and before the speaker's style notes. Everything else (Understand, Resolve intent, Polish, the rules, the personal dictionary, style notes) stays the same. SenseVoice writes simplified characters without punctuation, so this block is also what turns the text into traditional characters with full-width punctuation.

## Checking quality (manual, your own key)

`evals/manual/cantonese_cases.json` holds Cantonese cleanup cases: inputs that look like SenseVoice output, and what the cleaned text should be. Run them by hand; they never run on GitHub:

```bash
WIRL_CANTONESE_EVAL=1 ANTHROPIC_API_KEY=sk-... ./gradlew testDebugUnitTest --tests org.wirl.CantoneseEvalTest
```

Haiku judges each output (traditional characters, spoken Cantonese, English kept, particles kept, full-width punctuation). Results go to `app/build/cantonese-eval/results.json`.

## The block

Keep this identical in both apps. On Android it's `CleanupPrompt.CANTONESE_MODE`, and `CantonesePromptTest` checks that it matches this file exactly.

```text
--- CANTONESE MODE ---
The speaker is talking Cantonese, often mixed with English.
- Write exactly as spoken, in written spoken Cantonese (口語), in traditional characters (Hong Kong style). For example: 佢, 嘅, 唔, 咗, 喺, 啲, 咁, 冇, 嚟, 睇.
- Never rewrite into formal written Chinese (書面語) or Mandarin phrasing, and never use simplified characters.
- Keep English words and phrases in English, exactly where they were spoken.
- If the transcript uses Mandarin-style characters where the speaker clearly said Cantonese, correct them to the Cantonese characters.
- Remove pure filler sounds (such as 呃 and 嗯) and false starts. Keep sentence-final particles (such as 呀, 啦, 喎, 囉, 嘛, 㗎, 啩) because they carry meaning and tone.
- Use full-width Chinese punctuation (，。？！：) in Chinese text.
- Polish lightly. The result should read like the speaker typed it themselves in a message.
- Apply the personal dictionary as usual.
--- END CANTONESE MODE ---
```
