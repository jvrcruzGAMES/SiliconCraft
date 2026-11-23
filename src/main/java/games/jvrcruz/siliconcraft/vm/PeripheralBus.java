package games.jvrcruz.siliconcraft.vm;

/** Single-request mailbox shared by the CPU worker and the server-thread peripheral host. */
public final class PeripheralBus {
    public static final int IDLE = 0;
    public static final int PENDING = 1;
    public static final int OK = 2;
    public static final int MISSING = -1;
    public static final int UNWRAPPED = -2;
    public static final int UNSUPPORTED = -3;
    public static final int FAILED = -4;

    public static final int PRESENT = 1;
    public static final int WRAPPED = 2;
    public static final int AUTOMATIC = 4;

    public enum Operation { INFO, WRAP, CALL, CALL_STRING, SCAN }

    public record Request(long session, Operation operation, int side, int method, int arg0, int arg1, String text) {}

    private long session;
    private Request request;
    private int status;
    private int result;

    public synchronized boolean submit(Operation operation, int side, int method, int arg0, int arg1, String text) {
        if (status != IDLE) {
            return false;
        }
        request = new Request(session, operation, side, method, arg0, arg1, text);
        status = PENDING;
        return true;
    }

    public synchronized Request pendingRequest() {
        return request;
    }

    public synchronized void complete(Request completed, int status, int value) {
        if (request == completed) {
            request = null;
            this.status = status;
            result = value;
        }
    }

    public synchronized int status() {
        return status;
    }

    public synchronized int consumeResult() {
        if (status == IDLE || status == PENDING) {
            return 0;
        }
        status = IDLE;
        int value = result;
        result = 0;
        return value;
    }

    public synchronized void reset() {
        session++;
        request = null;
        status = IDLE;
        result = 0;
    }
}
