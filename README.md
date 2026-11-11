# order-book

A single-instrument limit order book with price-time priority matching, written as a small dependency-free Java 21 library. It comes with a command-line tool that replays a CSV file of commands and prints every event the engine emits.

## Build, test and run

Requires JDK 21. The Gradle wrapper downloads everything else.

```
./gradlew build                              # compile with -Xlint:all -Werror and run all tests
./gradlew run --args="examples/sample.csv"   # replay the sample
./gradlew jmh                                # run the benchmark (about 90 seconds)
```

## Design decisions

- **`TreeMap` per side, intrusive linked list per level, `HashMap` id index.** The tree gives the best price and level insertion in O(log P). Each order carries its own `prev`/`next` links and a pointer to its level, so a cancel or a fill unlinks it in O(1) with no search and no node allocation.
- **Single-threaded.** Matching is inherently sequential: each command's result depends on every earlier one. One thread makes the output deterministic and replayable, and needs no locks.
- **Integer ticks.** Prices and quantities are `long`. Comparisons are exact, and there is no rounding to argue about.
- **Events out, no shared state.** Callers only ever see immutable records, so the mutable internals can change freely.

Where the spec was silent I took the simplest option; the full list with reasons is in [docs/DESIGN.md](docs/DESIGN.md). The ones a user will notice:

- `Order` has a `level` back-pointer that the spec does not list. Cancel cannot be O(1) without it.
- A market order whose id is already resting is rejected with `DUPLICATE_ORDER_ID`.
- An amend that re-enters the book cannot trade with itself, because it is removed first.
- A listener must not call back into the book that is notifying it.
- The CLI streams: events for lines before a malformed line are already printed when it exits with status 2.

## Benchmark

One million seeded commands replayed into a fresh book with a no-op listener:

| Result | Machine | JDK |
| --- | --- | --- |
| 29.7M ± 1.6M commands/s (about 34 ns each) | Apple M5, 16 GB, macOS 26.6 | Temurin 21.0.13+9 |

The generated mix is 61.8% limit, 28.2% cancel and 10.0% market. Limit prices are uniform within 10 ticks of the mid on both sides, as specified, so bids and asks overlap heavily and the book stays shallow (5 orders resting at the end of the run). The figure therefore measures matching and event allocation far more than deep-book inserts and cancels.

## Known limitations

- One instrument, no order owners, no time-in-force, no self-trade prevention, no persistence.
- A level's total quantity is a `long` sum with no overflow check.
- Every event and every resting order is a fresh allocation, and `TreeMap`/`HashMap` box their `Long` keys.

A production version would add object pooling for orders and events, array-based price levels indexed by tick around the mid, a primitive-keyed id map, a ring-buffer hand-off to other threads, and a journal for recovery.

Work was done in the milestones listed in [docs/MILESTONES.md](docs/MILESTONES.md).
