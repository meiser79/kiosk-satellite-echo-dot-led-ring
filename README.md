# Echo Dot LED Ring

Kiosk Satellite plugin (SDK 1) that shows wake-word, voice-interaction and mute status on the Echo Dot 2 (biscuit) LED ring.

## How it works

- Subscribes to `wakeword.detected`, `voice.interaction`, `wakeword.state` and `voice.state` (capability `host.read`); reads the initial Voice Satellite state with `getVoiceState`.
- Updates the plugin subpage status as it moves between wake-word, listening, thinking, responding, mute and error states.
- Keeps the ring lit for every active voice interaction source. Mute overrides all other states and shows solid red.
- The ring is an IS31FL3236. Its kernel driver exposes `/sys/bus/i2c/devices/0-003f/frame`, which takes all 36 channels as one string of 72 hex characters. Three consecutive channels form one RGB LED (R, G, B), 12 LEDs in total.
- Auto write mode tries a direct write first and falls back to a persistent `su` shell.

## Setup

1. Allow root for Kiosk Satellite: `adb shell appops set me.jxl.kiosk_satellite SU allow`. `persist.sys.root_access` must allow apps (1 or 3).
2. Enable the plugin. It follows the device's Voice Satellite state reported by Kiosk Satellite, including realtime conversations; no Home Assistant entity selection is needed.
3. Say the wake word to test the ring.

## LED states and stage proposal

- Wake word: two complete blue spinner rotations, then off if no interaction starts. Each rotation takes about 1.2 seconds.
- Thinking (`processing`): slow amber pulse.
- Answer (`responding`): steady green.
- Error: red double flash if a turn returns from `processing` to `idle` without entering `responding`.
- Muted: solid red, including when mute was already enabled as the plugin starts.
- Return to `idle`: turn the ring off without a completion flash. A `processing` → `idle` transition without `responding` still uses the red error double flash.

Kiosk Satellite's `voice.state` covers Assist pipeline turns and realtime conversations, so the plugin follows the device directly without an entity picker. When the native Voice Satellite runtime is off, the ring stays dark and the plugin reports that status. Older Kiosk Satellite versions that do not support this event can still show wake-word and active-interaction indication.

The Voice Satellite state does not expose a dedicated pipeline-error state. The red double flash therefore treats `processing` → `idle` without `responding` as a likely error; a turn that intentionally finishes without speech may produce the same indication. Mute remains steady red and takes precedence over all other states. Wake-word color and the two-spin animation are fixed.

## Build

```
python3 tools/test.py
python3 tools/build.py
```

Install the ZIP from `dist/` through **Plugin Manager > Developer Tools > Install from ZIP**. See the [Kiosk Satellite plugin SDK](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the full guide.

## License

Apache-2.0
