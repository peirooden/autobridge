# AutoBridge

A **client-side** bridging assistant for Minecraft **1.20.1 (Fabric)** with two bridging modes. It drives only vanilla inputs — right-clicking, and (in Crouch mode) the sneak key. Your movement and your camera stay completely in your hands.

You start it yourself: crouch on a block edge, look down at your feet, and place one block under yourself. From that point the mod takes over, and it releases control again after a few seconds without a placement.

| Mode | What the mod does while active |
|---|---|
| **Crouch** (default) | Holds sneak for you while you are on an edge, and right-clicks. Vanilla then skips block interaction and places the block. |
| **God Bridge** | Never touches the sneak key. Enables vanilla's own edge protection (`PlayerEntity.clipAtLedge`), so you cannot walk off the edge, and right-clicks. |

[![Modrinth](https://img.shields.io/badge/Modrinth-AutoBridge-1bd96a)](https://modrinth.com/mod/autobridge)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)
![Loader](https://img.shields.io/badge/Loader-Fabric-orange)
![Environment](https://img.shields.io/badge/Environment-Client--side-lightgrey)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Design ground rules

These are non-negotiable; everything else follows from them.

1. **Fully client-side.** `fabric.mod.json` declares `"environment": "client"`, and only a `ClientModInitializer` is registered. There is no server entrypoint.
2. **Vanilla placement rules only.** No extended reach, no multi-block placement, no teleporting, no bypassing `BlockItem.place`'s legality checks. At most one placement per tick, and the rate is capped by vanilla's own `itemUseCooldown` (~5 CPS).
3. **Simulated right-click, never a hand-built packet.**
   - It goes `client.options.useKey.setPressed(true)` → vanilla `handleInputEvents()` → `doItemUse()`.
   - It **never** constructs a `PlayerInteractBlockC2SPacket` and sends it. That path leaves `sequence` un-incremented and rotation out of sync — the most obvious thing a server can flag.
4. **The camera is never touched.** No `player.setYaw/setPitch` calls anywhere. Aiming comes entirely from your own mouse, which means the rotation the server receives is exactly the rotation your client produced.
5. **Placement target:** one block in the **opposite direction of your view** (behind you), on the same layer as the block under your feet.
6. **No custom movement, ever.** Crouch mode presses the sneak key through vanilla's own input path, so it shows up in the game's normal control state. God Bridge mode presses nothing at all: it only sets the return value of vanilla's `PlayerEntity.clipAtLedge()` — the one-line gate behind vanilla's own "don't walk off the edge while sneaking" protection — so the clamp that runs is vanilla's own, unmodified. Neither mode changes your movement input, your speed, or your camera.

## Validation

Every placement passes through this chain. If any step fails, nothing happens and no click is sent.

| # | Condition |
|---|---|
| ① | Crosshair is on a block |
| ② | Main hand holds a `BlockItem` |
| ③ | Vanilla `ItemPlacementContext#canPlace` passes |
| ④ | There's a supporting block under your feet |
| ⑤ | Target doesn't overlap your own hitbox |
| ⑥ | Eye-to-target distance ≤ vanilla reach (4.5 survival) |
| ⑦ | You are sneaking — **Crouch mode only** (God Bridge doesn't sneak, so it skips this) |
| ⑧ | **You're aiming at a block's side face** — not the top or bottom |
| ⑨ | Target is on the same layer as the block under your feet |
| ⑩ | Target is 1–2 blocks away horizontally |
| ⑪ | Target is in the opposite direction of your view |

**Why vanilla's `ItemPlacementContext`:** the position it computes is the same position the server's `BlockItem.place` will compute — same source of truth. Hand-rolling an offset or fabricating a `hitVec` is where clients and servers start disagreeing.

**Why ⑦ is required:** in vanilla, sneaking makes `shouldCancelInteraction()` return true, so the right-click skips "open this chest / press this button" and goes straight to placement. That's also why Crouch mode requires sneaking at all. God Bridge skips this condition; the trade-off is that right-clicking an interactive block (a chest, a door, a button) opens it instead of placing a block — bridging aims at the side of the block under your feet, which is normally plain stone or dirt, so it doesn't come up in practice.

**Why ⑧ exists:** aiming at a *side* face places the block outward (horizontal extension — that's bridging). Aiming at a *top* face places it upward (stacking, not bridging). AutoBridge refuses top and bottom faces outright.

> Condition ⑦ implies sneaking must already be active for at least one tick: `player.isSneaking()` is updated in `tickMovement()`, while placement happens during `handleInputEvents()`. Those are one tick apart — which happens to match the packet ordering a vanilla client produces.

## Starting and stopping

**AutoBridge never decides on its own when to bridge.** You start it; it stops by itself when you're done.

### Starting

Stand on a block edge, press sneak **yourself**, look down at your feet, and place one block under yourself. That single placement is the start signal.

The mod watches the **3×3 layer one block below you** and starts on any cell in it going from empty to solid. Standing diagonally on a block corner, the block you place lands on the diagonal cell rather than the one directly below your centre — watching only the centre cell would silently never start there.

All of these must hold at the moment that block appears:

| # | Condition |
|---|---|
| ① | You are sneaking (the mod is not touching the key while idle, so this can only be you) |
| ② | You were on an edge within the last few ticks |
| ③ | `pitch` is greater than the startup angle (default **60°** — positive is looking down) |
| ④ | Your main hand holds a `BlockItem` |
| ⑤ | Any cell in the 3×3 layer below you went empty → solid |

The startup angle is a gate, not a guarantee: the placement itself still has to pass the validation chain, so a shallower gate cannot produce a misplaced block. Head-on, a side face needs roughly +79°~+84°; standing diagonally on a corner the ray travels further horizontally, and 60°–70° is enough — which is why the gate sits at 60°.

### While active

**Crouch mode:** the mod sneaks and right-clicks for you, **but only while you are on an edge**. Sneak is released the moment you leave the edge rather than held continuously — vanilla multiplies your movement speed by 0.3 while sneaking, so holding it would make bridging feel like walking through mud. It also releases sneak when your main hand is not a block, or when you press jump (vanilla blocks jumping while sneaking).

**God Bridge mode:** the mod right-clicks only. You keep walking at full speed and never sneak; vanilla's edge clamp keeps you on the blocks. That same clamp is why you don't auto-jump off the edge.

Opening any screen disengages it completely.

### Stopping

After **3 seconds (60 ticks, default)** without a confirmed placement it returns to idle. The timeout is adjustable in ModMenu (1–5 s).

The timer is reset by each placement **the world actually confirms**, not by each click sent. Every click is followed up by checking the target cell on the next tick: only if a block really appeared there does the timer reset. A click the server rejects does not count.

### What counts as an edge

Either of these:

1. Your center cell is already air (your hitbox is still supported by a neighbouring block) — the most extreme case.
2. Your center is within `edgeMargin` (default `0.03`) of a block boundary in some direction, and the block beyond that boundary is empty.

That is the whole check. There is deliberately **no** "is this edge actually dangerous" analysis: because you start the mod by hand, you have already declared that you are bridging, so any edge is worth sneaking for.

## Configuration

> **No in-game keybindings.** The mod registers zero keys, so the Key Binds menu stays clean and nothing can clash with your own binds.

Everything lives in the **ModMenu** settings screen (ModMenu is optional — without it the mod still works, you just lose the GUI):

| Setting | Default | Range / options |
|---|---|---|
| Auto mode | On | On / Off |
| **Bridging mode** | **Crouch** | Crouch / God Bridge |
| Idle timeout | 3 s | 1 / 2 / 3 / 4 / 5 s |
| Placement direction | Behind view | `BEHIND_VIEW` |
| Edge threshold | 0.03 | 0.03 → 0.08 → 0.15 → 0.22 → 0.30 blocks |
| Debug HUD | Off | On / Off |

Switching the bridging mode applies on the next tick — no restart. The startup pitch (**60°**) is a code default rather than a menu entry; it can be edited by hand in `config/autobridge.properties` (`lowHeadPitch`).

Every click saves immediately; the button always shows the real current value. Settings persist to `config/autobridge.properties`.

**About defaults:** defaults only apply on the **first** run, when the config file doesn't exist yet. After that, whatever you set in ModMenu is stored and kept. So if you once enabled the HUD in a dev build, switching to the user build leaves the HUD on — that's the stored value, not the default failing to apply.

`edgeMargin` in practice: `0.30` triggers as soon as your hitbox touches the edge; `0.03` requires you to be almost half off the block. **Smaller is stricter.**

## Debug HUD

The dev edition renders this in the top-left (the in-game labels are in Chinese; they're translated here for readability):

```
AutoBridge  AUTO(on)·Crouch  idle
State: idle — crouch on an edge, look down, place one block below you
  Conditions: sneakX edgeX pitchX holdX placed-below√
Crosshair: (12, 63, -8) face=north dist=1.87
Pitch: 81°  (head-on side face +79°~+84°; corners can go shallower)
Standing on edge: yes  threshold=0.03 blocks
Standing on: (12, 62, -8)
Expected: (12, 62, -9)
Actual: -
Sneak: no  forcedSneak=true  holding=Block{minecraft:stone}
Placed: 75   avg 4.85 blocks/s (vanilla hold cap 5/s)
```

**The "Conditions" line is the useful one while starting up** — it shows, live, which of the five startup conditions is failing.

**The `Placed` line is the one that tells you whether bridging actually keeps up.** It counts only placements the world confirmed, and the average is measured from the first placement of the current run, so idle time doesn't dilute it. Walking speed is 4.317 blocks/s, so anything around 4–5 means the mod is keeping up with you.

## Requirements

- Minecraft **1.20.1**
- **Fabric Loader** 0.16.14 or newer
- **Fabric API** 0.92.12+1.20.1 or newer
- **Java 21**
- Optional: **ModMenu** (not bundled, not required — declared under `suggests`)

## Building

### Two editions

One source tree, two jars:

| Edition | Command | Output | Difference |
|---|---|---|---|
| **User** | `.\dev.ps1 build` | `autobridge-0.3.0.jar` | Debug HUD **off** by default, no diagnostic logging, clean console |
| **Dev** | `.\dev.ps1 dev` | `autobridge-0.3.0-dev.jar` | Debug HUD **on** by default, full `DENIED:` logging |

They differ only by a build-time constant (`Edition.DEV`) generated by Gradle — the source is identical. With raw Gradle:

```bash
gradlew build                 # user edition
gradlew build -Pedition=dev   # dev edition
```

> **Use the dev edition when troubleshooting** — the user edition emits no diagnostic logging, so you can't see which check is blocking you.

### Running the client

```powershell
.\dev.ps1 runClient     # dev edition, full diagnostics
.\dev.ps1 build         # user jar  -> build/libs/autobridge-0.3.0.jar
.\dev.ps1 dev           # dev jar   -> build/libs/autobridge-0.3.0-dev.jar
```

`dev.ps1` locates a JDK 21 on this machine automatically, so no global `JAVA_HOME` is needed.

Manual install: drop the **user** jar (`autobridge-0.3.0.jar`) into `.minecraft/mods/` alongside **Fabric API**.

## FAQ

**The mod does nothing.** It stays idle until you start it. The HUD's "Conditions" line shows which of the five startup conditions is failing:

- `sneakX` → you are not sneaking. While idle the mod does not touch the sneak key, so this has to come from you.
- `edgeX` → you are not on an edge (see *What counts as an edge* above).
- `pitchX` → you are not looking down far enough (the gate is 60°). **Standing on top of a block you mathematically cannot see its side faces** — you have to be at the edge and looking down steeply: head-on that means roughly 79°–84°, and standing diagonally on a corner 60°–70° is enough because the ray travels further horizontally.
- `holdX` → your main hand is not a block.
- `placed-belowX` → the mod has not seen a block appear in the layer below you. That HUD indicator reflects only the cell directly under your centre; the start signal itself watches the whole 3×3 layer, so standing diagonally on a corner this can read `X` while the launch still works.

During bridging, blocked placements are named on the HUD's `Result:` line:

- `not sneaking` → forced sneak isn't taking effect.
- `crosshair not on a block` → your view isn't low enough.
- `target not on the foot layer` / `not in the opposite direction of view` → the target landed somewhere unexpected; compare the "Expected" and "Actual" coordinates on the HUD.

**It placed a block when I aimed at the top face.** It shouldn't — condition ⑧ rejects top and bottom faces. If you see this, the dev build logs one line per confirmed placement (`放成 #N: placePos=... hit=... pitch=... δ=... δtan=... 距上一块 N tick`) recording the face and the aim geometry; please report it with that line.

**Why is there no keybind?** By design. Settings are GUI-only so nothing can conflict with your own keys.

**Does God Bridge change how I move?** No input is written and your speed is untouched. What it does is set vanilla's `clipAtLedge()` to true while active — exactly the protection vanilla gives you while sneaking — so you cannot walk off the edge you are bridging along. It does not sneak, and the server sees no sneaking.

**Can I get banned for this?** AutoBridge reduces the likelihood of *automated* detection, and **offers no guarantee whatsoever**. It removes low-level packet-level tells (it builds no custom packets, sends no rotation it didn't receive), but a server can still identify assistance behaviourally — placement timing that's too precise, a suspiciously constant CPS, movement that's too regular. **Use it in singleplayer or on a private server you control.** On public servers, check the rules first; that's entirely up to the server.

## License

MIT — see [LICENSE](LICENSE).
