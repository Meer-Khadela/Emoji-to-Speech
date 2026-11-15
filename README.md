# Emoji-to-Speech

An emotion-aware AAC (augmentative and alternative communication) board that runs in a web browser. The user taps up to three emoji cards, picks a voice tone, and the app speaks a full sentence aloud.

It is two files: a single web page (`index.html`) and a small Java server (`EmojiWebServer.java`) with no external dependencies. Sentences can be written by Google Gemini, and the app keeps working without it.

## Why I built this

I spent four months as a trainee teacher at a special school, where I met students with many different needs. Some of them had motor impairments, and some could not speak properly. I saw how hard it was for these students to tell anyone what they needed, so I started looking for ways to help them.

I found that a standard AAC device paired with an eye tracker costs approximately ₹3,00,000 to ₹5,00,000 (about $3,100 to $5,200) in India, which a small special school simply cannot afford. So I built this to do the same job on a budget of ₹0.

## Features

**Communication**
- 30 built-in cards in four colour-coded groups: Needs, Answers, Feelings, Places and play
- Six voice tones (Calm, Happy, Excited, Sad, Angry, Scared / urgent) that change pitch, speed and volume
- Sentences in English, Hindi, Spanish and French, worded so they do not depend on the speaker's gender
- "No" turns a sentence into its negative ("I do not want any water")
- Speaks automatically after 3 or 6 seconds, or only when Speak is pressed
- Favourites and often-used combinations appear at the top after repeated use

**Personalisation**
- My cards: add up to 20 of your own cards with any emoji from the device keyboard, a name, and optionally the sentence it should say
- "Remove all my cards" returns to the original board
- Light, dark (true black) or same-as-system appearance
- Large buttons option

**Access**
- Dwell selection: hold a pointer (mouse, head mouse or eye tracker) on a button to press it
- Webcam eye tracking that runs entirely in the browser, with 9-point calibration and adjustable sensitivity
- On-screen scroll buttons for dwell users
- Picked cards are marked with a thick frame and a tick
- Screen-reader labels and reduced-motion support

> **Eye tracking is experimental.** It uses the device's front camera and is much
> less precise than a dedicated eye tracker. It currently works on laptops and
> iPads only. Phones are not supported, because the screen is too small for the
> tracking to tell the cards apart. Accuracy also depends on lighting, camera
> quality and how still the user's head is, so always recalibrate before use.

**Calm mode**
- Tapping a fourth card clears the sentence, plays soft generated music and speaks a calming line in a second voice

**Emergency (siren button, top left)**
- Siren with three sounds (rising and falling, fast, beeping) that starts quietly and rises to full loudness over 8 seconds
- Up to five emergency contacts, each with a Call button that opens the phone dialer

## Requirements

- Java 17 or newer
- A modern browser (Chrome or Edge recommended; webcam eye tracking needs one of these)
- Optional: a Google Gemini API key for AI-written sentences

## Quick start

Put `index.html` and `EmojiWebServer.java` in the same folder, then from that folder:

```bash
javac EmojiWebServer.java
java EmojiWebServer
```

Open <http://localhost:8080> in the browser.

### Turning on AI sentences

Set your Gemini key in the same terminal before starting the server. The key is read from the environment and is never stored in the source code.

| System | Command |
|---|---|
| Windows (Command Prompt) | `set GEMINI_API_KEY=your-key` |
| Windows (PowerShell) | `$env:GEMINI_API_KEY="your-key"` |
| macOS / Linux | `export GEMINI_API_KEY=your-key` |

Then run `java EmojiWebServer`.

| Variable | Purpose | Default |
|---|---|---|
| `GEMINI_API_KEY` | Gemini API key. Without it, sentences are built locally. | not set |
| `GEMINI_MODEL` | Model names, comma-separated, tried in order | `gemini-3.7-flash,gemini-3.8-flash` |

The top of Settings shows whether sentences are coming from the AI:

- 🟢 AI sentences are working
- 🟠 AI is off (no key), Gemini did not answer, or the server could not be reached

## How sentences are made

1. The page sends the selected cards, the tone and the language to the server.
2. If a key is set, the server asks Gemini for one short first-person sentence that connects all the cards.
3. If there is no key, Gemini returns an error, or it takes longer than 15 seconds, the server builds the sentence from its own phrase tables.
4. If the server cannot be reached at all, the page builds the same sentence itself.

The local builder also joins cards into one sentence, for example:

| Cards | Sentence |
|---|---|
| Water + Hungry | I want some water and I am hungry. |
| Water + Please | I want some water, please. |
| Hello + Water + Thank you | Hello, I want some water, thank you. |
| No + Water | I do not want any water. |

One of your own cards used by itself always says exactly what was typed for it.

## Server endpoints

| Endpoint | Returns |
|---|---|
| `GET /` | The page |
| `GET /api/emojis` | The built-in cards as JSON. Header `X-AI: on` or `off`. |
| `GET /api/combine?items=Water,Please&emotion=Calm&lang=en` | The sentence as plain text. Header `X-Sentence-Source: ai`, `no-key` or `ai-failed`. |

`/api/combine` also accepts `own=name|meaning~name|meaning` for up to three of the user's own cards.

## Privacy

- Favourites, settings, emergency contacts, your own cards and eye-tracking calibration are stored only in the browser on that device (`localStorage`).
- Webcam video is processed inside the page. It is never uploaded or recorded. Calibration saves a few numbers, never images.
- When AI is on, the selected card names are sent to Google Gemini. This includes the name and meaning of any of your own cards used in that sentence, so avoid putting private details in them.
- The face-tracking model is downloaded from a public CDN the first time eye tracking is switched on, so that step needs an internet connection.

## Adding or changing built-in cards

The page and the server each keep a copy of the card list and the phrase tables, and they must match:

- `index.html`: `DEFAULT_CARDS`, `LABELS`, `POSS`, `NEGS`
- `EmojiWebServer.java`: `VALID_EMOJIS`, `EMOJI_JSON`, and the phrase maps inside `LANGS`

The Java file is kept as plain ASCII, so non-English text there is written as `\uXXXX` escapes.

## Known limits

- **Siren loudness** depends on the device's own volume. A web page cannot raise the system volume or override silent mode.
- **Calling** opens the device's dialer. On a computer without a calling app the Call button does nothing.
- **Voices** come from the device. If no voice is installed for the chosen language, speech may sound wrong; the app shows a note when this happens.
- **Dwell selection** presses buttons only. Dropdowns, checkboxes and text boxes in Settings need a tap, click or keyboard, so a helper should set those up.
- **Network access:** the server listens on port 8080 on all network interfaces, so other devices on the same network can open it and use your Gemini quota.
- **Speech on iPhone and iPad** needs one real tap on the page before it will play.

## Important

Emoji-to-Speech is a communication aid, not a medical or safety device. Do not rely on the siren or the contact list as the only way to get help in an emergency.

## Project files

| File | Purpose |
|---|---|
| `index.html` | The whole app: layout, styles, speech, eye tracking, settings |
| `EmojiWebServer.java` | Serves the page and builds sentences, with or without Gemini |
| `README.md` | This file |
| `LICENSE` | MIT License |

## Credits

- **Eye tracking** uses [MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker) by Google (Apache 2.0), loaded from a CDN at run time.
- **AI sentences** are written by the [Google Gemini API](https://ai.google.dev/).
- **Development:** built with the help of Claude, an AI assistant by Anthropic.
- **Thanks** to the teachers and students of the special school where I trained, who showed me why this was needed.

## License

Released under the MIT License. See the [LICENSE](LICENSE) file for the full text.
