# AutoBridge

A **client-side** bridging assistant for Minecraft **1.20.1 (Fabric)** with two bridging modes — **Crouch** (needs an edge, holds sneak for you) and **God Bridge** (no sneaking at all). It drives only vanilla inputs; your movement and your camera stay completely in your hands.

Bridging a gap by hand is fiddly: hold sneak, line the crosshair up on the side of the block right at the edge, and click at the exact moment. Miss the timing and you drop into the void. AutoBridge handles the two fiddly parts and leaves you the two fun parts.

**You start it yourself, and it stops by itself.** Crouch on a block edge, look down at your feet, and place one block under yourself. That placement is the start signal; from then on AutoBridge takes over. Once you stop placing for three seconds, it hands control back.

---

## What it does

- **You decide when it starts** — it does nothing on its own. It waits until you crouch on an edge, look down, and place one block under your feet. That way it never surprises you while you are just walking around.
- **Two bridging modes** — **Crouch** holds sneak for you *only while you are on a block edge*, and releases it the moment you leave (holding it permanently would cut your movement speed to 30%). **God Bridge** never sneaks: it enables vanilla's own edge protection so you keep full walking speed and still cannot walk off the edge.
- **Auto right-click** — once your crosshair lands on a valid spot, AutoBridge clicks for you. Walk backward, and you bridge.
- **Stops by itself** — three seconds without a placement that the world actually confirms, and it returns to idle.
- **Never touches your camera** — no rotation is ever written. Not a single yaw or pitch value. Aiming is 100% your real mouse input.
- **Never writes your movement input** — no key presses are simulated for walking, sprinting, or jumping, and your speed is never changed. If you jump, AutoBridge immediately releases sneak and stays out of the way for the whole jump. (God Bridge only flips vanilla's own `clipAtLedge` return value — vanilla's protection against walking off an edge — so the clamp you get is vanilla's, unmodified.)
- **Hands off when you're not bridging** — while idle it touches neither sneak nor right-click. Holding right-click yourself? It steps aside and lets vanilla handle it.

## How it decides

**Starting.** All five of these must hold:

| # | Condition |
|---|---|
| ① | You are sneaking — while idle the mod does not touch the key, so this can only be you |
| ② | You were on a block edge within the last few ticks |
| ③ | You are looking down past the startup angle (default 60°) |
| ④ | Your main hand holds a `BlockItem` |
| ⑤ | Any cell in the 3×3 layer one block below you went from empty to solid — you just placed a block there |

Watching the whole 3×3 layer is what makes starting work while standing diagonally on a block corner, where the block you place lands on the diagonal cell instead of the one under your centre.

**Placing.** While active, each tick is checked in order:

1. Your crosshair is on a block.
2. Your main hand holds a `BlockItem`.
3. Vanilla's own `ItemPlacementContext.canPlace()` says the placement is legal.
4. You actually have a supporting block under your feet.
5. The target position doesn't overlap your own hitbox.
6. The target is within vanilla reach.
7. You are sneaking — Crouch mode only; God Bridge skips this condition.
8. **You're aiming at the *side* of a block** — not the top or bottom face. Aiming at a top face stacks upward, which isn't bridging, so it's rejected.
9. The geometry matches: same layer as the block under your feet, 1–2 blocks horizontally, and in the **opposite direction of your view** (behind you).

If anything fails, nothing happens and no click is sent.

**Stopping.** Three seconds (60 ticks) without a confirmed placement. The timer is reset by placements **the world actually confirms**, not by clicks sent — each click is followed up by checking the target cell on the next tick.

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
| Bridging mode | Crouch | `Crouch` (needs an edge, holds sneak) or `God Bridge` (no sneak, vanilla edge clamp). Applies on the next tick, no restart |
| Idle timeout | 3 s | How long without a confirmed placement before it stops (1–5 s) |
| Placement direction | Behind view | Fixed to behind-view |
| Edge threshold | 0.03 | How close to the block edge counts as "at the edge" — smaller is stricter |
| Debug HUD | Off | On-screen diagnostics |

Settings are saved to `config/autobridge.properties` and persist between sessions. The startup pitch (default **60°**) is a code default rather than a menu entry; edit `lowHeadPitch` in that file to change it.

## How it's implemented

AutoBridge does **not** construct or send any custom placement packets. It sets vanilla's own `useKey` pressed state and lets Minecraft's normal input handling run `doItemUse()` — the exact same code path as a real click, including vanilla's block-interaction rules and its built-in use cooldown. The placement position is derived from vanilla's `ItemPlacementContext`, the same source the server uses, so the client and server always agree on where the block goes.

In God Bridge mode the mod also sets vanilla's `PlayerEntity.clipAtLedge()` return value to true while it is active. That is the single gate behind vanilla's own "you cannot walk off the edge while sneaking" protection, so the clamping algorithm that runs is vanilla's, unmodified. Crouch mode never touches it.

The whole mod is client-side. It is never loaded on a dedicated server.

## Compatibility

- Works in singleplayer.
- On multiplayer servers, using this mod may violate the server's rules. **Check with the server owner before using it there.** Whether a given server permits assistance mods is entirely up to that server.
- One mixin in total: vanilla's `clipAtLedge`, and only while God Bridge mode is active. Nothing is mixed into rendering or networking — client tick and HUD render use Fabric's normal API.

## Notes

- Server-side checks still apply. If the server rejects a placement, the block simply doesn't appear.
- This mod is a convenience tool for players who struggle with edge timing — it is not intended to give anyone an advantage over other players on public servers.

## License

MIT
