import dev.ventry.raschet.bridge.NativeCorrection;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeCorrectionSmoke {
    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        int count = 0;
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split(" ");
            int kind = switch (f[1]) {
                case "debt" -> NativeCorrection.DEBT;
                case "payment" -> NativeCorrection.PAYMENT;
                default -> 0;
            };
            long expected = switch (f[4]) {
                case "invalid" -> NativeCorrection.INVALID;
                case "paid" -> NativeCorrection.PAID;
                case "range" -> NativeCorrection.RANGE;
                default -> Long.parseLong(f[4]);
            };
            long actual = NativeCorrection.correctedBalance(kind,
                    Long.parseLong(f[0]), Long.parseLong(f[2]), Long.parseLong(f[3]));
            if (actual != expected) throw new AssertionError(line + ": " + actual + " != " + expected);
            count++;
        }
        if (count != 18) throw new AssertionError("Expected 18 correction cases");
        System.out.println("JNI correction contract: " + count + " PASS");
    }
}
