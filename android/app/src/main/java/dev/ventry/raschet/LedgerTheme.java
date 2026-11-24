package dev.ventry.raschet;

/** Shared AMOLED ledger palette. Accent denotes action/payment, red overdue, amber due soon. */
final class LedgerTheme {
    static final int BG=0xff000000, PANEL=0xff111412, ELEVATED=0xff1a201d;
    static final int BORDER=0xff333c37, TEXT=0xfff1f5f2, MUTED=0xffaab5ae;
    static final int ACCENT=0xff48cf99, INK=0xff061c13, ALERT=0xffffcb83, RED=0xffff8d94;
    static final int SELECTED=0xff102d21;
    private LedgerTheme() {}
}
