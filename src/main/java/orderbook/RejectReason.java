package orderbook;

/** Why a command was rejected. A rejected command leaves the book unchanged. */
public enum RejectReason {
    /** Quantity was zero or negative. */
    INVALID_QUANTITY,
    /** Price was zero or negative. */
    INVALID_PRICE,
    /** The id of a new order belongs to an order currently resting. */
    DUPLICATE_ORDER_ID,
    /** A cancel or amend named an id that is not resting. */
    UNKNOWN_ORDER
}
