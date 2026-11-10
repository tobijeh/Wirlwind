# Mandarin mode

Wirl can dictate Mandarin Chinese, often mixed with English, written in **simplified characters** (mainland China style). This file holds the cleanup instructions for Mandarin, so Wirl for Android and Wirl for Windows send Claude exactly the same text. Cantonese works the same way; see `docs/cantonese.md`.

## How it works (Android)

- **Speech to text.** In Settings › Dictation, **Mandarin (普通话)** uses the same SenseVoice model files as Cantonese (one 166 MB download serves both), told the language `zh` instead of `yue`. `use_itn` is on.
- **Google fallback.** Until the model is downloaded, or if it can't run, dictation uses Google's recognizer in Mandarin (China), `zh-CN`.
- **Cleanup.** When the Mandarin model or Google's Mandarin recognizer produced the transcript, the block below is added to the built-in core prompt, after it and before the speaker's style notes. Everything else stays the same.

## Checking quality (manual, your own key)

`evals/manual/mandarin_cases.json` holds Mandarin cleanup cases. Run them by hand; they never run on GitHub:

```bash
WIRL_CANTONESE_EVAL=1 ANTHROPIC_API_KEY=sk-... ./gradlew testDebugUnitTest --tests org.wirl.CantoneseEvalTest
```

(The same command runs both the Cantonese and the Mandarin sets.)

## The block

Keep this identical in both apps. On Android it's `CleanupPrompt.MANDARIN_MODE`, and `CantonesePromptTest` checks that it matches this file exactly.

```text
--- MANDARIN MODE ---
The speaker is talking Mandarin Chinese, often mixed with English.
- Write in natural written Mandarin as the speaker said it, in simplified characters (mainland China style).
- Never use traditional characters, and never use Cantonese words or characters (such as 佢, 嘅, 唔, 咗, 喺).
- Keep English words and phrases in English, exactly where they were spoken.
- Fix characters the speech recognizer got wrong, where a character with the same sound (同音字) clearly makes more sense.
- Remove filler sounds and words (such as 嗯, 呃, 那个, 就是说) and false starts. Keep sentence-final particles (such as 吧, 呢, 啊, 嘛) when they carry meaning or tone.
- Use full-width Chinese punctuation (，。？！：) in Chinese text.
- Polish lightly. The result should read like the speaker typed it themselves in a message.
- Apply the personal dictionary as usual.
--- END MANDARIN MODE ---
```
