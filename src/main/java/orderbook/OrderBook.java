package orderbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A single-instrument limit order book that matches by price-time priority.
 *
 * <p>Commands are method calls; every outcome is reported as an {@link Event} through the
 * {@link EventListener} given at construction. The book is single-threaded, deterministic
 * and does no I/O.
 */
public final class OrderBook {

    /** An aggregated, read-only snapshot of one price level. */
    public record LevelView(long price, long totalQuantity, int orderCount) {}

    // Valid prices are positive, so 0 stands for "this side is empty" in the cached top.
    private static final long NO_PRICE = 0;

    private final EventListener listener;
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    private final HashMap<Long, Order> orders = new HashMap<>();

    private long topBidPrice = NO_PRICE;
    private long topBidQuantity;
    private long topAskPrice = NO_PRICE;
    private long topAskQuantity;

    /**
     * Creates an empty book.
     *
     * @param listener receives every event the book emits
     */
    public OrderBook(EventListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Submits a limit order. It trades against the opposite side while the book crosses,
     * then rests any remainder at the back of the queue at its price.
     *
     * @param orderId  id that no resting order currently uses
     * @param side     buy or sell
     * @param price    limit price in ticks, positive
     * @param quantity order size, positive
     */
    public void submitLimit(long orderId, Side side, long price, long quantity) {
        Objects.requireNonNull(side, "side");
        if (quantity <= 0) {
            reject(orderId, RejectReason.INVALID_QUANTITY);
        } else if (price <= 0) {
            reject(orderId, RejectReason.INVALID_PRICE);
        } else if (orders.containsKey(orderId)) {
            reject(orderId, RejectReason.DUPLICATE_ORDER_ID);
        } else {
            listener.onEvent(new Event.OrderAccepted(orderId));
            place(orderId, side, price, quantity);
            publishTopIfChanged();
        }
    }

    /**
     * Submits a market order. It trades at any price until it is filled or the opposite
     * side is empty; an unfilled remainder is cancelled, never rested.
     *
     * @param orderId  id that no resting order currently uses
     * @param side     buy or sell
     * @param quantity order size, positive
     */
    public void submitMarket(long orderId, Side side, long quantity) {
        Objects.requireNonNull(side, "side");
        if (quantity <= 0) {
            reject(orderId, RejectReason.INVALID_QUANTITY);
        } else if (orders.containsKey(orderId)) {
            reject(orderId, RejectReason.DUPLICATE_ORDER_ID);
        } else {
            listener.onEvent(new Event.OrderAccepted(orderId));
            long anyPrice = side == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE;
            long unfilled = match(orderId, side, anyPrice, quantity);
            if (unfilled > 0) {
                listener.onEvent(new Event.OrderCancelled(orderId, unfilled));
            }
            publishTopIfChanged();
        }
    }

    /**
     * Cancels a resting order.
     *
     * @param orderId id of a resting order
     */
    public void cancel(long orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            reject(orderId, RejectReason.UNKNOWN_ORDER);
            return;
        }
        long remaining = order.remaining;
        remove(order);
        listener.onEvent(new Event.OrderCancelled(orderId, remaining));
        publishTopIfChanged();
    }

    /**
     * Changes a resting order's price and remaining quantity. A reduction at the same price
     * keeps the order's queue position; a price change or a quantity increase re-enters it
     * as a new limit order, which may trade immediately.
     *
     * @param orderId     id of a resting order
     * @param newPrice    new limit price in ticks, positive
     * @param newQuantity new remaining quantity (not the original order size), positive
     */
    public void amend(long orderId, long newPrice, long newQuantity) {
        Order order = orders.get(orderId);
        if (order == null) {
            reject(orderId, RejectReason.UNKNOWN_ORDER);
        } else if (newQuantity <= 0) {
            reject(orderId, RejectReason.INVALID_QUANTITY);
        } else if (newPrice <= 0) {
            reject(orderId, RejectReason.INVALID_PRICE);
        } else {
            listener.onEvent(new Event.OrderAmended(orderId, newPrice, newQuantity));
            if (newPrice == order.price && newQuantity <= order.remaining) {
                order.level.reduce(order, order.remaining - newQuantity);
            } else {
                remove(order);
                place(orderId, order.side, newPrice, newQuantity);
            }
            publishTopIfChanged();
        }
    }

    /** Returns the highest resting buy price, or empty if there are no bids. */
    public OptionalLong bestBid() {
        return bestPrice(bids);
    }

