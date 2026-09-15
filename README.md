# AutoBridge

A **client-side** bridging assistant for Minecraft **1.20.1 (Fabric)**. It takes over exactly **two inputs** — sneaking and right-clicking — and nothing else. Your movement and your camera stay completely in your hands.

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
| ⑦ | You are sneaking |
| ⑧ | **You're aiming at a block's side face** — not the top or bottom |
| ⑨ | Target is on the same layer as the block under your feet |
| ⑩ | Target is 1–2 blocks away horizontally |
| ⑪ | Target is in the opposite direction of your view |

**Why vanilla's `ItemPlacementContext`:** the position it computes is the same position the server's `BlockItem.place` will compute — same source of truth. Hand-rolling an offset or fabricating a `hitVec` is where clients and servers start disagreeing.

**Why ⑦ is required:** in vanilla, sneaking makes `shouldCancelInteraction()` return true, so the right-click skips "open this chest / press this button" and goes straight to placement. That's also why bridging requires sneaking at all.

**Why ⑧ exists:** aiming at a *side* face places the block outward (horizontal extension — that's bridging). Aiming at a *top* face places it upward (stacking, not bridging). AutoBridge refuses top and bottom faces outright.

> Condition ⑦ implies sneaking must already be active for at least one tick: `player.isSneaking()` is updated in `tickMovement()`, while placement happens during `handleInputEvents()`. Those are one tick apart — which happens to match the packet ordering a vanilla client produces.

## Edge detection

"Standing on a dangerous edge" requires **all three** of these:

1. You're actually supported — `findFootBlock` finds a block under your feet.
2. Your center is within `edgeMargin` (default `0.03`) of a block boundary in some direction.
3. That direction is genuinely dangerous.

Step 3 has two layers, OR'd together:

- **Open void** — `edgeLookAhead` (default `2`) consecutive blocks outward are all "empty *and* nothing to land on below". Bare bridges and cliffs fall here. Anything below doesn't matter: a lone block under a bridge, or a low bridge over solid ground, still counts as dangerous — you'd survive the fall but leave the bridge line, which wastes the bridge.
- **Deep hole** — only one block wide (solid ground resumes beyond it), but probing `edgeDropDepth` (default `3`) blocks down finds nothing to land on. A one-block-wide chasm in flat ground falls here.

Both layers depend on the idea of a **landing surface**: *a single block is not a landing spot.* Isolated blocks and one-block-wide beams won't catch you — you'd glance off and keep falling. So a landing surface requires at least `landingArea` (default `5`) solid blocks in the 3×3 around it. Decent ground (9) and a shallow pit floor (9) qualify; an isolated block (1) and a one-block beam (3) don't.

**Terrain one block lower doesn't trigger it:** the block above it is indeed air, but probing one block down hits solid ground — the "deep hole" layer exempts it. A pit one block deep beside flat ground satisfies neither layer, so no sneak.

**Speed participates:** if you're moving *toward* that direction faster than `fastApproachSpeed` (default `0.25` blocks/tick), step 3 looks one block further out. Sneaking takes a tick to reach the server, and during that window you're still walking toward the void — the faster you're going, the further you travel, so the cliff needs to be detected earlier.

AUTO also checks two extra things so it stays out of your way:

- **You must be holding a block item** — otherwise walking past any cliff empty-handed would force-sneak you.
- **You must not be pressing jump** — vanilla blocks jumping while sneaking, so AutoBridge releases sneak the moment you want to jump down.

Opening any screen (inventory / pause / chat) makes the mod disengage completely, and it never leaves the sneak key held down.

## Configuration

> **No in-game keybindings.** The mod registers zero keys, so the Key Binds menu stays clean and nothing can clash with your own binds.

Everything lives in the **ModMenu** settings screen (ModMenu is optional — without it the mod still works, you just lose the GUI):

| Setting | Default | Range / options |
|---|---|---|
| Auto mode | On | On / Off |
| Placement direction | Behind view | `BEHIND_VIEW` |
| Edge threshold | 0.03 | 0.03 → 0.08 → 0.15 → 0.22 → 0.30 blocks |
| Danger check depth | 3 | 1–5 blocks |
| Horizontal look-ahead | 2 | 2–4 blocks |
| Landing area | 5 | 1–9 blocks (3×3) |
| Approach speed threshold | 0.25 | 0.15 / 0.20 / 0.25 / 0.30 / 0.40 blocks/tick |
| Debug HUD | Off | On / Off |

Every click saves immediately; the button always shows the real current value. Settings persist to `config/autobridge.properties`.

**About defaults:** defaults only apply on the **first** run, when the config file doesn't exist yet. After that, whatever you set in ModMenu is stored and kept. So if you once enabled the HUD in a dev build, switching to the user build leaves the HUD on — that's the stored value, not the default failing to apply.

`edgeMargin` in practice: `0.30` triggers as soon as your hitbox touches the edge; `0.03` requires you to be almost half off the block.

## Debug HUD

The dev edition renders this in the top-left:

```
AutoBridge  AUTO(on)  active
Direction: behind view
Crosshair: (12, 63, -8) face=north dist=1.87
Pitch: -58°  (hitting a side face usually needs -45° ~ -70°)
Standing on: (12, 62, -8)
Expected: (12, 62, -9)
Actual: (12, 62, -9)
Result: OK — simulated right-click
→ (when blocked, this line tells you what to do next)
Sneak: yes  forcedSneak=true  holding=Block{minecraft:stone}
Simulated right-clicks: 12  last: 0s ago
```

**The "Result" + "→" lines are the useful ones** — they name which of the 11 checks blocked you, and translate it into "here's what to do next".

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
| **User** | `.\dev.ps1 build` | `autobridge-0.1.0.jar` | Debug HUD **off** by default, no diagnostic logging, clean console |
| **Dev** | `.\dev.ps1 dev` | `autobridge-0.1.0-dev.jar` | Debug HUD **on** by default, full `DENIED:` logging |

They differ only by a build-time constant (`Edition.DEV`) generated by Gradle — the source is identical. With raw Gradle:

```bash
gradlew build                 # user edition
gradlew build -Pedition=dev   # dev edition
```

> **Use the dev edition when troubleshooting** — the user edition emits no diagnostic logging, so you can't see which check is blocking you.

### Running the client

```powershell
.\dev.ps1 runClient     # dev edition, full diagnostics
.\dev.ps1 build         # user jar  -> build/libs/autobridge-0.1.0.jar
.\dev.ps1 dev           # dev jar   -> build/libs/autobridge-0.1.0-dev.jar
```

`dev.ps1` locates a JDK 21 on this machine automatically, so no global `JAVA_HOME` is needed.

Manual install: drop the **user** jar (`autobridge-0.1.0.jar`) into `.minecraft/mods/` alongside **Fabric API**.

## FAQ

**The mod does nothing.** Check the HUD's "Result" line:

- `not sneaking` → forced sneak isn't taking effect.
- `crosshair not on a block` → your view isn't low enough. **Standing directly on top of a block, you mathematically cannot see its side faces** — you have to be at the edge and looking down roughly 65° or steeper.
- `target not on the foot layer` / `not in the opposite direction of view` → the target landed somewhere unexpected; check the "Expected" vs "Actual" coordinates on the HUD.

**It placed a block when I aimed at the top face.** It shouldn't — condition ⑧ rejects top and bottom faces. If you see this, the log line `模拟右键 #N: placePos=... hit=.../...` records the face for each placement; please report it with that line.

**Why is there no keybind?** By design. Settings are GUI-only so nothing can conflict with your own keys.

**Can I get banned for this?** AutoBridge reduces the likelihood of *automated* detection, and **offers no guarantee whatsoever**. It removes low-level packet-level tells (it builds no custom packets, sends no rotation it didn't receive), but a server can still identify assistance behaviourally — placement timing that's too precise, a suspiciously constant CPS, movement that's too regular. **Use it in singleplayer or on a private server you control.** On public servers, check the rules first; that's entirely up to the server.

## License

MIT — see [LICENSE](LICENSE).
