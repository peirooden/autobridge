# AutoBridge

A **client-side** bridging assistant for Minecraft **1.20.1 (Fabric)**. It takes over exactly **two inputs** — sneaking and right-clicking — and nothing else. Your movement and your camera stay completely in your hands.

Bridging a gap by hand is fiddly: hold sneak, line the crosshair up on the side of the block right at the edge, and click at the exact moment. Miss the timing and you drop into the void. AutoBridge handles the two fiddly parts and leaves you the two fun parts.

---

## What it does

- **Auto-sneak at edges** — as soon as you stand on the dangerous edge of a block while holding a block item, AutoBridge holds sneak for you. Sneaking is what makes vanilla treat your right-click as *place a block* instead of *interact with a block*.
- **Auto right-click** — once your crosshair lands on a valid spot, AutoBridge clicks for you. Walk forward, and you bridge.
- **Never touches your camera** — no rotation is ever written. Not a single yaw or pitch value. Aiming is 100% your real mouse input.
- **Never touches your movement** — no key presses are simulated for walking, sprinting, or jumping. If you jump, AutoBridge immediately releases sneak and stays out of the way for the whole jump.
- **Hands off when you're not bridging** — empty-handed? It won't force-sneak you at every cliff. Holding right-click yourself? It steps aside and lets vanilla handle it.

## How it decides

AutoBridge runs once per client tick and checks, in order:

1. Your crosshair is on a block.
2. Your main hand holds a `BlockItem`.
3. Vanilla's own `ItemPlacementContext.canPlace()` says the placement is legal.
4. You actually have a supporting block under your feet.
5. The target position doesn't overlap your own hitbox.
6. The target is within vanilla reach.
7. You are sneaking.
8. **You're aiming at the *side* of a block** — not the top or bottom face. Aiming at a top face stacks upward, which isn't bridging, so it's rejected.
9. The geometry matches: same layer as the block under your feet, 1–2 blocks horizontally, and in the **opposite direction of your view** (behind you).

If anything fails, nothing happens and no click is sent.

## Requirements

- Minecraft **1.20.1**
- **Fabric Loader** 0.16.14 or newer
- **Fabric API** 0.92.12+1.20.1 or newer
- **Java 21**
- Optional: **ModMenu** — if installed, AutoBridge adds a settings screen. It is not required and not bundled.

## Configuration

AutoBridge registers **no in-game keybindings** — the "Key Binds" menu stays clean, and nothing can clash with your own keys. Everything lives in the ModMenu settings screen:

| Setting | Default | What it does |
| :--- | :--- | :--- |
| Auto mode | On | Master on/off switch |
| Placement direction | Behind view | Fixed to behind-view |
| Edge threshold | 0.03 | How close to the block edge counts as "at the edge" |
| Danger check depth | 3 | How far down to look before calling a drop dangerous |
| Horizontal look-ahead | 2 | How many blocks outward must be empty to count as a real gap |
| Landing area | 5 / 9 | Minimum solid blocks in a 3×3 to count as a safe landing |
| Approach speed threshold | 0.25 | Speed at which the look-ahead distance is increased |
| Debug HUD | Off | On-screen diagnostics |

Settings are saved to `config/autobridge.properties` and persist between sessions.

## How it's implemented

AutoBridge does **not** construct or send any custom placement packets. It sets vanilla's own `useKey` pressed state and lets Minecraft's normal input handling run `doItemUse()` — the exact same code path as a real click, including vanilla's block-interaction rules and its built-in use cooldown. The placement position is derived from vanilla's `ItemPlacementContext`, the same source the server uses, so the client and server always agree on where the block goes.

The whole mod is client-side. It is never loaded on a dedicated server.

## Compatibility

- Works in singleplayer.
- On multiplayer servers, using this mod may violate the server's rules. **Check with the server owner before using it there.** Whether a given server permits assistance mods is entirely up to that server.
- No mixins into rendering or networking; it hooks client tick and HUD render only.

## Notes

- Server-side checks still apply. If the server rejects a placement, the block simply doesn't appear.
- This mod is a convenience tool for players who struggle with edge timing — it is not intended to give anyone an advantage over other players on public servers.

## License

_License text here._