    /** Returns the lowest resting sell price, or empty if there are no asks. */
    public OptionalLong bestAsk() {
        return bestPrice(asks);
    }

    /**
     * Returns the total resting quantity on {@code side} at {@code price}.
     *
     * @return the quantity, or 0 if there is no such level
     */
    public long volumeAt(Side side, long price) {
        PriceLevel level = side(side).get(price);
        return level == null ? 0 : level.totalQuantity;
    }

    /** Returns the number of resting orders across both sides. */
    public int orderCount() {
        return orders.size();
    }

    /**
     * Returns a snapshot of every price level on {@code side}, best price first.
     *
     * @return an unmodifiable list that later commands do not change
     */
    public List<LevelView> levels(Side side) {
        List<LevelView> views = new ArrayList<>(side(side).size());
        for (PriceLevel level : side(side).values()) {
            views.add(new LevelView(level.price, level.totalQuantity, level.orderCount));
        }
        return List.copyOf(views);
    }

    /** The live level map for one side, for invariant checks in tests. */
    TreeMap<Long, PriceLevel> side(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** The live id index, for invariant checks in tests. */
    HashMap<Long, Order> index() {
        return orders;
    }

    private static OptionalLong bestPrice(TreeMap<Long, PriceLevel> levels) {
        return levels.isEmpty() ? OptionalLong.empty() : OptionalLong.of(levels.firstKey());
    }

    private void reject(long orderId, RejectReason reason) {
        listener.onEvent(new Event.OrderRejected(orderId, reason));
    }

    /** Matches a limit order, then rests whatever is left at the back of its price level. */
    private void place(long orderId, Side side, long price, long quantity) {
        long unfilled = match(orderId, side, price, quantity);
        if (unfilled > 0) {
            Order order = new Order(orderId, side, price, unfilled);
            side(side).computeIfAbsent(price, PriceLevel::new).append(order);
            orders.put(orderId, order);
        }
    }

    /**
     * Fills an incoming order against the opposite side, best price first and oldest order
     * first within a price, until it is filled or the book no longer crosses {@code limitPrice}.
     *
     * @return the quantity left unfilled
     */
    private long match(long aggressorId, Side side, long limitPrice, long quantity) {
        TreeMap<Long, PriceLevel> opposite = side == Side.BUY ? asks : bids;
        while (quantity > 0 && !opposite.isEmpty()) {
            PriceLevel level = opposite.firstEntry().getValue();
            boolean crosses = side == Side.BUY ? level.price <= limitPrice : level.price >= limitPrice;
            if (!crosses) {
                break;
            }
            while (quantity > 0 && !level.isEmpty()) {
                Order resting = level.head;
                long fill = Math.min(quantity, resting.remaining);
                quantity -= fill;
                level.reduce(resting, fill);
                if (resting.remaining == 0) {
                    level.unlink(resting);
                    orders.remove(resting.id);
                }
                listener.onEvent(new Event.Trade(aggressorId, resting.id, level.price, fill));
            }
            if (level.isEmpty()) {
                opposite.remove(level.price);
            }
        }
        return quantity;
    }

    /** Takes a resting order out of its level and the id index, dropping the level if it empties. */
    private void remove(Order order) {
        PriceLevel level = order.level;
        level.unlink(order);
        if (level.isEmpty()) {
            side(order.side).remove(level.price);
        }
        orders.remove(order.id);
    }

    private void publishTopIfChanged() {
        PriceLevel bid = bids.isEmpty() ? null : bids.firstEntry().getValue();
        PriceLevel ask = asks.isEmpty() ? null : asks.firstEntry().getValue();
        long bidPrice = bid == null ? NO_PRICE : bid.price;
        long bidQuantity = bid == null ? 0 : bid.totalQuantity;
        long askPrice = ask == null ? NO_PRICE : ask.price;
        long askQuantity = ask == null ? 0 : ask.totalQuantity;
        if (bidPrice == topBidPrice && bidQuantity == topBidQuantity
                && askPrice == topAskPrice && askQuantity == topAskQuantity) {
            return;
        }
        topBidPrice = bidPrice;
        topBidQuantity = bidQuantity;
        topAskPrice = askPrice;
        topAskQuantity = askQuantity;
        listener.onEvent(new Event.TopOfBook(
                bid == null ? OptionalLong.empty() : OptionalLong.of(bidPrice), bidQuantity,
                ask == null ? OptionalLong.empty() : OptionalLong.of(askPrice), askQuantity));
    }
}
