# Owner test, 18:00 Oslo today — the plan

*Written 11:25 Oslo, 2026-08-26. **6.6 hours.** The owner will install and play
this himself. Everything below is ordered by what that requires.*

## What "ready" means, precisely

The owner does **not** need our harness, our playthrough bot, or a green gate
to play. He needs three things and nothing else matters more than these:

1. **A jar that installs and boots.**
2. **The first two hours of play working without a wall** — found a hearth,
   settlers arrive, hang a plaque, hire a lumberjack, see logs flow without
   his labour. That is the loop he named, and it is what a first session is.
3. **An honest known-issues note**, so he does not spend twenty minutes
   fighting something we already know is broken. A bug he was warned about
   costs a shrug; the same bug undocumented costs his afternoon and his
   trust in every other claim we make.

Everything else — the video, the fire raid, sugar cane, the full ten-suite
gate — is bonus and gets cut in that order if the clock demands it.

## Timeline

**11:25 → 13:30 — converge.** Land what is in flight: the harness input-decay
fix, the blind-spot investigation (save compatibility matters most today: if
he builds a world this afternoon and a later jar eats it, that is the worst
outcome available to us), and the fire-raid/paper/wall work. Suite green after
each sweep. **Freeze new feature work at 13:30** — anything not landed by then
is cut, not rushed.

**13:30 → 15:00 — play the path he will walk.** Not a full playthrough: the
first two hours, exactly. Bare hands → hearth → settlers → plaque → lumberjack
→ logs arriving without player labour. Every wall found in that window gets
fixed the same hour or written into the known-issues note. This is the only
verification that counts, because it is the only one shaped like his session.

**15:00 → 16:30 — freeze and package.** No new code. Full suite twice at one
fingerprint. Build the jar, install it clean, boot it, found one settlement to
prove the artifact itself works. Write the quick-start and the known-issues
note.

**16:30 → 18:00 — buffer.** Deliberately empty. Every schedule tonight that
had no buffer lost to something nobody predicted — a container reset, a
GitHub 503, a camera helper that killed the player. If the buffer goes unused,
that is when the video gets made.

## What he gets at 18:00

- The jar, and one line on how to install it.
- **Quick start**: what to craft first, what to expect in the first ten
  minutes, what the plaque is for.
- **Known issues**, ranked, honest — including anything the playthrough hit
  that we chose not to fix in time, and why.
- The current test count and what it does and does not prove.

## The standing rule for the next six hours

**Nothing ships as "works" that has not been run.** Tonight produced that
lesson three separate times at the cost of hours each. With a deadline the
temptation to reason instead of run is strongest — and a claim that fails in
his hands at 18:00 costs more than an honest gap in the note.
