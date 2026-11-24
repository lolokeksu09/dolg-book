package dev.ventry.raschet;

/** Advisory credit limit for new debt. The ledger's accounting rules remain authoritative. */
final class DebtLimits {
    private DebtLimits(){}
    static long remaining(long limit,long balance){return Math.max(0L,limit-balance);}
    static boolean requiresConfirmation(long limit,long balance,long addition){
        return limit>0&&addition>0&&addition>remaining(limit,balance);
    }
    static void requireConsent(long limit,long balance,long addition,boolean confirmed){
        if(requiresConfirmation(limit,balance,addition)&&!confirmed)
            throw new IllegalArgumentException("Превышен лимит задолженности. Подтвердите превышение или уменьшите сумму.");
    }
}
