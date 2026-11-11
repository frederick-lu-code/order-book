package orderbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Replays one million pre-generated commands into a fresh book with a no-op listener.
 * The reported throughput is commands per second.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class OrderBookBenchmark {

    private static final int COMMANDS = 1_000_000;
    private static final long SEED = 20261111L;
    private static final long MID = 10_000;
    private static final int TICK_RANGE = 10;
    private static final int MAX_QUANTITY = 100;

    private static final byte LIMIT = 0;
    private static final byte MARKET = 1;
    private static final byte CANCEL = 2;

    private final byte[] types = new byte[COMMANDS];
    private final long[] ids = new long[COMMANDS];
    private final Side[] sides = new Side[COMMANDS];
    private final long[] prices = new long[COMMANDS];
    private final long[] quantities = new long[COMMANDS];

    /**
     * Generates about 60% limit orders, 30% cancels and 10% market orders. The commands are
     * run through a scratch book as they are generated, so that every cancel names an order
     * that is still resting at that point of the replay.
     */
    @Setup(Level.Trial)
    public void generate() {
        Random random = new Random(SEED);
        Map<Long, Long> remaining = new HashMap<>();
        List<Long> cancelCandidates = new ArrayList<>();
        OrderBook scratch = new OrderBook(event -> {
            if (event instanceof Event.Trade trade) {
                remaining.computeIfPresent(trade.restingId(),
                        (id, left) -> left == trade.quantity() ? null : Long.valueOf(left - trade.quantity()));
                remaining.computeIfPresent(trade.aggressorId(),
                        (id, left) -> left == trade.quantity() ? null : Long.valueOf(left - trade.quantity()));
            }
        });

        for (int i = 0; i < COMMANDS; i++) {
            long id = i + 1;
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long quantity = 1 + random.nextInt(MAX_QUANTITY);
            int roll = random.nextInt(10);
            long liveId = roll >= 6 && roll < 9 ? pickLive(random, cancelCandidates, remaining) : -1;

            if (liveId >= 0) {
                types[i] = CANCEL;
                ids[i] = liveId;
                remaining.remove(liveId);
                scratch.cancel(liveId);
            } else if (roll == 9) {
                types[i] = MARKET;
                ids[i] = id;
                sides[i] = side;
                quantities[i] = quantity;
                scratch.submitMarket(id, side, quantity);
            } else {
                // Also the fallback for a cancel drawn while nothing is resting.
                long price = MID - TICK_RANGE + random.nextInt(2 * TICK_RANGE + 1);
                types[i] = LIMIT;
                ids[i] = id;
                sides[i] = side;
                prices[i] = price;
                quantities[i] = quantity;
                remaining.put(id, quantity);
                cancelCandidates.add(id);
                scratch.submitLimit(id, side, price, quantity);
            }
        }
    }

    /** Removes and returns a random id that is still resting, or -1 if there is none. */
    private static long pickLive(Random random, List<Long> candidates, Map<Long, Long> remaining) {
        while (!candidates.isEmpty()) {
            int at = random.nextInt(candidates.size());
            long id = candidates.get(at);
            candidates.set(at, candidates.get(candidates.size() - 1));
            candidates.remove(candidates.size() - 1);
            if (remaining.containsKey(id)) {
                return id;
            }
        }
        return -1;
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS)
    public int replay() {
        OrderBook book = new OrderBook(event -> {});
        for (int i = 0; i < COMMANDS; i++) {
            switch (types[i]) {
                case LIMIT -> book.submitLimit(ids[i], sides[i], prices[i], quantities[i]);
                case MARKET -> book.submitMarket(ids[i], sides[i], quantities[i]);
                default -> book.cancel(ids[i]);
            }
        }
        return book.orderCount();
    }
}
