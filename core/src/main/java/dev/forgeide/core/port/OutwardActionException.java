package dev.forgeide.core.port;

/**
 * Any failure executing an {@code outward} action (SDD SR-4/T17): network trouble, a non-2xx
 * from the PR/Jira host, a rejected {@code git push}. The engine treats every one of these the
 * same way regardless of cause — {@code FAILED(script)}-class, retried by the step's own {@link
 * dev.forgeide.core.policy.RetryPolicy#script()} budget (T17 scope note: "ошибки внешних систем
 * ... FAILED(script)-класс с ретраем по политике").
 */
public final class OutwardActionException extends Exception {

    public OutwardActionException(String message) {
        super(message);
    }

    public OutwardActionException(String message, Throwable cause) {
        super(message, cause);
    }
}
