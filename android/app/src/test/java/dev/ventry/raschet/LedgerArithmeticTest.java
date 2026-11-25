package dev.ventry.raschet;

import dev.ventry.raschet.domain.LedgerArithmetic;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LedgerArithmeticTest {
    @Test public void sharedCorrectionContract() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("correction.tsv");
        assertNotNull("Shared financial contract missing", in);
        int checked = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split(" ");
                assertEquals(line, 5, fields.length);
                String actual;
                try {
                    actual = Long.toString(LedgerArithmetic.correctedBalance(
                        Long.parseLong(fields[0]), fields[1], Long.parseLong(fields[2]), Long.parseLong(fields[3])));
                } catch (IllegalArgumentException ex) {
                    switch (ex.getMessage()) {
                        case "Неверная операция.": actual = "invalid"; break;
                        case "Этот долг уже погашался. Сначала исправьте последнюю оплату.": actual = "paid"; break;
                        case "Исправление создаёт переплату или превышение.": actual = "range"; break;
                        default: throw ex;
                    }
                }
                assertEquals(line, fields[4], actual);
                checked++;
            }
        }
        assertTrue("Expected all shared cases", checked >= 18);
    }

    @Test public void missingKindRejected() {
        try {
            LedgerArithmetic.correctedBalance(1L, null, 1L, 1L);
            fail("Missing operation accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("Неверная операция.", expected.getMessage());
        }
    }
}
