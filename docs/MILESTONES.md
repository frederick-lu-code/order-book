# Milestones

Each milestone is one or more commits and leaves `./gradlew build` green.

| # | Milestone | Done when |
| --- | --- | --- |
| 1 | Scaffold | Gradle Kotlin DSL build, committed wrapper, Java 21 toolchain, `-Xlint:all -Werror`. |
| 2 | Engine | Public types, `Order`, `PriceLevel` and `OrderBook` compile with no warnings. |
| 3 | Engine tests | The 16 specified cases and the 10,000-command invariant test pass. |
| 4 | CLI | `./gradlew run --args="examples/sample.csv"` matches the worked example byte for byte; `CliTest` passes. |
| 5 | Benchmark | `./gradlew jmh` runs with 3 warm-up and 5 measurement iterations and 1 fork. |
| 6 | Documentation | README with the measured result, `docs/DESIGN.md` with decisions and trade-offs. |
| 7 | Acceptance | Every acceptance criterion in the spec is checked on a clean clone. |
