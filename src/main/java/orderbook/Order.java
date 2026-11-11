package orderbook;

/** A mutable resting order, linked into its {@link PriceLevel}'s queue. */
final class Order {
    final long id;
    final Side side;
    long price;
    long remaining;
    // Back-pointer so a cancel reaches its level without a tree lookup.
    PriceLevel level;
    Order prev;
    Order next;

    Order(long id, Side side, long price, long remaining) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = remaining;
    }
}
