package com.example.booking;

@lombok.Getter
public class InventoryFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean temporary;
    private final boolean definiteRejection;

    public InventoryFailure(int status, String code, String message, boolean temporary) {
        this(status, code, message, temporary, false);
    }

    public InventoryFailure(
            int status, String code, String message, boolean temporary, boolean definiteRejection) {
        super(message);
        this.status = status;
        this.code = code;
        this.temporary = temporary;
        this.definiteRejection = definiteRejection;
    }
}
