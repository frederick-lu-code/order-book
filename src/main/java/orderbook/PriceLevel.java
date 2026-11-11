package orderbook;

/** All resting orders at one price, as an intrusive doubly linked list in arrival order. */
final class PriceLevel {
    final long price;
    long totalQuantity;
    int orderCount;
    Order head;
    Order tail;

    PriceLevel(long price) {
        this.price = price;
    }

    boolean isEmpty() {
        return head == null;
    }

    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.remaining;
        orderCount++;
    }

    void unlink(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        order.prev = null;
        order.next = null;
        order.level = null;
        totalQuantity -= order.remaining;
        orderCount--;
    }

    /** Takes {@code quantity} off a queued order, by a fill or an in-place amend. */
    void reduce(Order order, long quantity) {
        order.remaining -= quantity;
        totalQuantity -= quantity;
    }
}
