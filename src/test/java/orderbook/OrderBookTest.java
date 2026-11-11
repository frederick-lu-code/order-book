package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import orderbook.Event.OrderAccepted;
import orderbook.Event.OrderAmended;
import orderbook.Event.OrderCancelled;
import orderbook.Event.OrderRejected;
import orderbook.Event.TopOfBook;
import orderbook.Event.Trade;
import orderbook.OrderBook.LevelView;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static final long NONE = 0;

    private final List<Event> events = new ArrayList<>();
    private final OrderBook book = new OrderBook(events::add);

    /** Returns the events emitted since the last call, and forgets them. */
    private List<Event> drain() {
        List<Event> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    /** Builds a TopOfBook where a price of NONE means that side is empty. */
    private static TopOfBook top(long bidPrice, long bidQuantity, long askPrice, long askQuantity) {
        return new TopOfBook(
                bidPrice == NONE ? OptionalLong.empty() : OptionalLong.of(bidPrice), bidQuantity,
                askPrice == NONE ? OptionalLong.empty() : OptionalLong.of(askPrice), askQuantity);
    }

    @Test
    void limitOrderRestsInEmptyBookAndUpdatesTop() {
        book.submitLimit(1, Side.BUY, 100, 10);

        assertEquals(List.of(new OrderAccepted(1), top(100, 10, NONE, 0)), drain());
        assertEquals(OptionalLong.of(100), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertEquals(1, book.orderCount());
    }

    @Test
    void crossingLimitFullyFillsRestingOrderAtRestingPrice() {
        book.submitLimit(1, Side.SELL, 101, 5);
        drain();

        book.submitLimit(2, Side.BUY, 102, 5);

        assertEquals(List.of(new OrderAccepted(2), new Trade(2, 1, 101, 5), top(NONE, 0, NONE, 0)), drain());
        assertEquals(0, book.orderCount());
    }

    @Test
    void partialFillLeavesAggressorRemainderResting() {
        book.submitLimit(1, Side.SELL, 101, 5);
        drain();

        book.submitLimit(2, Side.BUY, 101, 8);

        assertEquals(List.of(new OrderAccepted(2), new Trade(2, 1, 101, 5), top(101, 3, NONE, 0)), drain());
        assertEquals(3, book.volumeAt(Side.BUY, 101));
        assertEquals(1, book.orderCount());
    }

    @Test
    void partialFillLeavesRestingRemainderAtFrontOfQueue() {
        book.submitLimit(1, Side.SELL, 101, 10);
        book.submitLimit(2, Side.SELL, 101, 5);
        drain();

        book.submitLimit(3, Side.BUY, 101, 4);
        assertEquals(List.of(new OrderAccepted(3), new Trade(3, 1, 101, 4), top(NONE, 0, 101, 11)), drain());

        book.submitLimit(4, Side.BUY, 101, 6);
        assertEquals(List.of(new OrderAccepted(4), new Trade(4, 1, 101, 6), top(NONE, 0, 101, 5)), drain());
    }

    @Test
    void aggressorSweepsSeveralLevelsBestPriceFirst() {
        book.submitLimit(1, Side.SELL, 103, 5);
        book.submitLimit(2, Side.SELL, 101, 5);
        book.submitLimit(3, Side.SELL, 102, 5);
        drain();

        book.submitLimit(4, Side.BUY, 103, 12);

        assertEquals(List.of(
                new OrderAccepted(4),
                new Trade(4, 2, 101, 5),
                new Trade(4, 3, 102, 5),
                new Trade(4, 1, 103, 2),
                top(NONE, 0, 103, 3)), drain());
        assertEquals(List.of(new LevelView(103, 3, 1)), book.levels(Side.SELL));
    }

    @Test
    void ordersAtOnePriceFillInArrivalOrder() {
        book.submitLimit(1, Side.SELL, 101, 5);
        book.submitLimit(2, Side.SELL, 101, 5);
        drain();

        book.submitLimit(3, Side.BUY, 101, 10);

        assertEquals(List.of(
                new OrderAccepted(3),
                new Trade(3, 1, 101, 5),
                new Trade(3, 2, 101, 5),
                top(NONE, 0, NONE, 0)), drain());
    }

    @Test
    void marketOrderFillsAcrossLevelsAndCancelsRemainder() {
        book.submitLimit(1, Side.BUY, 100, 5);
        book.submitLimit(2, Side.BUY, 99, 5);
        drain();

        book.submitMarket(3, Side.SELL, 15);

        assertEquals(List.of(
                new OrderAccepted(3),
                new Trade(3, 1, 100, 5),
                new Trade(3, 2, 99, 5),
                new OrderCancelled(3, 5),
                top(NONE, 0, NONE, 0)), drain());
        assertEquals(0, book.orderCount());
    }

    @Test
    void marketOrderIntoEmptyBookIsAcceptedThenCancelled() {
        book.submitMarket(1, Side.BUY, 7);

        assertEquals(List.of(new OrderAccepted(1), new OrderCancelled(1, 7)), drain());
        assertEquals(0, book.orderCount());
    }

    @Test
    void cancellingOnlyOrderAtLevelRemovesLevel() {
        book.submitLimit(1, Side.BUY, 100, 5);
        book.submitLimit(2, Side.BUY, 99, 5);
        drain();

        book.cancel(1);

        assertEquals(List.of(new OrderCancelled(1, 5), top(99, 5, NONE, 0)), drain());
        assertEquals(List.of(new LevelView(99, 5, 1)), book.levels(Side.BUY));
        assertEquals(0, book.volumeAt(Side.BUY, 100));
    }

    @Test
    void cancellingPartiallyFilledOrderReportsRemainingQuantity() {
        book.submitLimit(1, Side.SELL, 101, 10);
        book.submitLimit(2, Side.BUY, 101, 4);
        drain();

        book.cancel(1);

        assertEquals(List.of(new OrderCancelled(1, 6), top(NONE, 0, NONE, 0)), drain());
    }

    @Test
    void amendReducingQuantityAtSamePriceKeepsQueuePosition() {
        book.submitLimit(1, Side.SELL, 101, 10);
        book.submitLimit(2, Side.SELL, 101, 5);
        drain();

        book.amend(1, 101, 6);
        assertEquals(List.of(new OrderAmended(1, 101, 6), top(NONE, 0, 101, 11)), drain());

        book.submitLimit(3, Side.BUY, 101, 6);
        assertEquals(List.of(new OrderAccepted(3), new Trade(3, 1, 101, 6), top(NONE, 0, 101, 5)), drain());
    }

    @Test
    void amendIncreasingQuantityLosesQueuePosition() {
        book.submitLimit(1, Side.SELL, 101, 5);
        book.submitLimit(2, Side.SELL, 101, 5);
        drain();

        book.amend(1, 101, 8);
        assertEquals(List.of(new OrderAmended(1, 101, 8), top(NONE, 0, 101, 13)), drain());

        book.submitLimit(3, Side.BUY, 101, 5);
        assertEquals(List.of(new OrderAccepted(3), new Trade(3, 2, 101, 5), top(NONE, 0, 101, 8)), drain());
    }

    @Test
    void amendToCrossingPriceTradesImmediately() {
        book.submitLimit(1, Side.SELL, 101, 5);
        book.submitLimit(2, Side.BUY, 100, 8);
        drain();

        book.amend(2, 101, 8);

        assertEquals(List.of(
                new OrderAmended(2, 101, 8),
                new Trade(2, 1, 101, 5),
                top(101, 3, NONE, 0)), drain());
        assertEquals(1, book.orderCount());
    }

    @Test
    void eachRejectReasonIsProducedByItsTriggerAndLeavesBookUnchanged() {
        book.submitLimit(1, Side.BUY, 100, 10);
        book.submitLimit(2, Side.SELL, 105, 10);
        drain();

        assertRejected(() -> book.submitLimit(3, Side.BUY, 100, 0), 3, RejectReason.INVALID_QUANTITY);
        assertRejected(() -> book.submitLimit(3, Side.BUY, 100, -5), 3, RejectReason.INVALID_QUANTITY);
        assertRejected(() -> book.submitLimit(3, Side.BUY, 0, 10), 3, RejectReason.INVALID_PRICE);
        assertRejected(() -> book.submitLimit(1, Side.SELL, 105, 5), 1, RejectReason.DUPLICATE_ORDER_ID);
        assertRejected(() -> book.submitMarket(3, Side.BUY, 0), 3, RejectReason.INVALID_QUANTITY);
        assertRejected(() -> book.submitMarket(2, Side.BUY, 5), 2, RejectReason.DUPLICATE_ORDER_ID);
        assertRejected(() -> book.cancel(9), 9, RejectReason.UNKNOWN_ORDER);
        assertRejected(() -> book.amend(9, 100, 5), 9, RejectReason.UNKNOWN_ORDER);
        assertRejected(() -> book.amend(1, 100, 0), 1, RejectReason.INVALID_QUANTITY);
        assertRejected(() -> book.amend(1, 0, 5), 1, RejectReason.INVALID_PRICE);
    }

    @Test
    void rejectionsAreCheckedInSpecifiedOrder() {
        book.submitLimit(1, Side.BUY, 100, 10);
        drain();

        // Quantity before price before duplicate id for new orders.
        assertRejected(() -> book.submitLimit(1, Side.BUY, 0, 0), 1, RejectReason.INVALID_QUANTITY);
        assertRejected(() -> book.submitLimit(1, Side.BUY, 0, 5), 1, RejectReason.INVALID_PRICE);
        assertRejected(() -> book.submitMarket(1, Side.BUY, 0), 1, RejectReason.INVALID_QUANTITY);
        // Unknown order first for amend, then quantity before price.
        assertRejected(() -> book.amend(9, 0, 0), 9, RejectReason.UNKNOWN_ORDER);
        assertRejected(() -> book.amend(1, 0, 0), 1, RejectReason.INVALID_QUANTITY);
    }

    private void assertRejected(Runnable command, long orderId, RejectReason reason) {
        List<LevelView> bidsBefore = book.levels(Side.BUY);
        List<LevelView> asksBefore = book.levels(Side.SELL);
        int countBefore = book.orderCount();

        command.run();

        assertEquals(List.of(new OrderRejected(orderId, reason)), drain());
        assertEquals(bidsBefore, book.levels(Side.BUY));
        assertEquals(asksBefore, book.levels(Side.SELL));
        assertEquals(countBefore, book.orderCount());
    }

    @Test
    void fullyFilledOrderIdCanBeReused() {
        book.submitLimit(1, Side.SELL, 101, 5);
        book.submitLimit(2, Side.BUY, 101, 5);
        drain();

        book.submitLimit(1, Side.BUY, 100, 3);

        assertEquals(List.of(new OrderAccepted(1), top(100, 3, NONE, 0)), drain());
    }

    @Test
    void cancelledOrderIdCanBeReused() {
        book.submitLimit(1, Side.SELL, 101, 5);
        book.cancel(1);
        drain();

        book.submitLimit(1, Side.SELL, 102, 4);

        assertEquals(List.of(new OrderAccepted(1), top(NONE, 0, 102, 4)), drain());
    }

    @Test
    void noTopOfBookWhenCommandLeavesTopUnchanged() {
        book.submitLimit(1, Side.BUY, 100, 5);
        book.submitLimit(2, Side.BUY, 99, 5);
        drain();

        book.submitLimit(3, Side.BUY, 98, 5);
        assertEquals(List.of(new OrderAccepted(3)), drain());

        book.amend(3, 97, 4);
        assertEquals(List.of(new OrderAmended(3, 97, 4)), drain());

        book.cancel(2);
        assertEquals(List.of(new OrderCancelled(2, 5)), drain());

        // An amend that changes nothing at the top is silent too.
        book.amend(1, 100, 5);
        assertEquals(List.of(new OrderAmended(1, 100, 5)), drain());
    }

    @Test
    void levelsAreListedBestPriceFirst() {
        book.submitLimit(1, Side.BUY, 99, 1);
        book.submitLimit(2, Side.BUY, 100, 2);
        book.submitLimit(3, Side.BUY, 100, 3);
        book.submitLimit(4, Side.SELL, 103, 4);
        book.submitLimit(5, Side.SELL, 102, 5);

        assertEquals(List.of(new LevelView(100, 5, 2), new LevelView(99, 1, 1)), book.levels(Side.BUY));
        assertEquals(List.of(new LevelView(102, 5, 1), new LevelView(103, 4, 1)), book.levels(Side.SELL));
    }

    @Test
    void randomCommandsNeverBreakInvariants() {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            applyRandomCommand(book, random);
            assertInvariants(i);
        }
        assertTrue(events.stream().anyMatch(e -> e instanceof Trade), "the run should trade");
    }

    @Test
    void sameInputProducesSameEvents() {
        List<Event> second = new ArrayList<>();
        OrderBook other = new OrderBook(second::add);
        Random a = new Random(7);
        Random b = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            applyRandomCommand(book, a);
            applyRandomCommand(other, b);
        }
        assertEquals(events, second);
    }

    /** Ids are drawn from a small range so cancels, amends and duplicates often hit live orders. */
    private static void applyRandomCommand(OrderBook target, Random random) {
        long id = 1 + random.nextInt(300);
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long price = 95 + random.nextInt(11);
        long quantity = random.nextInt(20) == 0 ? 0 : 1 + random.nextInt(50);
        switch (random.nextInt(10)) {
            case 0, 1, 2, 3, 4 -> target.submitLimit(id, side, price, quantity);
            case 5 -> target.submitMarket(id, side, quantity);
            case 6, 7 -> target.cancel(id);
            default -> target.amend(id, price, quantity);
        }
    }

    private void assertInvariants(int step) {
        String at = "after command " + step;
        if (book.bestBid().isPresent() && book.bestAsk().isPresent()) {
            assertTrue(book.bestBid().getAsLong() < book.bestAsk().getAsLong(), "book crossed " + at);
        }
        int resting = 0;
        for (Side side : Side.values()) {
            for (PriceLevel level : book.side(side).values()) {
                long quantity = 0;
                int count = 0;
                Order previous = null;
                for (Order order = level.head; order != null; order = order.next) {
                    assertSame(order, book.index().get(order.id), "order missing from index " + at);
                    assertSame(level, order.level, at);
                    assertSame(previous, order.prev, at);
                    assertEquals(level.price, order.price, at);
                    assertEquals(side, order.side, at);
                    assertTrue(order.remaining > 0, "empty order left resting " + at);
                    quantity += order.remaining;
                    count++;
                    previous = order;
                }
                assertSame(previous, level.tail, at);
                assertTrue(count > 0, "empty level left in book " + at);
                assertEquals(quantity, level.totalQuantity, "level quantity " + at);
                assertEquals(count, level.orderCount, "level order count " + at);
                resting += count;
            }
        }
        assertEquals(resting, book.orderCount(), "orderCount() " + at);
        assertEquals(resting, book.index().size(), "id index size " + at);
    }
}
