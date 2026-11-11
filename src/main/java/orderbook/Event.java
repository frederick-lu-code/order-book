package orderbook;

import java.util.OptionalLong;

/** An immutable fact emitted by the {@link OrderBook} in the order it happened. */
public sealed interface Event {

    /** A new limit or market order passed validation. */
    record OrderAccepted(long orderId) implements Event {}

    /** A command failed validation and changed nothing. */
    record OrderRejected(long orderId, RejectReason reason) implements Event {}

    /** A fill between an incoming order and a resting order, at the resting order's price. */
    record Trade(long aggressorId, long restingId, long price, long quantity) implements Event {}

    /** An order left the book unfilled, or a market order's unfilled remainder was dropped. */
    record OrderCancelled(long orderId, long remainingQuantity) implements Event {}

    /** An amend was applied; {@code quantity} is the requested new remaining quantity. */
    record OrderAmended(long orderId, long price, long quantity) implements Event {}

    /**
     * The best price and the total resting quantity at that price on each side.
     * {@code OptionalLong.empty()} means that side of the book is empty, and its quantity is 0.
     */
    record TopOfBook(OptionalLong bidPrice, long bidQuantity,
                     OptionalLong askPrice, long askQuantity) implements Event {}
}
