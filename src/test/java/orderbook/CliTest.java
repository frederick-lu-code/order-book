package orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import orderbook.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private static final String WORKED_EXAMPLE = """
            ACCEPTED,1
            TOB,-,-,101,5
            ACCEPTED,2
            TOB,-,-,101,10
            ACCEPTED,3
            ACCEPTED,4
            TOB,100,10,101,10
            ACCEPTED,5
            TRADE,5,1,101,5
            TRADE,5,2,101,2
            TOB,100,10,101,3
            ACCEPTED,6
            TRADE,6,4,100,4
            TOB,100,6,101,3
            AMENDED,3,102,6
            REJECTED,9,UNKNOWN_ORDER
            BOOK
            ASK,102,6,1
            ASK,101,3,1
            BID,100,6,1
            """;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }

    @Test
    void sampleFileProducesWorkedExampleOutput() {
        int status = run("examples/sample.csv");

        assertEquals(0, status);
        assertEquals(WORKED_EXAMPLE, text(out));
        assertEquals("", text(err));
    }

    @Test
    void malformedLineExitsWithStatus2AndNamesTheLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.csv");
        Files.writeString(file, "# comment\n\nL,1,B,100,10\nL,2,S,abc,5\n");

        int status = run(file.toString());

        assertEquals(2, status);
        assertTrue(text(err).startsWith("line 4: "), text(err));
        assertFalse(text(out).contains("BOOK"), "the final book is not printed after a failure");
    }

    @Test
    void unknownCommandAndWrongFieldCountAreMalformed(@TempDir Path dir) throws IOException {
        Path unknown = dir.resolve("unknown.csv");
        Files.writeString(unknown, "X,1\n");
        assertEquals(2, run(unknown.toString()));
        assertTrue(text(err).startsWith("line 1: "), text(err));

        err.reset();
        Path shortLine = dir.resolve("short.csv");
        Files.writeString(shortLine, "L,1,B,100,10\nC\n");
        assertEquals(2, run(shortLine.toString()));
        assertTrue(text(err).startsWith("line 2: "), text(err));
    }

    @Test
    void missingOrUnreadableFileExitsWithStatus1AndUsage(@TempDir Path dir) {
        assertEquals(1, run());
        assertTrue(text(err).contains("usage:"), text(err));

        err.reset();
        assertEquals(1, run(dir.resolve("no-such-file.csv").toString()));
        assertTrue(text(err).contains("usage:"), text(err));
        assertEquals("", text(out));
    }
}
