package orderbook;

/**
 * Receives every {@link Event} an {@link OrderBook} emits, synchronously and in order.
 * A listener must not call back into the book that is notifying it.
 */
@FunctionalInterface
public interface EventListener {
    /** Called once per event, on the thread that issued the command. */
    void onEvent(Event event);
}
