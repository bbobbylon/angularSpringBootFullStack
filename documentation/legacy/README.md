# Legacy documentation

**Status:** Archive — not maintained, not linked from the current doc set, kept for historical
reference only. If a claim here disagrees with [GUIDE.md](../GUIDE.md), the code and GUIDE.md win.

## What's here and why

| Document | What it was | Why it's archived |
|---|---|---|
| [PHASE-2-IMPLEMENTATION.md](PHASE-2-IMPLEMENTATION.md) | Source document for the Master's course Implementation 2 deliverable (built Jul 11 → Aug 3, 2026) | The course ended 2026-08-20. Its content was folded into [IMPLEMENTATION-HISTORY.md](../IMPLEMENTATION-HISTORY.md) and [FEATURE-INVENTORY.md](../FEATURE-INVENTORY.md) during the 2026-08-19 doc consolidation, both of which are kept current; this one is not |
| [PHASE-2-ADDITIONS.md](PHASE-2-ADDITIONS.md) | Exhaustive itemized catalog of Phase 2 additions — the handoff document for producing that same deliverable | Same reason — superseded by the same two documents, academic scope only |

Both files still describe real, shipped work — nothing in them is *wrong*, they're just a snapshot
frozen at August 3, 2026, describing a project state roughly two hundred commits behind the current
one. Anything in them still true today is now stated in the current docs; the current docs are the
only ones that get updated going forward.

## Why a folder instead of deletion

Deleting a doc that nine other files still cross-linked to was exactly the failure mode
[IMPLEMENTATION-HISTORY.md §4.20](../IMPLEMENTATION-HISTORY.md#420-the-documentation-itself-rotted)
diagnosed — dead links with no mechanism to catch them. Moving instead of deleting keeps every
existing link resolvable (updated to point here) rather than reproducing that same failure a second
time.
