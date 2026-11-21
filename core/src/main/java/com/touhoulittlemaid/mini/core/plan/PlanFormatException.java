package com.touhoulittlemaid.mini.core.plan;

/**
 * A plan that cannot be executed as written.
 *
 * <p>Checked on purpose: parsing and binding happen at the edge (command, client, network
 * handler), where the caller must turn the code into a user-facing message.
 */
public class PlanFormatException extends Exception {

    private final String code;

    public PlanFormatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
