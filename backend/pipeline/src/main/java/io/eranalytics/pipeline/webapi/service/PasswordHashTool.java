package io.eranalytics.pipeline.webapi.service;

import java.io.Console;
import java.util.Arrays;

/** Offline console entry point; never accepts passwords in arguments or echoed stdin. */
public final class PasswordHashTool {
    private PasswordHashTool() {}

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null || args.length != 0) {
            System.err.println("Run in an interactive console without arguments.");
            System.exit(1);
            return;
        }
        int result = generate(console);
        if (result != 0) System.exit(result);
    }

    private static int generate(Console console) {
        char[] first = console.readPassword("Password: ");
        char[] second = console.readPassword("Repeat password: ");
        try {
            if (!valid(first, second)) {
                System.err.println("Passwords must match and contain 1 to 200 characters.");
                return 1;
            }
            System.out.println(PasswordHashes.create(first));
            return 0;
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    private static boolean valid(char[] first, char[] second) {
        return first != null
                && second != null
                && first.length >= 1
                && first.length <= 200
                && Arrays.equals(first, second);
    }
}
