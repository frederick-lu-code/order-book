# Design notes: decisions and trade-offs

The spec fixes most of the design. This file records every place where it was silent or where two readings were possible, what was chosen, and what the choice costs.

## Engine

| Topic | Choice | Why, and what it costs |
| --- | --- | --- |
| Finding an order's level on cancel | `Order` has a `level` back-pointer, a field the spec does not list. | The spec requires cancel in O(1). Looking the level up by price in the `TreeMap` is O(log P). Cost: one extra reference per resting order. `Order.price` is kept as specified although it now duplicates `level.price`. |
| Market order with a resting id | Rejected with `DUPLICATE_ORDER_ID`. | The spec says the check applies to "new orders", and a market order is one. Allowing it would produce trades whose aggressor id is ambiguous. |
| Market order validation | `INVALID_QUANTITY`, then `DUPLICATE_ORDER_ID`. | A market order carries no price, so `INVALID_PRICE` cannot apply. |
| Amend validation | `UNKNOWN_ORDER`, then `INVALID_QUANTITY`, then `INVALID_PRICE`. | Follows the spec's order with `UNKNOWN_ORDER` moved first. An amend to quantity 0 is a rejection, not a cancel. |
| Amend with nothing changed | Emits `OrderAmended` and nothing else; the order keeps its place. | It falls under "price unchanged and quantity at or below remaining". |
| Amend that loses priority | The old `Order` is removed and a new one is created. | Removing first means the order can never match against itself. Reusing the object would save one allocation but makes the state harder to reason about. |
| Amend at the same price with more quantity | The order moves to the back of its own level. | That is what "loses priority" means when the price does not change. |
| Top-of-book change detection | The book caches the last published four values and compares at the end of each accepted command. | Four `long` fields and no allocation unless something changed. Price 0 stands for "empty side" internally, which is safe because valid prices are positive. A rejected command changes nothing, so it skips the check. |
| `TopOfBook` quantity on an empty side | 0, alongside `OptionalLong.empty()`. | The record needs some value; the CLI prints `-` for both. |
| Event timing | Each event is emitted after the state change it describes. | The book is always consistent when a listener runs. Even so, a listener must not call back into the book: a re-entrant command would interleave its events with the outer command's. This is documented on `EventListener`, not enforced. |
| Null `side` or `listener` | `NullPointerException`. | A null is a programming error, not a market event, so it is not a `RejectReason`. |
| `levels(side)` | Returns an immutable copy. | Callers must never see mutable internals. Cost: O(P) per call, which is fine for a query used for display. |
| Test access to internals | `OrderBook.side(Side)` and `OrderBook.index()` are package-private. | The invariant test has to walk the real linked lists to compare them with the cached totals. They are invisible outside the package. |
| Overflow | Not checked. | A level's `totalQuantity` could overflow only if resting quantities sum past 2^63. Listed as a limitation. |
| Best price lookup | `TreeMap.firstEntry()`, O(log P). | Required by the spec. Caching the best level would make it O(1) at the cost of more state to keep in step. During a sweep the level is looked up once per level, not once per fill, so each fill is O(1). |

## Command-line tool

| Topic | Choice | Why, and what it costs |
| --- | --- | --- |
| Testability | `Main.run(args, out, err)` returns the exit status; `main` calls `System.exit` only for a non-zero status. | Tests can drive the CLI in-process. |
| Malformed line | Streaming: events for earlier lines are already on stdout when the tool exits with status 2. The final `BOOK` section is not printed. | Simplest, and uses constant memory. The alternative, validating the whole file first, needs two passes. |
| Whitespace | Leading and trailing whitespace on a line is ignored. Whitespace inside a field is malformed. | Tolerates trailing spaces and Windows line endings without inventing a looser grammar. |
| Unreadable file | Prints `cannot read <path>` and the usage line, exits with status 1. | The spec asks for a usage message; naming the path makes it useful. |
| Line endings | Output always uses `\n`. | `println` would emit `\r\n` on Windows and break the byte-for-byte match. |
| Engine rejections | Printed as `REJECTED` lines; the replay continues. | A rejection is a valid engine outcome, unlike a line that cannot be parsed. |
| Number format | `Long.parseLong`, so a leading `+` or `-` is accepted. | Negative values reach the engine and are rejected there with the proper reason. |

## Build

| Topic | Choice | Why, and what it costs |
| --- | --- | --- |
| Warnings | `-Xlint:all -Werror` on main, test and benchmark sources. | "No warnings" is enforced by the build, not by inspection. JMH's generated sources are compiled without it because they are not ours. |
| JDK | Gradle toolchain pinned to 21, with no auto-download plugin. | Auto-provisioning needs the foojay resolver plugin, which would be an extra dependency. Cost: you must have JDK 21 installed. |
| Versions | Gradle 8.14.3, JUnit 5.11.4, JMH 1.37, `me.champeau.jmh` 0.7.2. | Current stable releases that work together. |
| Benchmark settings | Set by annotations on the benchmark class. | One place to read them; the Gradle `jmh` block only pins the JMH version. |

## Tests

- Every engine test asserts on the complete event list for the command under test, using a recording listener that is drained between steps.
- Beyond the 16 required cases there are tests for the order of rejection checks, id reuse after a cancel, `levels()` ordering and determinism (two books fed the same seeded commands emit equal event lists).
- The randomised test draws ids from 1 to 300 so that cancels, amends and duplicate ids frequently hit live orders, and makes one command in twenty invalid. After each of the 10,000 commands it walks both sides and checks: the book is not crossed, every level's `totalQuantity` and `orderCount` match its list, the `prev`/`next`/`tail` links are consistent, no empty level or zero-quantity order remains, and `orderCount()` equals both the number of linked orders and the size of the id index.

## Benchmark

- The generator runs each command through a scratch book as it goes and tracks fills from the `Trade` events, so every cancel names an order that is resting at that point of the replay. If a cancel is drawn while nothing is resting, a limit order is generated instead, which is why the mix is 61.8/28.2/10.0 and not exactly 60/30/10.
- Order ids are 1 to 1,000,000 in sequence and are not reused.
- The benchmark method builds a fresh book and replays all commands; `@OperationsPerInvocation(1_000_000)` makes the score commands per second.
- Because limit prices are uniform within 10 ticks of the mid on both sides, as the spec asks, bids and asks overlap heavily and resting orders are soon traded away: only 5 orders are resting at the end of the run. The score is a fair measure of matching and event allocation and a poor measure of behaviour with a deep book. A more realistic generator would place bids below and asks above a drifting mid.
