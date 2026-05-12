package com.aq.cfrselect;

import com.aq.cfrselect.cli.CliOptions;
import com.aq.cfrselect.cli.UsageException;
import com.aq.cfrselect.cli.UsagePrinter;
import com.aq.cfrselect.core.SelectiveDecompiler;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.help) {
                UsagePrinter.print();
                return;
            }

            SelectiveDecompiler tool = new SelectiveDecompiler(options);
            int exitCode = tool.run();
            System.exit(exitCode);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            UsagePrinter.print();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            if (CliOptions.has(args, "--debug")) {
                e.printStackTrace(System.err);
            } else {
                System.err.println("Run with --debug to print the full stack trace.");
            }
            System.exit(1);
        }
    }
}
