package dev.ventry.raschet;

/** One namespace for external Activity and permission results. Never reuse IDs. */
final class ActivityRequests {
    private ActivityRequests() {}
    static final int REQUEST_IMPORT=101;
    static final int REQUEST_EXPORT=102;
    static final int REQUEST_PDF=103;
    static final int NOTIFICATION_PERMISSION_REQUEST=104;
    static final int REQUEST_REPORT=105;
    static final int CREDENTIAL_OPEN=106;
    static final int CREDENTIAL_ENABLE=107;
    static final int CREDENTIAL_DISABLE=108;
    static final int REQUEST_ANALYTICS=109;

    static boolean isCredential(int request){
        return request==CREDENTIAL_OPEN||request==CREDENTIAL_ENABLE||request==CREDENTIAL_DISABLE;
    }

    static boolean isFile(int request){
        return request==REQUEST_IMPORT||request==REQUEST_EXPORT||request==REQUEST_PDF
                ||request==REQUEST_REPORT||request==REQUEST_ANALYTICS;
    }
}
