## OpenCfMoto v1.0.8

Handlebar control polish: long-press Select, tunable tap timing, and the double-tap fix from #2.

### What's new
- **Select (hold)** — press and hold Enter / ★ for a second action (default: Assistant / voice). Remappable in Customize buttons.
- **Select ×2** + fixed Backward/Forward double-tap — dashes that send two separate presses now register ×2 correctly (from PR #2).
- **MS delay in Setup** — set **double-tap delay** (200 / 300 / 450 ms) and **Select hold delay** (500 / 600 / 800 ms). Applies live.

### Notes
- Single presses wait for the double-tap window before firing (that's how singles vs doubles are told apart). Shorter delay = snappier singles; longer = more forgiving doubles.
- Select hold needs a real key-up from the dash (Enter / ★ path). Volume ▲/▼ has no release event, so no long-press there.
