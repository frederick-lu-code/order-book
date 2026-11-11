package orderbook.cli;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import orderbook.Event;
import orderbook.OrderBook;
import orderbook.OrderBook.LevelView;
import orderbook.Side;

/**
 * Replays a CSV file of commands through an {@link OrderBook}, printing one line per event
 * and then the final book.
 */
public final class Main {

    /** Exit status when the file argument is missing or the file cannot be read. */
    public static final int EXIT_USAGE = 1;
    /** Exit status when an input line is malformed. */
    public static final int EXIT_MALFORMED = 2;

    private static final String USAGE = "usage: order-book <commands.csv>";

    private Main() {}

    /**
     * Program entry point.
     *
     * @param args a single argument, the path of the CSV file to replay
     */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out), false);
        int status = run(args, out, System.err);
        out.flush();
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Replays the file named by {@code args} and returns the process exit status, without
     * exiting the JVM.
     *
     * @param args the command-line arguments
     * @param out  receives event lines and the final book
     * @param err  receives usage and malformed-line messages
     * @return 0 on success, {@link #EXIT_USAGE} or {@link #EXIT_MALFORMED} otherwise
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println(USAGE);
            return EXIT_USAGE;
        }
        OrderBook book = new OrderBook(event -> out.print(format(event) + "\n"));
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String command = line.strip();
                if (!command.isEmpty() && !command.startsWith("#")) {
                    apply(book, command);
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("line " + lineNumber + ": " + e.getMessage());
            return EXIT_MALFORMED;
        } catch (IOException | UncheckedIOException e) {
            err.println("cannot read " + args[0]);
            err.println(USAGE);
            return EXIT_USAGE;
        }
        printBook(book, out);
        return 0;
    }

    /**
     * Formats an event as its CSV output line, without a line terminator.
     *
     * @param event the event to format
     * @return the output line
     */
    public static String format(Event event) {
        return switch (event) {
            case Event.OrderAccepted e -> "ACCEPTED," + e.orderId();
            case Event.OrderRejected e -> "REJECTED," + e.orderId() + "," + e.reason();
            case Event.Trade e -> "TRADE," + e.aggressorId() + "," + e.restingId()
                    + "," + e.price() + "," + e.quantity();
            case Event.OrderCancelled e -> "CANCELLED," + e.orderId() + "," + e.remainingQuantity();
            case Event.OrderAmended e -> "AMENDED," + e.orderId() + "," + e.price() + "," + e.quantity();
            case Event.TopOfBook e -> "TOB," + topSide(e.bidPrice(), e.bidQuantity())
                    + "," + topSide(e.askPrice(), e.askQuantity());
        };
    }

    private static String topSide(OptionalLong price, long quantity) {
        return price.isPresent() ? price.getAsLong() + "," + quantity : "-,-";
    }

    private static void apply(OrderBook book, String line) {
        String[] fields = line.split(",", -1);
        switch (fields[0]) {
            case "L" -> {
                requireFields(fields, 5);
                book.submitLimit(number(fields[1], "order id"), side(fields[2]),
                        number(fields[3], "price"), number(fields[4], "quantity"));
            }
            case "M" -> {
                requireFields(fields, 4);
                book.submitMarket(number(fields[1], "order id"), side(fields[2]),
                        number(fields[3], "quantity"));
            }
            case "C" -> {
                requireFields(fields, 2);
                book.cancel(number(fields[1], "order id"));
            }
            case "A" -> {
                requireFields(fields, 4);
                book.amend(number(fields[1], "order id"), number(fields[2], "price"),
                        number(fields[3], "quantity"));
            }
            default -> throw new IllegalArgumentException("unknown command '" + fields[0] + "'");
        }
    }

    private static void requireFields(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("command " + fields[0] + " takes " + expected
                    + " fields but the line has " + fields.length);
        }
    }

    private static long number(String field, String name) {
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " '" + field + "' is not a whole number");
        }
    }

    private static Side side(String field) {
        return switch (field) {
            case "B" -> Side.BUY;
            case "S" -> Side.SELL;
            default -> throw new IllegalArgumentException("side '" + field + "' is not B or S");
        };
    }

    private static void printBook(OrderBook book, PrintStream out) {
        out.print("BOOK\n");
        List<LevelView> asks = book.levels(Side.SELL);
        for (LevelView level : asks.reversed()) {
            out.print("ASK," + level.price() + "," + level.totalQuantity() + "," + level.orderCount() + "\n");
        }
        for (LevelView level : book.levels(Side.BUY)) {
            out.print("BID," + level.price() + "," + level.totalQuantity() + "," + level.orderCount() + "\n");
        }
    }
}
